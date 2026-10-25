"""Upstream microphone firmware control on the combined device."""

from .dot_controls import controls


async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities(controls(entry.runtime_data, "update"))
