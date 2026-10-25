"""Role-specific display settings and Crown's HA voice-session pause."""

from homeassistant.components.switch import SwitchEntity
from homeassistant.components.wyoming.switch import WyomingSatelliteMuteSwitch
from homeassistant.const import EntityCategory

from .crown import on_vision_device
from .entity import OverlayEntity


async def async_setup_entry(hass, entry, async_add_entities):
    from .dot_controls import controls

    async_add_entities(controls(entry.runtime_data, "switch"))
    r = entry.runtime_data
    controls = (
        [("overlay_enabled", "Overlays"), ("alarm", "Alarm")]
        if r.is_overlay
        else [
            ("screen_on", "Screen"),
            ("screen_always_on", "Keep screen on"),
            ("screen_auto_brightness", "Automatic brightness"),
            ("screen_saver", "Screen saver"),
            ("dark_mode", "Dark mode"),
            ("diagnostics_enabled", "Diagnostics"),
            ("quick_actions", "Quick actions"),
            ("screen_on_motion", "Wake screen on motion"),
        ]
    )
    entities = [DisplaySwitch(r, key, name, "mdi:monitor") for key, name in controls]
    if r.has_crown:
        entities.append(
            on_vision_device(
                WyomingSatelliteMuteSwitch(r.crown_device),
                r,
                "Pause Crown voice requests",
            )
        )
    async_add_entities(entities)


class DisplaySwitch(OverlayEntity, SwitchEntity):
    def __init__(self, runtime, key, name, icon):
        super().__init__(runtime, key, name, icon)
        self.key = key
        if key not in ("screen_on", "alarm", "overlay_enabled"):
            self._attr_entity_category = EntityCategory.CONFIG

    @property
    def is_on(self):
        return self.runtime.state.get(self.key)

    async def set_value(self, value):
        if self.key == "alarm":
            if value:
                await self.runtime.show_alarm()
            else:
                await self.runtime.action("alarm", {"activate": False})
        elif self.key == "overlay_enabled":
            await self.runtime.action("set-overlay-enabled", {"enabled": value})
        else:
            await self.runtime.set_display({self.key: value})

    async def async_turn_on(self, **kwargs):
        await self.set_value(True)

    async def async_turn_off(self, **kwargs):
        await self.set_value(False)
