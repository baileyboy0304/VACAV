class VacavSetup extends HTMLElement {
  set hass(value) { this._hass = value; if (!this.started) { this.started = true; this.init(); } }
  get hass() { return this._hass; }
  async api(operation, fields = {}) {
    return this.hass.callWS({type: 'vaca_vision/es8_setup', operation, entry_id: this.entryId, ...fields});
  }
  async run(action) {
    if (this.busy) return;
    this.busy = true;
    this.querySelectorAll('button').forEach(b => b.disabled = true);
    try { await action(); } catch (error) { this.message.textContent = error.message || String(error); }
    finally { this.busy = false; this.querySelectorAll('button').forEach(b => b.disabled = false); }
  }
  async init() {
    this.entryId = new URLSearchParams(location.search).get('entry_id');
    this.innerHTML = `<style>
      vacav-setup {display:block;padding:24px;max-width:800px;color:var(--primary-text-color);font-family:var(--paper-font-body1_-_font-family,sans-serif)}
      vacav-setup label {display:block;margin:18px 0} vacav-setup input {display:block;padding:12px;width:95%;font:inherit;color:var(--primary-text-color);background:var(--card-background-color)}
      vacav-setup button {padding:12px 20px;margin:8px 8px 8px 0;cursor:pointer} vacav-setup pre {white-space:pre-wrap;line-height:1.6}
      vacav-setup [hidden] {display:none!important}
      vacav-setup a {color:var(--primary-color)}
    </style><a href="/config/integrations/integration/vaca_vision">Back to VACAVision</a>
    <h1>Install example dashboards</h1><h2 id="device-name"></h2>
    <p>Review the settings from this device’s View Assist entry and the dashboard destinations.</p>
    <pre id="summary"></pre><label id="music-row" hidden>Music Assistant page path<input id="music" placeholder="/app/your-addon_music_assistant"></label>
    <p id="message" role="status"></p><button id="refresh">Refresh connections</button><button id="deploy">Install example dashboards</button>
    <pre id="paths"></pre>`;
    this.message = this.querySelector('#message'); this.music = this.querySelector('#music');
    this.querySelector('#refresh').onclick = () => this.run(() => this.refresh());
    this.querySelector('#deploy').onclick = () => this.run(() => this.deploy());
    await this.run(async () => {
      if (!this.entryId) throw new Error('Open this action from your VACAVision device’s Configure menu.');
      const panels = await this.hass.callWS({type:'get_panels'});
      const matches = Object.keys(panels).filter(k => k === 'music-assistant' || k.endsWith('_music_assistant'));
      if (matches.length === 1) this.music.value = '/' + matches[0];
      this.querySelector('#music-row').hidden = !!this.music.value;
      await this.refresh();
    });
  }
  async refresh() {
    this.data = await this.api('inspect');
    this.querySelector('#device-name').textContent = this.data.name;
    this.querySelector('#summary').textContent = [
      'View Assist: ' + (this.data.va_sensor || 'Create this device in View Assist'),
      'Microphone: ' + (this.data.microphone || 'waiting'), 'Display: ' + (this.data.display || 'waiting for touchscreen sign-in'),
      'Music and alerts: ' + (this.data.player || 'waiting'), 'Weather: ' + (this.data.suggested_weather || 'Set weather in View Assist'),
      'Dashboards: ' + ['home_slug','va_slug','tv_slug'].map(k => '/' + this.data.plan[k]).join(', ')
    ].join('\n');
    this.message.textContent = this.data.errors.join('\n') || (this.data.va_sensor ? 'Ready to review dashboard installation.' : 'Create this device in View Assist using the README settings, then refresh.');
  }
  async confirmReplacement(names) {
    return new Promise(resolve => {
      const dialog = document.createElement('dialog');
      dialog.style.cssText = 'background:var(--card-background-color,#222);color:var(--primary-text-color,#eee);padding:24px;max-width:520px';
      const title = document.createElement('h2'); title.textContent = 'Install these dashboards?';
      const description = document.createElement('p'); description.textContent = 'Existing contents will be backed up and replaced: ' + names.join(', ');
      const finish = value => { dialog.close(); dialog.remove(); resolve(value); };
      const cancel = document.createElement('button'); cancel.textContent = 'Cancel'; cancel.onclick = () => finish(false);
      const accept = document.createElement('button'); accept.textContent = 'Back up and install'; accept.onclick = () => finish(true);
      dialog.oncancel = event => { event.preventDefault(); finish(false); };
      dialog.append(title, description, cancel, accept); this.append(dialog); dialog.showModal();
    });
  }
  async deploy() {
    if (!this.music.value.trim()) throw new Error('Open Music Assistant from HA’s sidebar and enter the page path after the host and port.');
    const prepared = await this.api('dashboards', {music_path:this.music.value.trim()});
    const names = Object.keys(prepared.configs);
    const resources = await this.hass.callWS({type:'lovelace/resources'});
    if (!resources.some(r => /button-card/.test(r.url))) throw new Error('Install button-card through HACS before installing these dashboards.');
    const dashboards = await this.hass.callWS({type:'lovelace/dashboards/list'});
    for (const name of names) {
      if (dashboards.some(d => d.url_path === name && d.mode !== 'storage')) throw new Error('Cannot replace a YAML dashboard: ' + name);
    }
    if (!await this.confirmReplacement(names)) return;
    const backup = await this.api('backup');
    this.message.textContent = 'Backup saved in HA: ' + backup.backup;
    for (const url of prepared.resources) {
      const old = resources.find(r => r.url === url);
      if (!old) await this.hass.callWS({type:'lovelace/resources/create', res_type:'module', url});
    }
    for (const name of names) {
      const known = dashboards.some(d => d.url_path === name);
      // Refresh the list: do not overwrite a dashboard created since the confirmation.
      const current = await this.hass.callWS({type:'lovelace/dashboards/list'});
      if (current.some(d => d.url_path === name) !== known) throw new Error('Dashboard list changed. Refresh and retry.');
      if (known) {
        const before = await this.hass.callWS({type:'lovelace/config', url_path:name});
        if (JSON.stringify(before) !== JSON.stringify(backup.configs[name])) throw new Error('Dashboard changed since backup: ' + name);
      } else await this.hass.callWS({type:'lovelace/dashboards/create', url_path:name, title:prepared.configs[name].title, icon:'mdi:tablet-dashboard', show_in_sidebar:true, require_admin:false});
      await this.hass.callWS({type:'lovelace/config/save', url_path:name, config:prepared.configs[name]});
      const saved = await this.hass.callWS({type:'lovelace/config', url_path:name});
      if (JSON.stringify(saved) !== JSON.stringify(prepared.configs[name])) throw new Error('Dashboard verification failed: ' + name);
    }
    const done = await this.api('complete');
    this.message.textContent = done.message + '\nBackup: ' + backup.backup;
    this.querySelector('#paths').textContent = 'View Assist → Configure → Dashboard Options\n' + Object.entries(done.paths).map(([k,v]) => k + ': ' + v).join('\n');
  }
}
customElements.define('vacav-setup', VacavSetup);
