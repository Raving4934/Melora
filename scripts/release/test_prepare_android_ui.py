#!/usr/bin/env python3
"""离线回归测试：只使用标准库、临时日志和虚拟时钟，不连接任何设备。"""

from __future__ import annotations

from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, call, patch

import prepare_android_ui as ui


SERIAL = "emulator-5554"
HOME_PACKAGE = "com.google.android.apps.nexuslauncher"
HOME_COMPONENT = HOME_PACKAGE + "/" + HOME_PACKAGE + ".NexusLauncherActivity"
HOME_WINDOW = "Window{87eb99f u0 " + HOME_COMPONENT + "}"
RESOLVE_HOME = (
    "cmd", "package", "resolve-activity", "--brief",
    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
)
STAY_AWAKE = ("svc", "power", "stayon", "true")
DISMISS_KEYGUARD = ("wm", "dismiss-keyguard")
DUMP_WINDOWS = ("dumpsys", "window", "-a")

# 从 API 35 的 .superpowers/tmp/ci-v020-20261016/window-verbose.log
# 抽取当前窗口和相邻壁纸的原始关键行；fixture 内嵌，运行不依赖本地日志。
API35_HOME_BLOCK = """  Window #8 Window{87eb99f u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}:
    mOwnerUid=10179 showForAllUsers=false package=com.google.android.apps.nexuslauncher appop=NONE
    mActivityRecord=ActivityRecord{86db25c u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7}
    drawnStateEvaluated=true    mightAffectAllDrawn=true
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
    WindowStateAnimator{c1eadba com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}:
      mSurface=Surface(name=com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity)/@0x5dbc97
      Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
    isOnScreen=true
    isVisible=true
"""
API35_WALLPAPER_BLOCK = """  Window #9 Window{40f0aa2 u0 com.android.systemui.wallpapers.ImageWallpaper}:
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
    WindowStateAnimator{575eee5 com.android.systemui.wallpapers.ImageWallpaper}:
      Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
"""


def window_snapshot(
    focus: str | None = HOME_WINDOW, *, home_block: str = API35_HOME_BLOCK,
) -> str:
    focus_line = "" if focus is None else "  mCurrentFocus=" + focus + "\n"
    return (
        "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
        + focus_line
        + "  mFocusedApp=ActivityRecord{86db25c u0 "
        + HOME_PACKAGE + "/.NexusLauncherActivity t7}\n"
        + "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
        + home_block + API35_WALLPAPER_BLOCK
    )


def anr_snapshot(package: str, token: str = "dead001") -> str:
    return window_snapshot(
        "Window{" + token + " u0 Application Not Responding: " + package + "}",
    )


READY_SNAPSHOT = window_snapshot()


class HomeComponentTest(unittest.TestCase):
    def test_resolves_short_and_fully_qualified_activities(self) -> None:
        for output, expected in (
            (HOME_PACKAGE + "/.NexusLauncherActivity", HOME_COMPONENT),
            (HOME_COMPONENT, HOME_COMPONENT),
            ("org.example.home/.Home$Desktop", "org.example.home/org.example.home.Home$Desktop"),
            ("org.example.home/other.package.Home", "org.example.home/other.package.Home"),
        ):
            with self.subTest(output=output):
                self.assertEqual(ui.home_component(output), expected)

    def test_ignores_resolver_noise_and_uses_last_component(self) -> None:
        output = (
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\r\n"
            "com.android.launcher3/.Launcher\r\n"
            "  " + HOME_PACKAGE + "/.NexusLauncherActivity  \r\n\r\n"
        )
        self.assertEqual(ui.home_component(output), HOME_COMPONENT)

    def test_rejects_missing_or_malformed_home(self) -> None:
        for output in ("", "No activity found\n", HOME_PACKAGE, "package/", "/.Home"):
            with self.subTest(output=output):
                with self.assertRaisesRegex(RuntimeError, "no resolved HOME activity"):
                    ui.home_component(output)


class LauncherReadyTest(unittest.TestCase):
    def test_api35_verbose_current_window_is_drawn_and_focused(self) -> None:
        self.assertEqual(ui.focused_window(READY_SNAPSHOT), HOME_WINDOW)
        self.assertTrue(ui.launcher_ready(READY_SNAPSHOT, HOME_COMPONENT))

    def test_drawn_wallpaper_cannot_substitute_for_current_window_state(self) -> None:
        for field, replacement in (
            ("mDrawState=HAS_DRAWN", "mDrawState=DRAW_PENDING"),
            ("mHasSurface=true", "mHasSurface=false"),
            ("mViewVisibility=0x0", "mViewVisibility=0x4"),
            ("mDrawState=HAS_DRAWN", ""),
            ("mHasSurface=true", ""),
            ("mViewVisibility=0x0", ""),
        ):
            with self.subTest(field=field, replacement=replacement):
                snapshot = window_snapshot(home_block=API35_HOME_BLOCK.replace(field, replacement))
                self.assertIn("mDrawState=HAS_DRAWN", snapshot)
                self.assertFalse(ui.launcher_ready(snapshot, HOME_COMPONENT))

    def test_matching_component_in_a_different_window_is_insufficient(self) -> None:
        for home_block in ("", API35_HOME_BLOCK.replace("87eb99f", "abcdef0")):
            with self.subTest(home_block=home_block):
                self.assertFalse(ui.launcher_ready(window_snapshot(home_block=home_block), HOME_COMPONENT))

    def test_drawn_home_without_current_focus_is_not_ready(self) -> None:
        for focus in (None, "null", "Window{123abcd u0 org.example.player/.MainActivity}"):
            with self.subTest(focus=focus):
                snapshot = "  mLastFocus=" + HOME_WINDOW + "\n" + window_snapshot(focus)
                self.assertEqual(ui.focused_window(snapshot), focus or "")
                self.assertFalse(ui.launcher_ready(snapshot, HOME_COMPONENT))

    def test_anr_focus_cannot_be_masked_by_drawn_home(self) -> None:
        self.assertFalse(ui.launcher_ready(anr_snapshot(HOME_PACKAGE), HOME_COMPONENT))

    def test_past_anr_drawn_window_cannot_substitute_for_current_window(self) -> None:
        stale_block = API35_HOME_BLOCK.replace("Window #8", "Window #7").replace("87eb99f", "old1234")
        current_block = API35_HOME_BLOCK.replace("mDrawState=HAS_DRAWN", "mDrawState=DRAW_PENDING")
        snapshot = window_snapshot(home_block=stale_block + current_block)
        self.assertFalse(ui.launcher_ready(snapshot, HOME_COMPONENT))

    def test_last_anr_snapshot_cannot_supply_current_window_draw_state(self) -> None:
        historical = (
            "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n"
            + "  mCurrentFocus=" + HOME_WINDOW + "\n" + API35_HOME_BLOCK
        )
        pending = window_snapshot(
            home_block=API35_HOME_BLOCK.replace("mDrawState=HAS_DRAWN", "mDrawState=NO_SURFACE"),
        )
        self.assertFalse(ui.launcher_ready(historical + pending, HOME_COMPONENT))
        self.assertFalse(ui.launcher_ready(historical, HOME_COMPONENT))

    def test_api26_current_focus_in_windows_overrides_historical_anr_focus(self) -> None:
        historical = (
            "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n"
            + "  mCurrentFocus=" + HOME_WINDOW + "\n" + API35_HOME_BLOCK
        )
        focus = "Window{anr002 u0 Application Not Responding: com.android.settings}"
        current = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n  mCurrentFocus=" + focus + "\n"
        self.assertEqual(ui.focused_window(historical + current), focus)
        self.assertFalse(ui.launcher_ready(historical + current, HOME_COMPONENT))
        current = current.replace(focus, HOME_WINDOW) + API35_HOME_BLOCK
        self.assertTrue(ui.launcher_ready(historical + current, HOME_COMPONENT))


class ShellTest(unittest.TestCase):
    def test_adb_uses_explicit_serial_and_verbose_window_dump(self) -> None:
        with patch.object(ui.subprocess, "check_output", return_value=READY_SNAPSHOT) as adb:
            self.assertEqual(ui.shell(SERIAL, *DUMP_WINDOWS), READY_SNAPSHOT)
        adb.assert_called_once_with(
            ["adb", "-s", SERIAL, "shell", *DUMP_WINDOWS], text=True, timeout=15,
        )

    def test_adb_process_timeout_and_missing_executable_errors_propagate(self) -> None:
        command = ["adb", "-s", SERIAL, "shell", *DUMP_WINDOWS]
        for error in (
            subprocess.CalledProcessError(1, command, output="device offline"),
            subprocess.TimeoutExpired(command, 15),
            FileNotFoundError("adb is unavailable"),
        ):
            with self.subTest(error=type(error).__name__):
                with patch.object(ui.subprocess, "check_output", side_effect=error) as adb:
                    with self.assertRaises(type(error)) as caught:
                        ui.shell(SERIAL, *DUMP_WINDOWS)
                self.assertIs(caught.exception, error)
                adb.assert_called_once_with(command, text=True, timeout=15)


class PrepareUiTest(unittest.TestCase):
    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory(prefix="test-prepare-android-ui-")
        self.addCleanup(temp.cleanup)
        self.log_dir = Path(temp.name) / "logs"
        self.home = HOME_COMPONENT
        self.home_output = HOME_PACKAGE + "/.NexusLauncherActivity\n"
        self.snapshots = iter(())
        self.failures: dict[tuple[str, ...], Exception] = {}
        self.now = 0.0
        self.clock = Mock(spec=["monotonic", "sleep"])
        self.clock.monotonic.side_effect = lambda: self.now
        self.clock.sleep.side_effect = self.advance_time
        self.shell = Mock(side_effect=self.fake_shell)
        for target, name, value in (
            (ui, "shell", self.shell),
            (ui, "time", self.clock),
            (ui.subprocess, "check_output", Mock(side_effect=AssertionError("Real adb is forbidden"))),
        ):
            patcher = patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def advance_time(self, seconds: float) -> None:
        self.now += seconds

    def stop_command(self) -> tuple[str, ...]:
        return ("am", "force-stop", self.home.split("/", 1)[0])

    def start_command(self) -> tuple[str, ...]:
        return (
            "am", "start", "-W", "-n", self.home,
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
        )

    def fake_shell(self, serial: str, *args: str) -> str:
        self.assertEqual(serial, SERIAL)
        if args in self.failures:
            raise self.failures[args]
        if args == RESOLVE_HOME:
            return self.home_output
        if args == DUMP_WINDOWS:
            return next(self.snapshots)
        if args in (STAY_AWAKE, DISMISS_KEYGUARD, self.stop_command(), self.start_command()):
            return ""
        # 包括 boot 时注入 MENU(82)/HOME 的旧链路在内，任何额外命令都失败。
        raise AssertionError(f"Unexpected adb shell command: {args!r}")

    def prepare(self, snapshots: list[str], *, timeout: float = 20, serial: str = SERIAL) -> None:
        self.snapshots = iter(snapshots)
        with redirect_stdout(io.StringIO()):
            ui.prepare_ui(serial, self.log_dir, timeout=timeout)

    def assert_poll_count(self, count: int) -> None:
        self.assertEqual(self.shell.call_args_list.count(call(SERIAL, *DUMP_WINDOWS)), count)

    def test_three_stable_verbose_snapshots_ready_without_key_events(self) -> None:
        self.prepare([READY_SNAPSHOT] * 3)
        self.assertEqual(self.shell.call_args_list, [
            call(SERIAL, *RESOLVE_HOME),
            call(SERIAL, *STAY_AWAKE),
            call(SERIAL, *DISMISS_KEYGUARD),
            *[call(SERIAL, *DUMP_WINDOWS)] * 3,
        ])
        self.assertEqual(self.clock.sleep.call_args_list, [call(1), call(1)])
        self.assertEqual((self.log_dir / "window-before-tests.log").read_text(), READY_SNAPSHOT)
        self.assertFalse((self.log_dir / "window-boot-anr.log").exists())

    def test_one_or_two_stable_snapshots_are_not_ready(self) -> None:
        for count in (1, 2):
            with self.subTest(count=count):
                self.shell.reset_mock()
                self.clock.reset_mock()
                with self.assertRaisesRegex(RuntimeError, "did not become interactable"):
                    self.prepare([READY_SNAPSHOT] * count, timeout=count)
                self.assert_poll_count(count)
                self.assertEqual(self.clock.sleep.call_count, count)

    def test_lost_focus_resets_stability(self) -> None:
        for focus in (None, "null", "Window{123abcd u0 org.example.player/.MainActivity}"):
            with self.subTest(focus=focus):
                self.shell.reset_mock()
                self.clock.reset_mock()
                self.prepare([READY_SNAPSHOT] * 2 + [window_snapshot(focus)] + [READY_SNAPSHOT] * 3)
                self.assert_poll_count(6)
                self.assertEqual(self.clock.sleep.call_count, 5)

    def test_undrawn_current_home_resets_stability_despite_drawn_wallpaper(self) -> None:
        pending = window_snapshot(
            home_block=API35_HOME_BLOCK.replace("mDrawState=HAS_DRAWN", "mDrawState=DRAW_PENDING"),
        )
        self.prepare([READY_SNAPSHOT] * 2 + [pending] + [READY_SNAPSHOT] * 3)
        self.assert_poll_count(6)
        self.assertEqual(self.clock.sleep.call_count, 5)

    def test_launcher_anr_recovers_only_resolved_home_and_resets_stability(self) -> None:
        package = "org.example.alternativehome"
        self.home = package + "/" + package + ".Desktop"
        self.home_output = package + "/.Desktop\n"
        ready = READY_SNAPSHOT.replace(HOME_COMPONENT, self.home)
        anr = anr_snapshot(package)
        self.prepare([ready] * 2 + [anr] + [ready] * 3)
        self.assertEqual(self.shell.call_args_list, [
            call(SERIAL, *RESOLVE_HOME),
            call(SERIAL, *STAY_AWAKE),
            call(SERIAL, *DISMISS_KEYGUARD),
            *[call(SERIAL, *DUMP_WINDOWS)] * 3,
            call(SERIAL, "am", "force-stop", package),
            call(SERIAL, "am", "start", "-W", "-n", self.home,
                 "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"),
            *[call(SERIAL, *DUMP_WINDOWS)] * 3,
        ])
        self.assertEqual(self.clock.sleep.call_count, 5)
        self.assertEqual((self.log_dir / "window-boot-anr.log").read_text(), anr)
        self.assertEqual((self.log_dir / "window-before-tests.log").read_text(), ready)

    def test_other_app_anr_fails_immediately_without_force_stop(self) -> None:
        for package in ("org.example.player", "com.android.launcher3", HOME_PACKAGE + ".overlay"):
            with self.subTest(package=package):
                self.shell.reset_mock()
                self.clock.reset_mock()
                anr = anr_snapshot(package)
                with self.assertRaisesRegex(RuntimeError, "blocked by an ANR window"):
                    self.prepare([anr] + [READY_SNAPSHOT] * 3)
                self.assertEqual(self.shell.call_args_list, [
                    call(SERIAL, *RESOLVE_HOME),
                    call(SERIAL, *STAY_AWAKE),
                    call(SERIAL, *DISMISS_KEYGUARD),
                    call(SERIAL, *DUMP_WINDOWS),
                ])
                self.clock.sleep.assert_not_called()
                self.assertEqual((self.log_dir / "window-boot-anr.log").read_text(), anr)
                self.assertEqual((self.log_dir / "window-before-tests.log").read_text(), anr)

    def test_historical_home_focus_cannot_hide_current_other_app_anr(self) -> None:
        snapshot = (
            "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n"
            + "  mCurrentFocus=" + HOME_WINDOW + "\n" + API35_HOME_BLOCK
            + anr_snapshot("com.android.settings")
        )
        with self.assertRaisesRegex(RuntimeError, "blocked by an ANR window"):
            self.prepare([snapshot] * 3)
        self.assert_poll_count(1)
        self.assertNotIn(call(SERIAL, *self.stop_command()), self.shell.call_args_list)

    def test_second_launcher_anr_after_recovery_fails_without_second_restart(self) -> None:
        first = anr_snapshot(HOME_PACKAGE)
        second = anr_snapshot(HOME_PACKAGE, "dead002")
        with self.assertRaisesRegex(RuntimeError, "blocked by an ANR window"):
            self.prepare([first, READY_SNAPSHOT, READY_SNAPSHOT, second] + [READY_SNAPSHOT] * 3)
        self.assert_poll_count(4)
        self.assertEqual(self.shell.call_args_list.count(call(SERIAL, *self.stop_command())), 1)
        self.assertEqual(self.shell.call_args_list.count(call(SERIAL, *self.start_command())), 1)
        self.assertEqual(self.clock.sleep.call_count, 3)
        self.assertEqual((self.log_dir / "window-boot-anr.log").read_text(), second)
        self.assertEqual((self.log_dir / "window-before-tests.log").read_text(), second)

    def test_no_focus_times_out_and_saves_latest_window_log(self) -> None:
        snapshots = [window_snapshot(None) + f"poll={index}\n" for index in range(3)]
        self.assertIn("mDrawState=HAS_DRAWN", snapshots[-1])
        with self.assertRaisesRegex(RuntimeError, "did not become interactable"):
            self.prepare(snapshots, timeout=3)
        self.assert_poll_count(3)
        self.assertEqual(self.clock.sleep.call_count, 3)
        self.assertEqual(self.now, 3)
        self.assertEqual((self.log_dir / "window-before-tests.log").read_text(), snapshots[-1])
        self.assertFalse((self.log_dir / "window-boot-anr.log").exists())
        self.assertNotIn(call(SERIAL, *self.stop_command()), self.shell.call_args_list)

    def test_non_emulator_serial_is_rejected_before_adb_or_log_creation(self) -> None:
        for serial in ("R58M123456A", "192.0.2.1:5555", "", "emulator-", "emulator-5554-extra", "emulator-5554\n"):
            with self.subTest(serial=serial):
                with self.assertRaisesRegex(RuntimeError, "restricted to the CI emulator"):
                    self.prepare([], serial=serial)
                self.shell.assert_not_called()
                self.clock.monotonic.assert_not_called()
                self.clock.sleep.assert_not_called()
                self.assertFalse(self.log_dir.exists())

    def test_unresolved_home_fails_before_unlock_or_window_poll(self) -> None:
        self.home_output = "No activity found\n"
        with self.assertRaisesRegex(RuntimeError, "no resolved HOME activity"):
            self.prepare([])
        self.shell.assert_called_once_with(SERIAL, *RESOLVE_HOME)
        self.clock.monotonic.assert_not_called()
        self.clock.sleep.assert_not_called()

    def test_adb_failures_propagate_at_every_preparation_stage(self) -> None:
        for command in (
            RESOLVE_HOME, STAY_AWAKE, DISMISS_KEYGUARD, DUMP_WINDOWS,
            self.stop_command(), self.start_command(),
        ):
            with self.subTest(command=command):
                self.shell.reset_mock()
                self.clock.reset_mock()
                error = subprocess.CalledProcessError(
                    1, ["adb", "-s", SERIAL, "shell", *command], output="device offline",
                )
                self.failures = {command: error}
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    self.prepare([anr_snapshot(HOME_PACKAGE)])
                self.assertIs(caught.exception, error)
                self.assertEqual(self.shell.call_args_list[-1], call(SERIAL, *command))
                self.clock.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
