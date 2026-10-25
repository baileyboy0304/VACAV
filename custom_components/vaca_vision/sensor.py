"""Only diagnostics and the dashboard path used by overlays."""

from homeassistant.components.sensor import SensorEntity
from homeassistant.const import EntityCategory

from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    if entry.runtime_data.has_crown:
        async_add_entities(
            [
                OverlaySensor(
                    entry.runtime_data,
                    "crown_state",
                    "Crown voice state",
                    "mdi:microphone",
                )
            ]
        )
    async_add_entities(
        [
            OverlaySensor(entry.runtime_data, *args)
            for args in (
                ("current_path", "Current path", "mdi:web"),
                ("display_device", "View Assist display device", "mdi:identifier"),
                ("app_version", "App version", "mdi:application"),
            )
        ]
    )


class OverlaySensor(OverlayEntity, SensorEntity):
    def __init__(self, runtime, key, name, icon):
        super().__init__(runtime, key, name, icon)
        self.key = key
        if key != "current_path":
            self._attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def available(self):
        return self.key == "display_device" or super().available

    @property
    def native_value(self):
        if self.key == "display_device":
            return f"va-{self.runtime.info.id}"
        if self.key == "app_version":
            return self.runtime.state.get(self.key, self.runtime.info.version)
        if self.key == "crown_state":
            return self.runtime.state.get("crown", {}).get("state", "OFFLINE")
        return self.runtime.state.get(self.key)
