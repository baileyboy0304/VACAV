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
    if len(players) != 1: errors.append('Wait for the paired physical Music Assistant player to become available in HA.')
    va_entries = [e for e in hass.config_entries.async_entries('view_assist')
                  if len(displays) == 1 and e.data.get('display_device') == displays[0]]
    if len(va_entries) > 1: errors.append('More than one VA device uses this display. Remove only the duplicate mapping before continuing.')
    va_sensor = None
    if len(va_entries) == 1:
        va_sensor = next((r.entity_id for r in er.async_entries_for_config_entry(registry, va_entries[0].entry_id)
                          if r.entity_id.startswith('sensor.') and available(hass.states.get(r.entity_id))), None)
    weather = [s.entity_id for s in hass.states.async_all('weather') if available(s)]
    va_state = hass.states.get(va_sensor) if va_sensor else None
    inherited = va_state.attributes.get('weather_entity') if va_state else None
    return {'plan': plan, 'microphone': microphones[0] if len(microphones) == 1 else None,
            'display': displays[0] if len(displays) == 1 else None,
            'player': players[0] if len(players) == 1 else None,
            'va_entry': va_entries[0].entry_id if len(va_entries) == 1 else None,
            'va_sensor': va_sensor, 'weather': weather, 'suggested_weather': inherited,
            'name': entry.title, 'paths': dashboard_paths(plan), 'errors': errors}



def dashboard_paths(plan):
    path = '/' + plan['va_slug']
    return {'dashboard': path, 'home': path + '/clock', 'music': path + '/music',
            'intent': path + '/intent', 'list_view': path + '/list'}


async def refresh_plan(hass, entry):
    """Import the allowlisted USB choices from this exact display when available."""
    from .protocol import probe
    values = {**entry.data, **entry.options}
    try:
        info = await probe(values['host'], values['port'])
    except (OSError, TimeoutError):
        if values.get('setup_plan'): return
        raise ValueError('Start VACAV on the Echo, then refresh.') from None
    if info.id != entry.data['id']:
        raise ValueError('Device identity changed. Check this VACAV connection.')
    plan = validate_plan(info.capabilities.get('es8_install'))
    if plan != values.get('setup_plan'):
        hass.config_entries.async_update_entry(entry, data={**entry.data, 'setup_plan': plan},
            options={k:v for k,v in entry.options.items() if k != 'setup_plan'})


async def setup_panel(hass):
    websocket_api.async_register_command(hass, setup_command)
    base = Path(__file__).parent
    await hass.http.async_register_static_paths([
        StaticPathConfig('/vaca_vision/es8-setup.js', str(base/'es8-setup.js'), False),
        StaticPathConfig('/vaca_vision/es8-assets', str(base/'es8/assets'), False)])
    await panel_custom.async_register_panel(hass, frontend_url_path='vacav-setup',
        webcomponent_name='vacav-setup',
        module_url=f'/vaca_vision/es8-setup.js?v={VERSION}', require_admin=True)


@websocket_api.websocket_command({vol.Required('type'): 'vaca_vision/es8_setup',
    vol.Required('operation'): vol.In(['inspect', 'dashboards', 'backup', 'complete']),
    vol.Required('entry_id'): str, vol.Optional('music_path'): str})
@websocket_api.require_admin
@websocket_api.async_response
async def setup_command(hass, connection, msg):
    try:
        entry = entry_for(hass, msg['entry_id'])
        lock = hass.data.setdefault('vacav_es8_setup_locks', {}).setdefault(entry.entry_id, asyncio.Lock())
        async with lock:
            op = msg['operation']
            if op == 'inspect':
                await refresh_plan(hass, entry)
            data = inspect(hass, entry)
            if op == 'inspect': result = data
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
                    raise ValueError(' '.join(data['errors']) or 'Create this device in View Assist, then refresh.')
                state = hass.states.get(data['va_sensor'])
                attrs = state.attributes
                mic_record = er.async_get(hass).async_get(data['microphone'])
                if (attrs.get('mic_device') not in (data['microphone'], mic_record.device_id)
                        or attrs.get('display_device') != data['display']
                        or attrs.get('weather_entity') not in data['weather']
                        or attrs.get('musicplayer_device') != data['player'] or attrs.get('mediaplayer_device') != data['player']):
                    raise ValueError('Check the microphone, display, media player, music player and weather in this device’s View Assist settings, then refresh.')
                if op == 'dashboards':
                    templates = await hass.async_add_executor_job(load_templates)
                    configs = render(data['plan'], data['va_sensor'], attrs.get('weather_entity', ''), msg.get('music_path', ''), templates)
                    result = {'configs': {data['plan'][k+'_slug']: configs[k] for k in ('home', 'va', 'tv')},
                              'resources': ['/vaca_vision/es8-assets/athena-music-card.js?v=2',
                                '/vaca_vision/es8-assets/athena-dashboard-surface.js?v=3&dashboards='+','.join(data['plan'][k] for k in ('home_slug','va_slug','tv_slug'))]}
                else:
                    dashboard = '/' + data['plan']['home_slug'] + '/home'
                    hass.config_entries.async_update_entry(entry, options={**entry.options, 'dashboard': dashboard})
                    result = {'message': 'Dashboards installed. Set the View Assist dashboard paths shown below.', 'paths': dashboard_paths(data['plan'])}
        connection.send_result(msg['id'], result)
    except Exception as exc:
        # No credentials or exception bodies from lower-level connections.
        message = str(exc) if isinstance(exc, ValueError) else 'Setup could not finish (' + type(exc).__name__ + '). Check HA logs and retry.'
        connection.send_error(msg['id'], 'setup_failed', message[:400])
