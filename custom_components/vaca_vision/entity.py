"""Push state shared by the small set of Overlay entities."""

from homeassistant.helpers.entity import Entity


class OverlayEntity(Entity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, runtime, key, name, icon=None):
        self.runtime = runtime
        self._attr_unique_id = f"{runtime.info.id}_{key}"
        self._attr_name = name
        self._attr_icon = icon
        self._attr_device_info = runtime.device_info

    @property
    def available(self):
        return self.runtime.connection.connected

    async def async_added_to_hass(self):
        await super().async_added_to_hass()
        self.runtime.listeners.add(self.async_write_ha_state)
        self.async_on_remove(
            lambda: self.runtime.listeners.discard(self.async_write_ha_state)
        )
