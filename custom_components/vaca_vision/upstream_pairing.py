"""Authenticated EDA adoption and its narrowly scoped arbitration tag bridge."""

import asyncio
import base64
import contextlib
import hashlib
import re
import secrets

from aioesphomeapi import APIClient, InvalidEncryptionKeyAPIError, ZERO_NOISE_PSK
from homeassistant.components.tag import async_scan_tag
from homeassistant.helpers.storage import Store

TAG = re.compile(r"hassmic_[0-9a-f]{64}\Z")


def check_identity(info, values, saved_mac=None):
    if not info.project_name.startswith("hassmic."):
        raise ValueError("not_eda")
    mac = info.mac_address.lower()
    for expected in (values.get("eda_mac"), saved_mac):
        if expected and expected.lower() != mac:
            raise ValueError("eda_identity_changed")
    return mac


async def authenticated_values(hass, values):
    # Options validation and an entry reconnect may otherwise provision together.
    address = (values["host"], values.get("eda_port", 26053))
    locks = hass.data.setdefault("vaca_vision_eda_key_locks", {})
    async with locks.setdefault(address, asyncio.Lock()):
        return await _authenticated_values(hass, values)


async def _authenticated_values(hass, values):
    """Provision an unkeyed EDA once; persist before sending, recover interrupted setup.

    Keys stay in HA's private storage. A supplied existing key is never replaced.
    A failed normal connection never causes key rotation or a plaintext fallback.
    """
    if values.get("eda_noise_psk"):
        return values
    address = f'{values["host"]}:{values.get("eda_port", 26053)}'
    store = Store(hass, 1, "vaca_vision.eda_key_" + hashlib.sha256(address.encode()).hexdigest())
    saved = await store.async_load()
    if saved and saved.get("confirmed"):
        if values.get("eda_mac") and values["eda_mac"].lower() != saved["mac"]:
            raise ValueError("eda_identity_changed")
        return {**values, "eda_noise_psk": saved["key"]}

    def client(key):
        return APIClient(values["host"], values.get("eda_port", 26053), "", noise_psk=key)

    # If setup stopped after the device accepted the key, recover that exact key.
    if saved:
        cli = client(saved["key"])
        try:
            async with asyncio.timeout(15):
                await cli.connect()
                check_identity(await cli.device_info(), values, saved["mac"])
            await store.async_save({**saved, "confirmed": True})
            return {**values, "eda_noise_psk": saved["key"]}
        except InvalidEncryptionKeyAPIError:
            pass  # Only an unprovisioned device can accept the zero-PSK retry.
        finally:
            with contextlib.suppress(Exception):
                await cli.disconnect()

    cli = client(ZERO_NOISE_PSK)
    try:
        async with asyncio.timeout(15):
            await cli.connect()
            info = await cli.device_info()
            mac = check_identity(info, values, saved["mac"] if saved else None)
            if not info.api_encryption_provisionable:
                raise ValueError("eda_key_required")
            if not saved:
                saved = {"key": base64.b64encode(secrets.token_bytes(32)).decode(), "mac": mac, "confirmed": False}
                await store.async_save(saved)
            if not await cli.noise_encryption_set_key(saved["key"].encode("ascii")):
                raise ValueError("eda_key_required")
            await store.async_save({**saved, "confirmed": True})
    finally:
        with contextlib.suppress(Exception):
            await cli.disconnect()
    return {**values, "eda_noise_psk": saved["key"]}


class ArbitrationTags:
    """Only EDA pairing tags: no general service execution or HA state access."""

    def __init__(self, hass, cli, device_id, task):
        self.hass, self.cli, self.device_id, self.task = hass, cli, device_id, task
        self.active = True

    def start(self):
        self.cli.subscribe_service_calls(self.on_action)
        self.cli.subscribe_home_assistant_states(self.on_state, self.on_state)

    def close(self):
        self.active = False  # API subscriptions are removed by client.disconnect().

    def on_action(self, request):
        if (not self.active or not self.device_id or not request.is_event
                or request.service != "esphome.tag_scanned"
                or request.data_template or request.variables):
            return
        tag_id = request.data.get("tag_id", "")
        if TAG.fullmatch(tag_id):
            self.task(self.scan(tag_id))

    async def scan(self, tag_id):
        if self.active:
            await async_scan_tag(self.hass, tag_id, self.device_id)

    def on_state(self, entity_id, attribute=None):
        if not self.active or attribute or not entity_id.startswith("tag."):
            return
        if not TAG.fullmatch(entity_id[4:]):
            return
        state = self.hass.states.get(entity_id)
        self.cli.send_home_assistant_state(entity_id, None, state.state if state else "unknown")
