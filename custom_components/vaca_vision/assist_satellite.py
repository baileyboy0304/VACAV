"""Crown Assist, on the same VACA_Vision device as display and Sendspin controls."""

from homeassistant.components.assist_satellite import AssistSatelliteEntityFeature
from homeassistant.components.wyoming.assist_satellite import WyomingAssistSatellite

from .crown import on_vision_device


async def async_setup_entry(hass, entry, async_add_entities):
    runtime = entry.runtime_data
    if runtime.has_upstream:
        from .upstream import UpstreamSatellite
        async_add_entities([UpstreamSatellite(runtime)])
        return
    if runtime.has_dot:
        from .dot import DotSatellite
        async_add_entities([DotSatellite(runtime)])
        return
    if not runtime.has_crown:
        return
    entity = WyomingAssistSatellite(
        hass, runtime.crown_service, runtime.crown_device, entry
    )
    # Crown supports locally triggered conversations. Do not advertise unverified announcements/PTT.
    entity._attr_supported_features = AssistSatelliteEntityFeature(0)
    async_add_entities([on_vision_device(entity, runtime, "Crown Assist")])
