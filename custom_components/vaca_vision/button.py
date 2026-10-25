"""Dashboard and picker buttons, with no screen or voice controls."""

from homeassistant.components.button import ButtonEntity
from homeassistant.const import EntityCategory

from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    from .dot_controls import controls

    async_add_entities(controls(entry.runtime_data, "button"))
    if not entry.runtime_data.is_overlay:
        async_add_entities(
            [
                OverlayButton(
                    entry.runtime_data, "show-settings", "Open app settings", "mdi:cog"
                )
            ]
        )
    async_add_entities(
        [
            OverlayButton(entry.runtime_data, *args)
            for args in (
                ("refresh", "Refresh dashboard", "mdi:refresh"),
                ("hide-overlay", "Hide dashboard", "mdi:close"),
                ("show-picker", "Open picker", "mdi:view-dashboard"),
                ("show-ble-setup", "Bluetooth remote setup", "mdi:bluetooth-settings"),
            )
            if args[0] == "refresh" or entry.runtime_data.is_overlay
        ]
    )


class OverlayButton(OverlayEntity, ButtonEntity):
    def __init__(self, runtime, key, name, icon):
        super().__init__(runtime, key, name, icon)
        self.action = key
        if key in ("show-ble-setup", "show-settings"):
            self._attr_entity_category = EntityCategory.CONFIG

    async def async_press(self):
        await self.runtime.action(self.action)
