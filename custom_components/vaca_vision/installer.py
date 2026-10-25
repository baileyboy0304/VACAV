"""Bounded HA-owned Android installer. Never uninstalls or unlocks a device."""

import hashlib
import io
import json
import logging
import re
import threading
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path

from .adb import PACKAGE, AdbSetupError, create_device, require_adb

PACKAGES = Path(__file__).parent / "packages"
ACTIVITY = f"{PACKAGE}/com.msp1974.vacompanion.MainActivity"
CROWN = "/data/local/crown-assist"
POLICY_HEADER = "Name\tPackage ID\tAllow internet access\tMode"


@dataclass(frozen=True)
class Target:
    manufacturer: str
    model: str
    sdk: int
    abis: tuple[str, ...]
    identity: str
    profile: str
    installed: bool
    crown: bool

    def as_dict(self):
        return asdict(self)


def classify(manufacturer, model):
    if model.lower().startswith("portal") and manufacturer.lower() in (
        "facebook",
        "meta",
    ):
        return "portal"
    if manufacturer.lower() == "amazon":
        if model.startswith("AFT"):
            return "fire_tv"
        if model == "AEOCW":
            return "es8"
        if model == "AEOBC":
            return "dot"
    return "android"


def inspect(device):
    def prop(name):
        return device.shell(f"getprop {name}", timeout_s=10).strip()

    manufacturer, model = prop("ro.product.manufacturer"), prop("ro.product.model")
    try:
        sdk = int(prop("ro.build.version.sdk"))
    except ValueError as ex:
        raise AdbSetupError("unsupported_android") from ex
    abis = tuple(
        (prop("ro.product.cpu.abilist") or prop("ro.product.cpu.abi")).split(",")
    )
    identity = prop("ro.serialno") or prop("ro.boot.serialno")
    if not identity:
        identity = device.shell("settings get secure android_id", timeout_s=10).strip()
    if not identity or identity in ("null", "unknown"):
        raise AdbSetupError("target_identity_missing")
    return Target(
        manufacturer,
        model,
        sdk,
        abis,
        identity,
        classify(manufacturer, model),
        device.shell(f"pm path {PACKAGE}", timeout_s=10).strip().startswith("package:"),
        device.shell(
            f"test -f {CROWN}/firewall.sh && echo present", timeout_s=10
        ).strip()
        == "present",
    )


def validate_target(target, kind):
    if target.sdk < 25 or not set(target.abis).intersection(
        {"armeabi-v7a", "arm64-v8a"}
    ):
        raise AdbSetupError("unsupported_android")
    if target.profile == "dot":
        raise AdbSetupError("select_display_address")
    if kind == "overlay" and target.profile != "fire_tv":
        raise AdbSetupError("not_fire_tv")
    if kind not in ("overlay", "dot") and target.profile == "fire_tv":
        raise AdbSetupError("choose_fire_tv")
    if kind == "es8" and (target.profile != "es8" or not target.crown):
        raise AdbSetupError("es8_prerequisite")


def package_kind(target, kind):
    """A Fire TV paired with a Dot keeps its overlay build and permissions."""
    return "overlay" if target.profile == "fire_tv" and kind == "dot" else kind


def verify_packages(kind, directory=PACKAGES):
    """Check all bundled payloads before opening a write session."""
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        names = ("overlay", "picker") if kind == "overlay" else ("standard",)
        payloads = []
        for name in names:
            meta = manifest[name]
            filename = meta["file"]
            if Path(filename).name != filename or not filename.endswith(".apk"):
                raise AdbSetupError("apk_invalid")
            path = directory / filename
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest != meta["sha256"]:
                raise AdbSetupError("apk_invalid")
            payloads.append((path, meta))
        return payloads
    except (OSError, KeyError, ValueError, TypeError) as ex:
        if isinstance(ex, AdbSetupError):
            raise
        raise AdbSetupError("apk_missing") from ex


@contextmanager
def connection(host, port, key_path):
    require_adb()
    from adb_shell import exceptions
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_cryptography import CryptographySigner

    path = Path(key_path)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            keygen(str(path))
        path.chmod(0o600)
        signer = CryptographySigner(str(path))
    except (OSError, ValueError) as ex:
        raise AdbSetupError("adb_key_failed") from ex
    device = create_device(host, port)
    stage = "adb_connect_failed"

    def awaiting(_device):
        nonlocal stage
        stage = "adb_auth_failed"

    try:
        if not device.connect(
            rsa_keys=[signer],
            auth_timeout_s=45,
            read_timeout_s=50,
            auth_callback=awaiting,
        ):
            raise AdbSetupError("adb_auth_failed")
        stage = "adb_command_failed"
        yield device
    except AdbSetupError:
        raise
    except (
        OSError,
        exceptions.AdbConnectionError,
        exceptions.AdbTimeoutError,
        exceptions.DeviceAuthError,
        exceptions.InvalidChecksumError,
        exceptions.InvalidCommandError,
        exceptions.InvalidResponseError,
        exceptions.InvalidTransportError,
        exceptions.TcpTimeoutException,
        exceptions.AdbCommandFailureException,
    ) as ex:
        if isinstance(ex, exceptions.DeviceAuthError):
            stage = "adb_auth_failed"
        logging.getLogger(__name__).warning(
            "Vision ADB operation failed at %s", stage, exc_info=True
        )
        raise AdbSetupError(stage) from ex
    finally:
        device.close()


def inspect_target(host, port, key_path, kind):
    with connection(host, port, key_path) as device:
        target = inspect(device)
        validate_target(target, kind)
        verify_packages(package_kind(target, kind))
        return target


def shell_checked(device, command, code="adb_command_failed", timeout=20):
    # Fixed commands only. A shell exit marker catches commands with empty output.
    output = device.shell(
        command + "; echo VISION_EXIT_$?",
        timeout_s=timeout,
        read_timeout_s=timeout,
        transport_timeout_s=timeout,
    )
    if not re.search(r"(?m)^VISION_EXIT_0\s*$", output) or re.search(
        r"(?im)^\s*(Failure\s*\[|Error|Exception|SecurityException|Permission Denial)",
        output,
    ):
        raise AdbSetupError(code)
    return output


def whitelist_policy(text):
    lines = text.splitlines()
    if (
        not lines
        or lines[0] != POLICY_HEADER
        or any(len(line.split("\t")) != 4 for line in lines[1:] if line)
    ):
        raise AdbSetupError("crown_policy_failed")
    lines = [
        line
        for line in lines
        if len(line.split("\t")) < 2 or line.split("\t")[1] != PACKAGE
    ]
    return "\n".join(lines + [f"VACA_Vision\t{PACKAGE}\tYES\tWEB", ""])


def install_target(host, port, key_path, kind, expected, cancelled: threading.Event):
    build = package_kind(expected, kind)
    payloads = verify_packages(build)

    def checkpoint():
        if cancelled.is_set():
            raise AdbSetupError("installation_cancelled")

    checkpoint()
    with connection(host, port, key_path) as device:
        checkpoint()
        target = inspect(device)
        if target != expected:
            raise AdbSetupError("target_changed")
        validate_target(target, kind)
        policy = None
        if target.crown:
            if device.shell("id -u", timeout_s=10).strip() != "0":
                raise AdbSetupError("crown_root_required")
            policy = whitelist_policy(
                device.shell(f"cat {CROWN}/apps.tsv", timeout_s=10)
            )
        checkpoint()
        locked = False
        try:
            if policy is not None:
                shell_checked(device, f"touch {CROWN}/policy-update.lock")
                locked = True
                shell_checked(device, f"sh {CROWN}/firewall.sh close")
            for path, meta in payloads:
                checkpoint()
                remote = f"/data/local/tmp/vaca-vision-{uuid.uuid4().hex}.apk"
                try:
                    device.push(
                        str(path),
                        remote,
                        read_timeout_s=30,
                        transport_timeout_s=30,
                        progress_callback=lambda *_args: checkpoint(),
                    )
                    checkpoint()
                    # No uninstall or downgrade: incompatible signatures/versions stop here.
                    output = shell_checked(
                        device, f"pm install -r {remote}", "apk_install_failed", 120
                    )
                    if not re.search(r"(?m)^Success\s*$", output):
                        raise AdbSetupError("apk_install_failed")
                finally:
                    try:
                        device.shell(f"rm -f {remote}", timeout_s=10)
                    except Exception:
                        logging.getLogger(__name__).warning(
                            "Could not remove temporary Vision APK after transfer",
                            exc_info=True,
                        )
            checkpoint()
            permission = (
                "SYSTEM_ALERT_WINDOW" if build == "overlay" else "WRITE_SETTINGS"
            )
            shell_checked(
                device,
                f"appops set {PACKAGE} {permission} allow",
                "adb_permission_failed",
            )
            result = device.shell(f"appops get {PACKAGE} {permission}", timeout_s=10)
            if not re.search(rf"{permission}:\s*allow\b", result):
                raise AdbSetupError("adb_permission_failed")
            if build == "overlay":
                permissions = (
                    ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT")
                    if target.sdk >= 31
                    else ("ACCESS_FINE_LOCATION",)
                )
                for permission in permissions:
                    shell_checked(
                        device,
                        f"pm grant {PACKAGE} android.permission.{permission}",
                        "adb_permission_failed",
                    )
            if policy is not None:
                device.push(
                    io.BytesIO(policy.encode("utf-8")), f"{CROWN}/apps-vision.new"
                )
                shell_checked(
                    device,
                    f"chmod 600 {CROWN}/apps-vision.new && chown 0:0 {CROWN}/apps-vision.new && cp -p {CROWN}/apps.tsv {CROWN}/apps-before-vision-install.tsv && mv {CROWN}/apps-vision.new {CROWN}/apps.tsv",
                    "crown_policy_failed",
                )
        finally:
            if locked:
                shell_checked(device, f"rm -f {CROWN}/policy-update.lock")
        checkpoint()
        shell_checked(device, f"am start -n {ACTIVITY}", "adb_launch_failed")
