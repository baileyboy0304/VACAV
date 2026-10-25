/* HA supplies the authenticated `hass` object. No tokens or snapshots in localStorage. */
class OverlayPickers extends HTMLElement {
  constructor() { super(); this.attachShadow({mode: "open"}); this.profiles = []; this.devices = []; this.busy = false; }
  set hass(value) { this._hass = value; if (!this.started) { this.started = true; this.render(); this.run(() => this.refresh()); } }
  call(operation, data = {}) { return this._hass.callWS({type: "vaca_vision/pickers", operation, ...data}); }
  node(tag, text, parent = this.shadowRoot) { const n = document.createElement(tag); if (text) n.textContent = text; parent.append(n); return n; }
  button(text, action, parent) { const n = this.node("button", text, parent); n.onclick = action; return n; }
  render() {
    this.shadowRoot.innerHTML = `<style>
      :host{display:block;height:100%;overflow:auto;background:var(--primary-background-color);color:var(--primary-text-color);font:16px system-ui}
      main{max-width:1000px;padding:28px;margin:auto}h1{font-size:28px;margin:0}p{line-height:1.5;color:var(--secondary-text-color)}
      section{padding:22px;margin-top:20px;background:var(--card-background-color);border:1px solid var(--divider-color);border-radius:14px}
      h2{font-size:20px;margin-top:0}.row{display:flex;flex-wrap:wrap;gap:12px;align-items:center}label{display:flex;flex-direction:column;gap:8px;flex:1;min-width:220px}
      select,input,button{font:inherit;padding:12px;border-radius:8px;border:1px solid var(--divider-color);background:var(--card-background-color);color:var(--primary-text-color)}
      input,select{box-sizing:border-box;width:100%}button{cursor:pointer;color:var(--primary-color);font-weight:600}button:disabled{opacity:.45;cursor:default}
      button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--primary-color)}.actions{margin-top:18px}.message{min-height:24px;white-space:pre-wrap}
    </style><main></main>`;
    const main = this.shadowRoot.querySelector("main");
    const header = this.node("div", "", main); header.className = "row";
    this.button("☰", () => this.dispatchEvent(new CustomEvent("hass-toggle-menu", {bubbles:true,composed:true})), header).setAttribute("aria-label", "Toggle sidebar");
    this.node("h1", "Overlay pickers", header);
    this.node("p", "Save a TV’s picker here, then load a copy on another device. Each device keeps its own edits. Profiles include views, icons, layouts, durations, loading behaviour and named presets.", main);
    const deviceSection = this.node("section", "", main);
    this.node("h2", "Device", deviceSection);
    const dl = this.node("label", "Overlay device", deviceSection); this.device = this.node("select", "", dl);
    const capture = this.node("div", "", deviceSection); capture.className = "row actions";
    const nl = this.node("label", "Profile name", capture); this.name = this.node("input", "", nl); this.name.maxLength = 60; this.name.placeholder = "Living room picker";
    this.button("Save as new profile", () => this.run(async () => { await this.call("capture", {device:this.deviceId(),name:this.profileName()}); await this.refresh(); this.message.textContent = "Picker saved to Home Assistant."; }), capture);
    this.button("Restore previous picker", () => this.run(async () => {
      const device = this.deviceId(); if (!confirm("Restore this device’s previous picker? Its current picker will become the next restore point.")) return;
      await this.call("restore", {device}); this.message.textContent = "Previous picker restored on the device.";
    }), deviceSection);
    const library = this.node("section", "", main); this.node("h2", "Saved profiles", library);
    const pl = this.node("label", "Profile", library); this.profile = this.node("select", "", pl);
    const actions = this.node("div", "", library); actions.className = "row actions";
    this.button("Load on device", () => this.run(async () => {
      const row = this.selected(), device = this.deviceId();
      if (!confirm(`Replace this device’s picker and presets with “${row.name}”? A restore point will be saved first.`)) return;
      await this.call("apply", {device,profile_id:row.id,revision:row.revision}); this.message.textContent = "Profile loaded on the device. Its previous picker can be restored.";
    }), actions);
    this.button("Update from device", () => this.run(async () => {
      const row = this.selected(), device = this.deviceId(); if (!confirm(`Overwrite saved profile “${row.name}” with this device’s picker?`)) return;
      await this.call("capture", {device,name:row.name,profile_id:row.id,revision:row.revision}); await this.refresh(); this.message.textContent = "Saved profile updated.";
    }), actions);
    this.button("Download JSON", () => this.run(async () => {
      const row = await this.call("get", {profile_id:this.selected().id});
      const url = URL.createObjectURL(new Blob([JSON.stringify(row.profile,null,2)+"\n"], {type:"application/json"}));
      const a = document.createElement("a"); a.href = url; a.download = (row.name.replace(/[^a-z0-9_-]+/gi,"-") || "picker")+".json"; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
      this.message.textContent = "Profile downloaded.";
    }), actions);
    this.button("Delete profile", () => this.run(async () => {
      const row = this.selected(); if (!confirm(`Delete saved profile “${row.name}”? Device copies are kept.`)) return;
      await this.call("delete", {profile_id:row.id,revision:row.revision}); await this.refresh(); this.message.textContent = "Saved profile deleted.";
    }), actions);
    const upload = this.node("label", "Upload profile JSON as a new profile", library); upload.className = "actions";
    this.file = this.node("input", "", upload); this.file.type = "file"; this.file.accept = ".json,application/json";
    this.file.onchange = () => { const file = this.file.files[0]; if (!file) return; this.run(async () => {
      if (file.size > 524288) throw Error("Profile is too large (maximum 512 KiB).");
      const profile = JSON.parse(await file.text());
      const name = prompt("Name for the imported profile", file.name.replace(/\.json$/i,"").slice(0,60)); if (name === null) return;
      await this.call("save", {name:name.trim(),profile}); await this.refresh(); this.message.textContent = "Profile uploaded to Home Assistant.";
    }).finally(() => { this.file.value = ""; }); };
    this.button("Refresh", () => this.run(() => this.refresh()), main);
    this.message = this.node("p", "", main); this.message.className = "message"; this.message.setAttribute("role", "status");
    this.node("p", "Device identity, Home Assistant login, BLE pairing and player settings are never included. Dashboard paths must exist on the receiving device’s Home Assistant server.", main);
  }
  selected() { const row = this.profiles.find(p => p.id === this.profile.value); if (!row) throw Error("Select a saved profile."); return row; }
  deviceId() { const row = this.devices.find(d => d.id === this.device.value); if (!row || !row.connected || !row.supported) throw Error("Select a connected device running preview.39 or later."); return row.id; }
  profileName() { const name = this.name.value.trim(); if (!name) throw Error("Enter a profile name."); return name; }
  async refresh() {
    const data = await this.call("list"); this.profiles = data.profiles; this.devices = data.devices;
    const fill = (select, rows, describe, disabled) => {
      const previous = select.value; select.replaceChildren();
      if (!rows.length) this.node("option", "None available", select).value = "";
      for (const row of rows) { const option = this.node("option", describe(row), select); option.value = row.id; option.disabled = disabled(row); }
      if (rows.some(r => r.id === previous)) select.value = previous;
    };
    fill(this.device, this.devices, d => `${d.name}${!d.supported?" — update app":!d.connected?" — offline":""}`, d => !d.connected || !d.supported);
    fill(this.profile, this.profiles, p => `${p.name} · ${p.views} views · ${p.presets} presets`, () => false);
    if (!this.name.value && this.devices.length) this.name.value = `${this.devices[0].name} picker`.slice(0,60);
  }
  async run(fn) {
    if (this.busy) return; this.busy = true; this.message.textContent = "Working…";
    this.shadowRoot.querySelectorAll("button,input,select").forEach(n => n.disabled = true);
    try { await fn(); if (this.message.textContent === "Working…") this.message.textContent = ""; }
    catch (e) { this.message.textContent = e.message || String(e); }
    finally { this.busy = false; this.shadowRoot.querySelectorAll("button,input,select").forEach(n => n.disabled = false); }
  }
}
customElements.define("vaca_vision-pickers", OverlayPickers);
