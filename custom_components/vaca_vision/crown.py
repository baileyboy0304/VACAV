"""Crown voice through HA's Wyoming implementation, owned by the Vision device.

No device-registry merging, patched HA state, root control or extra music player.
"""

from homeassistant.components.wyoming.data import WyomingService
from homeassistant.components.wyoming.devices import SatelliteDevice
from homeassistant.exceptions import ConfigEntryNotReady
from homeassistant.helpers import entity_registry as er

from .const import DOMAIN


class CrownDevice(SatelliteDevice):
    def _entity(self, hass, platform, key):
        return er.async_get(hass).async_get_entity_id(
            platform, DOMAIN, f"{self.satellite_id}-{key}"
        )

    def get_pipeline_entity_id(self, hass):
        return self._entity(hass, "select", "pipeline")

    def get_vad_sensitivity_entity_id(self, hass):
        return self._entity(hass, "select", "vad_sensitivity")

    def get_muted_entity_id(self, hass):
        return self._entity(hass, "switch", "mute")

    def get_assist_in_progress_entity_id(self, hass):
        return self._entity(hass, "binary_sensor", "assist_in_progress")

    def get_noise_suppression_level_entity_id(self, hass):
        return self._entity(hass, "select", "noise_suppression_level")

    def get_auto_gain_entity_id(self, hass):
        return self._entity(hass, "number", "auto_gain")

    def get_volume_multiplier_entity_id(self, hass):
        return self._entity(hass, "number", "volume_multiplier")


async def validate_crown(hass, host, port, player):
    for entry in hass.config_entries.async_entries("wyoming"):
        if (
            not entry.disabled_by
            and entry.data.get("host") == host
            and entry.data.get("port") == port
        ):
            raise ValueError("crown_in_use")
    entity = er.async_get(hass).async_get(player)
    if (
        entity is None
        or entity.platform != "music_assistant"
        or entity.domain != "media_player"
    ):
        raise ValueError("invalid_sendspin")
    service = await WyomingService.create(host, port)
    if service is None or service.info.satellite is None:
        raise ValueError("crown_unavailable")
    # Explicit Crown identity: never attach a generic VACA or unrelated Wyoming satellite.
    name = service.info.satellite.name.lower()
    if "crown" not in name:
        raise ValueError("wrong_crown")
    return service


async def async_prepare_crown(runtime):
    if not runtime.has_crown:
        return
    values = {**runtime.entry.data, **runtime.entry.options}
    try:
        runtime.crown_service = await validate_crown(
            runtime.hass,
            values["host"],
            values.get("crown_port", 16700),
            values.get("sendspin_entity", ""),
        )
    except ValueError as ex:
        raise ConfigEntryNotReady(str(ex)) from ex
    runtime.crown_device = CrownDevice(satellite_id=runtime.info.id, device_id="")


def on_vision_device(entity, runtime, name):
    entity._attr_device_info = runtime.device_info
    entity._attr_name = name
    entity._attr_translation_key = None
    return entity
