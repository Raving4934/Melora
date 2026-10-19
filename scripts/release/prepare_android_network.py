#!/usr/bin/env python3
"""准备 CI 独立 AVD 的真实网络；不修改联网验证或应用网络策略。"""

from __future__ import annotations

import ipaddress
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def host_dns(paths: tuple[Path, ...]) -> str:
    # Ubuntu 的 /etc/resolv.conf 常指向 127.0.0.53 stub。直接给两个虚拟网络
    # 后端同一组真实上游，避免 Wi-Fi/netsim 与蜂窝/slirp 各自推断 DNS。
    for path in paths:
        if not path.is_file():
            continue
        addresses = []
        for line in path.read_text().splitlines():
            fields = line.split()
            if len(fields) < 2 or fields[0] != "nameserver":
                continue
            try:
                address = ipaddress.ip_address(fields[1])
            except ValueError:
                continue
            if address.version != 4 or any((address.is_loopback, address.is_link_local,
                                           address.is_unspecified, address.is_multicast)):
                continue
            if str(address) not in addresses:
                addresses.append(str(address))
        if addresses:
            return ",".join(addresses[:3])
    raise RuntimeError("No upstream IPv4 DNS found; refusing to guess a public resolver")


def ready_network(snapshot: str) -> str | None:
    active = re.search(r"(?m)^Active default network: (\d+)\s*$", snapshot)
    if not active or "Current Networks:\n" not in snapshot:
        return None
    # 只读当前 NetworkAgentInfo，不能误用另一个网络或历史日志中的能力。
    current = snapshot.split("Current Networks:\n", 1)[1]
    for line in current.splitlines():
        if line and not line.startswith(" "):
            break
        if "NetworkAgentInfo{" not in line or f"network{{{active[1]}}}" not in line:
            continue
        caps = re.search(r"nc\{\[.*?Capabilities: ([A-Z_&|]+)", line)
        if caps and {"INTERNET", "NOT_METERED", "VALIDATED"} <= set(re.split(r"[&|]", caps[1])):
            return line.strip()
    return None


def shell(serial: str, *args: str) -> str:
    return subprocess.check_output(
        ["adb", "-s", serial, "shell", *args], text=True, timeout=15,
    ).strip()


def prepare_network(serial: str, log_dir: Path, *, timeout: float = 240) -> None:
    if not re.fullmatch(r"emulator-\d+", serial):
        raise RuntimeError("Network preparation is restricted to the CI emulator")
    log_dir.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout

    def read(name: str, *args: str) -> str:
        value = shell(serial, *args)
        (log_dir / name).write_text(value + "\n")
        return value

    def await_state(check, label: str) -> None:
        while time.monotonic() < deadline:
            if check():
                print(label, flush=True)
                return
            time.sleep(1)
        raise RuntimeError("Emulator network prerequisite failed: " + label)

    def subscription_ready() -> bool:
        sim = read("sim-before-tests.log", "getprop", "gsm.sim.state")
        sub = read("subscription-before-tests.log", "settings", "get", "global", "multi_sim_data_call")
        return bool({"READY", "LOADED"} & set(sim.split(","))) and sub.isdigit()

    # svc data 的 shell 退出码不会反映无有效订阅时的失败。先等订阅，再读回设置。
    await_state(subscription_ready, "Default data subscription ready")
    shell(serial, "svc", "data", "disable")
    await_state(lambda: read("mobile-data-before-tests.log", "settings", "get", "global", "mobile_data") == "0",
                "Mobile data disabled")
    shell(serial, "svc", "wifi", "disable")

    def disconnected() -> bool:
        wifi = read("wifi-before-tests.log", "dumpsys", "wifi")
        network = read("connectivity-before-tests.log", "dumpsys", "connectivity")
        # default none 也可能意味着尚未初始化，必须同时确认 Wi-Fi 服务已完成关闭。
        return bool(re.search(r"(?m)^Wi-Fi is disabled\s*$", wifi) and
                    re.search(r"(?m)^Active default network: none\s*$", network))

    await_state(disconnected, "Wi-Fi disabled and previous default network disconnected")
    shell(serial, "svc", "wifi", "enable")
    await_state(lambda: ready_network(read("connectivity-before-tests.log", "dumpsys", "connectivity")),
                "Verified active unmetered Internet network")


if __name__ == "__main__":
    if sys.argv[1:] == ["--host-dns"]:
        print(host_dns((Path("/run/systemd/resolve/resolv.conf"), Path("/etc/resolv.conf"))))
    elif sys.argv[1:]:
        raise SystemExit("Usage: prepare_android_network.py [--host-dns]")
    else:
        prepare_network(os.environ["ANDROID_SERIAL"], Path(os.environ["DEVICE_LOG_DIR"]))
