/*
 * VACA display helper, injected into the Home Assistant page at document start.
 *
 * View Assist hides the HA sidebar/header from its own frontend script *after* each navigation,
 * so on slow devices the sidebar/header flash on every dashboard change. It also retries forever
 * when an element it expects is missing, re-writing the same inline styles several times a
 * second, which forces full style recalculation of the dashboard.
 *
 * This script:
 *  1. Uses HA's native kiosk mode to keep the sidebar hidden inside HA's own render (no flash).
 *  2. Hides the dashboard header inside hui-root's own render (no flash).
 *  3. Gives View Assist's hide_sidebar() the element it waits for, so its retry loop ends.
 *  4. Skips inline style writes that would not change anything.
 *  5. Refreshes View Assist's cached server time offset when the device clock jumps.
 *
 * Sidebar/header hiding follows View Assist's hide_sidebar/hide_header/mimic_device config once
 * it is available, and the last known values before that.
 */
(function () {
  if (window.__vacaDisplay) return;
  window.__vacaDisplay = true;

  var KEY_SIDEBAR = 'vaca_hide_sidebar';
  var KEY_HEADER = 'vaca_hide_header';

  function readPref(key) {
    try { return localStorage.getItem(key) !== 'false'; } catch (e) { return true; }
  }
  function writePref(key, value) {
    try { localStorage.setItem(key, value ? 'true' : 'false'); } catch (e) { /* ignore */ }
  }

  var hideSidebar = readPref(KEY_SIDEBAR);
  var hideHeader = readPref(KEY_HEADER);

  // 4. Identical inline style writes still invalidate style; skip them.
  var styleProto = CSSStyleDeclaration.prototype;
  var origSetProperty = styleProto.setProperty;
  styleProto.setProperty = function (name, value, priority) {
    if (value != null && this.getPropertyValue(name) === String(value) &&
        this.getPropertyPriority(name) === (priority || '')) {
      return;
    }
    return origSetProperty.apply(this, arguments);
  };

  function haMain() {
    var ha = document.querySelector('home-assistant');
    return ha && ha.shadowRoot && ha.shadowRoot.querySelector('home-assistant-main');
  }

  // 3. View Assist <= 1.0.29 waits for "ha-drawer $ aside", which HA 2026.x no longer renders.
  function ensureAside(drawer) {
    var sr = drawer && drawer.shadowRoot;
    if (sr && !sr.querySelector('aside')) {
      var aside = document.createElement('aside');
      aside.hidden = true;
      sr.appendChild(aside);
    }
  }

  // 2. Header
  var HEADER_CSS = '.header{display:none!important}hui-view-container{padding-top:0!important}';
  function applyHeader(root) {
    var sr = root && root.shadowRoot;
    if (!sr) return;
    var style = sr.getElementById('vaca-hide-header');
    if (hideHeader && !style) {
      style = document.createElement('style');
      style.id = 'vaca-hide-header';
      style.textContent = HEADER_CSS;
      sr.appendChild(style);
    } else if (!hideHeader && style) {
      style.remove();
    }
  }

  function patchUpdated(tag, fn) {
    customElements.whenDefined(tag).then(function () {
      var proto = customElements.get(tag).prototype;
      var orig = proto.updated;
      proto.updated = function () {
        if (orig) orig.apply(this, arguments);
        try { fn(this); } catch (e) { /* never break HA rendering */ }
      };
    });
  }
  patchUpdated('ha-drawer', ensureAside);
  patchUpdated('hui-root', applyHeader);

  // 1. Sidebar, via HA kiosk mode. HA only listens once <home-assistant> has rendered, so keep
  // re-sending until hass reflects the wanted state.
  function syncKiosk() {
    var ha = document.querySelector('home-assistant');
    if (ha && ha.hass && ha.hass.kioskMode !== hideSidebar) {
      window.dispatchEvent(new CustomEvent('hass-kiosk-mode', { detail: { enable: hideSidebar } }));
    }
    var main = haMain();
    if (main && main.shadowRoot) ensureAside(main.shadowRoot.querySelector('ha-drawer'));
  }

  function syncViewAssistConfig() {
    var c = window.viewassist && window.viewassist.config;
    if (!c || typeof c.hide_sidebar !== 'boolean') return;
    var sidebar = c.hide_sidebar && !c.mimic_device;
    var header = !!c.hide_header && !c.mimic_device;
    if (sidebar !== hideSidebar) { hideSidebar = sidebar; writePref(KEY_SIDEBAR, sidebar); }
    if (header !== hideHeader) {
      hideHeader = header;
      writePref(KEY_HEADER, header);
      var main = haMain();
      var panel = main && main.shadowRoot && main.shadowRoot.querySelector('partial-panel-resolver ha-panel-lovelace');
      applyHeader(panel && panel.shadowRoot && panel.shadowRoot.querySelector('hui-root'));
    }
  }

  // 5. View Assist's clock adds a cached HA-to-device time offset and only refreshes it every
  // 5 minutes. When the device clock is corrected (e.g. synced from HA shortly after boot),
  // refresh the offset straight away instead of showing a wrong time until then.
  var clockBase = Date.now() - performance.now();
  setInterval(function () {
    var base = Date.now() - performance.now();
    if (Math.abs(base - clockBase) < 5000) return;
    clockBase = base;
    var ha = document.querySelector('home-assistant');
    var va = window.viewassist;
    if (ha && ha.hass && va && typeof va.server_time_delta === 'number') {
      ha.hass.callWS({ type: 'view_assist/get_server_time_delta', epoch: Date.now() })
        .then(function (delta) { va.server_time_delta = delta; })
        .catch(function () { /* View Assist will refresh it on its own schedule */ });
    }
  }, 2000);

  // Fast while HA starts up, then a cheap low-frequency check.
  var fastChecks = 0;
  var fast = setInterval(function () {
    syncKiosk();
    if (++fastChecks > 200) clearInterval(fast);
  }, 50);
  setInterval(function () {
    syncViewAssistConfig();
    syncKiosk();
  }, 2000);
})();
