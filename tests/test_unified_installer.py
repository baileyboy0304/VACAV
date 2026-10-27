"""Same artifact, distinct runtime selection; no automatic data-loss migration."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import ModuleType
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[1]

class UnifiedInstaller(unittest.TestCase):
    def setUp(self):
        package = ModuleType("unified_test")
        package.__path__ = [str(ROOT / "custom_components/vaca_vision")]
        self.modules = patch.dict(sys.modules, {"unified_test": package})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        import importlib
        self.installer = importlib.import_module("unified_test.installer")

    def test_shipped_payload_is_one_apk_for_both_modes(self):
        packages = ROOT / "custom_components/vaca_vision/packages"
        manifest = json.loads((packages / "manifest.json").read_text())
        self.assertEqual(set(manifest), {"unified"})
        self.assertEqual(set(manifest["unified"]["modes"]), {"standard", "overlay"})
        self.assertEqual(sorted(p.name for p in packages.glob("*.apk")), ["vacav.apk"])
        self.assertEqual(self.installer.verify_packages("overlay", packages),
                         self.installer.verify_packages("standard", packages))

    def test_every_mode_verifies_the_same_apk_and_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / "vacav.apk"
            apk.write_bytes(b"unified test artifact")
            (root / "manifest.json").write_text(json.dumps({"unified": {
                "file": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()
            }}))
            for kind in ("standard", "overlay", "dot", "es8"):
                self.assertEqual([p for p, _ in self.installer.verify_packages(kind, root)], [apk])
            apk.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "apk_invalid"):
                self.installer.verify_packages("overlay", root)

    def test_fire_tv_dot_selects_overlay_and_es8_selects_standard(self):
        m = self.installer
        for profile, kind, mode, model in (("fire_tv", "dot", "overlay", "AFTKA"),
                                          ("es8", "standard", "standard", "AEOCW")):
            target = m.Target("Amazon", model, 25, ("arm64-v8a",), "serial", profile, True, False)
            device = MagicMock()
            def shell(command, **kwargs):
                if command.startswith("appops get"):
                    return command.rsplit(" ", 1)[-1] + ": allow"
                return "Success\nVISION_EXIT_0\n"
            device.shell.side_effect = shell
            connection = MagicMock()
            connection.__enter__.return_value = device
            with patch.object(m, "verify_packages", return_value=[(Path("vacav.apk"), {})]), \
                 patch.object(m, "connection", return_value=connection), \
                 patch.object(m, "inspect", return_value=target):
                m.install_target("192.0.2.1", 5555, "key", kind, target, threading.Event())
            commands = [call.args[0].split("; echo VISION_EXIT_$?")[0] for call in device.shell.call_args_list]
            self.assertIn(f"am start -n {m.ACTIVITY} --es vacav_mode {mode}", commands)
            self.assertEqual(len([c for c in commands if c.startswith("pm install -r ")]), 1)
            self.assertFalse(any("uninstall" in c for c in commands))

if __name__ == "__main__":
    unittest.main()
