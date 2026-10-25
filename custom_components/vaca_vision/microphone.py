"""Forward only the paired Dot's confirmed microphone mute state to its display."""

from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.event import async_track_state_change_event


def mute_source(runtime):
    if not runtime.has_dot:
        return None
    return next(
        (
            entity.entity_id
            for entity in er.async_entries_for_config_entry(
                er.async_get(runtime.hass), runtime.dot_entry.entry_id
            )
            if entity.domain == "switch"
            and entity.original_name == "Mute"
            and entity.device_id == runtime.dot_device.id
            and not entity.disabled_by
        ),
        None,
    )


def microphone_state(runtime):
    source = mute_source(runtime)
    state = runtime.hass.states.get(source) if source else None
    return {
        "muted": state.state == "on" if state and state.state in ("on", "off") else None
    }


def watch_microphone(runtime):
    source = mute_source(runtime)
    if not source:
        return

    async def changed(event):
        if not runtime.connection.connected:
            return  # The next handshake sends the current state.
        try:
            await runtime.action("microphone-state", microphone_state(runtime))
        except (HomeAssistantError, ConnectionError, OSError):
            pass  # A disconnect racing this event is recovered by the handshake.

    runtime.entry.async_on_unload(
        async_track_state_change_event(runtime.hass, [source], changed)
    )
