#!/usr/bin/env python3
"""Dependency-free contract tests for the checked-in release workflows."""

from __future__ import annotations

import ast
from contextlib import redirect_stdout
import io
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

    def test_general_ci_skips_only_android_specific_changes(self) -> None:
        workflow = (WORKFLOWS / "ci.yml").read_text()
        triggers = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        for event in ("push", "pull_request"):
            block = re.search(rf"^  {event}:\n((?:    .*\n)+)", triggers, re.MULTILINE)
            self.assertIsNotNone(block)
            self.assertIn("    paths-ignore:\n", block.group(1))
            self.assertNotIn("    paths:\n", block.group(1))
            ignored = re.findall(r"^      - '([^']+)'$", block.group(1), re.MULTILINE)
            self.assertEqual(ignored, [
                "android/**", ".github/workflows/android-ci.yml", ".github/workflows/android-release.yml",
            ])
            for changed, expected in (
                (["android/app/src/main/AndroidManifest.xml"], False),
                (["android/tools/sdk/package-lock.json"], False),
                ([".github/workflows/android-ci.yml", ".github/workflows/android-release.yml"], False),
                (["android/app/build.gradle.kts", "apps/web/src/App.tsx"], True),
                (["android/app/build.gradle.kts", "apps/server/go.mod"], True),
                (["android/app/build.gradle.kts", "scripts/release/test_release_workflows.py"], True),
                (["package-lock.json"], True),
                ([".github/workflows/ci.yml"], True),
                (["README.md"], True),
            ):
                with self.subTest(event=event, changed=changed):
                    self.assertEqual(any(not any(fnmatchcase(path, rule) for rule in ignored) for path in changed), expected)

    def test_deleting_historical_tags_never_starts_a_new_release(self) -> None:
        for name in ("android-release.yml", "release.yml"):
            self.assertIn("if: github.event.deleted != true", (WORKFLOWS / name).read_text())

    def test_python_helpers_parse_without_import_side_effects(self) -> None:
        for path in (ROOT / "scripts/release").glob("*.py"):
            with self.subTest(path=path.name):
                ast.parse(path.read_text(), filename=str(path))


class DeviceWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = (WORKFLOWS / "android-ci.yml").read_text()
        self.job = self.workflow.split("\n  device-regression:\n", 1)[1]

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
        self.assertIn("    needs: verify\n", self.job)
        self.assertIn("    timeout-minutes: 60\n", self.job)
        self.assertIn("      fail-fast: false\n", self.job)
        self.assertNotIn("continue-on-error:", self.job)

    def test_device_paths_are_initialized_at_runtime_not_in_job_context(self) -> None:
        job_config = self.job.split("    steps:\n", 1)[0]
        self.assertNotIn("runner.", job_config)
        initialize = step(self.workflow, "初始化设备诊断目录")
        script = dedent(initialize.split("        run: |\n", 1)[1])
        with tempfile.TemporaryDirectory(prefix="melora workflow ") as directory:
            env_file = Path(directory) / "github-env"
            subprocess.run(["bash", "-euc", script], check=True, env={
                "RUNNER_TEMP": directory, "GITHUB_ENV": str(env_file),
                "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", "API_LEVEL": "35",
            })
            self.assertEqual(env_file.read_text().splitlines(), [
                f"ANDROID_AVD_HOME={directory}/melora-avd-123-2-35",
                f"DEVICE_LOG_DIR={directory}/melora-device-35",
            ])
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
        for selector in ("testInstrumentationRunnerArguments", "--tests", "am instrument", "pm grant"):
            self.assertNotIn(selector, self.job)
        self.assertEqual(self.job.count(":app:connectedDebugAndroidTest"), 1)
        connected = step(self.workflow, "执行完整 Android 设备回归")
        self.assertIn("rm -rf app/build/outputs/androidTest-results/connected", connected)
        self.assertIn("        timeout-minutes: 30", connected)

    def test_emulator_boot_is_bounded_and_checks_runtime_api_and_abi(self) -> None:
        boot = step(self.workflow, "启动并校验独立模拟器")
        for required in ("timeout 360 bash -c", "-port 5554", "-no-snapshot", "-gpu swangle", "-accel on",
                         "getprop sys.boot_completed", "getprop ro.build.version.sdk", '= "$API_LEVEL"',
                         "getprop ro.product.cpu.abi", "= x86_64", 'adb -s "$ANDROID_SERIAL"'):
            self.assertIn(required, boot)
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
        for required in ("${{ env.DEVICE_LOG_DIR }}/", "android/app/build/outputs/androidTest-results/connected/",
                         "android/app/build/reports/androidTests/connected/", "retention-days: 3",
                         "if-no-files-found: error", "api${{ matrix.api }}"):
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
