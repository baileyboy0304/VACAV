"""Direct EDA voice ownership, under the existing Vision config entry/device.

No ESPHome config entry is created. EDA owns recording, wake words and reply
playback and the light band; Android receives only expiring audio-focus commands.
"""

import asyncio
import contextlib
import logging

from aioesphomeapi import (
    APIClient,
    MediaPlayerCommand,
    MediaPlayerInfo,
    VoiceAssistantTimerEventType,
)
from aioesphomeapi import VoiceAssistantEventType as NativeEvent
from homeassistant.components.assist_satellite import (
    AssistSatelliteConfiguration,
    AssistSatelliteWakeWord,
)
from homeassistant.components.intent import async_register_timer_handler
from homeassistant.components.media_player import async_process_play_media_url
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er

from .const import DOMAIN
from .dot import DotSatellite
from .upstream_pairing import ArbitrationTags, authenticated_values

_LOGGER = logging.getLogger(__name__)


def client_for(values):
    return APIClient(
        address=values["host"],
        port=values.get("eda_port", 26053),
        password="",
        noise_psk=values.get("eda_noise_psk") or None,
    )


async def validate_upstream(hass, values, entry_id=None, *, expose_music=False):
    """Refuse a second owner, and pin the physical EDA identity at pairing."""
    for entry in hass.config_entries.async_entries(include_ignore=False):
        if entry.entry_id == entry_id:
            continue
        other = {**entry.data, **entry.options}
        if entry.domain in (DOMAIN, "esphome") and other.get("host") == values["host"]:
            raise ValueError("eda_already_owned")
    from .upstream_music import validate_music

    await validate_music(hass, values, expose=expose_music)
    values.update(await authenticated_values(hass, values))
    cli = client_for(values)
    try:
        async with asyncio.timeout(15):
            await cli.connect()
            info = await cli.device_info()
            if not info.project_name.startswith("hassmic."):
                raise ValueError("not_eda")
            mac = info.mac_address.lower()
            for entry in hass.config_entries.async_entries(include_ignore=False):
                if entry.entry_id != entry_id and (
                    (entry.domain == "esphome" and entry.unique_id == mac)
                    or (entry.domain == DOMAIN and entry.data.get("eda_mac") == mac)
                ):
                    raise ValueError("eda_already_owned")
            if values.get("eda_mac") and values["eda_mac"] != mac:
                raise ValueError("eda_identity_changed")
            return mac
    finally:
        with contextlib.suppress(Exception):
            await cli.disconnect()


class UpstreamSatellite(DotSatellite):
    """Reuse bounded capture and focus leases, with native EDA output."""

    def __init__(self, runtime):
        super().__init__(runtime)
        # Keep the deployed Crown satellite identity when switching backends.
        self._attr_unique_id = f"{runtime.info.id}-assist_satellite"
        self._attr_name = "Assist"
        self._reply_key = None

    async def async_added_to_hass(self):
        await super().async_added_to_hass()
        if self.registry_entry and self.registry_entry.device_id:
            self.async_on_remove(
                async_register_timer_handler(
                    self.hass, self.registry_entry.device_id, self.timer_event
                )
            )

    def timer_event(self, event_type, timer):
        if self.cli and self._native_connected:
            native = VoiceAssistantTimerEventType[
                f"VOICE_ASSISTANT_TIMER_{event_type.value.upper()}"
            ]
            self.cli.send_voice_assistant_timer_event(
                native,
                timer.id,
                timer.name,
                timer.created_seconds,
                timer.seconds_left,
                timer.is_active,
            )

    @property
    def pipeline_entity_id(self):
        suffix = f"_{self._wake_index + 1}" if self._wake_index else ""
        return er.async_get(self.hass).async_get_entity_id(
            "select", DOMAIN, f"{self.runtime.info.id}-pipeline{suffix}"
        )

    @property
    def vad_sensitivity_entity_id(self):
        return er.async_get(self.hass).async_get_entity_id(
            "select", DOMAIN, f"{self.runtime.info.id}-vad_sensitivity"
        )

    async def _connect(self):
        while True:
            cli = None
            pairing = None
            disconnected = asyncio.Event()
            unsubscribe = None

            async def stopped(expected, signal=disconnected):
                signal.set()

            try:
                values = await authenticated_values(self.hass, self.runtime.values)
                cli = client_for(values)
                await cli.connect(on_stop=stopped)
                info = await cli.device_info()
                if (
                    not info.project_name.startswith("hassmic.")
                    or info.mac_address.lower() != self.runtime.values["eda_mac"]
                ):
                    raise ValueError("EDA identity changed; reconfigure explicitly")
                cfg = await cli.get_voice_assistant_configuration(10)
                entities, _ = await cli.list_entities_services()
                self._reply_key = next(
                    (e.key for e in entities if isinstance(e, MediaPlayerInfo)), None
                )
                self._config = AssistSatelliteConfiguration(
                    available_wake_words=[
                        AssistSatelliteWakeWord(
                            id=w.id,
                            wake_word=w.wake_word,
                            trained_languages=list(w.trained_languages),
                        )
                        for w in cfg.available_wake_words
                    ],
                    active_wake_words=list(cfg.active_wake_words),
                    max_active_wake_words=cfg.max_active_wake_words,
                )
                self.cli = cli
                pairing = ArbitrationTags(self.hass, cli,
                    self.registry_entry.device_id if self.registry_entry else None, self.task)
                pairing.start()
                unsubscribe = cli.subscribe_voice_assistant(
                    handle_start=self.start,
                    handle_stop=self.stop,
                    handle_audio=self.audio,
                    handle_announcement_finished=self.announcement_finished,
                )
                self._native_connected = True
                self.async_write_ha_state()
                await disconnected.wait()
            except asyncio.CancelledError:
                raise
            except Exception as ex:  # noqa: BLE001 - reconnect boundary; never log the PSK
                _LOGGER.warning("EDA connection unavailable (%s)", type(ex).__name__)
            finally:
                self._native_connected = False
                if pairing:
                    pairing.close()
                if unsubscribe:
                    with contextlib.suppress(Exception):
                        unsubscribe()
                await self.cancel()
                self.cli = None
                with contextlib.suppress(Exception):
                    if cli:
                        await cli.disconnect()
                self.async_write_ha_state()
            await asyncio.sleep(5)

    def on_pipeline_event(self, event):
        if not self.session:
            return
        kind, data = event.type.value, event.data or {}
        if kind == "run-start":
            path = (data.get("tts_output") or {}).get("url")
            self.reply_url = (
                async_process_play_media_url(self.hass, path) if path else None
            )
            self.send(
                NativeEvent.VOICE_ASSISTANT_RUN_START,
                {"url": self.reply_url} if self.reply_url else {},
            )
        elif (
            kind == "intent-progress"
            and data.get("tts_start_streaming")
            and self.reply_url
        ):
            self.reply_sent = True
            self.send(
                NativeEvent.VOICE_ASSISTANT_INTENT_PROGRESS,
                {"tts_start_streaming": "1"},
            )
        elif kind == "tts-end":
            path = (data.get("tts_output") or {}).get("url")
            if path:
                self.reply_sent = True
                self.send(
                    NativeEvent.VOICE_ASSISTANT_TTS_END,
                    {"url": async_process_play_media_url(self.hass, path)},
                )
        elif kind == "run-end":
            self.ended = True
            # EDA receives pipeline completion even while its reply is playing.
            self.send(NativeEvent.VOICE_ASSISTANT_RUN_END)
            if not self.reply_sent or self.reply_done:
                self.task(self.finish(self.session))
        else:
            super().on_pipeline_event(event)

    async def cancel(self):
        if self.session and self.cli and self._native_connected:
            # Native replies must stop too: releasing Android focus alone would
            # let an abandoned reply continue over restored streaming audio.
            with contextlib.suppress(Exception):
                if self._reply_key is not None:
                    self.cli.media_player_command(
                        self._reply_key, command=MediaPlayerCommand.STOP
                    )
                self.send(
                    NativeEvent.VOICE_ASSISTANT_ERROR,
                    {"code": "cancelled", "message": "Voice session ended"},
                )
        await super().cancel()

    async def announcement_finished(self, result):
        if not self.session:
            return
        self._playback_failure = not result.success
        self.reply_done = True
        if self.ended or not result.success:
            await self.finish(self.session)

    async def play(self, session, url, preannounce=None):
        if session != self.session or self.cli is None:
            return
        try:
            result = await self.cli.send_voice_assistant_announcement_await_response(
                media_id=url,
                preannounce_media_id=async_process_play_media_url(
                    self.hass, preannounce
                )
                if preannounce
                else "",
                timeout=180,
            )
        except Exception as ex:
            if session == self.session:
                self._playback_failure = True
                await self.cancel()
            raise HomeAssistantError("EDA announcement failed") from ex
        if session == self.session:
            await self.announcement_finished(result)

    async def stop(self, abort):
        if abort:
            await self.cancel()
            return
        try:
            async with asyncio.timeout(1):
                await self.queue.put(None)
        except TimeoutError:
            await self.cancel()

    async def finish(self, session):
        if not session or session != self.session:
            return
        self.session = ""
        if self._lease and self._lease is not asyncio.current_task():
            self._lease.cancel()
        with contextlib.suppress(HomeAssistantError, ConnectionError):
            await self.runtime.action("voice-end", {"session": session})
        self.tts_response_finished()
        self._done.set()
