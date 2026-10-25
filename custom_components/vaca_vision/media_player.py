"""Sendspin controls on the Vision device, forwarding to the selected MA player.

Music Assistant and Crown remain the only music transport and volume owners.
"""

from homeassistant.components.media_player import (
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
)
from homeassistant.helpers.event import async_track_state_change_event

from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    if entry.runtime_data.has_upstream:
        from .upstream_music import UpstreamMusic
        async_add_entities([UpstreamMusic(entry.runtime_data)])
    if entry.runtime_data.has_dot:
        async_add_entities(
            [VisionSpeaker(entry.runtime_data), VisionMusic(entry.runtime_data)]
        )
    if entry.runtime_data.has_crown:
        async_add_entities([CrownMediaPlayer(entry.runtime_data)])


class CrownMediaPlayer(OverlayEntity, MediaPlayerEntity):
    def __init__(self, runtime):
        super().__init__(runtime, "sendspin", "Sendspin", "mdi:speaker")
        self.source = runtime.entry.options.get(
            "sendspin_entity", runtime.entry.data.get("sendspin_entity")
        )

    @property
    def target(self):
        return self.hass.states.get(self.source)

    @property
    def available(self):
        return self.target is not None and self.target.state not in (
            "unavailable",
            "unknown",
        )

    @property
    def state(self):
        return self.target.state if self.target else None

    def attr(self, key):
        return self.target.attributes.get(key) if self.target else None

    @property
    def supported_features(self):
        allowed = (
            MediaPlayerEntityFeature.PLAY
            | MediaPlayerEntityFeature.PAUSE
            | MediaPlayerEntityFeature.STOP
            | MediaPlayerEntityFeature.PREVIOUS_TRACK
            | MediaPlayerEntityFeature.NEXT_TRACK
            | MediaPlayerEntityFeature.VOLUME_SET
            | MediaPlayerEntityFeature.VOLUME_MUTE
            | MediaPlayerEntityFeature.PLAY_MEDIA
            | MediaPlayerEntityFeature.SEEK
        )
        return MediaPlayerEntityFeature(self.attr("supported_features") or 0) & allowed

    @property
    def volume_level(self):
        return self.attr("volume_level")

    @property
    def is_volume_muted(self):
        return self.attr("is_volume_muted")

    @property
    def media_title(self):
        return self.attr("media_title")

    @property
    def media_artist(self):
        return self.attr("media_artist")

    @property
    def media_duration(self):
        return self.attr("media_duration")

    @property
    def media_position(self):
        return self.attr("media_position")

    @property
    def extra_state_attributes(self):
        return {
            "source_entity": self.source,
            "audio_owner": "Crown Sendspin / Music Assistant",
        }

    async def async_added_to_hass(self):
        await super().async_added_to_hass()
        self.async_on_remove(
            async_track_state_change_event(
                self.hass, [self.source], lambda event: self.async_write_ha_state()
            )
        )

    async def command(self, name, **data):
        await self.hass.services.async_call(
            "media_player", name, {"entity_id": self.source, **data}, blocking=True
        )

    async def async_media_play(self):
        await self.command("media_play")

    async def async_media_pause(self):
        await self.command("media_pause")

    async def async_media_stop(self):
        await self.command("media_stop")

    async def async_media_next_track(self):
        await self.command("media_next_track")

    async def async_media_previous_track(self):
        await self.command("media_previous_track")

    async def async_set_volume_level(self, volume):
        await self.command("volume_set", volume_level=volume)

    async def async_mute_volume(self, mute):
        await self.command("volume_mute", is_volume_muted=mute)

    async def async_media_seek(self, position):
        await self.command("media_seek", seek_position=position)

    async def async_play_media(self, media_type, media_id, **kwargs):
        await self.command(
            "play_media",
            media_content_type=media_type,
            media_content_id=media_id,
            **kwargs,
        )


class VisionSpeaker(OverlayEntity, MediaPlayerEntity):
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.STOP
    )

    def __init__(self, runtime):
        super().__init__(runtime, "speaker", "Speaker", "mdi:speaker")

    @property
    def state(self):
        return "playing" if self.runtime.state.get("playback") == "playing" else "idle"

    @property
    def volume_level(self):
        return self.runtime.state.get("reply_volume", 0.7)

    async def async_set_volume_level(self, volume):
        await self.runtime.action("reply-volume", {"volume": volume})

    async def async_media_stop(self):
        if self.runtime.dot_satellite:
            await self.runtime.dot_satellite.cancel()

    async def async_play_media(self, media_type, media_id, **kwargs):
        from homeassistant.components import media_source
        from homeassistant.components.assist_satellite import (
            AssistSatelliteAnnouncement,
        )

        if media_source.is_media_source_id(media_id):
            resolved = await media_source.async_resolve_media(
                self.hass, media_id, self.entity_id
            )
            media_id = resolved.url
        satellite = self.runtime.dot_satellite
        if satellite is None:
            raise RuntimeError("Vision satellite unavailable")
        await satellite.async_announce(
            AssistSatelliteAnnouncement(
                message="",
                media_id=media_id,
                original_media_id=media_id,
                tts_token=None,
                media_id_source="url",
            )
        )


class VisionMusic(OverlayEntity, MediaPlayerEntity):
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.STOP
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.NEXT_TRACK
        | MediaPlayerEntityFeature.PREVIOUS_TRACK
    )

    def __init__(self, runtime):
        super().__init__(runtime, "sendspin", "Sendspin", "mdi:music")

    @property
    def state(self):
        return "playing" if self.runtime.state.get("music_playing") else "idle"

    @property
    def volume_level(self):
        volume = self.runtime.state.get("music_volume")
        return volume / 100 if volume is not None else None

    @property
    def is_volume_muted(self):
        return self.runtime.state.get("music_muted")

    @property
    def extra_state_attributes(self):
        return {
            "sendspin_id": self.runtime.state.get("sendspin_id"),
            "connection": self.runtime.state.get("sendspin_state"),
            "audio_owner": "Vision display",
            "duck_gain": self.runtime.state.get("music_duck_gain", 1),
        }

    async def command(self, command, **data):
        await self.runtime.action("music-command", {"command": command, **data})

    async def async_media_play(self):
        await self.command("play")

    async def async_media_pause(self):
        await self.command("pause")

    async def async_media_stop(self):
        await self.command("pause")

    async def async_media_next_track(self):
        await self.command("next")

    async def async_media_previous_track(self):
        await self.command("previous")

    async def async_set_volume_level(self, volume):
        await self.command("volume", volume=round(volume * 100))
