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
            self.assertEqual(notes, "- Android release note.\n")

    def export_body(self, body: str, section: str = "安卓客户端") -> tuple[subprocess.CompletedProcess, str | None]:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            changelog = root / "CHANGELOG.md"
            output = root / "notes.md"
            changelog.write_text(
                f"## [0.2.0] - 2026-10-16\n\n### {section}\n{body}\n"
                "### 提交追溯\n\n- [commit](https://example.com/commit)\n"
                "## [0.1.10] - 2026-10-11\n\n### 安卓客户端\n- Older note.\n",
                encoding="utf-8",
            )
            result = subprocess.run(
                [str(SCRIPT), "--version", "0.2.0", "--section", section,
                 "--changelog", str(changelog), "--output", str(output)],
                cwd=ROOT, text=True, capture_output=True, check=False,
            )
            return result, output.read_text(encoding="utf-8") if output.exists() else None

    def test_exports_plain_android_body_without_developer_metadata(self) -> None:
        body = "【新增】\n• 启动页恢复开关。\n\n【修复】\n• 改善后台播放。"
        result, notes = self.export_body("\n\n" + body + "\n\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(notes, body + "\n")

    def test_web_body_retains_its_markdown_and_only_loses_export_wrapper(self) -> None:
        body = "- **Web update**\n\n[Download](https://example.com/download)"
        result, notes = self.export_body("\n" + body + "\n", section="Web / NAS、FPK 与 Docker")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(notes, body + "\n")

    def test_empty_body_fails_without_publishing_an_output(self) -> None:
        for body in ("", "\n\n", " \n\t\n"):
            with self.subTest(body=body):
                result, notes = self.export_body(body)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("is empty", result.stderr)
                self.assertIsNone(notes)

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
            notes = output.read_text(encoding="utf-8")
            self.assertTrue(notes.strip())
            self.assertNotIn("### 安卓客户端", notes)
            self.assertNotIn("Exported from", notes)
            self.assertNotIn("# Melora", notes)
            self.assertNotIn("提交追溯", notes)
            self.assertNotRegex(notes, r"(?m)^(#{1,6}\s|>)|https?://|`|versionCode")

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
