"""Vision diagnostics without credentials or dashboard contents."""


async def async_get_config_entry_diagnostics(hass, entry):
    runtime = getattr(entry, "runtime_data", None)
    if runtime is None:
        return {"loaded": False}
    return {
        "role": runtime.role,
        "app_version": runtime.info.version,
        "connected": runtime.connection.connected,
        "capabilities": runtime.info.capabilities,
        "dot": {
            "connected": runtime.dot_satellite.available
            if runtime.dot_satellite
            else False,
            **{
                key: runtime.state.get(key)
                for key in (
                    "sendspin_state",
                    "playback",
                    "playback_error",
                    "music_duck_gain",
                )
            },
        }
        if runtime.has_dot
        else None,
        "crown": {
            key: runtime.state.get("crown", {}).get(key)
            for key in ("connected", "state", "muted")
        }
        if runtime.has_crown
        else None,
    }
