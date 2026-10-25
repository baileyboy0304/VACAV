"""HA-admin-only setup. No HA credentials or bearer tokens are sent to Linux/Android."""
import asyncio
import datetime as dt
from pathlib import Path

import voluptuous as vol
from homeassistant.components import panel_custom, websocket_api
from homeassistant.components.http import StaticPathConfig
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.storage import Store

from .const import DOMAIN, ROLE_UPSTREAM, VERSION
from .es8_setup import validate_plan, render, load_templates


def entry_for(hass, entry_id):
    entry = hass.config_entries.async_get_entry(entry_id)
    if not entry or entry.domain != DOMAIN or entry.data.get('role') != ROLE_UPSTREAM:
        raise ValueError('Select this Echo Show’s VACAVision device.')
    return entry


def available(state):
    return state is not None and state.state not in ('unknown', 'unavailable')


def inspect(hass, entry):
    values = {**entry.data, **entry.options}
    plan = validate_plan(values.get('setup_plan'))
    registry = er.async_get(hass)
    rows = er.async_entries_for_config_entry(registry, entry.entry_id)
    microphones = [r.entity_id for r in rows if r.entity_id.startswith('assist_satellite.') and available(hass.states.get(r.entity_id))]
    displays = [hass.states.get(r.entity_id).state for r in rows if r.entity_id.startswith('sensor.')
                and available(hass.states.get(r.entity_id)) and hass.states.get(r.entity_id).state.startswith('va-')]
    players = [r.entity_id for r in registry.entities.values() if r.entity_id.startswith('media_player.') and r.platform == 'music_assistant'
               and r.config_entry_id == values.get('ma_entry_id') and r.unique_id == values.get('ma_player_id')
               and available(hass.states.get(r.entity_id))]
    errors = []
    if len(microphones) != 1: errors.append('Wait for this Echo’s Assist satellite to connect and select its voice pipeline.')
    if len(displays) != 1: errors.append('Sign into HA on the Echo Show touchscreen, open a dashboard, then refresh.')
    if len(displays) == 1 and displays[0] not in hass.data.get('view_assist', {}).get('va_browser_ids', {}):
        errors.append('View Assist has not seen this browser. Sign into HA on the Echo, open a dashboard and refresh.')
    if len(players) != 1: errors.append('Wait for the paired physical Music Assistant player to become available in HA.')
    va_entries = [e for e in hass.config_entries.async_entries('view_assist')
                  if len(displays) == 1 and e.data.get('display_device') == displays[0]]
    if len(va_entries) > 1: errors.append('More than one VA device uses this display. Remove only the duplicate mapping before continuing.')
    va_sensor = None
    if len(va_entries) == 1:
        va_sensor = next((r.entity_id for r in er.async_entries_for_config_entry(registry, va_entries[0].entry_id)
                          if r.entity_id.startswith('sensor.') and available(hass.states.get(r.entity_id))), None)
    weather = [s.entity_id for s in hass.states.async_all('weather') if available(s)]
    inherited = next((s.attributes.get('weather_entity') for s in hass.states.async_all('sensor')
                      if registry.async_get(s.entity_id) and registry.async_get(s.entity_id).platform == 'view_assist'
                      and s.attributes.get('weather_entity') in weather), None)
    return {'plan': plan, 'microphone': microphones[0] if len(microphones) == 1 else None,
            'display': displays[0] if len(displays) == 1 else None,
            'player': players[0] if len(players) == 1 else None,
            'va_entry': va_entries[0].entry_id if len(va_entries) == 1 else None,
            'va_sensor': va_sensor, 'weather': weather, 'suggested_weather': inherited,
            'errors': errors}


async def create_va(hass, entry, weather):
    data = inspect(hass, entry)
    if data['errors']: raise ValueError(' '.join(data['errors']))
    if weather not in data['weather']: raise ValueError('Choose an available weather entity.')
    if data['va_entry']:
        va = hass.config_entries.async_get_entry(data['va_entry'])
        expected = {'mic_device': data['microphone'], 'mediaplayer_device': data['player'],
                    'musicplayer_device': data['player']}
        mic = er.async_get(hass).async_get(data['microphone'])
        # VA may store the microphone's device-registry ID instead of entity ID.
        if va.data.get('mic_device') not in (data['microphone'], mic.device_id) or any(
                va.data.get(k) != v for k, v in expected.items() if k != 'mic_device'):
            raise ValueError('An existing View Assist device has different audio mappings. Correct it in VA before continuing.')
    else:
        flow = await hass.config_entries.flow.async_init('view_assist', context={'source': 'user'})
        flow_id = flow['flow_id']
        try:
            form = await hass.config_entries.flow.async_configure(flow_id, {'type': 'view_audio'})
            if form.get('step_id') != 'options': raise ValueError('Unsupported View Assist setup form; update VA.')
            # Browser registration is checked above before starting the flow.
            schema = form.get('data_schema')
            if schema is None: raise ValueError('View Assist returned no device form.')
            result = await hass.config_entries.flow.async_configure(flow_id, {
                'name': entry.title, 'mic_device': data['microphone'], 'display_device': data['display'],
                'mediaplayer_device': data['player'], 'musicplayer_device': data['player']})
            if result.get('type') != 'create_entry': raise ValueError('View Assist did not accept the mapping. Check display discovery and retry.')
            va = result['result']
        finally:
            if any(f['flow_id'] == flow_id for f in hass.config_entries.flow.async_progress()):
                hass.config_entries.flow.async_abort(flow_id)
    path = '/' + data['plan']['va_slug']
    hass.config_entries.async_update_entry(va, options={**va.options, 'weather_entity': weather,
        'dashboard': path, 'home': path+'/clock', 'music': path+'/music', 'intent': path+'/intent',
        'list_view': path+'/list', 'use_announce': 'on', 'ducking_volume': 0})
    return {'message': 'View Assist device configured. Wait for it to reload, then refresh this page.'}


async def setup_panel(hass):
    websocket_api.async_register_command(hass, setup_command)
    base = Path(__file__).parent
    await hass.http.async_register_static_paths([
        StaticPathConfig('/vaca_vision/es8-setup.js', str(base/'es8-setup.js'), False),
        StaticPathConfig('/vaca_vision/es8-assets', str(base/'es8/assets'), False)])
    await panel_custom.async_register_panel(hass, frontend_url_path='vacav-setup',
        webcomponent_name='vacav-setup', sidebar_title='VACAV Setup', sidebar_icon='mdi:tablet-dashboard',
        module_url=f'/vaca_vision/es8-setup.js?v={VERSION}', require_admin=True)


@websocket_api.websocket_command({vol.Required('type'): 'vaca_vision/es8_setup',
    vol.Required('operation'): vol.In(['list', 'import', 'inspect', 'create_va', 'dashboards', 'backup', 'complete']),
    vol.Optional('entry_id'): str, vol.Optional('weather'): str, vol.Optional('music_path'): str})
@websocket_api.require_admin
@websocket_api.async_response
async def setup_command(hass, connection, msg):
    try:
        if msg['operation'] == 'list':
            result = [{'id': e.entry_id, 'name': e.title} for e in hass.config_entries.async_entries(DOMAIN)
                      if e.data.get('role') == ROLE_UPSTREAM]
        else:
            entry = entry_for(hass, msg['entry_id'])
            lock = hass.data.setdefault('vacav_es8_setup_locks', {}).setdefault(entry.entry_id, asyncio.Lock())
            async with lock:
                op = msg['operation']
                if op == 'import':
                    from .protocol import probe
                    info = await probe(entry.data['host'], entry.data['port'])
                    if info.id != entry.data['id']:
                        raise ValueError('Device identity changed; refusing setup import.')
                    plan = validate_plan(info.capabilities.get('es8_install'))
                    hass.config_entries.async_update_entry(entry, data={**entry.data, 'setup_plan': plan})
                data = inspect(hass, entry)
                if op in ('inspect', 'import'): result = data
                elif op == 'create_va': result = await create_va(hass, entry, msg.get('weather'))
                elif op == 'backup':
                    from homeassistant.components.lovelace.const import LOVELACE_DATA
                    dashboards = hass.data[LOVELACE_DATA].dashboards
                    saved = {}
                    for key in ('home_slug', 'va_slug', 'tv_slug'):
                        slug = data['plan'][key]
                        dash = dashboards.get(slug)
                        if dash and dash.mode != 'storage':
                            raise ValueError('Refusing to replace a YAML dashboard: ' + slug)
                        saved[slug] = await dash.async_load(False) if dash else None
                    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S%f')
                    key = 'vaca_vision.dashboard_backup_' + stamp
                    await Store(hass, 1, key, private=True).async_save(saved)
                    result = {'backup': '.storage/' + key, 'configs': saved}
                else:
                    if data['errors'] or not data['va_sensor']:
                        raise ValueError(' '.join(data['errors']) or 'Create View Assist, wait for it to reload and refresh.')
                    state = hass.states.get(data['va_sensor'])
                    attrs = state.attributes
                    path = '/' + data['plan']['va_slug']
                    mic_record = er.async_get(hass).async_get(data['microphone'])
                    if (attrs.get('mic_device') not in (data['microphone'], mic_record.device_id)
                            or attrs.get('display_device') != data['display']
                            or attrs.get('weather_entity') not in data['weather']
                            or attrs.get('musicplayer_device') != data['player'] or attrs.get('mediaplayer_device') != data['player']
                            or attrs.get('home_screen') != path+'/clock'):
                        raise ValueError('View Assist mappings are not ready; configure the device above and refresh.')
                    if op == 'dashboards':
                        templates = await hass.async_add_executor_job(load_templates)
                        configs = render(data['plan'], data['va_sensor'], attrs.get('weather_entity', ''), msg.get('music_path', ''), templates)
                        result = {'configs': {data['plan'][k+'_slug']: configs[k] for k in ('home', 'va', 'tv')},
                                  'resources': ['/vaca_vision/es8-assets/athena-music-card.js?v=2',
                                    '/vaca_vision/es8-assets/athena-dashboard-surface.js?v=3&dashboards='+','.join(data['plan'][k] for k in ('home_slug','va_slug','tv_slug'))]}
                    else:
                        dashboard = '/' + data['plan']['home_slug'] + '/home'
                        hass.config_entries.async_update_entry(entry, options={**entry.options, 'dashboard': dashboard})
                        result = {'message': 'Setup complete. Test voice and music on this Echo, then sign into streaming services.'}
        connection.send_result(msg['id'], result)
    except Exception as exc:
        # No credentials or exception bodies from lower-level connections.
        message = str(exc) if isinstance(exc, ValueError) else 'Setup could not finish (' + type(exc).__name__ + '). Check HA logs and retry.'
        connection.send_error(msg['id'], 'setup_failed', message[:400])
