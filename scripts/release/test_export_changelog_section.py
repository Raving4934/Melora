#!/usr/bin/env python3
"""Tests for channel-specific CHANGELOG release-note export."""

from __future__ import annotations

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

### Web/NAS, FPK, and Docker

- Web release note.

### Native Android application

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
                    "Native Android application",
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
            self.assertIn("Native Android application", notes)
            self.assertIn("Android release note.", notes)
            self.assertNotIn("Web release note.", notes)
            self.assertNotIn("Older note.", notes)

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
                    "Web/NAS, FPK, and Docker",
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
