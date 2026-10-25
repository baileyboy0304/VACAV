"""Split microphone/output satellite; unmodified echo-dot-assist over ESPHome.

Original projects: MSP1974 (VACA), Dinki (View Assist), Julian Imhof (echo-dot-assist).
Native ESPHome owns settings/updates; Vision is the sole voice subscriber.
"""

import asyncio
import contextlib
import logging
from uuid import uuid4

from aioesphomeapi import APIClient
from aioesphomeapi import VoiceAssistantEventType as DotEvent
from homeassistant.components.assist_pipeline import PipelineStage
from homeassistant.components.assist_satellite import (
    AssistSatelliteConfiguration,
    AssistSatelliteEntity,
    AssistSatelliteEntityFeature,
    AssistSatelliteWakeWord,
)
from homeassistant.components.media_player import async_process_play_media_url
from homeassistant.exceptions import ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.storage import Store

from .const import DOMAIN, DOT_ROLES

_LOGGER = logging.getLogger(__name__)


def dot_target(hass, values, vision_entry_id=None):
    registry = er.async_get(hass)
    native = registry.async_get(values.get("dot_satellite", ""))
    if (
        native is None
        or native.platform != "esphome"
        or native.domain != "assist_satellite"
    ):
        raise ValueError("invalid_dot")
    entry = hass.config_entries.async_get_entry(native.config_entry_id)
    if entry is None or entry.disabled_by:
        raise ValueError("dot_unavailable")
    device = dr.async_get(hass).async_get(native.device_id)
    if device is None or device.manufacturer != "hassmic":
        raise ValueError("invalid_dot")
    for other in hass.config_entries.async_entries(DOMAIN):
        config = {**other.data, **other.options}
        if (
            other.entry_id != vision_entry_id
            and config.get("role") in DOT_ROLES
            and config.get("dot_satellite") == native.entity_id
        ):
            raise ValueError("dot_in_use")
    return entry, native, device


async def prepare_dot(runtime):
    if not runtime.has_dot:
        return
    try:
        entry, native, device = dot_target(
            runtime.hass, runtime.values, runtime.entry.entry_id
        )
    except ValueError as ex:
        raise ConfigEntryNotReady(str(ex)) from ex
    runtime.dot_entry = entry
    runtime.dot_native = native
    runtime.dot_device = device
    store = Store(runtime.hass, 1, f"{DOMAIN}.{runtime.entry.entry_id}.dot_pairing")
    snapshot = await store.async_load()
    if snapshot and snapshot["native_entity"] != native.entity_id:
        # Entry reload has stopped the old voice subscription. Restore its native
        # owner before reserving the replacement, without changing display IDs.
        await release_dot(runtime.hass, runtime.entry)
        snapshot = None
    if not snapshot:
        await store.async_save(
            {
                "native_entity": native.entity_id,
                "dot_entry": entry.entry_id,
                "device_id": device.id,
                "disabled_by": native.disabled_by.value if native.disabled_by else None,
            }
        )
    if native.disabled_by is None:
        er.async_get(runtime.hass).async_update_entity(
            native.entity_id, disabled_by=er.RegistryEntryDisabler.INTEGRATION
        )
        # Ensure the native voice subscription is actually removed before ours starts.
        await runtime.hass.config_entries.async_reload(entry.entry_id)


async def release_dot(hass, entry):
    store = Store(hass, 1, f"{DOMAIN}.{entry.entry_id}.dot_pairing")
    snapshot = await store.async_load()
    if not snapshot:
        return
    registry = er.async_get(hass)
    native = registry.async_get(snapshot["native_entity"])
    if native and native.disabled_by == er.RegistryEntryDisabler.INTEGRATION:
        previous = snapshot.get("disabled_by")
        registry.async_update_entity(
            native.entity_id,
            disabled_by=er.RegistryEntryDisabler(previous) if previous else None,
        )
        await hass.config_entries.async_reload(snapshot["dot_entry"])
    await store.async_remove()


class DotSatellite(AssistSatelliteEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False
    _attr_name = "Assist"
    _attr_supported_features = AssistSatelliteEntityFeature.ANNOUNCE

    def __init__(self, runtime):
        self.runtime = runtime
        self._attr_unique_id = f"{runtime.info.id}_dot_assist"
        self._attr_device_info = runtime.device_info
        self.cli = None
        self.session = ""
        self.queue = asyncio.Queue(maxsize=128)
        self.ended = False
        self.reply_sent = False
        self.reply_done = False
        self.reply_url = None
        self._native_connected = False
        self._worker = None
        self._run_job = None
        self._lease = None
        self._jobs = set()
        self._done = asyncio.Event()
        self._config = AssistSatelliteConfiguration(
            available_wake_words=[], active_wake_words=[], max_active_wake_words=1
        )
        self._wake_index = 0
        self._playback_failure = False

    @property
    def available(self):
        return self._native_connected and self.runtime.connection.connected

    def _select(self, suffix):
        prefix = self.runtime.dot_native.unique_id.removesuffix("-assist_satellite")
        return er.async_get(self.hass).async_get_entity_id(
            "select", "esphome", f"{prefix}-{suffix}"
        )

    @property
    def pipeline_entity_id(self):
        return er.async_get(self.hass).async_get_entity_id(
            "select",
            DOMAIN,
            f"{self.runtime.info.id}_dot_pipeline"
            + (f"_{self._wake_index + 1}" if self._wake_index else ""),
        )

    @property
    def vad_sensitivity_entity_id(self):
        return er.async_get(self.hass).async_get_entity_id(
            "select", DOMAIN, f"{self.runtime.info.id}_dot_vad_sensitivity"
        )

    @property
    def tts_options(self):
        return {
            "preferred_format": "mp3",
            "preferred_sample_rate": 48000,
            "preferred_sample_channels": 1,
            "preferred_sample_bytes": 2,
        }

    def async_get_configuration(self):
        return self._config

    async def async_set_configuration(self, config):
        if self.cli is None:
            raise HomeAssistantError("Dot disconnected")
        await self.cli.set_voice_assistant_configuration(
            active_wake_words=config.active_wake_words
        )
        self._config = config
        for listener in list(self.runtime.listeners):
            listener()

    def task(self, coroutine):
        task = self.runtime.entry.async_create_background_task(
            self.hass, coroutine, "Vision Dot"
        )
        self._jobs.add(task)
        task.add_done_callback(self._jobs.discard)
        return task

    async def async_added_to_hass(self):
        await super().async_added_to_hass()
        self.runtime.dot_satellite = self
        self.runtime.listeners.add(self.async_write_ha_state)
        self.async_on_remove(
            lambda: self.runtime.listeners.discard(self.async_write_ha_state)
        )
        self._worker = self.task(self._connect())

    async def async_will_remove_from_hass(self):
        self.runtime.dot_satellite = None
        await self.cancel()
        for task in list(self._jobs):
            task.cancel()
        await asyncio.gather(*self._jobs, return_exceptions=True)
        await super().async_will_remove_from_hass()

    async def _connect(self):
        while True:
            data = self.runtime.dot_entry.data
            client = APIClient(
                address=data["host"],
                port=data.get("port", 6053),
                password=data.get("password", ""),
                noise_psk=data.get("noise_psk"),
            )
            stopped = asyncio.Event()

            async def stop(expected, signal=stopped):
                signal.set()

            unsubscribe = None
            try:
                await client.connect(on_stop=stop)
                info = await client.device_info()
                if not info.project_name.startswith("hassmic."):
                    raise ValueError("This endpoint is not upstream echo-dot-assist")
                self.cli = client
                cfg = await client.get_voice_assistant_configuration(10)
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
                unsubscribe = client.subscribe_voice_assistant(
                    handle_start=self.start,
                    handle_stop=self.stop,
                    handle_audio=self.audio,
                )
                self._native_connected = True
                for listener in list(self.runtime.listeners):
                    listener()
                await stopped.wait()
            except asyncio.CancelledError:
                raise
            except Exception as ex:  # noqa: BLE001 - reconnect boundary includes device/API failures
                _LOGGER.warning(
                    "Vision Dot connection unavailable (%s)", type(ex).__name__
                )
            finally:
                self._native_connected = False
                if unsubscribe:
                    with contextlib.suppress(Exception):
                        unsubscribe()
                await self.cancel()
                self.cli = None
                with contextlib.suppress(Exception):
                    await client.disconnect()
                self.async_write_ha_state()
            await asyncio.sleep(5)

    def send(self, event, data=None):
        if self.cli and self._native_connected:
            self.cli.send_voice_assistant_event(event, data or {})

    async def start(self, conversation_id, flags, audio_settings, wake_word_phrase):
        await self.cancel()
        if not self.available:
            self.send(
                DotEvent.VOICE_ASSISTANT_ERROR,
                {
                    "code": "display_unavailable",
                    "message": "Vision display is disconnected",
                },
            )
            return None
        self._wake_index = 0
        words = {word.id: word.wake_word for word in self._config.available_wake_words}
        for index, word in enumerate(self._config.active_wake_words):
            if words.get(word) == wake_word_phrase:
                self._wake_index = index
                break
        self._playback_failure = False
        self.session = str(uuid4())
        self.queue = asyncio.Queue(maxsize=128)
        self.ended = self.reply_sent = self.reply_done = False
        self.reply_url = None
        self._done = asyncio.Event()
        await self.runtime.action(
            "voice-begin",
            {
                "session": self.session,
                "gain": 1 - self.runtime.values.get("ducking_percent", 90) / 100,
            },
        )
        self._lease = self.task(self.keepalive(self.session))
        self._run_job = self.task(
            self.run_pipeline(flags, wake_word_phrase, self.session)
        )
        return 0

    async def run_pipeline(self, flags, phrase, session):
        try:
            await self.async_accept_pipeline_from_satellite(
                audio_stream=self.stream(),
                start_stage=PipelineStage.WAKE_WORD if flags & 2 else PipelineStage.STT,
                end_stage=PipelineStage.TTS,
                wake_word_phrase=phrase,
            )
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - pipeline failures must release output
            if self.session == session:
                self.send(
                    DotEvent.VOICE_ASSISTANT_ERROR,
                    {"code": "pipeline_error", "message": "Vision pipeline failed"},
                )
                await self.finish(session)

    async def stream(self):
        while (chunk := await self.queue.get()) is not None:
            yield chunk

    async def audio(self, data, data2=None):
        if not self.session:
            return
        try:
            self.queue.put_nowait(data)
        except asyncio.QueueFull:
            self.send(
                DotEvent.VOICE_ASSISTANT_ERROR,
                {"code": "audio_overrun", "message": "Microphone queue overrun"},
            )
            await self.cancel()

    async def stop(self, abort):
        if abort:
            await self.cancel()
        else:
            with contextlib.suppress(asyncio.QueueFull):
                self.queue.put_nowait(None)

    async def keepalive(self, session):
        try:
            async with asyncio.timeout(180):
                while self.session == session:
                    await asyncio.sleep(4)
                    await self.runtime.action("voice-keepalive", {"session": session})
        except (TimeoutError, HomeAssistantError, ConnectionError):
            if self.session == session:
                self.send(
                    DotEvent.VOICE_ASSISTANT_ERROR,
                    {"code": "output_timeout", "message": "Vision output unavailable"},
                )
                await self.cancel()

    def on_pipeline_event(self, event):
        if not self.session:
            return
        kind = event.type.value
        data = event.data or {}
        if kind == "run-start":
            self.reply_url = (data.get("tts_output") or {}).get("url")
            self.send(
                DotEvent.VOICE_ASSISTANT_RUN_START
            )  # Never hand the Dot a reply URL.
        elif kind == "stt-start":
            self.send(DotEvent.VOICE_ASSISTANT_STT_START)
        elif kind == "stt-vad-end":
            self.send(DotEvent.VOICE_ASSISTANT_STT_VAD_END)
        elif kind == "stt-end":
            self.send(
                DotEvent.VOICE_ASSISTANT_STT_END, {"text": data["stt_output"]["text"]}
            )
        elif kind == "wake_word-end":
            self.send(DotEvent.VOICE_ASSISTANT_WAKE_WORD_END)
        elif kind == "intent-end":
            output = data.get("intent_output", {})
            self.send(
                DotEvent.VOICE_ASSISTANT_INTENT_END,
                {
                    "continue_conversation": str(
                        int(output.get("continue_conversation", False))
                    )
                },
            )
        elif (
            kind == "intent-progress"
            and data.get("tts_start_streaming")
            and self.reply_url
        ):
            self.start_reply(self.reply_url)
        elif kind == "tts-end":
            output = data.get("tts_output") or {}
            if output.get("url"):
                self.start_reply(output["url"])
        elif kind == "run-end":
            self.ended = True
            if not self.reply_sent or self.reply_done:
                self.task(self.finish(self.session))
        elif kind == "error":
            self.send(
                DotEvent.VOICE_ASSISTANT_ERROR,
                {
                    "code": data.get("code", "error"),
                    "message": "Vision pipeline failed",
                },
            )
            self.task(self.finish(self.session))

    def start_reply(self, path):
        if self.reply_sent:
            return
        self.reply_sent = True
        url = async_process_play_media_url(self.hass, path)
        self.task(self.play(self.session, url))

    async def play(self, session, url, preannounce=None):
        try:
            if session == self.session:
                await self.runtime.action(
                    "voice-play",
                    {"session": session, "url": url, "preannounce": preannounce},
                )
        except (HomeAssistantError, ConnectionError):
            await self.finish(session)

    def playback(self, data):
        if not self.session or data.get("session") != self.session:
            return
        result = data.get("playback")
        if result == "finished":
            self.reply_done = True
            if self.ended:
                self.task(self.finish(self.session))
        elif result in ("failed", "expired", "disconnected", "closed"):
            self._playback_failure = True
            self.send(
                DotEvent.VOICE_ASSISTANT_ERROR,
                {"code": "playback_failed", "message": "Vision playback failed"},
            )
            self.task(self.cancel())

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
        # Upstream's no-local-TTS completion path; can start an indicated follow-up.
        self.send(DotEvent.VOICE_ASSISTANT_RUN_END)

    async def cancel(self):
        session, self.session = self.session, ""
        if self._lease and self._lease is not asyncio.current_task():
            self._lease.cancel()
        if self._run_job and self._run_job is not asyncio.current_task():
            self._run_job.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._run_job
        self._run_job = None
        if session:
            with contextlib.suppress(HomeAssistantError, ConnectionError):
                await self.runtime.action("voice-end", {"session": session})
            if self.hass:
                self.tts_response_finished()
        self._done.set()

    async def async_announce(self, announcement):
        if not self.available:
            raise HomeAssistantError("Vision display or Dot unavailable")
        await self.cancel()
        self._playback_failure = False
        self.session = str(uuid4())
        self.ended = self.reply_sent = True
        self.reply_done = False
        self._done = asyncio.Event()
        await self.runtime.action(
            "voice-begin",
            {
                "session": self.session,
                "gain": 1 - self.runtime.values.get("ducking_percent", 90) / 100,
            },
        )
        session, done = self.session, self._done
        self._lease = self.task(self.keepalive(session))
        await self.play(
            self.session,
            async_process_play_media_url(self.hass, announcement.media_id),
            announcement.preannounce_media_id,
        )
        try:
            async with asyncio.timeout(180):
                await done.wait()
            if self._playback_failure:
                raise HomeAssistantError(
                    "Vision speaker could not play the announcement"
                )
        finally:
            if self.session == session:
                await self.cancel()
