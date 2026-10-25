"""Identify physical Sendspin players and retain installer choices per display."""
from ipaddress import ip_address
import json


def address(value):
    try:
        return str(ip_address(str(value).split('%')[0]))
    except ValueError:
        return None


def physical_sendspin(player, providers):
    provider = providers.get(player.provider)
    domain = getattr(provider, 'domain', player.provider)
    sendspin = domain == 'sendspin' or (domain == 'universal_player' and any(
        protocol.protocol_domain == 'sendspin' and protocol.available
        and not getattr(protocol, 'derived_from', None)
        for protocol in getattr(player, 'output_protocols', ())))
    return (sendspin and str(player.type) == 'player' and player.available
            and getattr(player, 'enabled', True) and not getattr(player, 'needs_setup', False))


def player_matches(player, host, mac=None, client_id=None):
    if client_id:
        return player.player_id == client_id or any(
            p.protocol_domain == 'sendspin' and p.output_protocol_id == client_id
            for p in getattr(player, 'output_protocols', ()))
    info = player.device_info
    if mac and getattr(info, 'mac_address', None):
        normalize = lambda v: str(v).lower().replace(':', '').replace('-', '')
        return normalize(info.mac_address) == normalize(mac)
    return address(host) is not None and address(getattr(info, 'ip_address', None)) == address(host)


async def music_choices(hass, host, mac=None, client_id=None):
    from homeassistant.components.music_assistant.helpers import get_music_assistant_client
    from homeassistant.exceptions import HomeAssistantError
    choices, matches = {}, []
    for entry in hass.config_entries.async_entries('music_assistant'):
        try:
            client = get_music_assistant_client(hass, entry.entry_id)
            providers = {p.instance_id: p for p in client.providers}
            for player in client.players.players:
                if not physical_sendspin(player, providers):
                    continue
                key = json.dumps([entry.entry_id, player.player_id], separators=(',', ':'))
                ip = getattr(player.device_info, 'ip_address', None)
                choices[key] = f'{player.name} ({ip or "address unavailable"}) — {entry.title}'
                if player_matches(player, host, mac, client_id):
                    matches.append(key)
        except HomeAssistantError:
            continue
    return choices, matches


async def setup_defaults(hass, device_id, values=None):
    from homeassistant.helpers.storage import Store
    store = Store(hass, 1, 'vaca_vision.setup_defaults')
    import asyncio
    async with hass.data.setdefault('vaca_vision_setup_defaults_lock', asyncio.Lock()):
        saved = await store.async_load() or {}
        if values is not None:
            saved[device_id] = values
            await store.async_save(saved)
        return saved.get(device_id, {})
