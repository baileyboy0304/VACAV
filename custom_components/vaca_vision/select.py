"""Crown's pipeline and HA end-of-speech settings; never a VACA wake engine."""

from homeassistant.components.select import SelectEntity
from homeassistant.components.wyoming.select import (
    WyomingSatellitePipelineSelect,
    WyomingSatelliteVadSensitivitySelect,
)
from homeassistant.const import EntityCategory

from .crown import on_vision_device
from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    from .dot_controls import controls

    async_add_entities(controls(entry.runtime_data, "select"))
    r = entry.runtime_data
    if not r.is_overlay:
        async_add_entities([MotionMode(r)])
    if r.has_upstream:
        from homeassistant.components.assist_pipeline.select import AssistPipelineSelect, VadSensitivitySelect
        entities = [AssistPipelineSelect(hass, "vaca_vision", r.info.id, index=i) for i in range(2)]
        entities.append(VadSensitivitySelect(hass, r.info.id))
        for entity in entities:
            entity._attr_device_info = r.device_info
        async_add_entities(entities)
    if r.has_crown:
        async_add_entities(
            [
                on_vision_device(
                    WyomingSatellitePipelineSelect(hass, r.crown_device), r, "Assistant"
                ),
                on_vision_device(
                    WyomingSatelliteVadSensitivitySelect(hass, r.crown_device),
                    r,
                    "Finished speaking detection",
                ),
            ]
        )


class MotionMode(OverlayEntity, SelectEntity):
    @property
    def options(self):
        return ["none", "motion", "face"]

    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, runtime):
        super().__init__(
            runtime, "motion_detection_mode", "Camera detection", "mdi:camera"
        )

    @property
    def current_option(self):
        return self.runtime.state.get("motion_detection_mode")

    async def async_select_option(self, option):
        if option not in self.options:
            raise ValueError("Unsupported camera mode")
        await self.runtime.set_display({"motion_detection_mode": option})
