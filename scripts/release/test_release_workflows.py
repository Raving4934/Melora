#!/usr/bin/env python3
"""Dependency-free contract tests for the checked-in release workflows."""

from __future__ import annotations

import ast
from contextlib import redirect_stdout
import io
from itertools import product
import json
import os
from fnmatch import fnmatchcase
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from textwrap import dedent
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github/workflows"
RELEASE_STEPS = (
    "校验公开全源码 baseline",
    "构建 R8 Release APK",
    "验证签名、包名、版本与非 Debug 属性",
    "创建、校验并发布同仓 Android Release",
)


def job(workflow: str, name: str) -> str:
    jobs = workflow.split("\njobs:\n", 1)[1]
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [\w-]+:\n|\Z)", jobs,
    )
    if match is None:
        raise AssertionError(f"Workflow job not found: {name}")
    return match.group(1)


def step(workflow: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^      - name: {re.escape(name)}\n(.*?)(?=^      - |^  [\w-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError(f"Workflow step not found: {name}")
    return match.group(1)


def shell_script(step_body: str) -> str:
    marker = "        run: |\n"
    if marker not in step_body:
        raise AssertionError("Workflow step has no literal run script")
    return dedent(step_body.split(marker, 1)[1])


def tag_patterns(workflow: str) -> list[str]:
    match = re.search(r"(?m)^    tags:([^\n]*)(?:\n((?:      - .*\n)+))?", workflow)
    if match is None:
        return []
    return re.findall(r"['\"]([^'\"]+)['\"]", "\n".join(part or "" for part in match.groups()))


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.android = (WORKFLOWS / "android-ci.yml").read_text()
        self.web = (WORKFLOWS / "release.yml").read_text()

    def test_workflow_helpers_stop_at_the_next_step_and_job(self) -> None:
        verify = job(self.android, "verify")
        device = job(self.android, "device-regression")
        self.assertNotIn("device-regression:", verify)
        self.assertNotIn("release-build:", device)
        publish = step(self.android, RELEASE_STEPS[-1])
        self.assertIn("publish_android_release.py", publish)
        self.assertNotIn("android-ci-result:", publish)
        self.assertNotIn("needs.prepare.outputs.artifact-id", publish)

    def test_baseline_tags_trigger_only_their_own_channel(self) -> None:
        for tag, android_expected, web_expected in (
            ("android-v0.1.0", True, False), ("v0.1.0", False, True), ("unrelated", False, False),
        ):
            with self.subTest(tag=tag):
                self.assertEqual(any(fnmatchcase(tag, rule) for rule in tag_patterns(self.android)), android_expected)
                self.assertEqual(any(fnmatchcase(tag, rule) for rule in tag_patterns(self.web)), web_expected)
        self.assertIn("  workflow_dispatch:\n", self.android)
        dispatch = self.android.split("  workflow_dispatch:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("      tag:\n", dispatch)
        self.assertIn("        required: false\n", dispatch)
        self.assertIn("        default: ''\n", dispatch)
        self.assertIn("android-v*", self.android)

    def run_prepare(self, *, event: str, ref_type: str, ref_name: str, sha: str,
                    input_tag: str = "") -> tuple[subprocess.CompletedProcess[str], dict[str, str]]:
        script = shell_script(step(self.android, "解析检查与发布目标"))
        with tempfile.TemporaryDirectory(prefix="melora-prepare-") as directory:
            output = Path(directory) / "github-output"
            result = subprocess.run(
                ["bash", "-euc", script], capture_output=True, text=True,
                env={
                    **os.environ,
                    "EVENT_NAME": event,
                    "REF_TYPE": ref_type,
                    "REF_NAME": ref_name,
                    "INPUT_TAG": input_tag,
                    "GITHUB_SHA": sha,
                    "GITHUB_OUTPUT": str(output),
                },
            )
            values = {}
            if output.exists():
                values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        return result, values

    def test_prepare_shell_selects_ci_or_release_target_for_each_event(self) -> None:
        sha = "0123456789abcdef0123456789abcdef01234567"
        cases = (
            ("push", "branch", "feature/test", "", {"tag": "", "release": "false", "ref": sha}),
            ("pull_request", "", "42/merge", "", {"tag": "", "release": "false", "ref": sha}),
            ("workflow_dispatch", "branch", "main", "", {"tag": "", "release": "false", "ref": sha}),
            ("workflow_dispatch", "branch", "main", "android-v1.2.3",
             {"tag": "android-v1.2.3", "release": "true", "ref": "refs/tags/android-v1.2.3"}),
            ("push", "tag", "android-v1.2.3", "",
             {"tag": "android-v1.2.3", "release": "true", "ref": sha}),
        )
        for event, ref_type, ref_name, input_tag, expected in cases:
            with self.subTest(event=event, ref_name=ref_name, input_tag=input_tag):
                result, outputs = self.run_prepare(
                    event=event, ref_type=ref_type, ref_name=ref_name, sha=sha, input_tag=input_tag,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(outputs, expected)
        self.assertIn("if: needs.prepare.outputs.release == 'true'", job(self.android, "release-build"))
        for name in ("构建 Debug APK（仅 CI 验证，不用于发布）", "上传短期 Debug 诊断产物"):
            self.assertIn("if: needs.prepare.outputs.release != 'true'", step(self.android, name))

    def test_prepare_shell_rejects_invalid_and_injected_tags(self) -> None:
        for event, ref_type, ref_name, input_tag in (
            ("push", "tag", "v1.2.3", ""),
            ("workflow_dispatch", "branch", "main", "android-v1.2"),
            ("workflow_dispatch", "branch", "main", "../../android-v1.2.3"),
            ("workflow_dispatch", "branch", "main", "android-v1.2.3$(touch /tmp/melora-tag-injected)"),
            ("workflow_dispatch", "branch", "main", "android-v1.2.3\nrelease=false"),
        ):
            with self.subTest(event=event, ref_name=ref_name, input_tag=input_tag):
                result, _ = self.run_prepare(
                    event=event, ref_type=ref_type, ref_name=ref_name,
                    sha="0123456789abcdef0123456789abcdef01234567", input_tag=input_tag,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Expected an existing android-v<semver> tag", result.stdout + result.stderr)

    def test_tag_and_non_tag_runs_have_isolated_concurrency_groups(self) -> None:
        concurrency = self.android.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        self.assertIn(
            "group: android-${{ (github.event_name == 'push' && github.ref_type == 'tag' || "
            "github.event_name == 'workflow_dispatch' && inputs.tag != '') && "
            "format('release-{0}', inputs.tag || github.ref_name) || format('ci-{0}', github.ref) }}",
            concurrency,
        )
        self.assertIn(
            "cancel-in-progress: ${{ !(github.event_name == 'push' && github.ref_type == 'tag' || "
            "github.event_name == 'workflow_dispatch' && inputs.tag != '') }}",
            concurrency,
        )
        # A manual run with no selected tag must take ci-<ref>, not join/cancel release-<tag>.
        self.assertIn("inputs.tag != ''", concurrency)
        self.assertIn("format('release-{0}', inputs.tag || github.ref_name)", concurrency)
        self.assertIn("format('ci-{0}', github.ref)", concurrency)

    def test_all_downstream_checkouts_use_the_prepare_sha(self) -> None:
        prepare = job(self.android, "prepare")
        self.assertIn("ref: ${{ steps.target.outputs.ref }}", prepare)
        self.assertIn("sha: ${{ steps.commit.outputs.sha }}", prepare)
        self.assertIn('echo "sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"', prepare)
        for name in ("verify", "device-regression", "release-build", "release"):
            with self.subTest(job=name):
                body = job(self.android, name)
                self.assertEqual(body.count("uses: actions/checkout@"), 1)
                self.assertIn("ref: ${{ needs.prepare.outputs.sha }}", body)
                self.assertNotIn("ref: ${{ github.sha }}", body)

    def test_android_release_is_gated_and_uses_only_the_build_artifact(self) -> None:
        build = job(self.android, "release-build")
        release = job(self.android, "release")
        self.assertIn("    needs: [prepare, release-build, android-ci-result]\n", release)
        self.assertIn("needs.prepare.outputs.release == 'true'", release)
        self.assertIn("needs.android-ci-result.result == 'success'", release)
        self.assertIn("needs.release-build.result == 'success'", release)
        self.assertIn("artifact-id: ${{ steps.upload.outputs.artifact-id }}", build)
        self.assertIn("artifact-ids: ${{ needs.release-build.outputs.artifact-id }}", release)
        self.assertIn("actions/download-artifact@v4.3.0", release)
        self.assertNotIn("actions/download-artifact", build)

        publisher = step(self.android, RELEASE_STEPS[-1])
        self.assertIn('GITHUB_TOKEN: ${{ github.token }}', publisher)
        self.assertIn('--repository "$GITHUB_REPOSITORY"', publisher)
        self.assertIn('--expected-commit "$(git rev-parse HEAD)"', publisher)
        self.assertEqual(self.android.count('scripts/release/publish_android_release.py'), 1)
        for legacy in ("MELORA_DISTRIBUTION_TOKEN", "Melora-projects", "--require-private-repository",
                       "--mapping", "--signing-summary", "--ensure-public-tag"):
            self.assertNotIn(legacy, self.android)

        jobs = ("prepare", "verify", "device-regression", "release-build", "android-ci-result", "release")
        self.assertEqual(
            set(re.findall(r"(?m)^  ([\w-]+):$", self.android.split("\njobs:\n", 1)[1])),
            set(jobs),
        )
        writable = [name for name in jobs if "    permissions:\n" in job(self.android, name)
                    and "      contents: write\n" in job(self.android, name)]
        self.assertEqual(writable, ["release"])
        self.assertIn("  contents: read\n", self.android.split("\npermissions:\n", 1)[1].split("\nconcurrency:", 1)[0])

        expected_secrets = {
            "MELORA_KEYSTORE_BASE64", "MELORA_KEYSTORE_PASSWORD", "MELORA_KEY_ALIAS", "MELORA_KEY_PASSWORD",
        }
        self.assertEqual(set(re.findall(r"secrets\.([A-Z0-9_]+)", self.android)), expected_secrets)
        for name in jobs:
            if name != "release-build":
                self.assertIsNone(re.search(r"secrets\.(?:" + "|".join(expected_secrets) + r")", job(self.android, name)))
        self.assertNotRegex(release, r"MELORA_(?:KEYSTORE|KEY_ALIAS|KEY_PASSWORD)")

    def test_release_build_preserves_validation_and_does_not_repeat_ci_tests(self) -> None:
        verify = job(self.android, "verify")
        build = job(self.android, "release-build")
        quality = step(self.android, "执行 Android 单元测试与 lint")
        self.assertIn(":app:testDebugUnitTest", quality)
        self.assertIn(":app:lintDebug", quality)
        self.assertEqual(verify.count(":app:testDebugUnitTest"), 1)
        self.assertNotIn(":app:testDebugUnitTest", build)
        self.assertIn(":app:lintRelease", build)

        version = step(self.android, "校验 Android 标签格式")
        self.assertIn(r"^android-v([0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?)$", version)
        self.assertIn("version_code", version)
        self.assertIn("^[1-9][0-9]*$", version)
        self.assertIn("scripts/release/verify-android-apk.sh", step(self.android, RELEASE_STEPS[2]))

    def run_release_version_check(
        self, tag: str, version_code: str = "42",
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        script = shell_script(step(self.android, "校验 Android 标签格式"))
        with tempfile.TemporaryDirectory(prefix="melora-version-") as directory:
            root = Path(directory)
            (root / "android/app").mkdir(parents=True)
            (root / "android/app/build.gradle.kts").write_text(f"versionCode = {version_code}\n")
            output = root / "github-output"
            result = subprocess.run(
                ["bash", "-euc", script], cwd=root, capture_output=True, text=True,
                env={**os.environ, "RELEASE_TAG": tag, "GITHUB_OUTPUT": str(output)},
            )
            return result, output.read_text() if output.exists() else ""

    def test_release_version_shell_requires_valid_tag_and_unique_positive_version_code(self) -> None:
        valid, outputs = self.run_release_version_check("android-v1.2.3-rc.1")
        self.assertEqual(valid.returncode, 0, valid.stdout + valid.stderr)
        self.assertEqual(outputs, "version=1.2.3-rc.1\nversion_code=42\n")
        for tag in ("v1.2.3", "android-v1.2", "android-v1.2.3/extra", "android-v1.2.3;false"):
            with self.subTest(tag=tag):
                result, _ = self.run_release_version_check(tag)
                self.assertNotEqual(result.returncode, 0)
        for version_code in ("0", "missing"):
            with self.subTest(version_code=version_code):
                result, _ = self.run_release_version_check("android-v1.2.3", version_code)
                self.assertNotEqual(result.returncode, 0)

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
        r8 = step(self.android, RELEASE_STEPS[1])
        self.assertIn(":app:assembleRelease", r8)
        self.assertIn("--build-cache --max-workers=2", r8)
        self.assertIn('-Dorg.gradle.jvmargs="$CI_GRADLE_JVM_ARGS"', r8)
        self.assertIn("test -s app/build/outputs/mapping/release/mapping.txt", r8)
        verify_apk = step(self.android, RELEASE_STEPS[2])
        self.assertIn("MELORA_EXPECTED_VERSION_CODE: ${{ steps.version.outputs.version_code }}", verify_apk)
        self.assertIn("scripts/release/verify-android-apk.sh", verify_apk)

    def test_android_jobs_use_bounded_cached_gradle_on_pinned_runner(self) -> None:
        for name in ("verify", "device-regression", "release-build"):
            with self.subTest(job=name):
                body = job(self.android, name)
                self.assertIn("runs-on: ubuntu-24.04", body)
                self.assertIn("cache: gradle", body)
                self.assertIn("cache-dependency-path:", body)
                self.assertIn("CI_GRADLE_JVM_ARGS: -Xmx4G", body)
                self.assertIn("--build-cache", body)
                self.assertIn("cmake;3.22.1", body)
        self.assertIn("cache: npm", job(self.android, "verify"))

    def test_public_asset_naming_and_diagnostic_mapping_are_separate(self) -> None:
        assets = step(self.android, "准备公库 Release 资产")
        self.assertIn('apk_name="melora-android-v${RELEASE_VERSION}-arm64-v8a.apk"', assets)
        self.assertIn('checksum_name="${apk_name}.sha256"', assets)
        self.assertNotIn("mapping.txt", assets)

        published = step(self.android, "上传已验证的发布资产")
        self.assertIn("id: upload", published)
        self.assertIn("android-release-assets-${{ github.run_id }}-${{ github.run_attempt }}", published)
        self.assertIn("${{ steps.artifacts.outputs.dir }}/", published)
        self.assertIn("retention-days: 30", published)
        self.assertNotIn("mapping.txt", published)
        self.assertEqual(len(re.findall(r"(?m)^\s+retention-days: 30$", self.android)), 1)

        diagnostic = step(self.android, "上传短期 Android 构建诊断资产")
        self.assertIn("android/app/build/outputs/mapping/release/mapping.txt", diagnostic)
        self.assertIn("retention-days: 3", diagnostic)
        self.assertIn("github.run_id }}-${{ github.run_attempt", diagnostic)
        for name in ("上传短期 Debug 诊断产物", "保留失败的单元测试与 Debug lint 诊断",
                     "保留失败的 Release lint 诊断", "上传设备回归诊断"):
            with self.subTest(diagnostic=name):
                self.assertIn("retention-days: 3", step(self.android, name))
        self.assertNotIn("mapping.txt", step(self.android, RELEASE_STEPS[-1]))

    def test_verify_and_release_build_failure_diagnostics_are_separate_and_retry_safe(self) -> None:
        verify = step(self.android, "保留失败的单元测试与 Debug lint 诊断")
        self.assertIn("if: failure()", verify)
        self.assertIn("actions/upload-artifact@v4.6.2", verify)
        self.assertIn("android-verify-failure-${{ github.run_id }}-${{ github.run_attempt }}", verify)
        for path in (
            "android/app/build/test-results/testDebugUnitTest/",
            "android/app/build/reports/tests/testDebugUnitTest/",
            "android/app/build/reports/lint-results-debug.*",
        ):
            self.assertIn(path, verify)
        self.assertIn("retention-days: 3", verify)

        release_lint = step(self.android, "保留失败的 Release lint 诊断")
        self.assertIn("if: failure()", release_lint)
        self.assertIn("actions/upload-artifact@v4.6.2", release_lint)
        self.assertIn("android-release-lint-failure-${{ github.run_id }}-${{ github.run_attempt }}", release_lint)
        self.assertIn("android/app/build/reports/lint-results-release.*", release_lint)
        self.assertNotIn("testDebugUnitTest", release_lint)
        self.assertNotIn("lint-results-debug", release_lint)
        self.assertIn("retention-days: 3", release_lint)

    def test_single_public_repository_has_no_legacy_storage_janitor(self) -> None:
        legacy_paths = (
            WORKFLOWS / "android-storage-cleanup.yml",
            ROOT / "scripts/release/cleanup_github_storage.py",
            ROOT / "scripts/release/test_cleanup_github_storage.py",
        )
        for path in legacy_paths:
            with self.subTest(path=path.name):
                self.assertFalse(path.exists())
        self.assertNotIn("android-storage-cleanup", self.android)
        self.assertNotIn("cleanup_github_storage", self.android)

    def test_guard_contracts_are_wired_into_each_active_ci_and_release_workflow(self) -> None:
        for filename in ("android-ci.yml", "ci.yml", "release.yml"):
            with self.subTest(workflow=filename):
                self.assertIn("scripts/release/test_release_workflows.py", (WORKFLOWS / filename).read_text())

    def test_general_ci_runs_only_for_web_server_and_shared_build_inputs(self) -> None:
        workflow = (WORKFLOWS / "ci.yml").read_text()
        triggers = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        event_paths = []
        for event in ("push", "pull_request"):
            block = re.search(rf"^  {event}:\n((?:    .*\n)+)", triggers, re.MULTILINE)
            self.assertIsNotNone(block)
            self.assertIn("    paths:\n", block.group(1))
            self.assertNotIn("    paths-ignore:\n", block.group(1))
            paths = re.findall(r"^      - '([^']+)'$", block.group(1), re.MULTILINE)
            self.assertTrue(paths)
            event_paths.append(paths)
            for changed, expected in (
                (["CHANGELOG.md"], False),
                (["README.md", "README.zh-CN.md", "docs/guide.md"], False),
                (["assets/screenshots/preview-player.jpg"], False),
                (["android/app/src/main/AndroidManifest.xml"], False),
                (["android/tools/sdk/package-lock.json"], False),
                ([".github/workflows/android-ci.yml"], False),
                (["CHANGELOG.md", "android/app/build.gradle.kts"], False),
                (["android/app/build.gradle.kts", "apps/web/src/App.tsx"], True),
                (["android/app/build.gradle.kts", "apps/server/go.mod"], True),
                (["CHANGELOG.md", "scripts/release/test_release_workflows.py"], True),
                (["apps/web/tests/player.spec.ts"], True),
                (["apps/server/go.sum"], True),
                (["packaging/fpk/manifest.in"], True),
                (["packaging/tools/stage.py"], True),
                (["deploy/nginx.conf"], True),
                (["deploy/.env.example"], True),
                (["scripts/build-server.sh"], True),
                (["scripts/compress-web.mjs"], True),
                (["scripts/e2e-server.mjs"], True),
                (["scripts/release/verify_public_repository.py"], True),
                (["package.json"], True),
                (["package-lock.json"], True),
                (["Dockerfile"], True),
                ([".dockerignore"], True),
                (["docker-compose.yml"], True),
                ([".env.example"], True),
                ([".gitignore"], True),
                ([".prettierrc.json"], True),
                ([".prettierignore"], True),
                (["LICENSE"], True),
                (["THIRD_PARTY_NOTICES.md"], True),
                ([".github/workflows/ci.yml"], True),
                ([".github/workflows/release.yml"], True),
            ):
                with self.subTest(event=event, changed=changed):
                    self.assertEqual(any(fnmatchcase(path, rule) for path in changed for rule in paths), expected)
        self.assertEqual(event_paths[0], event_paths[1], "push/PR must share the same scope")

    def test_deleted_android_workflow_has_no_remaining_entry_point(self) -> None:
        self.assertFalse((WORKFLOWS / "android-release.yml").exists())
        active_workflows = "\n".join(path.read_text() for path in WORKFLOWS.glob("*.yml"))
        self.assertNotIn(".github/workflows/android-release.yml", active_workflows)
        self.assertNotIn("android-release.yml", active_workflows)

    def test_deleting_historical_tags_never_starts_a_new_release(self) -> None:
        self.assertIn("if: github.event.deleted != true", job(self.android, "prepare"))
        self.assertIn("if: github.event.deleted != true", job(self.web, "verify"))

    def test_python_helpers_parse_without_import_side_effects(self) -> None:
        for path in (ROOT / "scripts/release").glob("*.py"):
            with self.subTest(path=path.name):
                ast.parse(path.read_text(), filename=str(path))

    def run_unit_test_summary(self, reports: dict[str, str]) -> subprocess.CompletedProcess[str]:
        body = step(self.android, "汇总 Android 单元测试结果")
        script = shell_script(body)
        with tempfile.TemporaryDirectory(prefix="melora-unit-results-") as directory:
            result_dir = Path(directory) / "android/app/build/test-results/testDebugUnitTest"
            result_dir.mkdir(parents=True)
            for name, xml in reports.items():
                (result_dir / name).write_text(xml)
            return subprocess.run(
                ["bash", "-euc", script], cwd=directory, capture_output=True, text=True,
                env=os.environ.copy(),
            )

    def test_shared_verify_unit_test_summary_requires_real_nonzero_results(self) -> None:
        summary = step(self.android, "汇总 Android 单元测试结果")
        self.assertIn("        if: always()", summary)
        passed = self.run_unit_test_summary({
            "TEST-one.xml": '<testsuite tests="2" failures="0" errors="0" skipped="1"/>',
            "TEST-two.xml": '<testsuite tests="1" failures="0" errors="0" skipped="0"/>',
        })
        self.assertEqual(passed.returncode, 0, passed.stdout + passed.stderr)
        self.assertIn("suites=2", passed.stdout)
        for reports in (
            {},
            {"TEST-empty.xml": '<testsuite tests="0" failures="0" errors="0" skipped="0"/>'},
            {"TEST-failure.xml": '<testsuite tests="1" failures="1" errors="0" skipped="0"/>'},
            {"TEST-error.xml": '<testsuite tests="1" failures="0" errors="1" skipped="0"/>'},
            {"TEST-malformed.xml": "<testsuite"},
        ):
            with self.subTest(reports=reports):
                failed = self.run_unit_test_summary(reports)
                self.assertNotEqual(failed.returncode, 0)


class DeviceWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = (WORKFLOWS / "android-ci.yml").read_text()
        self.job = job(self.workflow, "device-regression")

    def test_api_matrix_requires_explicit_manual_opt_in(self) -> None:
        dispatch = self.workflow.split("  workflow_dispatch:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("      api_matrix:\n", dispatch)
        self.assertIn("        type: boolean\n", dispatch)
        self.assertIn("        default: false\n", dispatch)
        choices = re.search(
            r"fromJSON\(github.event_name == 'workflow_dispatch' && inputs.api_matrix && '([^']+)' \|\| '([^']+)'\)",
            self.job,
        )
        self.assertIsNotNone(choices)
        self.assertEqual(json.loads(choices.group(1)), [26, 35, 37])
        self.assertEqual(json.loads(choices.group(2)), [35])
        self.assertIn("        shard: [0, 1]\n", self.job)
        self.assertIn("      TEST_SHARD_INDEX: ${{ matrix.shard }}\n", self.job)
        self.assertIn("      TEST_SHARD_COUNT: 2\n", self.job)
        self.assertIn("    needs: prepare\n", self.job)
        self.assertIn("    timeout-minutes: 60\n", self.job)
        self.assertIn("      fail-fast: false\n", self.job)
        self.assertNotIn("continue-on-error:", self.job)

    def test_parallel_jobs_depend_only_on_prepare_and_feed_required_summary(self) -> None:
        jobs = ("verify", "device-regression", "release-build")
        self.assertEqual(
            set(re.findall(r"(?m)^  ([\w-]+):$", self.workflow.split("\njobs:\n", 1)[1])),
            {"prepare", "verify", "device-regression", "release-build", "android-ci-result", "release"},
        )
        for name in jobs:
            with self.subTest(job=name):
                body = job(self.workflow, name)
                self.assertEqual(re.findall(r"(?m)^    needs:.*$", body), ["    needs: prepare"])
        self.assertIn("    if: needs.prepare.outputs.release == 'true'\n", job(self.workflow, "release-build"))
        self.assertNotRegex(job(self.workflow, "verify"), r"(?m)^    if:")
        self.assertNotRegex(job(self.workflow, "device-regression"), r"(?m)^    if:")

        summary = job(self.workflow, "android-ci-result")
        self.assertIn("    needs: [prepare, verify, device-regression, release-build]\n", summary)
        self.assertIn("    if: ${{ always() && github.event.deleted != true }}\n", summary)
        self.assertIn("    timeout-minutes: 5\n", summary)
        for variable, need in (
            ("PREPARE_RESULT", "prepare"), ("VERIFY_RESULT", "verify"),
            ("DEVICE_RESULT", "device-regression"), ("BUILD_RESULT", "release-build"),
        ):
            self.assertIn(f"{variable}: ${{{{ needs.{need}.result }}}}", summary)
        self.assertNotIn("continue-on-error:", self.workflow)

    def test_summary_shell_rejects_every_unsuccessful_required_result(self) -> None:
        script = shell_script(step(self.workflow, "汇总 Android CI 结果"))
        statuses = ("success", "failure", "cancelled", "skipped", "")
        variables = ("PREPARE_RESULT", "VERIFY_RESULT", "DEVICE_RESULT", "BUILD_RESULT")
        for release, results in product(("false", "true", ""), product(statuses, repeat=4)):
            with self.subTest(release=release, results=results):
                expected_build = "success" if release == "true" else "skipped"
                expected_success = (release in ("true", "false") and
                                    results[:3] == ("success",) * 3 and results[3] == expected_build)
                result = subprocess.run(
                    ["bash", "-euc", script], capture_output=True, text=True,
                    env={**os.environ, **dict(zip(variables, results)), "RELEASE": release},
                )
                self.assertEqual(result.returncode == 0, expected_success, result.stdout + result.stderr)
                self.assertIn("prepare=", result.stdout)
                self.assertIn("release-build=", result.stdout)

    def test_device_paths_are_initialized_at_runtime_not_in_job_context(self) -> None:
        job_config = self.job.split("    steps:\n", 1)[0]
        self.assertNotIn("runner.", job_config)
        initialize = step(self.workflow, "初始化设备诊断目录")
        script = dedent(initialize.split("        run: |\n", 1)[1])
        with tempfile.TemporaryDirectory(prefix="melora workflow ") as directory:
            shard_paths = []
            for shard_index in (0, 1):
                env_file = Path(directory) / f"github-env-{shard_index}"
                subprocess.run(["bash", "-euc", script], check=True, env={
                    "RUNNER_TEMP": directory, "GITHUB_ENV": str(env_file),
                    "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", "API_LEVEL": "35",
                    "TEST_SHARD_INDEX": str(shard_index),
                })
                values = env_file.read_text().splitlines()
                self.assertEqual(values, [
                    f"ANDROID_AVD_HOME={directory}/melora-avd-123-2-35-{shard_index}",
                    f"DEVICE_LOG_DIR={directory}/melora-device-35-{shard_index}",
                ])
                shard_paths.append(tuple(values))
            self.assertEqual(len(set(shard_paths)), 2)
        self.assertLess(self.job.index("- name: 初始化设备诊断目录"), self.job.index("- uses:"))

    def test_native_avd_uses_official_images_and_fresh_writable_storage(self) -> None:
        prepare = step(self.workflow, "准备设备 SDK 与全新 AVD")
        self.assertIn("runs-on: ubuntu-24.04", self.job)
        self.assertIn("ANDROID_AVD_HOME=$RUNNER_TEMP/melora-avd-", self.job)
        self.assertIn("ANDROID_SERIAL: emulator-5554", self.job)
        self.assertIn('image="system-images;android-${image_api};google_apis;x86_64"', prepare)
        self.assertIn('if [[ "$API_LEVEL" == 37 ]]; then image_api=37.0; fi', prepare)
        self.assertIn("sdkmanager --list --channel=0", prepare)
        self.assertIn('python3 - "$DEVICE_LOG_DIR/sdk-packages.log" "$image"', prepare)
        self.assertIn('test ! -e "$ANDROID_AVD_HOME"', prepare)
        self.assertIn('mkdir "$ANDROID_AVD_HOME"', prepare)
        self.assertIn('avdmanager create avd --name melora-ci --package "$image"', prepare)
        self.assertIn('--path "$ANDROID_AVD_HOME/melora-ci.avd"', prepare)
        for unsafe in ("-read-only", "-initdata", " -data ", "userdata", "emulator-runner", "avd-cache"):
            self.assertNotIn(unsafe, self.job)
        self.assertEqual(set(re.findall(r"uses: ([^@\s]+)@", self.job)), {
            "actions/checkout", "actions/setup-java", "android-actions/setup-android", "actions/upload-artifact",
        })

    def run_sdk_package_preflight(
        self, listing: str, api: str = "35", list_exit: int = 0,
    ) -> subprocess.CompletedProcess:
        prepare = step(self.workflow, "准备设备 SDK 与全新 AVD")
        script = dedent(prepare.split("        run: |\n", 1)[1])
        # Execute the real preflight, replacing only the external SDK listing command.
        script = script.split("(set +o pipefail; yes | sdkmanager --licenses", 1)[0]
        with tempfile.TemporaryDirectory(prefix="melora sdk listing ") as directory:
            return subprocess.run(
                ["bash", "-euc", 'sdkmanager() { printf "%s\\n" "$SDK_PACKAGE_LIST"; return "$SDK_LIST_EXIT"; }\n' + script],
                env={**os.environ, "API_LEVEL": api, "DEVICE_LOG_DIR": directory,
                     "SDK_PACKAGE_LIST": listing, "SDK_LIST_EXIT": str(list_exit)}, capture_output=True, text=True, timeout=10,
            )

    def test_sdk_preflight_accepts_android_cli_slash_package_ids(self) -> None:
        result = self.run_sdk_package_preflight(
            "WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated.\n"
            "Available packages:\n"
            "  system-images/android-35/google_apis/x86_64  9.0.0  Google APIs Intel x86_64 Atom System Image\n"
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_sdk_preflight_accepts_both_formats_for_every_matrix_api(self) -> None:
        for api, image_api in (("26", "26"), ("35", "35"), ("37", "37.0")):
            for separator in (";", "/"):
                with self.subTest(api=api, separator=separator):
                    package = separator.join(("system-images", f"android-{image_api}", "google_apis", "x86_64"))
                    result = self.run_sdk_package_preflight(
                        f"Available packages:\n  {package} | 9 | Google APIs Intel x86_64 Atom System Image\n", api,
                    )
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn(package, result.stdout)

    def test_sdk_preflight_rejects_missing_and_inexact_packages(self) -> None:
        for listing in (
            "", "Available packages:\n",
            "system-images/android-35/google_apis/x86_64_extra 9 description\n",
            "system-images/android-35/google_apis/arm64-v8a 9 description\n",
            "system-images/android-35/google_apis_playstore/x86_64 9 description\n",
            "system-images/android-36/google_apis/x86_64 9 description\n",
            "other-package 9 description system-images;android-35;google_apis;x86_64\n",
        ):
            with self.subTest(listing=listing):
                result = self.run_sdk_package_preflight(listing)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Required Android SDK package not found", result.stderr)

    def test_sdk_preflight_preserves_sdkmanager_failure(self) -> None:
        result = self.run_sdk_package_preflight(
            "system-images/android-35/google_apis/x86_64 9 description\n", list_exit=7,
        )
        self.assertEqual(result.returncode, 7)
        self.assertNotIn("Required Android SDK package not found", result.stderr)

    def test_device_gate_is_ordered_bounded_and_has_no_test_selection(self) -> None:
        names = (
            "准备设备 SDK 与全新 AVD", "构建设备测试 APK", "启动并校验独立模拟器",
            "安装测试目标并预置 overlay 权限", "执行完整 Android 设备回归", "校验 Android 设备 JUnit 结果",
            "收集设备诊断并清理模拟器", "上传设备回归诊断",
        )
        positions = [self.job.index(f"      - name: {name}\n") for name in names]
        self.assertEqual(positions, sorted(positions))
        for name in names[:5]:
            self.assertNotRegex(step(self.workflow, name), r"(?m)^        if:")
        for name, task in (
            ("构建设备测试 APK", ":app:assembleDebugAndroidTest"),
            ("安装测试目标并预置 overlay 权限", ":app:installDebug"),
            ("执行完整 Android 设备回归", ":app:connectedDebugAndroidTest"),
        ):
            body = step(self.workflow, name)
            for required in (task, "-Pmelora.debugAbi=x86_64", "--max-workers=1", "--build-cache", "set -euo pipefail", "| tee"):
                self.assertIn(required, body)
            self.assertNotIn("|| true", body)
        install = step(self.workflow, "安装测试目标并预置 overlay 权限")
        overlay = "appops set --uid com.leyu.melora.debug SYSTEM_ALERT_WINDOW allow"
        self.assertLess(install.index(":app:installDebug"), install.index(overlay))
        self.assertIn("appops get com.leyu.melora.debug SYSTEM_ALERT_WINDOW", install)
        for selector in ("--tests", "am instrument", "pm grant"):
            self.assertNotIn(selector, self.job)
        self.assertEqual(
            set(re.findall(r"testInstrumentationRunnerArguments\.([A-Za-z]+)", self.job)),
            {"numShards", "shardIndex"},
        )
        self.assertEqual(self.job.count(":app:connectedDebugAndroidTest"), 1)
        connected = step(self.workflow, "执行完整 Android 设备回归")
        self.assertIn("rm -rf app/build/outputs/androidTest-results/connected", connected)
        self.assertIn("        timeout-minutes: 30", connected)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.numShards="$TEST_SHARD_COUNT"', connected)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.shardIndex="$TEST_SHARD_INDEX"', connected)

    def test_emulator_boot_is_bounded_and_checks_runtime_api_and_abi(self) -> None:
        boot = step(self.workflow, "启动并校验独立模拟器")
        self.assertIn('adb -s "$ANDROID_SERIAL" shell settings put secure show_ime_with_hard_keyboard 1', boot)
        for required in ("timeout 360 bash -c", "-port 5554", "-no-snapshot", "-gpu swangle", "-accel on",
                         "getprop sys.boot_completed", "getprop ro.build.version.sdk", '= "$API_LEVEL"',
                         "getprop ro.product.cpu.abi", "= x86_64", 'adb -s "$ANDROID_SERIAL"',
                         'settings put global "$scale" 1'):
            self.assertIn(required, boot)
        for disabled_animation in (
            "settings put global window_animation_scale 0",
            "settings put global transition_animation_scale 0",
            "settings put global animator_duration_scale 0",
        ):
            self.assertNotIn(disabled_animation, self.job)
        self.assertIn('echo $! > "$DEVICE_LOG_DIR/emulator.pid"', boot)

    def test_result_check_diagnostics_and_pid_cleanup_always_run(self) -> None:
        for name in ("校验 Android 设备 JUnit 结果", "收集设备诊断并清理模拟器", "上传设备回归诊断"):
            self.assertIn("        if: always()\n", step(self.workflow, name))
        result = step(self.workflow, "校验 Android 设备 JUnit 结果")
        self.assertIn("set -euo pipefail", result)
        self.assertIn('tee "$DEVICE_LOG_DIR/junit-summary.log"', result)
        cleanup = step(self.workflow, "收集设备诊断并清理模拟器")
        for required in ('timeout 10 adb -s "$ANDROID_SERIAL" emu kill', 'logcat -d -v threadtime',
                         'kill "$pid"', 'kill -KILL "$pid"', 'rm -rf "$ANDROID_AVD_HOME"'):
            self.assertIn(required, cleanup)
        upload = step(self.workflow, "上传设备回归诊断")
        artifact_name = re.search(r"(?m)^          name: (.+)$", upload)
        self.assertIsNotNone(artifact_name)
        template = artifact_name.group(1)
        self.assertIn("api${{ matrix.api }}", template)
        self.assertIn("-shard${{ matrix.shard }}", template)
        artifact_names = {
            template.replace("${{ matrix.api }}", "35").replace("${{ matrix.shard }}", str(shard))
            for shard in (0, 1)
        }
        self.assertEqual(len(artifact_names), 2)
        for required in ("${{ env.DEVICE_LOG_DIR }}/", "android/app/build/outputs/androidTest-results/connected/",
                         "android/app/build/reports/androidTests/connected/", "retention-days: 3",
                         "if-no-files-found: error", "api${{ matrix.api }}", "-shard${{ matrix.shard }}"):
            self.assertIn(required, upload)

    def run_result_checker(self, documents: list[str]) -> tuple[BaseException | None, str]:
        # 执行 workflow 内真正的门禁代码；仅用内存 XML，不建临时文件或启动设备。
        body = step(self.workflow, "校验 Android 设备 JUnit 结果")
        source = dedent(body.split("<<'PYTEST'", 1)[1].split("\n", 1)[1].split("\n          PYTEST", 1)[0])
        reports = {Path(f"TEST-{index}.xml"): xml for index, xml in enumerate(documents)}
        output = io.StringIO()
        failure = None
        with patch.object(Path, "rglob", return_value=list(reports)), \
             patch.object(ET, "parse", side_effect=lambda path: ET.ElementTree(ET.fromstring(reports[path]))), \
             redirect_stdout(output):
            try:
                exec(compile(source, "android-ci-device-results", "exec"), {})
            except (SystemExit, ET.ParseError) as error:
                failure = error
        return failure, output.getvalue()

    def test_result_gate_accepts_multiple_reports_and_expected_api_skips(self) -> None:
        failure, output = self.run_result_checker([
            '<testsuite tests="2"><testcase name="pass"/><testcase name="api29"><skipped/></testcase></testsuite>',
            '<testsuites><testsuite tests="1"><testcase name="alsoPass"/></testsuite></testsuites>',
        ])
        self.assertIsNone(failure)
        self.assertIn("reports=2, executed=2", output)
        self.assertIn("'tests': 3", output)
        self.assertIn("'skipped': 1", output)

    def test_result_gate_rejects_missing_empty_and_skipped_only_execution(self) -> None:
        for documents in ([], ['<testsuite tests="0"/>'], ['<testsuite tests="371"/>'],
                          ['<testsuite tests="1"><testcase name="skip"><skipped/></testcase></testsuite>']):
            with self.subTest(documents=documents):
                failure, output = self.run_result_checker(documents)
                self.assertIsInstance(failure, SystemExit)
                self.assertIn("executed=0", output)

    def test_result_gate_logs_method_failures_and_errors_even_with_zero_suite_counts(self) -> None:
        for tag in ("failure", "error"):
            with self.subTest(tag=tag):
                failure, output = self.run_result_checker([
                    f'<testsuite tests="1" failures="0" errors="0"><testcase classname="Fixture" name="broken">'
                    f'<{tag} message="bad result">stack detail</{tag}></testcase></testsuite>',
                ])
                self.assertIsInstance(failure, SystemExit)
                self.assertIn(f"{tag.upper()} Fixture#broken: bad result stack detail", output)

    def test_result_gate_rejects_suite_level_crashes_and_malformed_xml(self) -> None:
        for key in ("errors", "failures"):
            for document in (
                f'<testsuite tests="1" {key}="1"><testcase name="pass"/></testsuite>',
                f'<testsuites {key}="1"><testsuite tests="1"><testcase name="pass"/></testsuite></testsuites>',
            ):
                with self.subTest(document=document):
                    failure, output = self.run_result_checker([document])
                    self.assertIsInstance(failure, SystemExit)
                    self.assertIn("JUnit suite failure", output)
        failure, _ = self.run_result_checker(['<testsuite'])
        self.assertIsInstance(failure, ET.ParseError)


class DockerContextTests(unittest.TestCase):
    def test_android_and_private_source_are_not_sent_to_web_build_context(self):
        root = Path(__file__).resolve().parents[2]
        rules = set((root / '.dockerignore').read_text().splitlines())
        self.assertTrue({'android', '.superpowers', '.env', '.env.*', '**/*.jks',
                         '**/*.keystore', '**/*.p12', '**/*.apk', '**/*.aab'} <= rules)
        self.assertTrue(any(rule.endswith('.js') for rule in rules))


if __name__ == "__main__":
    unittest.main(verbosity=2)
