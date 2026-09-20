#!/usr/bin/env python3
"""Tests for channel-specific CHANGELOG release-note export."""

from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release" / "export-changelog-section.sh"


class ChangelogExporterTest(unittest.TestCase):
    def test_exports_only_the_requested_channel_section(self) -> None:
        changelog = """# Changelog

## [0.1.0] - 2026-09-14 (Draft)

### Web / NAS、FPK 与 Docker

- Web release note.

### 安卓客户端

- Android release note.

## [0.0.1] - 2026-09-12

### Older section

- Older note.
"""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            changelog_path = root / "CHANGELOG.md"
            output_path = root / "notes.md"
            changelog_path.write_text(changelog, encoding="utf-8")
            result = subprocess.run(
                [
                    str(SCRIPT),
                    "--version",
                    "0.1.0",
                    "--section",
                    "安卓客户端",
                    "--changelog",
                    str(changelog_path),
                    "--output",
                    str(output_path),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            notes = output_path.read_text(encoding="utf-8")
            self.assertIn("安卓客户端", notes)
            self.assertIn("Android release note.", notes)
            self.assertNotIn("Web release note.", notes)
            self.assertNotIn("Older note.", notes)

    def test_repository_changelog_exports_the_declared_android_version(self) -> None:
        if not (ROOT / "android" / "app" / "build.gradle.kts").is_file():
            self.skipTest("Android tree is not integrated into the public repository yet")
        build = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        version = re.search(r'versionName\s*=\s*"([^"]+)"', build).group(1)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "notes.md"
            result = subprocess.run(
                [str(SCRIPT), "--version", version, "--section", "安卓客户端",
                 "--output", str(output)], cwd=ROOT, text=True, capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("### 安卓客户端", output.read_text(encoding="utf-8"))

    def test_fails_for_missing_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            changelog_path = root / "CHANGELOG.md"
            output_path = root / "notes.md"
            changelog_path.write_text(
                "## [0.1.0] - 2026-09-14\n\n### Android\n\n- note\n",
                encoding="utf-8",
            )
            result = subprocess.run(
                [
                    str(SCRIPT),
                    "--version",
                    "0.1.0",
                    "--section",
                    "Web / NAS、FPK 与 Docker",
                    "--changelog",
                    str(changelog_path),
                    "--output",
                    str(output_path),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("was not found", result.stderr)
            self.assertFalse(output_path.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
