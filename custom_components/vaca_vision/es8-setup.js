class VacavSetup extends HTMLElement {
  set hass(value) { this._hass = value; if (!this.started) { this.started = true; this.init(); } }
  get hass() { return this._hass; }
  async api(operation, fields = {}) {
    return this.hass.callWS({type: 'vaca_vision/es8_setup', operation, entry_id: this.device?.value, ...fields});
  }
  async run(action) {
    if (this.busy) return;
    this.busy = true;
    this.querySelectorAll('button').forEach(b => b.disabled = true);
    try { await action(); } catch (error) { this.message.textContent = error.message || String(error); }
    finally { this.busy = false; this.querySelectorAll('button').forEach(b => b.disabled = false); }
  }
  select(label, id) {
    const row = document.createElement('label'); row.textContent = label;
    const select = document.createElement('select'); select.id = id; row.append(select); this.form.append(row); return select;
  }
  choices(select, values, selected) {
    select.replaceChildren();
    for (const [value, label] of values) { const option = document.createElement('option'); option.value = value; option.textContent = label; select.append(option); }
    if (selected && values.some(v => v[0] === selected)) select.value = selected;
  }
  async init() {
    this.innerHTML = `<style>
      vacav-setup {display:block; padding:24px; max-width:900px; color:var(--primary-text-color); font-family:var(--paper-font-body1_-_font-family, sans-serif)}
      vacav-setup label {display:block;margin:18px 0} vacav-setup select, vacav-setup input {display:block;padding:12px;width:95%;font:inherit;color:var(--primary-text-color);background:var(--card-background-color)}
      vacav-setup button {padding:12px 20px;margin:8px 8px 8px 0;cursor:pointer} vacav-setup pre {white-space:pre-wrap;line-height:1.6}
    </style><h1>Finish Echo Show setup</h1>
    <p>Pair the Echo’s Sendspin player in Music Assistant, add the discovered VACA_Vision device, then sign into HA on the Echo Show touchscreen. Complete its voice-assistant setup and select the pipeline.</p>
    <p>This page uses your current HA administrator session. The Linux installer needs no HA login or token.</p>
    <div id="form"></div><label><input id="heard" type="checkbox" style="display:inline;width:auto"> I have heard an Assist reply and music from this Echo Show.</label><pre id="summary"></pre><p id="message" role="status"></p>
    <button id="import">Read installer choices from Echo</button><button id="refresh">Refresh connections</button><button id="create">Configure View Assist</button><button id="deploy">Install dashboards</button>`;
    this.form = this.querySelector('#form'); this.message = this.querySelector('#message');
    this.device = this.select('Echo Show', 'device'); this.weather = this.select('Weather (inherited where possible)', 'weather');
    const row = document.createElement('label'); row.textContent = 'Music Assistant sidebar path';
    this.music = document.createElement('input'); row.append(this.music); this.form.append(row);
    this.querySelector('#import').onclick = () => this.run(async () => { await this.api('import'); await this.refresh(); });
    this.querySelector('#refresh').onclick = () => this.run(() => this.refresh());
    this.device.onchange = () => this.run(() => this.refresh());
    this.querySelector('#create').onclick = () => this.run(async () => {
      const r = await this.api('create_va', {weather:this.weather.value}); this.message.textContent = r.message;
    });
    this.querySelector('#deploy').onclick = () => this.run(() => this.deploy());
    await this.run(async () => {
      const devices = await this.api('list'); this.choices(this.device, devices.map(d => [d.id, d.name]));
      const panels = await this.hass.callWS({type:'get_panels'});
      const matches = Object.keys(panels).filter(k => k === 'music-assistant' || k.endsWith('_music_assistant'));
      if (matches.length === 1) this.music.value = '/' + matches[0];
      if (!devices.length) { this.message.textContent = 'No prepared Echo found. Run the current Linux installer, then add its discovered VACA_Vision device. If it is already added, select Read installer choices from Echo.'; return; }
      await this.refresh();
    });
  }
  async refresh() {
    this.data = await this.api('inspect');
    this.choices(this.weather, this.data.weather.map(e => [e, this.hass.states[e]?.attributes.friendly_name || e]), this.weather.value || this.data.suggested_weather);
    this.querySelector('#summary').textContent = [
      'Microphone: ' + (this.data.microphone || 'waiting'), 'Display: ' + (this.data.display || 'waiting for touchscreen sign-in'),
      'Music and alerts: ' + (this.data.player || 'waiting'), 'View Assist: ' + (this.data.va_sensor || 'not configured'),
      'Dashboards: ' + ['home_slug','va_slug','tv_slug'].map(k => '/' + this.data.plan[k]).join(', ')
    ].join('\n');
    this.message.textContent = this.data.errors.join('\n') || 'Connections ready. Configure View Assist, refresh after it reloads, then install dashboards.';
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
    if (!this.querySelector('#heard').checked) throw new Error('Test voice and music on this Echo and tick the confirmation first.');
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
  }
}
customElements.define('vacav-setup', VacavSetup);
