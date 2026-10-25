"""Selected upstream Dot controls on the combined Vision device.

ESPHome remains responsible for its firmware and settings; these controls forward
standard HA services and never alter or duplicate the upstream protocol.
"""

from dataclasses import replace

from homeassistant.components.button import ButtonEntity
from homeassistant.components.number import NumberEntity
from homeassistant.components.select import SelectEntity
from homeassistant.components.switch import SwitchEntity
from homeassistant.components.update import UpdateEntity, UpdateEntityFeature
from homeassistant.const import EntityCategory
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.event import async_track_state_change_event

from .entity import OverlayEntity

# Keep the Dot's local music/Bluetooth/equalizer separate: Vision owns speaker audio.
CONTROLS = {
    "select": {
        "Assistant",
        "Assistant 2",
        "Finished speaking detection",
    },
    "switch": {"Mute", "Do not disturb", "Wake sound", "LED auto brightness"},
    "number": {"LED brightness"},
    "button": {"Restart", "Identify"},
    "update": {"Firmware"},
}
NAMES = {
    "Mute": "Microphone mute",
    "Restart": "Restart microphone",
    "Identify": "Identify microphone",
    "Firmware": "Microphone firmware",
    "LED brightness": "Microphone LED brightness",
    "LED auto brightness": "Microphone LED auto brightness",
}


def control_key(source):
    # ESPHome uses MAC-prefixed IDs for settings and protocol components.
    suffix = source.unique_id[17:].lstrip("-/")
    return "dot_" + suffix.replace("/", "_").replace(" ", "_").lower()


def controls(runtime, domain):
    if not runtime.has_dot:
        return []
    registry = er.async_get(runtime.hass)
    result = [
        CLASSES[domain](runtime, source)
        for source in er.async_entries_for_config_entry(
            registry, runtime.dot_entry.entry_id
        )
        if source.domain == domain
        and source.original_name in CONTROLS[domain]
        and not source.disabled_by
    ]
    if domain == "select":
        result.extend(DotWakeWord(runtime, index) for index in range(2))
    return result


class DotControl(OverlayEntity):
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, runtime, source):
        super().__init__(
            runtime,
            control_key(source),
            NAMES.get(source.original_name, source.original_name),
            source.icon or source.original_icon,
        )
        self.source = source.entity_id
        self.domain = source.domain

    @property
    def source_state(self):
        return self.hass.states.get(self.source) if self.hass else None

    @property
    def available(self):
        state = self.source_state
        return state is not None and state.state not in ("unavailable", "unknown")

    def attr(self, key, default=None):
        state = self.source_state
        return state.attributes.get(key, default) if state else default

    async def async_added_to_hass(self):
        await super().async_added_to_hass()

        async def source_changed(_event):
            self.async_write_ha_state()

        self.async_on_remove(
            async_track_state_change_event(
                self.hass, [self.source], source_changed
            )
        )

    async def command(self, service, **data):
        await self.hass.services.async_call(
            self.domain, service, {"entity_id": self.source, **data}, blocking=True
        )


class DotSelect(DotControl, SelectEntity):
    @property
    def options(self):
        return self.attr("options", [])

    @property
    def current_option(self):
        return self.source_state.state if self.available else None

    async def async_select_option(self, option):
        await self.command("select_option", option=option)


class DotSwitch(DotControl, SwitchEntity):
    @property
    def is_on(self):
        return self.source_state.state == "on" if self.available else None

    async def async_turn_on(self, **kwargs):
        await self.command("turn_on")

    async def async_turn_off(self, **kwargs):
        await self.command("turn_off")


class DotNumber(DotControl, NumberEntity):
    @property
    def native_value(self):
        return float(self.source_state.state) if self.available else None

    @property
    def native_min_value(self):
        return self.attr("min", 0)

    @property
    def native_max_value(self):
        return self.attr("max", 100)

    @property
    def native_step(self):
        return self.attr("step", 1)

    @property
    def native_unit_of_measurement(self):
        return self.attr("unit_of_measurement")

    async def async_set_native_value(self, value):
        await self.command("set_value", value=value)


class DotButton(DotControl, ButtonEntity):
    @property
    def available(self):
        # Buttons have an unknown state until their first press.
        return (
            self.source_state is not None and self.source_state.state != "unavailable"
        )

    async def async_press(self):
        await self.command("press")


class DotUpdate(DotControl, UpdateEntity):
    @property
    def supported_features(self):
        return UpdateEntityFeature(self.attr("supported_features", 0)) & (
            UpdateEntityFeature.INSTALL | UpdateEntityFeature.PROGRESS
        )

    @property
    def installed_version(self):
        return self.attr("installed_version")

    @property
    def latest_version(self):
        return self.attr("latest_version")

    @property
    def in_progress(self):
        return self.attr("in_progress", False)

    @property
    def release_url(self):
        return self.attr("release_url")

    async def async_install(self, version, backup, **kwargs):
        await self.command("install")


CLASSES = {
    "select": DotSelect,
    "switch": DotSwitch,
    "number": DotNumber,
    "button": DotButton,
    "update": DotUpdate,
}


class DotWakeWord(OverlayEntity, SelectEntity):
    """Wake settings belong to Vision's sole upstream voice connection."""

    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, runtime, index):
        self.index = index
        super().__init__(
            runtime,
            "dot_wake_word" + ("_2" if index else ""),
            "Wake word" + (" 2" if index else ""),
        )

    @property
    def configuration(self):
        satellite = self.runtime.dot_satellite
        return satellite.async_get_configuration() if satellite else None

    @property
    def available(self):
        satellite = self.runtime.dot_satellite
        return satellite is not None and satellite.available

    @property
    def options(self):
        config = self.configuration
        return (
            ["None"] + [word.wake_word for word in config.available_wake_words]
            if config
            else ["None"]
        )

    @property
    def current_option(self):
        config = self.configuration
        if not config or self.index >= len(config.active_wake_words):
            return "None"
        return next(
            (
                word.wake_word
                for word in config.available_wake_words
                if word.id == config.active_wake_words[self.index]
            ),
            "None",
        )

    async def async_select_option(self, option):
        config = self.configuration
        words = list(config.active_wake_words)
        if self.index < len(words):
            words.pop(self.index)
        if option != "None":
            word_id = next(
                word.id
                for word in config.available_wake_words
                if word.wake_word == option
            )
            if word_id in words:
                raise ValueError("Choose a different wake word for each assistant")
            words.insert(min(self.index, len(words)), word_id)
        if len(words) > config.max_active_wake_words:
            raise ValueError("This microphone does not support another wake word")
        await self.runtime.dot_satellite.async_set_configuration(
            replace(config, active_wake_words=words)
        )
