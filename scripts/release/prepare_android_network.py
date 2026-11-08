#!/usr/bin/env python3
"""准备 CI 独立 AVD 的真实网络；不修改联网验证或应用网络策略。"""

from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass
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


def current_networks(snapshot: str) -> list[str]:
    # 只读当前 NetworkAgentInfo，不能误用另一个网络或历史日志中的能力。
    if "Current Networks:\n" not in snapshot:
        return []
    current = snapshot.split("Current Networks:\n", 1)[1]
    networks = []
    for line in current.splitlines():
        if line and not line.startswith(" "):
            break
        if line.lstrip().startswith("NetworkAgentInfo{"):
            networks.append(line.strip())
    return networks


def network_capabilities(line: str) -> tuple[set[str], set[str]]:
    transports = re.search(r"nc\{\[\s*Transports: ([A-Z_&|]+)", line)
    caps = re.search(r"nc\{\[.*?Capabilities: ([A-Z_&|]+)", line)
    return (set(re.split(r"[&|]", transports[1])) if transports else set(),
            set(re.split(r"[&|]", caps[1])) if caps else set())


def ready_network(snapshot: str) -> str | None:
    active = re.search(r"(?m)^Active default network: (\d+)\s*$", snapshot)
    if not active:
        return None
    for line in current_networks(snapshot):
        if f"network{{{active[1]}}}" not in line:
            continue
        transports, caps = network_capabilities(line)
        if "WIFI" in transports and {"INTERNET", "NOT_METERED", "VALIDATED"} <= caps:
            return line
    return None


@dataclass(frozen=True)
class WifiNetwork:
    netid: int
    interface: str
    ipv4: ipaddress.IPv4Interface
    gateway: ipaddress.IPv4Address


def unvalidated_wifi(snapshot: str) -> WifiNetwork | None:
    wifi = [line for line in current_networks(snapshot)
            if "WIFI" in network_capabilities(line)[0]]
    # 一个健康或另一个并存的Wi-Fi都不能被整体重启误伤。
    if len(wifi) != 1:
        return None
    line = wifi[0]
    transports, caps = network_capabilities(line)
    if (transports != {"WIFI"} or not {"INTERNET", "NOT_METERED"} <= caps or
            "VALIDATED" in caps or not re.search(r"ni\{WIFI CONNECTED\b", line)):
        return None
    netid = re.match(r"NetworkAgentInfo\{network\{(\d+)\}", line)
    interface = re.search(r"\bInterfaceName: ([a-zA-Z][a-zA-Z0-9_.-]{0,14})\s", line)
    addresses = re.search(r"\bLinkAddresses: \[([^\]]+)\]", line)
    routes = re.search(r"\bRoutes: \[([^\]]+)\]", line)
    if not all((netid, interface, addresses, routes)):
        return None
    try:
        ipv4 = [address for value in re.split(r"[\s,]+", addresses[1].strip())
                if (address := ipaddress.ip_interface(value)).version == 4]
        if len(ipv4) != 1 or any((ipv4[0].ip.is_unspecified, ipv4[0].ip.is_loopback,
                                ipv4[0].ip.is_link_local, ipv4[0].ip.is_multicast)):
            return None
        gateway = re.search(r"(?:^|,)\s*0\.0\.0\.0/0 -> ([0-9.]+) " +
                            re.escape(interface[1]) + r"(?:\s|,|$)", routes[1])
        if not gateway:
            return None
        gateway = ipaddress.IPv4Address(gateway[1])
        if gateway not in ipv4[0].network:
            return None
    except ValueError:
        return None
    return WifiNetwork(int(netid[1]), interface[1], ipv4[0], gateway)


def missing_ipv4_routes(wifi: WifiNetwork, addresses: str, routes: str, rules: str) -> str | None:
    """仅凭当前内核证据认定缺路由；未知格式、IPv4地址变化或无显式网络规则都不重连。"""
    interface = None
    has_address = False
    for line in addresses.splitlines():
        header = re.match(r"\d+: ([^:@\s]+)(?:@[^:\s]+)?: <([^>]+)>", line)
        if header:
            interface = header[1] if "UP" in header[2].split(",") else None
        address = re.match(r"\s+inet ([0-9.]+/\d+)\b", line)
        if interface == wifi.interface and address:
            try:
                has_address |= ipaddress.IPv4Interface(address[1]) == wifi.ipv4
            except ValueError:
                return None
    if not has_address:
        return None
    tables = set()
    for line in rules.splitlines():
        mark = re.search(r"\bfwmark (0x[0-9a-fA-F]+|\d+)/(0x[0-9a-fA-F]+|\d+)\b", line)
        table = re.search(r"\blookup ([a-zA-Z0-9_.-]+)\s*$", line)
        if mark and table and "not" not in line.split():
            value, mask = int(mark[1], 0), int(mark[2], 0)
            if mask & 0x1ffff == 0x1ffff and value & 0x1ffff == 0x10000 | wifi.netid:
                tables.add(table[1])
    if len(tables) != 1 or not routes.strip():
        return None
    table = tables.pop()
    if table in {"main", "local", "default", "253", "254", "255"}:
        return None
    direct = default = False
    for line in routes.splitlines():
        fields = line.split()
        if not fields:
            continue
        kind = fields.pop(0) if fields[0] in {"unicast", "local", "broadcast", "multicast",
                                            "throw", "unreachable", "prohibit", "blackhole"} else "unicast"
        try:
            destination = ipaddress.IPv4Network("0.0.0.0/0" if fields[0] == "default" else fields[0])
            route_table = fields[fields.index("table") + 1] if "table" in fields else "main"
            device = fields[fields.index("dev") + 1] if "dev" in fields else None
            gateway = ipaddress.IPv4Address(fields[fields.index("via") + 1]) if "via" in fields else None
        except (IndexError, ValueError):
            return None
        if route_table == table and kind == "unicast" and device == wifi.interface:
            direct |= destination == wifi.ipv4.network and gateway is None
            default |= destination.prefixlen == 0 and gateway == wifi.gateway
    missing = [name for name, present in (("connected", direct), ("default", default)) if not present]
    return f"netId={wifi.netid} interface={wifi.interface} table={table} missing={','.join(missing)}" if missing else None


def shell(serial: str, *args: str, timeout: float = 15) -> str:
    return subprocess.check_output(
        ["adb", "-s", serial, "shell", *args], text=True, timeout=timeout,
    ).strip()


def prepare_network(serial: str, log_dir: Path, *, timeout: float = 240) -> None:
    if not re.fullmatch(r"emulator-\d+", serial):
        raise RuntimeError("Network preparation is restricted to the CI emulator")
    log_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    deadline = started + timeout
    label = "Verified active unmetered Wi-Fi Internet network"

    def command(*args: str) -> str:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError("Emulator network prerequisite failed: " + label)
        return shell(serial, *args, timeout=min(15, remaining))

    def read(name: str, *args: str) -> str:
        value = command(*args)
        (log_dir / name).write_text(value + "\n")
        return value

    def await_state(check, label: str) -> None:
        while time.monotonic() < deadline:
            if check():
                print(label, flush=True)
                return
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        raise RuntimeError("Emulator network prerequisite failed: " + label)

    requested_enable = False
    reconnected = False
    candidate = None
    candidate_since = started
    probed_at = started

    def kernel_routes() -> dict[str, str]:
        return {"addr": read("ipv4-addr-before-tests.log", "ip", "-4", "addr", "show"),
                "routes": read("ipv4-routes-before-tests.log", "ip", "-4", "route", "show", "table", "all"),
                "rules": read("ipv4-rules-before-tests.log", "ip", "-4", "rule", "show")}

    def wifi_ready() -> bool:
        nonlocal requested_enable, reconnected, candidate, candidate_since, probed_at
        wifi = read("wifi-before-tests.log", "dumpsys", "wifi")
        network = read("connectivity-before-tests.log", "dumpsys", "connectivity")
        # svc返回0不能证明启用成功，更不能用历史Wi-Fi能力或当前蜂窝网络放行。
        enabled = bool(re.search(r"(?m)^Wi-Fi is enabled\s*$", wifi))
        if enabled and ready_network(network):
            return True
        if not requested_enable:
            requested_enable = True
            if not enabled:
                command("svc", "wifi", "enable")
        current = unvalidated_wifi(network) if enabled else None
        now = time.monotonic()
        if current != candidate:
            candidate, candidate_since = current, now
        # 留出正常初始化时间；之后每5秒保留当前内核证据，失败时不只留下缓存的LinkProperties。
        if now - started < 30 or now - probed_at < 5:
            return False
        evidence = kernel_routes()
        probed_at = time.monotonic()
        if current is None or reconnected or now - candidate_since < 30:
            return False
        reason = missing_ipv4_routes(current, evidence["addr"], evidence["routes"], evidence["rules"])
        if not reason:
            return False
        # 采样期间可能已验证或换网，操作前再确认身份，避免打断刚恢复的正常连接。
        wifi = read("wifi-before-tests.log", "dumpsys", "wifi")
        network = read("connectivity-before-tests.log", "dumpsys", "connectivity")
        if re.search(r"(?m)^Wi-Fi is enabled\s*$", wifi) and ready_network(network):
            return True
        if unvalidated_wifi(network) != current:
            candidate = None
            return False
        for name, value in {"wifi": wifi, "connectivity": network, **evidence}.items():
            (log_dir / f"wifi-route-recovery-{name}.log").write_text(value + "\n")
        (log_dir / "wifi-route-recovery.log").write_text(reason + "\n")
        print("Missing current Wi-Fi IPv4 routes; reconnecting once: " + reason, flush=True)
        reconnected = True
        command("svc", "wifi", "disable")

        def disconnected() -> bool:
            wifi = read("wifi-before-tests.log", "dumpsys", "wifi")
            network = read("connectivity-before-tests.log", "dumpsys", "connectivity")
            return (bool(re.search(r"(?m)^Wi-Fi is disabled\s*$", wifi)) and
                    not any(f"network{{{current.netid}}}" in line for line in current_networks(network)))

        await_state(disconnected, "Verified Wi-Fi disabled and old network released")
        command("svc", "wifi", "enable")
        candidate = None
        return False

    await_state(wifi_ready, label)


if __name__ == "__main__":
    if sys.argv[1:] == ["--host-dns"]:
        print(host_dns((Path("/run/systemd/resolve/resolv.conf"), Path("/etc/resolv.conf"))))
    elif sys.argv[1:]:
        raise SystemExit("Usage: prepare_android_network.py [--host-dns]")
    else:
        prepare_network(os.environ["ANDROID_SERIAL"], Path(os.environ["DEVICE_LOG_DIR"]))
