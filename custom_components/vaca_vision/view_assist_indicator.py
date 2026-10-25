"""Use View Assist's custom-overlay extension for EDA's native voice band."""

import asyncio
import logging
import os
import tempfile
from html.parser import HTMLParser
from pathlib import Path

from homeassistant.const import EVENT_HOMEASSISTANT_STARTED, EVENT_STATE_CHANGED
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers.storage import Store

from .const import DOMAIN, ROLE_UPSTREAM

OPTION = "eda_native_indicator"
OVERLAY_ID = "native_voice_indicator"
_LOGGER = logging.getLogger(__name__)


class _Ids(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        self.ids.add(dict(attrs).get("id"))


def install_overlay(config_path):
    """Append our empty overlay atomically, preserving every existing byte."""
    target = Path(config_path) / "view_assist/custom_overlays/overlay.html"
    snippet = (
        Path(__file__).parent / "resources/view_assist/native_indicator.html"
    ).read_bytes()
    existing = target.read_bytes() if target.exists() else b""
    parsed = _Ids()
    parsed.feed(existing.decode("utf-8"))
    if OVERLAY_ID in parsed.ids:
        if snippet.strip() not in existing:
            raise ValueError("A different custom overlay already uses our indicator ID")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    pending = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
            pending = Path(stream.name)
            stream.write(existing + (b"\n" if existing else b"") + snippet)
        if target.exists():
            pending.chmod(target.stat().st_mode)
        os.replace(pending, target)
    finally:
        if pending is not None:
            pending.unlink(missing_ok=True)


class ViewAssistIndicator:
    """One-time per-device selection, with rollback and late View Assist discovery."""

    def __init__(self, hass, entry, *, release=False):
        self.hass = hass
        self.entry = entry
        self.store = Store(hass, 1, f"{DOMAIN}.native_indicator.{entry.entry_id}")
        self.records = {}
        self.task = None
        self.closed = False
        self.release = release
        self.installed = False
        self.refresh_task = None
        self.runtime = getattr(entry, "runtime_data", None)

    @property
    def enabled(self):
        values = {**self.entry.data, **self.entry.options}
        return (
            not self.release
            and values.get("role") == ROLE_UPSTREAM
            and values.get(OPTION, True)
        )

    async def start(self):
        self.records = await self.store.async_load() or {}
        await self.reconcile()
        if self.enabled:
            for event in (
                EVENT_HOMEASSISTANT_STARTED,
                EVENT_STATE_CHANGED,
                er.EVENT_ENTITY_REGISTRY_UPDATED,
            ):
                self.entry.async_on_unload(
                    self.hass.bus.async_listen(event, self.changed)
                )
            self.entry.async_on_unload(self.close)
            if self.runtime is not None:
                self.runtime.listeners.add(self.runtime_changed)
                self.runtime_changed()

    @callback
    def changed(self, event):
        if event.event_type == EVENT_STATE_CHANGED:
            state = event.data.get("new_state")
            # View Assist can be installed or pointed at this satellite after Vision.
            if not state or not state.attributes.get("mic_device"):
                return
        if not self.closed and (self.task is None or self.task.done()):
            self.task = self.hass.async_create_task(self.reconcile())

    @callback
    def close(self):
        self.closed = True
        if self.task is not None:
            self.task.cancel()
        if self.refresh_task is not None:
            self.refresh_task.cancel()
        if self.runtime is not None:
            self.runtime.listeners.discard(self.runtime_changed)

    @callback
    def runtime_changed(self):
        if (
            not self.closed
            and self.runtime is not None
            and self.runtime.connection.connected
            and any(saved.get("refresh_pending") for saved in self.records.values())
            and (self.refresh_task is None or self.refresh_task.done())
        ):
            self.refresh_task = self.hass.async_create_task(self.refresh_dashboard())

    async def refresh_dashboard(self):
        # Let View Assist apply its device options before the browser reconnects.
        await asyncio.sleep(1)
        try:
            await self.runtime.action("refresh")
        except HomeAssistantError:
            return  # Retry on the next connection/status event.
        for saved in self.records.values():
            saved.pop("refresh_pending", None)
        await self.store.async_save(self.records)

    def satellites(self):
        registry = er.async_get(self.hass)
        return {
            entity.entity_id
            for entity in er.async_entries_for_config_entry(
                registry, self.entry.entry_id
            )
            if entity.domain == "assist_satellite"
        }

    async def reconcile(self):
        lock = self.hass.data.setdefault(
            f"{DOMAIN}_native_indicator_lock", asyncio.Lock()
        )
        async with lock:
            try:
                await self._reconcile()
            except (OSError, ValueError) as ex:
                _LOGGER.warning(
                    "Unable to configure View Assist's native indicator: %s", ex
                )
                ir.async_create_issue(
                    self.hass,
                    DOMAIN,
                    f"native_indicator_{self.entry.entry_id}",
                    is_fixable=False,
                    severity=ir.IssueSeverity.WARNING,
                    translation_key="native_indicator",
                    translation_placeholders={"name": self.entry.title},
                )
            else:
                ir.async_delete_issue(
                    self.hass, DOMAIN, f"native_indicator_{self.entry.entry_id}"
                )
                self.runtime_changed()

    async def _reconcile(self):
        entries = {
            entry.entry_id: entry
            for entry in self.hass.config_entries.async_entries("view_assist")
        }
        linked = self.satellites() if self.enabled else set()
        # Restore only a value we selected and only while it is still selected.
        for entry_id, saved in list(self.records.items()):
            target = entries.get(entry_id)
            if target and target.data.get("mic_device") in linked:
                continue
            if target:
                display = dict(target.options.get("display_settings", {}))
                if display.get("assist_prompt") == OVERLAY_ID:
                    if saved["present"]:
                        display["assist_prompt"] = saved["value"]
                    else:
                        display.pop("assist_prompt", None)
                    options = dict(target.options)
                    if display or saved["section_present"]:
                        options["display_settings"] = display
                    else:
                        options.pop("display_settings", None)
                    self.hass.config_entries.async_update_entry(target, options=options)
            del self.records[entry_id]
            await self.store.async_save(self.records)
        if not self.enabled:
            return
        if not self.installed:
            await self.hass.async_add_executor_job(
                install_overlay, self.hass.config.path()
            )
            self.installed = True
        for target in entries.values():
            if (
                target.data.get("type") == "master_config"
                or target.data.get("mic_device") not in linked
            ):
                continue
            display = dict(target.options.get("display_settings", {}))
            saved = self.records.get(target.entry_id)
            if saved and saved.get("applied"):
                continue  # Do not fight a subsequent manual choice, including after restart.
            if display.get("assist_prompt") == OVERLAY_ID and saved is None:
                continue  # Already selected by the owner: do not claim it for rollback.
            if saved is None:
                saved = self.records[target.entry_id] = {
                    "present": "assist_prompt" in display,
                    "value": display.get("assist_prompt"),
                    "section_present": "display_settings" in target.options,
                    "applied": False,
                }
                # Persist the old value before changing another integration's options.
                await self.store.async_save(self.records)
            display["assist_prompt"] = OVERLAY_ID
            self.hass.config_entries.async_update_entry(
                target, options={**target.options, "display_settings": display}
            )
            saved["applied"] = True
            saved["refresh_pending"] = True
            await self.store.async_save(self.records)


async def async_prepare_indicator(hass, entry):
    manager = ViewAssistIndicator(hass, entry)
    await manager.start()


async def async_release_indicator(hass, entry):
    manager = ViewAssistIndicator(hass, entry, release=True)
    manager.records = await manager.store.async_load() or {}
    await manager.reconcile()
