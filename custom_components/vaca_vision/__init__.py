"""VACA_Vision: dashboards, alarms and media."""

import voluptuous as vol
from homeassistant.const import Platform
from homeassistant.exceptions import ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr

from .const import DOMAIN
from .picker_profiles import PickerProfiles
from .protocol import WrongDevice, probe
from .runtime import OverlayRuntime

PLATFORMS = [
    Platform.SENSOR,
    Platform.SWITCH,
    Platform.BUTTON,
    Platform.MEDIA_PLAYER,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.BINARY_SENSOR,
    Platform.ASSIST_SATELLITE,
    Platform.UPDATE,
]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


async def async_setup(hass, config):
    library = PickerProfiles(hass)
    await library.load()
    hass.data.setdefault(DOMAIN, {})["picker_profiles"] = library
    from .picker_panel import async_setup_panel

    await async_setup_panel(hass)

    async def handle(call):
        target = call.data["device_id"]
        device = dr.async_get(hass).async_get(target)
        runtimes = [
            entry.runtime_data
            for entry in hass.config_entries.async_entries(DOMAIN)
            if isinstance(getattr(entry, "runtime_data", None), OverlayRuntime)
            and device
            and entry.entry_id in device.config_entries
        ]
        if not runtimes:
            raise HomeAssistantError("Select a configured Overlay device")
        for runtime in runtimes:
            if call.service == "show_alarm":
                await runtime.show_alarm(
                    call.data.get("message", "Time is up"),
                    call.data.get("title", "Alarm"),
                )
            elif call.service == "dismiss_alarm":
                await runtime.action("alarm", {"activate": False})
            elif call.service == "show_dashboard":
                await runtime.action("navigate", {"path": call.data["path"]})

    base = {vol.Required("device_id"): str}
    hass.services.async_register(
        DOMAIN,
        "show_alarm",
        handle,
        schema=vol.Schema(
            {
                **base,
                vol.Optional("message", default="Time is up"): vol.All(
                    str, vol.Length(max=500)
                ),
                vol.Optional("title", default="Alarm"): vol.All(
                    str, vol.Length(max=80)
                ),
            }
        ),
    )
    hass.services.async_register(
        DOMAIN, "dismiss_alarm", handle, schema=vol.Schema(base)
    )

    def dashboard_path(value):
        if (
            not isinstance(value, str)
            or not value.startswith("/")
            or value.startswith("//")
        ):
            raise vol.Invalid("Use a local dashboard path")
        return value

    hass.services.async_register(
        DOMAIN,
        "show_dashboard",
        handle,
        schema=vol.Schema({**base, vol.Required("path"): dashboard_path}),
    )
    return True


async def async_setup_entry(hass, entry):
    try:
        info = await probe(entry.data["host"], entry.data["port"])
        if info.id != entry.unique_id:
            raise WrongDevice("Device identity has changed")
    except (OSError, TimeoutError, ValueError, KeyError, TypeError) as ex:
        raise ConfigEntryNotReady("Compatible Overlay device is not reachable") from ex
    runtime = entry.runtime_data = OverlayRuntime(hass, entry, info)
    eda_mac = None
    if runtime.has_upstream:
        from .upstream import validate_upstream
        try:
            eda_mac = await validate_upstream(hass, runtime.values, entry.entry_id)
        except Exception as ex:
            raise ConfigEntryNotReady("EDA or its unexposed MA player is not ready") from ex
    from .crown import async_prepare_crown
    from .dot import prepare_dot, release_dot

    if runtime.has_dot:
        await prepare_dot(runtime)
    else:
        await release_dot(hass, entry)
    await async_prepare_crown(runtime)
    runtime.dismissed_timers = set(await runtime.dismissal_store.async_load() or [])
    runtime.display_settings = await runtime.display_store.async_load() or {}
    runtime.device_id = (
        dr.async_get(hass)
        .async_get_or_create(config_entry_id=entry.entry_id, **runtime.device_info)
        .id
    )
    if runtime.has_dot:
        devices = dr.async_get(hass)
        device = devices.async_get(runtime.device_id)
        # A combined assistant is a logical Vision device, not the Dot's physical MAC.
        if device and device.connections == runtime.dot_device.connections:
            devices.async_update_device(device.id, new_connections=set())
    if runtime.crown_device is not None:
        runtime.crown_device.device_id = runtime.device_id
    from homeassistant.helpers import entity_registry as er

    registry = er.async_get(hass)
    for entity in er.async_entries_for_config_entry(registry, entry.entry_id):
        audio = entity.domain in (
            "assist_satellite",
            "media_player",
            "binary_sensor",
        ) or entity.unique_id.endswith(
            ("-mute", "_crown_state", "-pipeline", "-vad_sensitivity")
        )
        overlay_control = entity.unique_id.endswith(
            (
                "_overlay_enabled",
                "_alarm",
                "_hide-overlay",
                "_show-picker",
                "_show-ble-setup",
            )
        )
        if (audio and not (runtime.has_crown or runtime.has_dot or runtime.has_upstream)) or (
            overlay_control and not runtime.is_overlay
        ):
            registry.async_remove(entity.entity_id)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    from .eda_discovery import async_prepare_discovery

    await async_prepare_discovery(hass, entry, eda_mac)
    from .view_assist_indicator import async_prepare_indicator

    await async_prepare_indicator(hass, entry)
    entry.async_on_unload(entry.add_update_listener(async_options_updated))
    runtime.start()
    return True


async def async_options_updated(hass, entry):
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass, entry):
    if await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        await entry.runtime_data.stop()
        return True
    return False


async def async_remove_entry(hass, entry):
    """Restore native Dot voice ownership when its Vision pairing is removed."""
    from .dot import release_dot

    await release_dot(hass, entry)
    from .view_assist_indicator import async_release_indicator

    await async_release_indicator(hass, entry)
    from .eda_discovery import async_release_discovery

    await async_release_discovery(hass, entry)
