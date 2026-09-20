#!/usr/bin/env python3
"""Dependency-free contract tests for the checked-in release workflows."""

from __future__ import annotations

import ast
from fnmatch import fnmatchcase
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github/workflows"
RELEASE_STEPS = (
    "校验公开全源码 baseline",
    "构建 R8 Release APK",
    "验证签名、包名、版本与非 Debug 属性",
    "创建、校验并发布同仓 Android Release",
)


def step(workflow: str, name: str) -> str:
    marker = f"      - name: {name}\n"
    return workflow.split(marker, 1)[1].split("\n      - ", 1)[0]


def tag_patterns(workflow: str) -> list[str]:
    match = re.search(r"^    tags:\n((?:      - .*\n)+)", workflow, re.MULTILINE)
    return re.findall(r"- '([^']+)'", match.group(1)) if match else []


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.android = (WORKFLOWS / "android-release.yml").read_text()
        self.android_ci = (WORKFLOWS / "android-ci.yml").read_text()
        self.web = (WORKFLOWS / "release.yml").read_text()

    def test_baseline_tags_trigger_only_their_own_channel(self) -> None:
        for tag, android_expected, web_expected in (
            ("android-v0.1.0", True, False), ("v0.1.0", False, True), ("unrelated", False, False),
        ):
            with self.subTest(tag=tag):
                self.assertEqual(any(fnmatchcase(tag, rule) for rule in tag_patterns(self.android)), android_expected)
                self.assertEqual(any(fnmatchcase(tag, rule) for rule in tag_patterns(self.web)), web_expected)
        self.assertIn("  workflow_dispatch:\n", self.android)
        self.assertIn("ref: ${{ inputs.tag || github.ref }}", self.android)
        self.assertIn("android-v0.1.0", self.android)

    def test_android_release_binds_the_build_checkout_to_the_same_repository(self) -> None:
        publish = step(self.android, RELEASE_STEPS[-1])
        self.assertIn('GITHUB_TOKEN: ${{ github.token }}', publish)
        self.assertIn('--repository "$GITHUB_REPOSITORY"', publish)
        self.assertIn('--expected-commit "$(git rev-parse HEAD)"', publish)
        self.assertIn('    permissions:\n      contents: write', self.android)
        self.assertEqual(self.android.count('scripts/release/publish_android_release.py'), 1)
        for legacy in ("MELORA_DISTRIBUTION_TOKEN", "Melora-projects", "--require-private-repository",
                       "--mapping", "--signing-summary", "--ensure-public-tag"):
            self.assertNotIn(legacy, self.android)
        self.assertEqual(set(re.findall(r"secrets\.([A-Z0-9_]+)", self.android)), {
            "MELORA_KEYSTORE_BASE64", "MELORA_KEYSTORE_PASSWORD", "MELORA_KEY_ALIAS", "MELORA_KEY_PASSWORD",
        })

    def test_full_source_signed_r8_evidence_is_in_order_and_not_skippable(self) -> None:
        positions = [self.android.index(f"      - name: {name}\n") for name in RELEASE_STEPS]
        self.assertEqual(positions, sorted(positions))
        for name in RELEASE_STEPS:
            body = step(self.android, name)
            self.assertNotIn("continue-on-error:", body)
            self.assertNotIn("\n        if:", body)
        baseline = step(self.android, RELEASE_STEPS[0])
        for source in ("android/app/build.gradle.kts", "apps/server/go.mod", "apps/web/package.json"):
            self.assertIn(f"test -s {source}", baseline)
        self.assertIn("scripts/release/verify_public_repository.py", baseline)
        quality = step(self.android, "执行 Android 单元测试与 Release lint")
        self.assertIn("./gradlew --no-daemon --build-cache --max-workers=1", quality)
        self.assertIn(":app:testDebugUnitTest", quality)
        self.assertIn(":app:lintRelease", quality)
        self.assertNotIn(":app:testDebugUnitTest :app:lintRelease", quality)
        r8 = step(self.android, RELEASE_STEPS[1])
        self.assertIn(":app:assembleRelease", r8)
        self.assertIn("--build-cache --max-workers=2", r8)
        self.assertIn('-Dorg.gradle.jvmargs="$CI_GRADLE_JVM_ARGS"', r8)
        self.assertIn("test -s app/build/outputs/mapping/release/mapping.txt", r8)
        verify = step(self.android, RELEASE_STEPS[2])
        self.assertIn("MELORA_EXPECTED_VERSION_CODE: ${{ steps.version.outputs.version_code }}", verify)
        self.assertIn("scripts/release/verify-android-apk.sh", verify)

    def test_android_jobs_use_bounded_cached_gradle_on_pinned_runner(self) -> None:
        for name, workflow in (("release", self.android), ("ci", self.android_ci)):
            with self.subTest(workflow=name):
                self.assertIn("runs-on: ubuntu-24.04", workflow)
                self.assertIn("cache: npm", workflow)
                self.assertIn("cache: gradle", workflow)
                self.assertIn("cache-dependency-path:", workflow)
                self.assertIn("CI_GRADLE_JVM_ARGS: -Xmx4G", workflow)
                self.assertIn("--build-cache", workflow)
                self.assertIn("cmake;3.22.1", workflow)

    def test_public_asset_naming_and_diagnostic_mapping_are_separate(self) -> None:
        assets = step(self.android, "准备公库 Release 资产")
        self.assertIn('apk_name="melora-android-v${RELEASE_VERSION}-arm64-v8a.apk"', assets)
        self.assertIn('checksum_name="${apk_name}.sha256"', assets)
        self.assertNotIn("mapping.txt", assets)
        diagnostic = step(self.android, "上传短期 Android 构建诊断资产")
        self.assertIn("android/app/build/outputs/mapping/release/mapping.txt", diagnostic)
        self.assertIn("retention-days: 3", diagnostic)
        self.assertNotIn("mapping.txt", step(self.android, RELEASE_STEPS[-1]))

    def test_single_public_repository_has_no_legacy_storage_janitor(self) -> None:
        legacy_paths = (
            WORKFLOWS / "android-storage-cleanup.yml",
            ROOT / "scripts/release/cleanup_github_storage.py",
            ROOT / "scripts/release/test_cleanup_github_storage.py",
        )
        for path in legacy_paths:
            with self.subTest(path=path.name):
                self.assertFalse(path.exists())
        for workflow in (self.android, self.android_ci):
            self.assertNotIn("android-storage-cleanup", workflow)
            self.assertNotIn("cleanup_github_storage", workflow)

    def test_guard_contracts_are_wired_into_ci_and_release(self) -> None:
        for filename in ("android-ci.yml", "android-release.yml", "ci.yml", "release.yml"):
            with self.subTest(workflow=filename):
                self.assertIn("scripts/release/test_release_workflows.py", (WORKFLOWS / filename).read_text())

    def test_deleting_historical_tags_never_starts_a_new_release(self) -> None:
        for name in ("android-release.yml", "release.yml"):
            self.assertIn("if: github.event.deleted != true", (WORKFLOWS / name).read_text())

    def test_python_helpers_parse_without_import_side_effects(self) -> None:
        for path in (ROOT / "scripts/release").glob("*.py"):
            with self.subTest(path=path.name):
                ast.parse(path.read_text(), filename=str(path))


class DockerContextTests(unittest.TestCase):
    def test_android_and_private_source_are_not_sent_to_web_build_context(self):
        root = Path(__file__).resolve().parents[2]
        rules = set((root / '.dockerignore').read_text().splitlines())
        self.assertTrue({'android', '.superpowers', '.env', '.env.*', '**/*.jks',
                         '**/*.keystore', '**/*.p12', '**/*.apk', '**/*.aab'} <= rules)
        self.assertTrue(any(rule.endswith('.js') for rule in rules))


if __name__ == "__main__":
    unittest.main(verbosity=2)
