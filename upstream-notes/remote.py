"""One bounded remote button press before the overlay starts audio."""

import asyncio
import logging
import re
import threading
import time
from collections import deque
from contextlib import suppress
from pathlib import Path

from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)
REMOTE_PAUSE_COMMAND = "input keyevent 85"


def press_remote(
    host,
    port,
    key_path,
    expires_at,
    cancelled,
    committed,
    ready,
    expected_package=None,
    on_target=None,
):
    """Use the already authorised setup key. Never prompt or retry during playback.

    The app rechecks its audio activity after connection and before committing.
    Only the fixed remote Play/Pause command can be sent to this entry's TV.
    """
    from adb_shell.adb_device import AdbDeviceTcp
    from adb_shell.auth.sign_cryptography import CryptographySigner
    from adb_shell.exceptions import (
        AdbCommandFailureException,
        AdbConnectionError,
        AdbTimeoutError,
        DeviceAuthError,
        InvalidChecksumError,
        InvalidCommandError,
        InvalidResponseError,
        InvalidTransportError,
        TcpTimeoutException,
    )

    deadline = time.monotonic() + min(1.5, max(0, expires_at / 1000 - time.time()))

    def remaining():
        return max(0, deadline - time.monotonic())

    def require_authorisation(_device):
        # Raising before adb-shell sends a public key avoids a mid-film dialog.
        raise PermissionError("Authorise ADB in integration options first")

    if cancelled.is_set():
        return "cancelled"
    if not remaining():
        return "expired"
    if not Path(key_path).is_file():
        return "auth_required"
    device = None
    try:
        signer = CryptographySigner(key_path)
        device = AdbDeviceTcp(host, port, default_transport_timeout_s=0.5)
        if not device.connect(
            rsa_keys=[signer],
            auth_timeout_s=0.5,
            read_timeout_s=0.5,
            auth_callback=require_authorisation,
        ):
            return "auth_required"
        if cancelled.is_set() or not remaining():
            return "expired"
        windows = device.shell(
            "dumpsys window windows",
            read_timeout_s=min(0.5, remaining()),
            timeout_s=remaining(),
        )
        focus = re.search(r"mCurrentFocus=Window\{[^\n]+", windows)
        if not focus:
            return "no_foreground"
        if re.search(r"\bcom\.mph1970\.vaca_vision(?:/|\s|\})", focus[0]):
            return "foreground_overlay"
        package = re.search(r"\bu\d+\s+([A-Za-z0-9_.]+)(?:/|\s|\})", focus[0])
        if expected_package and (not package or package[1] != expected_package):
            return "foreground_changed"
        if on_target and package:
            on_target(package[1])
        if cancelled.is_set() or not remaining():
            return "expired"
        ready()
        if not committed.wait(remaining()) or cancelled.is_set() or not remaining():
            return "expired"
        if expected_package:
            # Recheck after the round trip to Android; never resume a newly
            # selected foreground app using the old pause's ownership ticket.
            windows = device.shell(
                "dumpsys window windows",
                read_timeout_s=min(0.5, remaining()),
                timeout_s=remaining(),
            )
            current = re.search(r"mCurrentFocus=Window\{[^\n]+", windows)
            current_package = (
                re.search(r"\bu\d+\s+([A-Za-z0-9_.]+)(?:/|\s|\})", current[0])
                if current
                else None
            )
            if not current_package or current_package[1] != expected_package:
                return "foreground_changed"
            if cancelled.is_set() or not remaining():
                return "expired"
        output = device.shell(
            REMOTE_PAUSE_COMMAND,
            read_timeout_s=min(0.5, remaining()),
            timeout_s=remaining(),
        )
        if output.strip():
            return "command_failed"
        return "sent"
    except PermissionError:
        return "auth_required"
    except (
        OSError,
        ValueError,
        AdbCommandFailureException,
        AdbConnectionError,
        AdbTimeoutError,
        DeviceAuthError,
        InvalidChecksumError,
        InvalidCommandError,
        InvalidResponseError,
        InvalidTransportError,
        TcpTimeoutException,
    ) as ex:
        _LOGGER.warning(
            "Fire TV remote pause failed (%s); not retried", type(ex).__name__
        )
        return "adb_failed"
    finally:
        if device is not None:
            with suppress(OSError):
                device.close()


class RemotePause:
    def __init__(self, runtime):
        self.runtime = runtime
        self.seen = deque(maxlen=128)
        self.pending = None
        self.task = None
        self.paused_target = None

    def event(self, kind, data):
        request_id = data.get("id")
        if not isinstance(request_id, str) or not re.fullmatch(
            r"[a-fA-F0-9-]{36}", request_id
        ):
            return
        if self.pending and self.pending[0] == request_id:
            if kind == "fire-tv-pause-commit":
                self.pending[2].set()
            elif kind == "fire-tv-pause-cancel":
                self.pending[1].set()
                self.pending[2].set()
            return
        if kind != "fire-tv-pause" or request_id in self.seen or self.pending:
            return
        expires = data.get("expires_at")
        if isinstance(expires, bool) or not isinstance(expires, (int, float)):
            return
        if not 0 < expires - time.time() * 1000 <= 2000:
            return
        operation = data.get("operation", "pause")
        if operation not in ("pause", "resume"):
            return
        expected_package = None
        if operation == "resume":
            if not self.paused_target or self.paused_target[0] != data.get("resume_of"):
                return
            expected_package = self.paused_target[1]
        self.paused_target = (
            None  # Never reuse a ticket after a resume attempt/new pause.
        )
        self.seen.append(request_id)
        cancelled, committed = threading.Event(), threading.Event()
        self.pending = (request_id, cancelled, committed)
        self.task = self.runtime.entry.async_create_background_task(
            self.runtime.hass,
            self.run(request_id, expires, cancelled, committed, expected_package),
            "Overlay Fire TV remote pause",
        )

    async def run(
        self, request_id, expires, cancelled, committed, expected_package=None
    ):
        runtime = self.runtime
        connection = runtime.connection
        client = connection.client
        target = []

        async def reply(action, result=None):
            if (
                client is not None
                and connection.client is client
                and connection.connected
            ):
                try:
                    await connection.action(
                        action, {"id": request_id, "result": result}
                    )
                except (OSError, TimeoutError):
                    cancelled.set()
                    committed.set()

        def ready():
            runtime.hass.loop.call_soon_threadsafe(
                lambda: runtime.entry.async_create_task(
                    runtime.hass, reply("fire-tv-pause-ready"), "Overlay remote ready"
                )
            )

        try:
            result = await runtime.hass.async_add_executor_job(
                press_remote,
                runtime.entry.data["host"],
                runtime.entry.options.get(
                    "adb_port", runtime.entry.data.get("adb_port", 5555)
                ),
                runtime.hass.config.path(".storage", f"{DOMAIN}_adb_setup"),
                expires,
                cancelled,
                committed,
                ready,
                expected_package,
                target.append,
            )
            if (
                result == "sent"
                and expected_package is None
                and target
                and not cancelled.is_set()
                and connection.client is client
                and connection.connected
            ):
                self.paused_target = (request_id, target[0])
            await reply("fire-tv-pause-result", result)
        finally:
            cancelled.set()
            committed.set()
            self.pending = None

    def cancel(self):
        self.paused_target = None
        if self.pending:
            self.pending[1].set()
            self.pending[2].set()

    async def close(self):
        self.cancel()
        if self.task:
            with suppress(asyncio.CancelledError):
                await self.task
