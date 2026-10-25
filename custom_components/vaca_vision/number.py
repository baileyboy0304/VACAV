"""Standard display brightness; no original VACA audio controls."""

from homeassistant.components.number import NumberEntity
from homeassistant.const import EntityCategory

from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    from .dot_controls import controls

    async_add_entities(controls(entry.runtime_data, "number"))
    if not entry.runtime_data.is_overlay:
        async_add_entities([Brightness(entry.runtime_data)])


class Brightness(OverlayEntity, NumberEntity):
    _attr_native_min_value = 1
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_native_unit_of_measurement = "%"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, runtime):
        super().__init__(runtime, "screen_brightness", "Brightness", "mdi:brightness-6")

    @property
    def native_value(self):
        return self.runtime.state.get("screen_brightness")

    async def async_set_native_value(self, value):
        await self.runtime.set_display(
            {"screen_brightness": value, "screen_auto_brightness": False}
        )
