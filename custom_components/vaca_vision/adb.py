"""Optional, bounded Fire TV setup. No user-supplied shell command."""

import logging
import re
import time
from pathlib import Path

PACKAGE = "com.mph1970.vacavision"
GRANT_COMMAND = f"appops set {PACKAGE} SYSTEM_ALERT_WINDOW allow"
CHECK_COMMAND = f"appops get {PACKAGE} SYSTEM_ALERT_WINDOW"
_LOGGER = logging.getLogger(__name__)


class AdbSetupError(ValueError):
    """A setup stage failed; code maps to an actionable translated form error."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require_adb() -> None:
    """HA installs this Python client; a system adb executable is not used."""
    try:
        from adb_shell.adb_device import AdbDeviceTcp  # noqa: F401
        from adb_shell.auth.sign_cryptography import CryptographySigner  # noqa: F401
    except ImportError as ex:
        raise AdbSetupError("adb_not_installed") from ex


def grant_overlay(host: str, port: int, key_path: str) -> None:
    """Run in HA's executor; authorization may need accepting on the TV."""
    require_adb()
    from adb_shell.adb_device import AdbDeviceTcp
    from adb_shell.auth.keygen import keygen
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

    failures = (
        OSError,
        AdbConnectionError,
        AdbTimeoutError,
        DeviceAuthError,
        InvalidChecksumError,
        InvalidCommandError,
        InvalidResponseError,
        InvalidTransportError,
        TcpTimeoutException,
        AdbCommandFailureException,
    )
    path = Path(key_path)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            keygen(str(path))
        path.chmod(0o600)
        signer = CryptographySigner(str(path))
    except (OSError, ValueError) as ex:
        raise AdbSetupError("adb_key_failed") from ex
    device = AdbDeviceTcp(
        host,
        port=port,
        default_transport_timeout_s=10,
        banner="VACA_Vision Home Assistant",
    )
    stage = "adb_connect_failed"

    def awaiting_authorization(_device):
        nonlocal stage
        stage = "adb_auth_failed"

    try:
        if not device.connect(
            rsa_keys=[signer],
            auth_timeout_s=45,
            read_timeout_s=50,
            auth_callback=awaiting_authorization,
        ):
            raise AdbSetupError("adb_auth_failed")
        stage = "adb_command_failed"
        installed = device.shell(f"pm path {PACKAGE}", timeout_s=10)
        if not installed.strip().startswith("package:"):
            raise AdbSetupError("adb_app_missing")
        result = device.shell(CHECK_COMMAND, timeout_s=10)
        if not re.search(r"SYSTEM_ALERT_WINDOW:\s*allow\b", result):
            device.shell(GRANT_COMMAND, timeout_s=10)
            # The verified permission is authoritative, not whether appops printed text.
            result = device.shell(CHECK_COMMAND, timeout_s=10)
            if not re.search(r"SYSTEM_ALERT_WINDOW:\s*allow\b", result):
                raise AdbSetupError("adb_permission_failed")
        stage = "adb_launch_failed"
        # Permission can be granted before the app has ever been able to start
        # its TCP listener. Open its normal startup flow (including HA sign-in).
        result = device.shell(
            f"am start -n {PACKAGE}/com.msp1974.vacompanion.MainActivity", timeout_s=15
        )
        if re.search(r"(?im)^\s*(error|exception)", result):
            raise AdbSetupError("adb_launch_failed")
    except failures as ex:
        if isinstance(ex, DeviceAuthError):
            stage = "adb_auth_failed"
        _LOGGER.warning(
            "ADB setup for %s:%s failed at %s (%s)",
            host,
            port,
            stage,
            type(ex).__name__,
        )
        raise AdbSetupError(stage) from ex
    finally:
        try:
            device.close()
        except failures:
            _LOGGER.debug("ADB transport already closed for %s:%s", host, port)


def write_all(writer, data, timeout):
    """adb-shell 0.4.4 ignores short socket writes; complete each ADB packet.

    Socket writes are allowed to return fewer bytes than requested, especially
    for APK transfers on HA's nonblocking Linux socket. Keep a single deadline
    across partial writes instead of extending the timeout for every chunk.
    """
    deadline = time.monotonic() + (timeout if timeout is not None else 10)
    sent = 0
    view = memoryview(data)
    while sent < len(view):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("ADB packet write timed out")
        try:
            count = writer(view[sent:], remaining)
        except BlockingIOError:
            continue
        if count <= 0:
            raise ConnectionError("ADB connection closed during packet write")
        sent += count
    return sent


def create_device(host, port):
    """Use the maintained ADB protocol with a complete-write TCP transport."""
    require_adb()
    from adb_shell.adb_device import AdbDevice
    from adb_shell.transport.tcp_transport import TcpTransport

    class CompleteTcpTransport(TcpTransport):
        def bulk_write(self, data, transport_timeout_s):
            return write_all(super().bulk_write, data, transport_timeout_s)

    return AdbDevice(
        CompleteTcpTransport(host, port),
        default_transport_timeout_s=10,
        banner="VACA_Vision Home Assistant",
    )
