"""Non-secret USB handoff and reviewed dashboard rendering. Never executes device code."""
import copy
import json
from pathlib import Path
import re

SERVICES = {'netflix': 'com.netflix.mediaclient.echo', 'iplayer': 'bbc.iplayer.android',
            'itvx': 'air.ITVMobilePlayer', 'apple_tv': 'com.amazon.cloud9'}
DEFAULTS = {'home_slug': 'athena-home', 'va_slug': 'view-assist-athena', 'tv_slug': 'athena-tv'}


def validate_plan(value):
    if not isinstance(value, dict) or value.get('version') != 1:
        raise ValueError('Run the current Linux VACAV installer to prepare this device.')
    name = value.get('name')
    if not isinstance(name, str) or not 1 <= len(name) <= 80:
        raise ValueError('Invalid device name in USB setup choices.')
    result = {'version': 1, 'name': name}
    for key, default in DEFAULTS.items():
        slug = value.get(key, default)
        if not isinstance(slug, str) or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)+', slug):
            raise ValueError('Invalid dashboard path in USB setup choices.')
        result[key] = slug
    if len({result[k] for k in DEFAULTS}) != 3:
        raise ValueError('Dashboard paths must be distinct.')
    services = value.get('services', [])
    if not isinstance(services, list) or any(s not in SERVICES for s in services):
        raise ValueError('Unknown streaming service in USB setup choices.')
    result['services'] = sorted(set(services))
    identity = value.get('sendspin_client_id', '')
    if identity and (not isinstance(identity, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', identity)):
        raise ValueError('Invalid public Sendspin identity.')
    result['sendspin_client_id'] = identity
    return result


def render(plan, va_entity, weather, music_path, templates):
    plan = validate_plan(plan)
    if not re.fullmatch(r'sensor\.[a-z0-9_]+', va_entity) or not re.fullmatch(r'weather\.[a-z0-9_]+', weather):
        raise ValueError('Select the real View Assist and weather entities.')
    if not re.fullmatch(r'/[A-Za-z0-9_/-]+', music_path):
        raise ValueError('Use the local Music Assistant sidebar path.')
    replacements = {'sensor.es8_setup': va_entity, 'weather.home': weather,
                    '/music-assistant': music_path, '/local/es8-athena/': '/vaca_vision/es8-assets/'}
    replacements.update({'/' + default: '/' + plan[key] for key, default in DEFAULTS.items()})
    pattern = re.compile('|'.join(re.escape(k) for k in sorted(replacements, key=len, reverse=True)))
    def sub(value):
        if isinstance(value, str): return pattern.sub(lambda m: replacements[m[0]], value)
        if isinstance(value, dict): return {k: sub(v) for k, v in value.items()}
        if isinstance(value, list): return [sub(v) for v in value]
        return value
    result = sub(copy.deepcopy(templates))
    cards = result['tv']['views'][0]['cards'][0]['custom_fields']['content']['card']['cards']
    selected = [SERVICES[s] for s in plan['services']]
    cards[:] = [c for c in cards if c.get('name') == 'Home' or any(
        package in c.get('tap_action', {}).get('url_path', '') for package in selected)]
    grid = result['tv']['views'][0]['cards'][0]['custom_fields']['content']['card']
    grid['columns'] = min(5, len(cards))
    music = result['home']['views'][1]['cards'][0]
    addon = re.fullmatch(r'/(?:app/)?([a-z0-9]+_music_assistant)', music_path)
    if addon: music['addon'] = addon[1]
    return result


def load_templates():
    return {k: json.loads((Path(__file__).parent / 'es8' / (k + '.json')).read_text())
            for k in ('home', 'va', 'tv')}
