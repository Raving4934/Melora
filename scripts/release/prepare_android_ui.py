#!/usr/bin/env python3
"""在测试安装前确认独立 AVD 的桌面真正可交互，而不只检查 boot_completed。"""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import time


def shell(serial: str, *args: str) -> str:
    return subprocess.check_output(
        ["adb", "-s", serial, "shell", *args], text=True, timeout=15,
    )


def home_component(output: str) -> str:
    for line in reversed(output.splitlines()):
        if re.fullmatch(r"[\w.]+/[\w.$]+", line.strip()):
            package, activity = line.strip().split("/", 1)
            return package + "/" + (package + activity if activity.startswith(".") else activity)
    raise RuntimeError("AVD has no resolved HOME activity")


def current_windows(snapshot: str) -> str:
    # LAST ANR 保存了旧焦点和完整窗口快照；绝不能把它当成当前设备状态。
    return "\n".join(re.findall(
        r"(?ms)^WINDOW MANAGER (?:DISPLAY CONTENTS|WINDOWS)[^\n]*\n.*?(?=^WINDOW MANAGER |\Z)",
        snapshot,
    ))


def focused_window(snapshot: str) -> str:
    match = re.search(r"(?m)^\s*mCurrentFocus=(.+)$", current_windows(snapshot))
    return match[1].strip() if match else ""


def launcher_ready(snapshot: str, component: str) -> bool:
    focus = focused_window(snapshot)
    if not focus.endswith(" " + component + "}"):
        return False
    # 不能用其它窗口或上次 ANR 记录中的 HAS_DRAWN 放行当前窗口。
    for block in re.split(r"(?m)^\s*Window #\d+ ", current_windows(snapshot))[1:]:
        if block.startswith(focus + ":\n"):
            return all(value in block for value in (
                "mHasSurface=true", "mViewVisibility=0x0", "mDrawState=HAS_DRAWN",
            ))
    return False


def prepare_ui(serial: str, log_dir: Path, *, timeout: float = 120) -> None:
    if not re.fullmatch(r"emulator-\d+", serial):
        raise RuntimeError("UI preparation is restricted to the CI emulator")
    log_dir.mkdir(parents=True, exist_ok=True)
    home = home_component(shell(serial, "cmd", "package", "resolve-activity", "--brief",
                                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"))
    package = home.split("/", 1)[0]
    # 使用系统服务解锁，不向尚未绘制完的 Launcher 注入 MENU/HOME 键而触发输入 ANR。
    shell(serial, "svc", "power", "stayon", "true")
    shell(serial, "wm", "dismiss-keyguard")
    deadline = time.monotonic() + timeout
    stable = 0
    restarted = False
    last_focus = ""
    while time.monotonic() < deadline:
        snapshot = shell(serial, "dumpsys", "window", "-a")
        (log_dir / "window-before-tests.log").write_text(snapshot)
        focus = focused_window(snapshot)
        if focus != last_focus:
            print(f"AVD window focus: {focus or '<missing>'}", flush=True)
            last_focus = focus
        anr = re.search(r" Application Not Responding: ([\w.]+)}$", focus)
        if anr:
            (log_dir / "window-boot-anr.log").write_text(snapshot)
            # 仅允许测试尚未开始时恢复一次启动器；其它应用/重复 ANR 都直接失败。
            if anr[1] != package or restarted:
                raise RuntimeError("AVD is blocked by an ANR window: " + focus)
            restarted = True
            print("Recovering boot-time Launcher ANR once: " + package, flush=True)
            shell(serial, "am", "force-stop", package)
            shell(serial, "am", "start", "-W", "-n", home,
                  "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
            stable = 0
        else:
            stable = stable + 1 if launcher_ready(snapshot, home) else 0
            if stable >= 3:
                print("Verified drawn and focused HOME window: " + home, flush=True)
                return
        time.sleep(1)
    raise RuntimeError("AVD HOME window did not become interactable: " + last_focus)


if __name__ == "__main__":
    prepare_ui(os.environ["ANDROID_SERIAL"], Path(os.environ["DEVICE_LOG_DIR"]))
