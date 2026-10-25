"""Entry-owned state and optional View Assist timer routing."""

import asyncio
import logging
from contextlib import suppress
from dataclasses import replace
from uuid import uuid4

from homeassistant.core import Event
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import (
    DOMAIN,
    DOT_ROLES,
    MIN_APP_VERSION,
    OVERLAY_ROLES,
    ROLE_CROWN,
    ROLE_UPSTREAM,
    ROLE_STANDARD,
    VERSION,
)
from .microphone import microphone_state, watch_microphone
from .picker_profiles import validate_profile
from .protocol import OverlayConnection

_LOGGER = logging.getLogger(__name__)


class OverlayRuntime:
    def __init__(self, hass, entry, info):
        self.hass, self.entry, self.info = hass, entry, info
        self.role = entry.options.get("role", entry.data.get("role", ROLE_STANDARD))
        self.is_overlay = self.role in OVERLAY_ROLES
        self.values = {**entry.data, **entry.options}
        self.has_dot = self.role in DOT_ROLES
        self.dot_satellite = None
        self.dot_device = None
        self.has_upstream = self.role == ROLE_UPSTREAM
        self.has_crown = self.role == ROLE_CROWN
        if self.role not in info.capabilities.get("roles", []):
            raise ValueError("This Vision build does not support the configured role")
        self.crown_device = None
        self.crown_service = None
        self.listeners = set()
        self.state = {"overlay_enabled": True, "alarm": False, "alarm_count": 0}
        self.display_settings = {}
        self.display_store = Store(
            hass, 1, f"{DOMAIN}.{entry.entry_id}.display_settings"
        )
        self.timer_ids = set()
        self.pending_dismissals = set()
        self.dismissed_timers = set()
        self.dismissal_store = Store(
            hass, 1, f"{DOMAIN}.{entry.entry_id}.dismissed_timers"
        )
        self.timer_task = None
        self.connection = OverlayConnection(
            entry.data["host"], entry.data["port"], info.id, self.settings, self.changed
        )
        self.task = None
        self.device_id = None
        self.profile_waiters = {}
        self.profile_tasks = set()

    def settings(self):
        return {
            "integration_version": VERSION,
            "min_required_apk_version": MIN_APP_VERSION,
            "ha_url": self.hass.config.internal_url or "",
            "time_zone": self.hass.config.time_zone,
            "role": self.role,
            "ha_dashboard": self.entry.options.get(
                "dashboard", self.entry.data.get("dashboard", "/view-assist/clock")
            ),
            "screen_always_on": True,
            "screen_on": True,
            "fire_tv_remote": False,
            "fire_tv_resume": False,
            "picker_profiles": 1 if self.is_overlay else 0,
            **self.display_settings,
            **({"microphone": microphone_state(self)} if self.has_dot else {}),
        }

    @property
    def device_info(self):
        return dr.DeviceInfo(
            identifiers={(DOMAIN, self.info.id)},
            name=self.entry.title,
            manufacturer="VACA_Vision",
            model=self.info.capabilities.get("device_signature", "TV overlay"),
            sw_version=self.info.version,
        )

    def changed(self, kind, data):
        if kind == "playback":
            self.state.update(data)
            if self.dot_satellite:
                self.dot_satellite.playback(data)
        if kind == "disconnected" and self.dot_satellite:
            self.dot_satellite.task(self.dot_satellite.cancel())
        if kind == "picker-profile-device-result":
            future = self.profile_waiters.get(data.get("request_id"))
            if future is not None and not future.done():
                future.set_result(data)
            return
        if kind == "picker-profile-request":
            if len(self.profile_tasks) < 4:
                task = self.entry.async_create_task(
                    self.hass, self.profile_request(data), "Picker profile request"
                )
                self.profile_tasks.add(task)
                task.add_done_callback(self.profile_tasks.discard)
            return
        if kind == "disconnected":
            self.state.pop("crown", None)
            self.cancel_profiles()
        if kind == "connected":
            self.info = replace(
                self.info,
                version=data.get("version", self.info.version),
                capabilities=data.get("capabilities", self.info.capabilities),
            )
            self.state["app_version"] = data["version"]
        elif kind in ("settings", "status"):
            self.state.update(data.get("sensors", {}))
            self.state.update(data.get("settings", {}))
            # The floating controls change these settings on the Android device.
            # Retain their acknowledged values so reconnect cannot restore an old
            # HA value over the owner's local choice. Never persist transient
            # screen/motion status or allow the app to change role/voice ownership.
            if self.has_upstream and kind == "settings":
                reported = data.get("settings", {})
                local = {}
                keep = reported.get("screen_always_on")
                level = reported.get("screen_brightness")
                if type(keep) is bool:
                    local["screen_always_on"] = keep
                if type(level) in (int, float) and 0 <= level <= 100:
                    local["screen_brightness"] = level
                if any(self.display_settings.get(k) != v for k, v in local.items()):
                    self.display_settings.update(local)
                    self.display_store.async_delay_save(lambda: dict(self.display_settings), 1)
            for key in (
                "alarm",
                "alarm_count",
                "overlay_enabled",
                "crown",
            ):
                if key in data:
                    self.state[key] = data[key]
            # Acknowledged dismissals are retried by the app until HA receives them.
            for alarm_id in data.get("dismissed_alarm_ids", []):
                if alarm_id not in self.pending_dismissals:
                    self.pending_dismissals.add(alarm_id)
                    self.entry.async_create_task(
                        self.hass, self.dismissed(alarm_id), "Overlay alarm dismissal"
                    )
        for listener in list(self.listeners):
            listener()

    def cancel_profiles(self):
        for future in self.profile_waiters.values():
            if not future.done():
                future.set_exception(
                    HomeAssistantError(
                        "Device disconnected; check its picker before retrying"
                    )
                )
        for task in self.profile_tasks:
            task.cancel()

    async def profile_request(self, data):
        identity = data.get("request_id")
        if not isinstance(identity, str) or not 1 <= len(identity) <= 80:
            return
        reply = {"request_id": identity}
        try:
            payload = data.get("payload", {})
            if not isinstance(payload, dict):
                raise TypeError("Invalid profile request")
            result = await self.hass.data[DOMAIN]["picker_profiles"].request(
                data.get("operation"), payload
            )
            reply.update(ok=True, result=result)
        except (ValueError, TypeError, KeyError) as ex:
            reply.update(ok=False, error=str(ex)[:180])
        except OSError:
            reply.update(ok=False, error="Home Assistant could not save the profile")
        try:
            await self.action("picker-profile-result", reply)
        except HomeAssistantError:
            pass

    async def picker_device(self, operation, profile=None):
        if self.info.capabilities.get("picker_profiles") != 1:
            raise HomeAssistantError(
                "Install VACA_Vision preview.39 or later on this device"
            )
        identity = str(uuid4())
        payload = {"request_id": identity, "operation": operation}
        if profile is not None:
            payload["profile"] = validate_profile(profile)
        future = self.hass.loop.create_future()
        self.profile_waiters[identity] = future
        try:
            await self.action("picker-profile-device", payload)
            async with asyncio.timeout(15):
                result = await future
            if not result.get("ok"):
                raise HomeAssistantError(
                    result.get("error", "Device rejected picker operation")
                )
            return result.get("result", {})
        except TimeoutError as ex:
            raise HomeAssistantError(
                "Device did not acknowledge; check its picker before retrying"
            ) from ex
        finally:
            self.profile_waiters.pop(identity, None)

    async def action(self, action, payload=None):
        try:
            await self.connection.action(action, payload)
        except (OSError, TimeoutError) as ex:
            raise HomeAssistantError(
                "VACA_Vision is offline; the action was not queued"
            ) from ex

    async def set_display(self, settings):
        await self.action("set-display", settings)
        self.display_settings.update(settings)
        await self.display_store.async_save(self.display_settings)

    async def show_alarm(self, message="Alarm", title="Alarm", alarm_id=None):
        await self.action(
            "alarm",
            {
                "activate": True,
                "id": alarm_id or str(uuid4()),
                "title": title[:80],
                "message": message[:500],
            },
        )

    async def dismissed(self, alarm_id):
        try:
            if alarm_id.startswith("va:"):
                timer_id = alarm_id[3:]
                self.dismissed_timers.add(timer_id)
                await self.dismissal_store.async_save(sorted(self.dismissed_timers))
                if self.hass.services.has_service("view_assist", "cancel_timer"):
                    try:
                        await self.hass.services.async_call(
                            "view_assist",
                            "cancel_timer",
                            {"timer_id": timer_id},
                            blocking=True,
                        )
                    except (HomeAssistantError, KeyError):
                        # Some View Assist versions pop ESPHome intent timers on
                        # expiry, then raise KeyError when cancelling them later.
                        # Dismiss the TV popup even if the separate mic cannot stop.
                        _LOGGER.warning(
                            "View Assist could not cancel timer %s; its TV popup remains dismissed",
                            timer_id,
                        )
                self.timer_ids.discard(timer_id)
            self.hass.bus.async_fire(
                f"{DOMAIN}_alarm_dismissed",
                {
                    "device_id": self.device_id,
                    "display_device": f"va-{self.info.id}",
                    "alarm_id": alarm_id,
                },
            )
            await self.action("ack-alarm-dismissal", {"id": alarm_id})
        except HomeAssistantError:
            _LOGGER.warning(
                "Alarm dismissal could not be delivered; it will be retried on reconnect"
            )
        finally:
            self.pending_dismissals.discard(alarm_id)

    async def timer_event(self, event):
        """Match the View Assist display, not its separate microphone device."""
        data = event.data
        state = self.hass.states.get(data.get("entity_id", ""))
        if (
            state is None
            or state.attributes.get("display_device") != f"va-{self.info.id}"
        ):
            return
        timer_id = str(data.get("timer_id", data.get("id", "")))
        if not timer_id:
            return
        try:
            if event.event_type == "va_timer_expired":
                if timer_id in self.dismissed_timers or timer_id in self.timer_ids:
                    return
                await self.show_alarm(
                    data.get("name") or "Time is up", "Alarm", f"va:{timer_id}"
                )
                self.timer_ids.add(timer_id)
            elif timer_id in self.timer_ids:
                self.timer_ids.discard(timer_id)
                await self.action(
                    "alarm",
                    {"activate": False, "id": f"va:{timer_id}", "notify": False},
                )
        except HomeAssistantError:
            _LOGGER.warning(
                "Could not deliver View Assist timer event to %s", self.entry.title
            )

    async def legacy_alarm_event(self, event):
        if event.data.get("device_id") != self.device_id:
            return
        try:
            if event.event_type == "va_alarm_start":
                await self.show_alarm(
                    event.data.get("message", "Time is up"), alarm_id="legacy"
                )
            else:
                await self.action("alarm", {"activate": False, "id": "legacy"})
        except HomeAssistantError:
            _LOGGER.warning("Could not deliver alarm to %s", self.entry.title)

    def start(self):
        if self.has_dot:
            watch_microphone(self)
        self.task = self.entry.async_create_background_task(
            self.hass, self.connection.run(), "Overlay TCP connection"
        )
        if not self.is_overlay:
            return
        self.timer_task = self.entry.async_create_background_task(
            self.hass, self.watch_timers(), "Overlay View Assist timers"
        )
        for event in ("va_timer_expired", "va_timer_cancelled", "va_timer_snoozed"):
            self.entry.async_on_unload(
                self.hass.bus.async_listen(event, self.timer_event)
            )
        for event in ("va_alarm_start", "va_alarm_stop"):
            self.entry.async_on_unload(
                self.hass.bus.async_listen(event, self.legacy_alarm_event)
            )

    async def stop(self):
        self.cancel_profiles()
        if self.profile_tasks:
            await asyncio.gather(*self.profile_tasks, return_exceptions=True)
        if self.timer_task:
            self.timer_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.timer_task
            self.timer_task = None
        if self.task:
            self.task.cancel()
            with suppress(asyncio.CancelledError):
                await self.task
            self.task = None

    async def watch_timers(self):
        while True:
            await asyncio.sleep(2)
            if self.connection.connected:
                try:
                    await self.sync_timers()
                except (HomeAssistantError, TimeoutError):
                    _LOGGER.debug("View Assist timers are not available")

    async def sync_timers(self):
        """The public timer service also covers ESPHome's non-event expiry path."""
        if not self.hass.services.has_service("view_assist", "get_timers"):
            return
        displays = {
            state.entity_id
            for state in self.hass.states.async_all("sensor")
            if state.attributes.get("display_device") == f"va-{self.info.id}"
        }
        if not displays:
            return
        # Do not filter by entity_id in the VA service: current VA versions remove
        # expired ESPHome timers from that filtered result. Filter by display here.
        async with asyncio.timeout(5):
            response = await self.hass.services.async_call(
                "view_assist",
                "get_timers",
                {"include_expired": True},
                blocking=True,
                return_response=True,
            )
        if not isinstance(response, dict) or not isinstance(
            response.get("result"), list
        ):
            return
        timers = {
            str(timer["id"]): timer
            for timer in response["result"]
            if isinstance(timer, dict)
            and timer.get("id")
            and timer.get("entity_id") in displays
        }
        for timer_id, timer in timers.items():
            if timer.get("status") == "expired":
                # On first pairing, do not replay days of stale expired timers
                # retained by View Assist's ESPHome path. Already visible app
                # alarms remain until dismissed, regardless of this catch-up limit.
                if timer.get("expires") is not None:
                    try:
                        if (
                            dt_util.utcnow().timestamp()
                            - dt_util.as_timestamp(timer["expires"])
                            > 600
                        ):
                            continue
                    except (ValueError, TypeError):
                        continue
                await self.timer_event(
                    Event("va_timer_expired", {**timer, "timer_id": timer_id})
                )
        for timer_id in list(self.timer_ids):
            if timer_id not in timers or timers[timer_id].get("status") != "expired":
                await self.action(
                    "alarm",
                    {"activate": False, "id": f"va:{timer_id}", "notify": False},
                )
                self.timer_ids.discard(timer_id)
        retained = {
            timer_id
            for timer_id in self.dismissed_timers
            if timer_id in timers and timers[timer_id].get("status") == "expired"
        }
        if retained != self.dismissed_timers:
            self.dismissed_timers = retained
            await self.dismissal_store.async_save(sorted(retained))
