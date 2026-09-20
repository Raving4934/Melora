#!/usr/bin/env python3
"""Unit tests for the Android release APK verifier without building an APK."""

from __future__ import annotations

import os
import stat
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release" / "verify-android-apk.sh"
CERTIFICATE = "AA:BB:CC:DD:EE:FF"
SECRET = "this-password-must-never-be-printed"


class AndroidApkVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.apk = self.root / "melora-android-v0.1.0-arm64-v8a.apk"
        self.apk.write_bytes(b"synthetic apk fixture")
        self.keystore = self.root / "melora-release.jks"
        self.keystore.write_bytes(b"synthetic keystore fixture")
        self.write_tool(
            "keytool",
            f"""#!/usr/bin/env bash
printf '%s\\n' 'Alias name: melora-release' 'SHA256: {CERTIFICATE}'
""",
        )
        self.write_tool(
            "apksigner",
            f"""#!/usr/bin/env bash
printf '%s\\n' 'Verified using v2 scheme (APK Signature Scheme v2): true' 'Signer #1 certificate SHA-256 digest: {CERTIFICATE}'
""",
        )
        self.write_tool(
            "aapt2",
            """#!/usr/bin/env bash
cat <<'BADGING'
package: name='com.leyu.melora' versionCode='6' versionName='0.1.0'
native-code: 'arm64-v8a'
application-label:'Melora'
BADGING
""",
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_tool(self, name: str, content: str) -> None:
        path = self.bin_dir / name
        path.write_text(content, encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def run_verifier(self) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["PATH"] = f"{self.bin_dir}:{env['PATH']}"
        env.update(
            {
                "KEYTOOL": str(self.bin_dir / "keytool"),
                "APKSIGNER": str(self.bin_dir / "apksigner"),
                "AAPT2": str(self.bin_dir / "aapt2"),
                "MELORA_KEYSTORE_PATH": str(self.keystore),
                "MELORA_KEYSTORE_PASSWORD": SECRET,
                "MELORA_KEY_ALIAS": "melora-release",
                "MELORA_EXPECTED_VERSION": "0.1.0",
                "MELORA_EXPECTED_VERSION_CODE": "6",
            }
        )
        return subprocess.run(
            [str(SCRIPT), str(self.apk)],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )

    @unittest.skipUnless(
        (ROOT / "android/app/src/main/AndroidManifest.xml").is_file(),
        "Android tree is not integrated into the public repository yet",
    )
    def test_lint_exceptions_are_scoped_to_documented_distribution_contract(self):
        root = Path(__file__).resolve().parents[2]
        app = root / "android/app"
        android = "{http://schemas.android.com/apk/res/android}"
        tools = "{http://schemas.android.com/tools}ignore"
        manifest = ET.parse(app / "src/main/AndroidManifest.xml").getroot()
        exceptions = {(node.tag, node.get(android + "name"), node.get(tools))
                      for node in manifest.iter() if node.get(tools)}
        self.assertEqual(exceptions, {
            ("uses-permission", "android.permission.MANAGE_EXTERNAL_STORAGE", "ScopedStorage"),
            ("service", ".playback.PlaybackService", "ExportedService"),
        })
        network = ET.parse(app / "src/main/res/xml/network_security_config.xml").getroot()
        self.assertEqual([(node.tag, node.get(tools)) for node in network.iter() if node.get(tools)],
                         [("base-config", "InsecureBaseConfiguration")])
        self.assertEqual([node.get("src") for node in network.findall(".//certificates")], ["system"])
        lint = ET.parse(app / "lint.xml").getroot()
        self.assertEqual([(issue.get("id"), [n.get("path") for n in issue.findall("ignore")])
                          for issue in lint.findall("issue")], [("ChromeOsAbiSupport", ["build.gradle.kts"])])

    @unittest.skipUnless(
        (ROOT / "android/app/src/main/AndroidManifest.xml").is_file(),
        "Android tree is not integrated into the public repository yet",
    )
    def test_android_backup_and_adaptive_icon_match_the_declared_policy(self):
        app = Path(__file__).resolve().parents[2] / "android/app"
        android = "{http://schemas.android.com/apk/res/android}"
        manifest = ET.parse(app / "src/main/AndroidManifest.xml").getroot()
        application = manifest.find("application")
        self.assertEqual(application.get(android + "allowBackup"), "false")
        self.assertEqual(application.get(android + "fullBackupContent"), "false")
        self.assertEqual(application.get(android + "dataExtractionRules"), "@xml/data_extraction_rules")
        rules = ET.parse(app / "src/main/res/xml/data_extraction_rules.xml").getroot()
        domains = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file",
                   "device_database", "device_sharedpref"}
        for mode in ("cloud-backup", "device-transfer"):
            self.assertEqual({(n.get("domain"), n.get("path")) for n in rules.find(mode)},
                             {(domain, ".") for domain in domains})
        icon = ET.parse(app / "src/main/res/mipmap-anydpi/ic_launcher.xml").getroot()
        self.assertEqual(icon.find("monochrome").get(android + "drawable"), "@drawable/ic_melora_wave")
        self.assertFalse((app / "src/main/res/mipmap-anydpi-v26").exists())

    @unittest.skipUnless(
        (ROOT / "android/app/build.gradle.kts").is_file(),
        "Android tree is not integrated into the public repository yet",
    )
    def test_gradle_release_never_falls_back_to_debug_signing(self) -> None:
        build = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('signingConfigs.getByName("debug")', build)
        self.assertIn('signingConfig = signingConfigs.findByName("release")', build)

    def test_accepts_signed_release_fixture(self) -> None:
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("versionName=0.1.0", result.stdout)
        self.assertIn("abi=arm64-v8a", result.stdout)
        self.assertNotIn(SECRET, result.stdout + result.stderr)

    def test_rejects_certificate_mismatch(self) -> None:
        self.write_tool(
            "apksigner",
            """#!/usr/bin/env bash
printf '%s\\n' 'Signer #1 certificate SHA-256 digest: 00:11:22:33:44:55'
""",
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match", result.stderr)
        self.assertNotIn(SECRET, result.stdout + result.stderr)

    def test_rejects_debuggable_fixture(self) -> None:
        self.write_tool(
            "aapt2",
            """#!/usr/bin/env bash
cat <<'BADGING'
package: name='com.leyu.melora' versionCode='6' versionName='0.1.0'
native-code: 'arm64-v8a'
application-debuggable
BADGING
""",
        )
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("debuggable", result.stderr)

    def test_rejects_test_only_manifest(self) -> None:
        self.write_tool("aapt2", """#!/usr/bin/env bash
if [[ "$2" == xmltree ]]; then
  echo 'A: android:testOnly(0x01010272)=(type 0x12)0xffffffff'
else
  echo "package: name='com.leyu.melora' versionCode='6' versionName='0.1.0'"
fi
""")
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("testOnly", result.stderr)

    def test_rejects_renamed_debug_certificate(self) -> None:
        self.write_tool("keytool", f"""#!/usr/bin/env bash
printf '%s\n' 'Owner: CN=Android Debug, O=Android, C=US' 'SHA256: {CERTIFICATE}'
""")
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("debug certificate", result.stderr)

    def test_rejects_old_version_code_under_the_same_0_1_0_tag(self) -> None:
        tool = self.bin_dir / "aapt2"
        tool.write_text(tool.read_text().replace("versionCode='6'", "versionCode='3'"))
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("versionCode is '3', expected '6'", result.stderr)

    def test_rejects_asset_filename_without_correct_abi_or_version(self) -> None:
        for name in ("melora-android-v0.1.0.apk", "melora-android-v0.1.0-x86_64.apk",
                     "melora-android-v0.2.0-arm64-v8a.apk"):
            self.apk = self.root / name
            self.apk.write_bytes(b"synthetic apk fixture")
            with self.subTest(name=name):
                result = self.run_verifier()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("filename must name", result.stderr)

    def test_rejects_architecture_mismatch(self) -> None:
        for abis in ["'armeabi-v7a'", "'arm64-v8a' 'armeabi-v7a'", ""]:
            with self.subTest(abis=abis):
                self.write_tool(
                    "aapt2",
                    "#!/usr/bin/env bash\n"
                    "cat <<'EOF'\n"
                    "package: name='com.leyu.melora' versionCode='6' versionName='0.1.0'\n"
                    f"native-code: {abis}\nEOF\n",
                )
                result = self.run_verifier()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("must be arm64-v8a only", result.stderr)

    def test_rejects_debug_alias(self) -> None:
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        env = os.environ.copy()
        env["PATH"] = f"{self.bin_dir}:{env['PATH']}"
        env.update(
            {
                "KEYTOOL": str(self.bin_dir / "keytool"),
                "APKSIGNER": str(self.bin_dir / "apksigner"),
                "AAPT2": str(self.bin_dir / "aapt2"),
                "MELORA_KEYSTORE_PATH": str(self.keystore),
                "MELORA_KEYSTORE_PASSWORD": SECRET,
                "MELORA_KEY_ALIAS": "androiddebugkey",
                "MELORA_EXPECTED_VERSION": "0.1.0",
                "MELORA_EXPECTED_VERSION_CODE": "6",
            }
        )
        result = subprocess.run(
            [str(SCRIPT), str(self.apk)],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("debug keystore alias", result.stderr)
        self.assertNotIn(SECRET, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
