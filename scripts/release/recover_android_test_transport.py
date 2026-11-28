#!/usr/bin/env python3
"""Restore the isolated CI test device after a confirmed ddmlib transport loss."""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def transport_id(devices: str, serial: str) -> str | None:
    match = re.search(rf"(?m)^{re.escape(serial)}[ \t]+device[ \t]+[^\n]*\btransport_id:(\d+)\b", devices)
    return match[1] if match else None


def was_interrupted(log: str, reports: Path, serial: str) -> bool:
    if f"adb: device '{serial}' not found" not in log:
        return False
    if "Execution failed for task ':app:connectedDebugAndroidTest'" not in log:
        return False

    incomplete = 0
    interrupted_cases = 0
    for path in reports.rglob("TEST-*.xml"):
        root = ET.parse(path).getroot()
        failures = root.findall(".//testcase/failure")
        if root.findall(".//failure") != failures or root.find(".//error") is not None:
            return False
        for suite in root.iter():
            if suite.tag not in ("testsuites", "testsuite"):
                continue
            if int(suite.get("errors", "0")):
                return False
            if "failures" in suite.attrib and int(suite.attrib["failures"]) != len(suite.findall(".//failure")):
                return False
        for case in root.iter("testcase"):
            for issue in case.findall("failure"):
                message = (issue.text or "").strip() or issue.get("message", "")
                if issue.get("message", message) != message:
                    return False
                if (case.get("classname"), case.get("name")) == ("android-test", "testRunFailed"):
                    match = re.fullmatch(r"Test run failed to complete\. Expected (\d+) tests, received (\d+)", message)
                    if not match or int(match[2]) >= int(match[1]):
                        return False
                    incomplete += 1
                elif message == "Test failed with status -1":
                    interrupted_cases += 1
                else:
                    return False
    return incomplete == 1 and interrupted_cases <= 1


def adb(*args: str, timeout: int = 15) -> str:
    return subprocess.check_output(["adb", *args], text=True, timeout=timeout).strip()


def recover(log: Path, prior_devices: Path, reports: Path, serial: str) -> bool:
    if not re.fullmatch(r"emulator-\d+", serial):
        return False
    previous = transport_id(prior_devices.read_text(), serial)
    if previous is None or not was_interrupted(log.read_text(), reports, serial):
        return False

    adb("-s", serial, "wait-for-device", timeout=30)
    current = transport_id(adb("devices", "-l"), serial)
    if current is None or current == previous:
        return False
    shell = ("-t", current, "shell")
    if adb(*shell, "getprop", "ro.boot.qemu.avd_name") != "melora-ci":
        return False

    package = "com.leyu.melora.debug"
    # Install/grant can be undone by ddmlib as it tears down the interrupted invocation.
    for running_package in (package + ".test", package):
        adb(*shell, "am", "force-stop", running_package)
    adb("-t", current, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk", timeout=120)
    if adb(*shell, "pm", "clear", package) != "Success":
        raise RuntimeError("Could not clear interrupted test app data")
    adb(*shell, "appops", "set", "--uid", package, "SYSTEM_ALERT_WINDOW", "allow")
    if "SYSTEM_ALERT_WINDOW: allow" not in adb(*shell, "appops", "get", package, "SYSTEM_ALERT_WINDOW"):
        raise RuntimeError("Could not restore the test overlay permission")
    print(f"::warning::ADB transport changed from {previous} to {current}. Re-running the entire device shard once.")
    return True


if __name__ == "__main__":
    try:
        restored = recover(*(Path(arg) for arg in sys.argv[1:]), serial=os.environ["ANDROID_SERIAL"])
    except (OSError, ValueError, ET.ParseError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"ADB transport recovery failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    raise SystemExit(0 if restored else 1)
