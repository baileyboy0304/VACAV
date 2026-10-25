"""Admin-only profile library UI and WebSocket API."""

from pathlib import Path

import voluptuous as vol
from homeassistant.components import panel_custom, websocket_api
from homeassistant.components.http import StaticPathConfig
from homeassistant.exceptions import HomeAssistantError

from .const import DOMAIN, VERSION


async def async_setup_panel(hass):
    websocket_api.async_register_command(hass, picker_command)
    await hass.http.async_register_static_paths(
        [
            StaticPathConfig(
                "/vaca_vision/picker-panel.js",
                str(Path(__file__).parent / "picker-panel.js"),
                False,
            )
        ]
    )
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path="vaca_vision-pickers",
        webcomponent_name="vaca_vision-pickers",
        sidebar_title="Overlay pickers",
        sidebar_icon="mdi:view-dashboard-edit",
        module_url=f"/vaca_vision/picker-panel.js?v={VERSION}",
        require_admin=True,
    )


def runtimes(hass):
    return {
        entry.entry_id: entry.runtime_data
        for entry in hass.config_entries.async_entries(DOMAIN)
        if getattr(entry, "runtime_data", None) is not None
    }


@websocket_api.websocket_command(
    {
        vol.Required("type"): "vaca_vision/pickers",
        vol.Required("operation"): vol.In(
            ["list", "get", "save", "delete", "capture", "apply", "restore"]
        ),
        vol.Optional("profile_id"): str,
        vol.Optional("revision"): vol.All(int, vol.Range(min=1)),
        vol.Optional("name"): vol.All(str, vol.Length(min=1, max=60)),
        vol.Optional("profile"): dict,
        vol.Optional("device"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def picker_command(hass, connection, msg):
    library = hass.data[DOMAIN]["picker_profiles"]
    op = msg["operation"]
    try:
        if op == "list":
            result = {
                "profiles": library.list(),
                "devices": [
                    {
                        "id": identity,
                        "name": runtime.entry.title,
                        "connected": runtime.connection.connected,
                        "supported": runtime.info.capabilities.get("picker_profiles")
                        == 1,
                    }
                    for identity, runtime in runtimes(hass).items()
                ],
            }
        elif op == "get":
            result = library.get(msg.get("profile_id"))
        elif op == "save":
            result = await library.save(
                msg.get("name"),
                msg.get("profile"),
                msg.get("profile_id"),
                msg.get("revision"),
            )
        elif op == "delete":
            await library.delete(msg.get("profile_id"), msg.get("revision"))
            result = {}
        else:
            runtime = runtimes(hass).get(msg.get("device"))
            if runtime is None:
                raise ValueError("Select a connected Overlay device")
            if op == "capture":
                data = await runtime.picker_device("get")
                result = await library.save(
                    msg.get("name"),
                    data["profile"],
                    msg.get("profile_id"),
                    msg.get("revision"),
                )
            elif op == "apply":
                row = library.get(msg.get("profile_id"))
                if msg.get("revision") != row["revision"]:
                    raise ValueError("Profile changed. Refresh before applying.")
                result = await runtime.picker_device("apply", row["profile"])
            else:
                result = await runtime.picker_device("restore")
        connection.send_result(msg["id"], result)
    except (ValueError, TypeError, KeyError, HomeAssistantError) as ex:
        connection.send_error(msg["id"], "profile_error", str(ex)[:200])
    except OSError:
        connection.send_error(
            msg["id"], "storage_error", "Home Assistant could not save the profile"
        )
