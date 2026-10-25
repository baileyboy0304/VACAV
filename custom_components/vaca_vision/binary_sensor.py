"""Truthful Crown status, read from the local native adapter."""

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)

from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    if entry.runtime_data.has_crown:
        async_add_entities(
            [
                CrownStatus(
                    entry.runtime_data,
                    "connected",
                    "Crown connected",
                    BinarySensorDeviceClass.CONNECTIVITY,
                ),
                CrownStatus(
                    entry.runtime_data, "muted", "Physical microphone mute", None
                ),
            ]
        )


class CrownStatus(OverlayEntity, BinarySensorEntity):
    def __init__(self, runtime, key, name, device_class):
        super().__init__(runtime, "crown_" + key, name)
        self.key = key
        self._attr_device_class = device_class

    @property
    def available(self):
        return super().available and self.runtime.state.get("crown", {}).get(
            "available", False
        )

    @property
    def is_on(self):
        return self.runtime.state.get("crown", {}).get(self.key)
