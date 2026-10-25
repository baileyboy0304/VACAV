"""MA-backed controls attached only to Vision; EDA keeps its Sendspin endpoint."""

from homeassistant.components.media_player import MediaPlayerEntity
from homeassistant.components.media_player import MediaPlayerEntityFeature as F
from homeassistant.components.music_assistant.helpers import get_music_assistant_client
from homeassistant.exceptions import HomeAssistantError

from .entity import OverlayEntity


async def validate_music(hass, values, *, expose=False):
    entry = hass.config_entries.async_get_entry(values.get("ma_entry_id", ""))
    if entry is None or entry.domain != "music_assistant":
        raise ValueError("ma_unavailable")
    client = get_music_assistant_client(hass, entry.entry_id)
    player_id = values.get("ma_player_id", "")
    if client.players.get(player_id) is None:
        raise ValueError("ma_player_missing")
    if not await client.config.get_player_config_value(
        player_id, "expose_player_to_ha", True
    ):
        if not expose:
            raise ValueError("ma_player_not_exposed")
        await client.config.save_player_config(player_id, {"expose_player_to_ha": True})
        if not await client.config.get_player_config_value(player_id, "expose_player_to_ha", False):
            raise ValueError("ma_player_not_exposed")


class UpstreamMusic(OverlayEntity, MediaPlayerEntity):
    _attr_should_poll = (
        True  # Re-acquire the client after MA reload; no stale subscription.
    )

    def __init__(self, runtime):
        super().__init__(runtime, "sendspin", "Music", "mdi:speaker")
        self.player_id = runtime.values["ma_player_id"]

    @property
    def client(self):
        return get_music_assistant_client(self.hass, self.runtime.values["ma_entry_id"])

    @property
    def player(self):
        try:
            return self.client.players.get(self.player_id)
        except HomeAssistantError:
            return None

    @property
    def available(self):
        # MA's HA entity owns MA actions; this legacy control remains usable.
        player = self.player
        return player is not None and player.available

    @property
    def state(self):
        player = self.player
        return (
            str(getattr(player, "playback_state", getattr(player, "state", "idle")))
            if player
            else None
        )

    @property
    def supported_features(self):
        result = F.PLAY | F.STOP | F.PLAY_MEDIA
        for name, flag in (
            ("pause", F.PAUSE),
            ("volume_set", F.VOLUME_SET),
            ("volume_mute", F.VOLUME_MUTE),
            ("seek", F.SEEK),
            ("next_previous", F.NEXT_TRACK | F.PREVIOUS_TRACK),
        ):
            if self.player and name in self.player.supported_features:
                result |= flag
        return result

    @property
    def volume_level(self):
        value = self.player.volume_level if self.player else None
        return value / 100 if value is not None else None

    @property
    def is_volume_muted(self):
        return self.player.volume_muted if self.player else None

    @property
    def media_title(self):
        media = self.player.current_media if self.player else None
        return media.title if media else None

    @property
    def media_artist(self):
        media = self.player.current_media if self.player else None
        return media.artist if media else None

    async def call(self, method, *args):
        if not self.available:
            raise HomeAssistantError("Music Assistant player is unavailable")
        await getattr(self.client.players, method)(self.player_id, *args)

    async def async_media_play(self):
        await self.call("play")

    async def async_media_pause(self):
        await self.call("pause")

    async def async_media_stop(self):
        await self.call("stop")

    async def async_media_next_track(self):
        await self.call("next_track")

    async def async_media_previous_track(self):
        await self.call("previous_track")

    async def async_set_volume_level(self, volume):
        await self.call("volume_set", round(volume * 100))

    async def async_mute_volume(self, mute):
        await self.call("volume_mute", mute)

    async def async_media_seek(self, position):
        await self.call("seek", int(position))

    async def async_play_media(self, media_type, media_id, **kwargs):
        if not self.available:
            raise HomeAssistantError("Music Assistant player is unavailable")
        await self.client.player_queues.play_media(self.player_id, media_id)
