#!/usr/bin/env python3
"""Check the device transport retry against a representative UTP report."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from recover_android_test_transport import recover, was_interrupted


SERIAL = "emulator-5554"
DEVICE = f"{SERIAL}\tdevice model:sdk_gphone64_x86_64 transport_id:"
FAILURE = f"""Failed to uninstall package com.leyu.melora.debug. Output:
Error Output: adb: device '{SERIAL}' not found
Execution failed for task ':app:connectedDebugAndroidTest'.
> There were failing tests. See the report at:
"""
REPORT = """<testsuites>
  <testsuite>
    <testcase name="first" classname="Playback"/>
    <testcase name="second" classname="Playback"/>
    <testcase name="third" classname="Playback"><failure>Test failed with status -1</failure></testcase>
  </testsuite>
  <testsuite><testcase name="testRunFailed" classname="android-test">
    <failure>Test run failed to complete. Expected 475 tests, received 2</failure>
  </testcase></testsuite>
</testsuites>"""


class DeviceTransportRecoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.devices = self.root / "devices.log"
        self.devices.write_text(DEVICE + "1\n")
        self.gradle = self.root / "gradle.log"
        self.gradle.write_text(FAILURE)
        self.report = self.root / "TEST-shard.xml"
        self.report.write_text(REPORT)

    def test_rejects_real_failures_even_if_the_device_later_disconnects(self) -> None:
        for report in (
            REPORT.replace('<testcase name="first" classname="Playback"/>',
                           '<testcase name="first" classname="Playback"><failure>AssertionError</failure></testcase>'),
            REPORT.replace("Test failed with status -1", "junit.framework.AssertionFailedError"),
            REPORT.replace("<testsuites>", '<testsuites failures="3">'),
            REPORT.replace("<testsuites>", '<testsuites errors="1">'),
            REPORT.replace("<failure>Test failed with status -1", '<failure message="AssertionError">Test failed with status -1'),
            REPORT.replace("received 2", "received 475"),
            REPORT.replace("Expected 475 tests, received 2", "did not respond for 30 seconds"),
            REPORT.replace("classname=\"android-test\"", "classname=\"Playback\""),
        ):
            self.report.write_text(report)
            self.assertFalse(was_interrupted(FAILURE, self.root, SERIAL))

    def test_requires_the_device_loss_message_and_an_incomplete_report(self) -> None:
        self.assertFalse(was_interrupted(FAILURE.replace("not found", "offline"), self.root, SERIAL))
        self.assertFalse(was_interrupted(FAILURE.replace("emulator-5554", "emulator-5556"), self.root, SERIAL))
        self.report.unlink()
        self.assertFalse(was_interrupted(FAILURE, self.root, SERIAL))

    @patch("recover_android_test_transport.adb")
    def test_disallows_recovery_on_a_different_avd_or_the_same_transport(self, adb) -> None:
        adb.side_effect = ["", DEVICE + "1"]
        self.assertFalse(recover(self.gradle, self.devices, self.root, SERIAL))
        self.assertEqual(adb.call_count, 2)
        adb.reset_mock()
        adb.side_effect = ["", DEVICE + "2", "Melora_Tablet_Test"]
        self.assertFalse(recover(self.gradle, self.devices, self.root, SERIAL))
        self.assertEqual(adb.call_count, 3)

    @patch("recover_android_test_transport.adb")
    def test_recovers_only_the_verified_ci_transport_and_reapplies_overlay(self, adb) -> None:
        adb.side_effect = ["", DEVICE + "2", "melora-ci", "", "", "Success", "Success", "",
                           "SYSTEM_ALERT_WINDOW: allow"]
        self.assertTrue(recover(self.gradle, self.devices, self.root, SERIAL))
        args = [call.args for call in adb.call_args_list]
        self.assertEqual(args[:3], [
            ("-s", SERIAL, "wait-for-device"), ("devices", "-l"),
            ("-t", "2", "shell", "getprop", "ro.boot.qemu.avd_name"),
        ])
        self.assertEqual(args[3:], [
            ("-t", "2", "shell", "am", "force-stop", "com.leyu.melora.debug.test"),
            ("-t", "2", "shell", "am", "force-stop", "com.leyu.melora.debug"),
            ("-t", "2", "install", "-r", "app/build/outputs/apk/debug/app-debug.apk"),
            ("-t", "2", "shell", "pm", "clear", "com.leyu.melora.debug"),
            ("-t", "2", "shell", "appops", "set", "--uid", "com.leyu.melora.debug", "SYSTEM_ALERT_WINDOW", "allow"),
            ("-t", "2", "shell", "appops", "get", "com.leyu.melora.debug", "SYSTEM_ALERT_WINDOW"),
        ])


if __name__ == "__main__":
    unittest.main()
