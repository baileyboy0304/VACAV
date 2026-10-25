"""Provision only Vision endpoints, with platform-specific roles and Crown setup."""

import asyncio
import json
import logging
import threading

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import callback
from homeassistant.helpers import selector

from .adb import AdbSetupError, grant_overlay, require_adb
from .const import (
    DEFAULT_PORT,
    DOMAIN,
    DOT_ROLES,
    ROLE_CROWN,
    ROLE_DOT,
    ROLE_OVERLAY_DOT,
    ROLE_UPSTREAM,
    ROLES,
    SERVICE_TYPE,
)
from .installer import inspect_target, install_target, package_kind
from .protocol import WrongDevice, probe


async def run_adb(hass, host, port, entry_key="setup"):
    lock = hass.data.setdefault(f"{DOMAIN}_adb_lock", asyncio.Lock())
    async with lock:
        await hass.async_add_executor_job(
            grant_overlay,
            host,
            port,
            hass.config.path(".storage", f"{DOMAIN}_adb_{entry_key}"),
        )


def role_schema(roles, values):
    return {
        vol.Required("name", default=values.get("name", "VACA_Vision")):
            vol.All(str, vol.Length(min=1, max=80)),
        vol.Required(
            "role", default=values.get("role", roles[0])
        ): selector.SelectSelector(
            selector.SelectSelectorConfig(
                options=[{"value": r, "label": ROLES[r]} for r in roles],
                mode=selector.SelectSelectorMode.DROPDOWN,
            )
        ),
        vol.Optional(
            "dashboard", default=values.get("dashboard", "/view-assist/clock")
        ): str,
    }


def upstream_schema(values):
    return {
        vol.Optional("eda_native_indicator", default=values.get("eda_native_indicator", True)): bool,
        vol.Optional("ma_entry_id", **({"default": values["ma_entry_id"]} if values.get("ma_entry_id") else {})): selector.ConfigEntrySelector(selector.ConfigEntrySelectorConfig(integration="music_assistant")),
        vol.Optional("ma_player_id", **({"default": values["ma_player_id"]} if values.get("ma_player_id") else {})): str,
        vol.Optional("eda_port", default=values.get("eda_port", 26053)): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
        vol.Optional("eda_noise_psk", default=values.get("eda_noise_psk", "")): selector.TextSelector(selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD)),
    }


def crown_schema(values):
    return {
        vol.Required("crown_port", default=values.get("crown_port", 16700)): vol.All(
            vol.Coerce(int), vol.Range(min=1, max=65535)
        ),
        vol.Required(
            "sendspin_entity", default=values.get("sendspin_entity", "")
        ): selector.EntitySelector(
            selector.EntitySelectorConfig(
                domain="media_player", integration="music_assistant"
            )
        ),
    }


def dot_schema(values):
    return {
        vol.Required(
            "dot_satellite", default=values.get("dot_satellite", "")
        ): selector.EntitySelector(
            selector.EntitySelectorConfig(
                domain="assist_satellite", integration="esphome"
            )
        ),
        vol.Optional(
            "ducking_percent", default=values.get("ducking_percent", 90)
        ): vol.All(vol.Coerce(int), vol.Range(min=0, max=100)),
    }


class VisionConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self):
        self._kind = None
        self._address = {}
        self._task = None
        self._error = None
        self._target = None
        self._found = {}
        self._existing_entry = None

    async def async_step_user(self, user_input=None):
        # The Linux wizard supplies non-secret defaults through HA's authenticated
        # config-flow API. Bind them to the probed display UUID, never just its IP.
        if user_input and user_input.get("prepare_only"):
            from .setup_helpers import setup_defaults
            try:
                host = vol.All(str, vol.Length(min=1))(user_input["host"])
                port = vol.All(vol.Coerce(int), vol.Range(min=1, max=65535))(user_input.get("port", 26900))
                name = vol.All(str, vol.Length(min=1, max=80))(user_input["name"])
                dashboard = user_input["dashboard"]
                if not isinstance(dashboard, str) or not dashboard.startswith("/") or dashboard.startswith("//") or any(c.isspace() for c in dashboard):
                    raise ValueError("Invalid dashboard path")
                info = await probe(host, port)
                if ROLE_UPSTREAM not in info.capabilities["roles"]:
                    raise ValueError("Unsupported display")
                await setup_defaults(self.hass, info.id, {"name": name, "dashboard": dashboard, "role": ROLE_UPSTREAM})
            except (KeyError, ValueError, vol.Invalid, OSError, TimeoutError):
                return self.async_abort(reason="setup_prepare_failed")
            return self.async_abort(reason="setup_prepared")
        return self.async_show_menu(
            step_id="user",
            menu_options=[
                "discover",
                "manual",
                "install_standard",
                "install_overlay",
                "install_dot",
            ],
        )

    async def _choose_install(self, kind):
        self._kind = kind
        try:
            await self.hass.async_add_executor_job(require_adb)
        except AdbSetupError:
            return await self.async_step_adb_required()
        return await self.async_step_target()

    async def async_step_adb_required(self, user_input=None):
        if user_input is not None:
            return await self._choose_install(self._kind)
        return self.async_show_form(step_id="adb_required", data_schema=vol.Schema({}))

    async def async_step_install_standard(self, user_input=None):
        return await self._choose_install("standard")

    async def async_step_install_overlay(self, user_input=None):
        return await self._choose_install("overlay")

    async def async_step_install_es8(self, user_input=None):
        return await self._choose_install("es8")

    async def async_step_install_dot(self, user_input=None):
        return await self._choose_install("dot")

    def _known_targets(self):
        found = {}
        for entry in self.hass.config_entries.async_entries():
            if entry.domain not in (
                DOMAIN,
                "vaca",
                "vaca_overlay",
                "vacaoverlay",
                "androidtv",
                "androidtv_remote",
            ):
                continue
            host = entry.options.get("host", entry.data.get("host"))
            if host:
                label = (
                    "Vision already configured"
                    if entry.domain == DOMAIN
                    else "known device; inspection required"
                )
                found[host] = f"{entry.title} ({host}; {label})"
        return found

    async def async_step_target(self, user_input=None):
        if user_input is not None:
            self._address = user_input
            self._error = None
            return await self.async_step_inspect()
        prerequisites = {
            "standard": "Enable network ADB debugging on the display. Android 7.1 or later and an ARM processor are required. Portal devices are recognised automatically; device administrator and screen locking are skipped.",
            "overlay": "On Fire TV, enable Developer Options and ADB debugging. Keep the TV awake. Vision installs the overlay app and its picker, then grants overlay and Bluetooth remote permissions.",
            "es8": "Complete the supported Echo Show 8 unlock and install echo-show-assist / Crown first. Connect Crown to Home Assistant and Sendspin to Music Assistant. Enable network ADB on the ES8. Vision does not unlock or flash the device.",
            "dot": "Enter the DISPLAY address, not the Dot address. Install standard upstream echo-dot-assist on the Dot and add its ESPHome integration to HA first. Enable network ADB on the display. On Fire TV, Vision keeps overlays and plays replies and Sendspin through the TV. You will select the Dot after installing Vision.",
        }
        options = [
            {"value": host, "label": label}
            for host, label in self._known_targets().items()
        ]
        return self.async_show_form(
            step_id="target",
            errors={"base": self._error} if self._error else {},
            description_placeholders={"prerequisites": prerequisites[self._kind]},
            data_schema=vol.Schema(
                {
                    vol.Required(
                        "host",
                        **({"default": self._address["host"]} if self._address else {}),
                    ): selector.SelectSelector(
                        selector.SelectSelectorConfig(
                            options=options,
                            custom_value=True,
                            mode=selector.SelectSelectorMode.DROPDOWN,
                        )
                    ),
                    vol.Required(
                        "adb_port", default=self._address.get("adb_port", 5555)
                    ): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
                }
            ),
        )

    async def _run_job(self, installing):
        cancelled = threading.Event()

        async def worker():
            lock = self.hass.data.setdefault(f"{DOMAIN}_adb_lock", asyncio.Lock())
            async with lock:
                if cancelled.is_set():
                    return
                args = [
                    self._address["host"],
                    self._address["adb_port"],
                    self.hass.config.path(".storage", f"{DOMAIN}_adb_setup"),
                    self._kind,
                ]
                if installing:
                    await self.hass.async_add_executor_job(
                        install_target, *args, self._target, cancelled
                    )
                else:
                    self._target = await self.hass.async_add_executor_job(
                        inspect_target, *args
                    )

        task = self.hass.async_create_task(worker())
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            # An executor cannot be forcibly cancelled. Retain the lock until it
            # has cleaned up; checkpoints prevent subsequent installation steps.
            cancelled.set()
            try:
                await asyncio.shield(task)
            except Exception:
                logging.getLogger(__name__).debug(
                    "Cancelled installer worker finished with an error", exc_info=True
                )
            raise
        except AdbSetupError as ex:
            self._error = ex.code
        except Exception:
            logging.getLogger(__name__).exception("Vision installer failed")
            self._error = "adb_command_failed"

    async def _progress(self, installing):
        step = "install" if installing else "inspect"
        if self._task is None:
            self._task = self.hass.async_create_task(self._run_job(installing))
        if not self._task.done():
            return self.async_show_progress(
                step_id=step, progress_action=step, progress_task=self._task
            )
        self._task.result()
        self._task = None
        return self.async_show_progress_done(
            next_step_id=(
                "target"
                if self._error
                else ("installed" if installing else "review_install")
            )
        )

    async def async_step_inspect(self, user_input=None):
        return await self._progress(False)

    async def async_step_install(self, user_input=None):
        return await self._progress(True)

    async def async_step_review_install(self, user_input=None):
        if user_input is not None:
            self._error = None
            return await self.async_step_install()
        return self.async_show_form(
            step_id="review_install",
            data_schema=vol.Schema({}),
            description_placeholders={
                "model": f"{self._target.manufacturer} {self._target.model}",
                "sdk": str(self._target.sdk),
                "profile": self._target.profile,
                "host": self._address["host"],
                "action": "Update the existing Vision app"
                if self._target.installed
                else "Install Vision alongside any original VACA app",
                "permissions": "Overlay display and Bluetooth remote access; also install the Vision picker"
                if package_kind(self._target, self._kind) == "overlay"
                else "Screen brightness/settings access; no device administrator request on Portal",
            },
        )

    async def async_step_installed(self, user_input=None):
        errors = {}
        if user_input is not None:
            result = await self._connect(self._address["host"], DEFAULT_PORT)
            if result is not None:
                return result
            errors["base"] = self._error
        return self.async_show_form(
            step_id="installed", data_schema=vol.Schema({}), errors=errors
        )

    async def _connect(self, host, port):
        try:
            info = await probe(host, port)
        except WrongDevice:
            self._error = "wrong_device"
            return None
        except (OSError, TimeoutError, ValueError, KeyError, TypeError):
            self._error = "cannot_connect"
            return None
        # Adopt only our automatic discovery prompt, never another user's setup.
        for flow in self.hass.config_entries.flow.async_progress_by_handler(DOMAIN):
            if (
                flow["flow_id"] != self.flow_id
                and flow["context"].get("source") == "zeroconf"
                and flow["context"].get("unique_id") == info.id
            ):
                self.hass.config_entries.flow.async_abort(flow["flow_id"])
        await self.async_set_unique_id(info.id)
        existing = next(
            (
                entry
                for entry in self.hass.config_entries.async_entries(DOMAIN)
                if entry.unique_id == info.id
            ),
            None,
        )
        if existing is not None and self._kind is None:
            self._abort_if_unique_id_configured()
        self._existing_entry = existing
        previous = {**existing.data, **existing.options} if existing else {}
        self._device = {
            **previous,
            "host": host,
            "port": port,
            "id": info.id,
            "name": existing.title if existing else info.name,
            "roles": info.capabilities["roles"],
        }
        role = {
            "standard": "standard_display_only",
            "overlay": "overlay_display_only",
            "es8": ROLE_CROWN,
            "dot": ROLE_OVERLAY_DOT if info.capabilities.get("overlay") else ROLE_DOT,
        }.get(self._kind)
        if role is not None and role not in self._device["roles"]:
            self._error = "role_requires_update"
            return None
        if role is not None:
            self._device["role"] = role
        return await self.async_step_confirm()

    async def async_step_manual(self, user_input=None):
        errors = {}
        if user_input is not None:
            for port in ([user_input["port"]] if user_input.get("port") else [26900, DEFAULT_PORT]):
                result = await self._connect(user_input["host"], port)
                if result is not None:
                    return result
            errors["base"] = self._error
        return self.async_show_form(
            step_id="manual",
            errors=errors,
            data_schema=vol.Schema(
                {
                    vol.Required("host"): str,
                    **({vol.Optional("port"): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535))}
                       if self.show_advanced_options else {}),
                }
            ),
        )

    async def async_step_discover(self, user_input=None):
        from .discovery import discover

        errors = {}
        if user_input is not None and user_input.get("device") in self._found:
            host, port, _name = self._found[user_input["device"]]
            result = await self._connect(host, port)
            if result is not None:
                return result
            errors["base"] = self._error
        self._found = await discover(self.hass)
        configured = {
            entry.unique_id for entry in self.hass.config_entries.async_entries(DOMAIN)
        }
        self._found = {
            key: value for key, value in self._found.items() if key not in configured
        }
        if not self._found:
            return await self.async_step_no_devices()
        return self.async_show_form(
            step_id="discover",
            errors=errors,
            data_schema=vol.Schema(
                {
                    vol.Required("device"): selector.SelectSelector(
                        selector.SelectSelectorConfig(
                            options=[
                                {"value": key, "label": f"{name} ({host})"}
                                for key, (host, _port, name) in self._found.items()
                            ],
                            mode=selector.SelectSelectorMode.DROPDOWN,
                        )
                    )
                }
            ),
        )

    async def async_step_no_devices(self, user_input=None):
        return self.async_show_menu(
            step_id="no_devices", menu_options=["discover", "manual", "user"]
        )

    async def async_step_zeroconf(self, discovery_info):
        if discovery_info.type != SERVICE_TYPE:
            return self.async_abort(reason="wrong_device")
        try:
            info = await probe(discovery_info.host, discovery_info.port)
        except (OSError, TimeoutError, ValueError, KeyError, TypeError):
            return self.async_abort(reason="cannot_connect")
        await self.async_set_unique_id(info.id)
        self._abort_if_unique_id_configured(
            updates={"host": discovery_info.host, "port": discovery_info.port}
        )
        self._device = {
            "host": discovery_info.host,
            "port": discovery_info.port,
            "id": info.id,
            "name": info.name,
            "roles": info.capabilities["roles"],
        }
        return await self.async_step_confirm()

    async def async_step_confirm(self, user_input=None):
        from .setup_helpers import setup_defaults
        # Discovery may precede the Linux installer handoff; refresh on submit.
        defaults = await setup_defaults(self.hass, self._device["id"])
        if self._existing_entry is None:
            self._device.update(defaults)
            if self._device["port"] == 26900 and ROLE_UPSTREAM in self._device["roles"]:
                self._device["role"] = ROLE_UPSTREAM
            # HA's standard dashboard is a safe landing page without the wizard.
            self._device.setdefault("dashboard", "/lovelace/0")
        es8 = self._device.get("role") == ROLE_UPSTREAM
        if user_input is not None:
            self._device.update(user_input)
            if self._device["role"] == ROLE_UPSTREAM:
                return await self.async_step_upstream()
            if self._device["role"] in DOT_ROLES:
                return await self.async_step_dot()
            if self._device["role"] == ROLE_CROWN:
                return await self.async_step_crown()
            return await self._finish(self._device)
        schema = {vol.Required("name", default=self._device["name"]): vol.All(str, vol.Length(min=1, max=80))} if es8 else role_schema(self._device["roles"], self._device)
        return self.async_show_form(step_id="confirm_es8" if es8 else "confirm",
            data_schema=vol.Schema(schema), description_placeholders={"name": self._device["name"]})

    async def async_step_confirm_es8(self, user_input=None):
        return await self.async_step_confirm(user_input)

    async def async_step_upstream_auto(self, user_input=None):
        return await self.async_step_upstream(user_input)

    async def async_step_upstream(self, user_input=None):
        from .setup_helpers import music_choices
        from .upstream import validate_upstream
        from aioesphomeapi import InvalidEncryptionKeyAPIError
        errors = {}
        values = self._device
        if user_input and user_input.get("eda_noise_psk"):
            values["eda_noise_psk"] = user_input["eda_noise_psk"]
        choices, matches = await music_choices(self.hass, values["host"], values.get("eda_mac"))
        selection = (user_input or {}).get("music_player")
        if selection:
            if selection not in choices:
                errors["base"] = "ma_player_missing"
            else:
                values["ma_entry_id"], values["ma_player_id"] = json.loads(selection)
        elif not values.get("ma_player_id") and len(matches) == 1:
            values["ma_entry_id"], values["ma_player_id"] = json.loads(matches[0])
        values.setdefault("eda_native_indicator", True)
        values.setdefault("eda_port", 26053)
        needs_key = getattr(self, "_needs_key", False)
        if not errors and values.get("ma_player_id"):
            try:
                values["eda_mac"] = await validate_upstream(self.hass, values,
                    self._existing_entry.entry_id if self._existing_entry else None,
                    expose_music=True)
            except InvalidEncryptionKeyAPIError:
                needs_key = self._needs_key = True
                errors["base"] = "eda_key_required"
            except ValueError as ex:
                errors["base"] = str(ex)
                if str(ex) == "eda_key_required":
                    needs_key = self._needs_key = True
            except Exception:
                errors["base"] = "cannot_connect"
            else:
                return await self._finish(values)
        schema = {}
        if not values.get("ma_player_id") or errors.get("base", "").startswith("ma_"):
            if choices:
                schema[vol.Required("music_player")] = selector.SelectSelector(
                    selector.SelectSelectorConfig(options=[{"value": k, "label": v} for k, v in choices.items()], mode=selector.SelectSelectorMode.DROPDOWN))
            else:
                errors["base"] = "ma_pair_first"
        if needs_key:
            schema[vol.Required("eda_noise_psk")] = selector.TextSelector(selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD))
        return self.async_show_form(step_id="upstream_auto", errors=errors, data_schema=vol.Schema(schema),
            description_placeholders={"settings_url": f'http://{values["host"]}:28931/'})

    async def async_step_crown(self, user_input=None):
        errors = {}
        if user_input is not None:
            from .crown import validate_crown

            try:
                await validate_crown(
                    self.hass,
                    self._device["host"],
                    user_input["crown_port"],
                    user_input["sendspin_entity"],
                )
            except ValueError as ex:
                errors["base"] = str(ex)
            else:
                return await self._finish({**self._device, **user_input})
        return self.async_show_form(
            step_id="crown",
            errors=errors,
            data_schema=vol.Schema(crown_schema(user_input or {})),
        )

    async def async_step_dot(self, user_input=None):
        errors = {}
        if user_input is not None:
            from .dot import dot_target

            try:
                dot_target(
                    self.hass,
                    user_input,
                    self._existing_entry.entry_id if self._existing_entry else None,
                )
            except ValueError as ex:
                errors["base"] = str(ex)
            else:
                return await self._finish({**self._device, **user_input})
        return self.async_show_form(
            step_id="dot",
            errors=errors,
            data_schema=vol.Schema(dot_schema(user_input or self._device)),
        )

    async def _finish(self, values):
        if self._existing_entry is None:
            self._abort_if_unique_id_configured()
            return self.async_create_entry(title=values["name"], data=values)
        entry = self.hass.config_entries.async_get_entry(self._existing_entry.entry_id)
        if entry is None or entry.unique_id != values["id"]:
            return self.async_abort(reason="device_changed")
        previous = {**entry.data, **entry.options}
        if previous.get("role") in DOT_ROLES and previous["role"] != values["role"]:
            return self.async_abort(reason="dot_remove_to_unpair")
        options = {
            **entry.options,
            **{
                key: values[key]
                for key in (
                    "role",
                    "dashboard",
                    "dot_satellite",
                    "ducking_percent",
                    "crown_port",
                    "sendspin_entity",
                    "eda_port", "eda_noise_psk", "eda_mac", "ma_entry_id", "ma_player_id",
                )
                if key in values
            },
        }
        self.hass.config_entries.async_update_entry(
            entry, title=values["name"], data={**entry.data, **values}, options=options
        )
        # Loaded entries have an update listener. Explicitly retry an unloaded
        # entry as well, e.g. after its previous APK stopped advertising this role.
        if entry.state != config_entries.ConfigEntryState.LOADED:
            await self.hass.config_entries.async_reload(entry.entry_id)
        return self.async_abort(reason="device_updated")

    @staticmethod
    @callback
    def async_get_options_flow(config_entry):
        return VisionOptionsFlow()


class VisionOptionsFlow(config_entries.OptionsFlow):
    async def async_step_es8(self, user_input=None):
        return await self.async_step_init(user_input)

    async def async_step_init(self, user_input=None):
        errors = {}
        values = {**self.config_entry.data, **self.config_entry.options}
        values["name"] = self.config_entry.title
        roles = values["roles"]
        try:
            info = await probe(
                (user_input or {}).get("host", values["host"]),
                (user_input or {}).get("port", values["port"]),
            )
            if info.id != self.config_entry.unique_id:
                return self.async_abort(reason="device_changed")
            roles = info.capabilities["roles"]
        except (OSError, TimeoutError, ValueError, KeyError, TypeError):
            pass  # Keep existing options usable while the display is offline.
        values["roles"] = roles
        if user_input is not None:
            user_input = dict(user_input)
            if not user_input.get("eda_noise_psk"):
                user_input.pop("eda_noise_psk", None)
            values.update(user_input)
            if values.get("music_player"):
                from .setup_helpers import music_choices
                choices, _ = await music_choices(self.hass, values["host"], values.get("eda_mac"))
                if values["music_player"] not in choices:
                    errors["base"] = "ma_player_missing"
                else:
                    values["ma_entry_id"], values["ma_player_id"] = json.loads(values.pop("music_player"))
            if values["role"] not in roles:
                errors["base"] = "role_requires_update"
            original_role = self.config_entry.options.get(
                "role", self.config_entry.data.get("role")
            )
            if original_role in DOT_ROLES and values["role"] != original_role:
                errors["base"] = "dot_remove_to_unpair"
            if values["role"] in DOT_ROLES:
                from .dot import dot_target

                try:
                    dot_target(self.hass, values, self.config_entry.entry_id)
                except ValueError as ex:
                    errors["base"] = str(ex)
            if values["role"] == ROLE_CROWN:
                from .crown import validate_crown

                try:
                    await validate_crown(
                        self.hass,
                        self.config_entry.data["host"],
                        values.get("crown_port", 16700),
                        values.get("sendspin_entity", ""),
                    )
                except ValueError as ex:
                    errors["base"] = str(ex)
            if values["role"] == ROLE_UPSTREAM and not errors:
                from .upstream import validate_upstream
                try:
                    values["eda_mac"] = await validate_upstream(self.hass, values, self.config_entry.entry_id, expose_music=True)
                except ValueError as ex:
                    errors["base"] = str(ex)
                except Exception:  # noqa: BLE001 - API boundary: keep pairing secrets out of errors
                    errors["base"] = "cannot_connect"
            if not errors:
                self.hass.config_entries.async_update_entry(
                    self.config_entry, title=values["name"]
                )
                return self.async_create_entry(title="", data=values)
        if values.get("role") == ROLE_UPSTREAM:
            from .setup_helpers import music_choices
            choices, _ = await music_choices(self.hass, values["host"], values.get("eda_mac"))
            schema = {
                vol.Required("name", default=values["name"]): str,
                vol.Optional("dashboard", default=values["dashboard"]): str,
            }
            current = json.dumps([values.get("ma_entry_id"), values.get("ma_player_id")], separators=(",", ":"))
            if choices:
                schema[vol.Optional("music_player", **({"default": current} if current in choices else {}))] = selector.SelectSelector(selector.SelectSelectorConfig(options=[{"value": k, "label": v} for k, v in choices.items()], mode=selector.SelectSelectorMode.DROPDOWN))
            if self.show_advanced_options:
                schema.update({vol.Optional("host", default=values["host"]): str,
                    vol.Optional("port", default=values["port"]): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
                    vol.Optional("eda_port", default=values.get("eda_port", 26053)): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
                    vol.Optional("eda_noise_psk"): selector.TextSelector(selector.TextSelectorConfig(type=selector.TextSelectorType.PASSWORD))})
            return self.async_show_form(step_id="es8", data_schema=vol.Schema(schema), errors=errors)
        schema = role_schema(roles, values)
        schema.update({
            vol.Optional("host", default=values["host"]): str,
            vol.Optional("port", default=values["port"]): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
        })
        if ROLE_UPSTREAM in roles:
            schema.update(upstream_schema(values))
        if ROLE_CROWN in roles:
            # Audio fields stay optional when a display-only role is selected.
            schema.update(
                {
                    vol.Optional(
                        "crown_port", default=values.get("crown_port", 16700)
                    ): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
                    vol.Optional(
                        "sendspin_entity", default=values.get("sendspin_entity", "")
                    ): selector.EntitySelector(
                        selector.EntitySelectorConfig(
                            domain="media_player", integration="music_assistant"
                        )
                    ),
                }
            )
        if DOT_ROLES.intersection(roles):
            schema.update(
                {
                    vol.Optional(
                        "dot_satellite", default=values.get("dot_satellite", "")
                    ): str,
                    vol.Optional(
                        "ducking_percent", default=values.get("ducking_percent", 90)
                    ): vol.All(vol.Coerce(int), vol.Range(min=0, max=100)),
                }
            )
        return self.async_show_form(
            step_id="init", errors=errors, data_schema=vol.Schema(schema)
        )
