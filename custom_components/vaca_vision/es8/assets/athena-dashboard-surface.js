// Keep the page behind dashboard cards blue while HA replaces a dashboard.
// Route-scoped: other HA dashboards, controls and embedded websites keep their themes.
const source = new URL(import.meta.url);
const dashboards = new Set((source.searchParams.get('dashboards') || '').split(',').filter(x => /^[a-z0-9]+(?:-[a-z0-9]+)+$/.test(x)));
const attribute = 'data-athena-dashboard-surface';
const styleId = 'athena-dashboard-surface';
{
  const style = document.getElementById(styleId) || document.createElement('style');
  style.id = styleId;
  style.textContent = `html[${attribute}],html[${attribute}] body,html[${attribute}] home-assistant {
    background-color:#101e30!important;
    --primary-background-color:#101e30!important;
    --lovelace-background:#101e30!important;
    --app-header-background-color:#101e30!important;
    --app-header-text-color:#e2edf5!important;
    --app-header-selection-bar-color:#8fdbed!important;
    --app-header-border-bottom:none!important;
  }`;
  if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
}
// Multiple installed profiles share the module, without overriding each other's routes.
const routes = window.__athenaDashboardSurfaces ||= new Set();
for (const path of dashboards) routes.add(path);
function applySurface() {
  document.documentElement.toggleAttribute(attribute, routes.has(location.pathname.split('/')[1]));
}
window.addEventListener('location-changed', applySurface);
window.addEventListener('popstate', applySurface);
applySurface();
