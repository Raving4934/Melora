#!/usr/bin/env python3
"""网络预检的状态转换回归：不放宽真实网络能力门禁。"""

from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import call, patch

import prepare_android_network as network


SERIAL = "emulator-5554"
SIM = ("getprop", "gsm.sim.state")
SUB = ("settings", "get", "global", "multi_sim_data_call")
DATA = ("settings", "get", "global", "mobile_data")
WIFI = ("dumpsys", "wifi")
CONNECTIVITY = ("dumpsys", "connectivity")
ADDR = ("ip", "-4", "addr", "show")
ROUTES = ("ip", "-4", "route", "show", "table", "all")
RULES = ("ip", "-4", "rule", "show")
DISABLE = ("svc", "wifi", "disable")
ENABLE = ("svc", "wifi", "enable")
NO_NETWORK = "Active default network: none\nCurrent Networks:\n"
KERNEL_ADDR = ("16: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n"
               "    inet 10.0.2.16/24 brd 10.0.2.255 scope global wlan0\n")
KERNEL_LOCAL = ("local 10.0.2.16 dev wlan0 table local proto kernel scope host\n"
                "10.0.2.0/24 dev wlan0 table 1000000016 proto static scope link\n"
                "10.0.2.0/24 dev wlan0 proto kernel scope link src 10.0.2.16\n")
KERNEL_DIRECT = "10.0.2.0/24 dev wlan0 table wlan0 proto static scope link\n"
KERNEL_DEFAULT = "default via 10.0.2.2 dev wlan0 table wlan0 proto static\n"
KERNEL_RULES = ("0: from all lookup local\n"
                "16000: from all fwmark 0x10064/0x1ffff iif lo lookup wlan0\n"
                "23000: from all fwmark 0x64/0x1ffff iif lo lookup wlan0\n")


def connected(capabilities="INTERNET&NOT_METERED&VALIDATED", transport="WIFI"):
    return ("Active default network: 100\nCurrent Networks:\n"
            "  NetworkAgentInfo{network{100} nc{[ Transports: " + transport + " Capabilities: "
            + capabilities + " ]}}\n")


def associated_wifi(*, netid=100, active=101, validated=False, address="10.0.2.16/24"):
    caps = "INTERNET&NOT_METERED" + ("&VALIDATED" if validated else "")
    return (f"Active default network: {active}\nCurrent Networks:\n"
            f"  NetworkAgentInfo{{network{{{netid}}} ni{{WIFI CONNECTED extra: }} "
            f"lp{{{{InterfaceName: wlan0 LinkAddresses: [ {address} ] "
            "Routes: [ 10.0.2.0/24 -> 0.0.0.0 wlan0 mtu 0,"
            "0.0.0.0/0 -> 10.0.2.2 wlan0 mtu 0 ]}} "
            f"nc{{[ Transports: WIFI Capabilities: {caps} ]}}}}\n")


class WifiRouteEvidenceTest(unittest.TestCase):
    def missing(self, *, snapshot=None, addresses=KERNEL_ADDR,
                routes=KERNEL_LOCAL, rules=KERNEL_RULES):
        wifi = network.unvalidated_wifi(snapshot or associated_wifi())
        self.assertIsNotNone(wifi)
        return network.missing_ipv4_routes(wifi, addresses, routes, rules)

    def test_only_explicit_network_table_proves_the_wifi_routes(self):
        # main/local/Android局域网专用表均有wlan0路由，也不能掩盖netId对应表的缺失。
        self.assertIn("missing=connected,default", self.missing())
        self.assertIn("missing=default", self.missing(routes=KERNEL_LOCAL + KERNEL_DIRECT))
        self.assertIn("missing=connected", self.missing(routes=KERNEL_LOCAL + KERNEL_DEFAULT))
        self.assertIsNone(self.missing(routes=KERNEL_LOCAL + KERNEL_DIRECT + KERNEL_DEFAULT))

    def test_numeric_route_tables_are_supported_as_well_as_root_aliases(self):
        routes = (KERNEL_LOCAL + KERNEL_DEFAULT).replace("table wlan0", "table 1016")
        rules = KERNEL_RULES.replace("lookup wlan0", "lookup 1016")
        self.assertIn("table=1016 missing=connected", self.missing(routes=routes, rules=rules))
        routes += KERNEL_DIRECT.replace("table wlan0", "table 1016")
        self.assertIsNone(self.missing(routes=routes, rules=rules))

    def test_unknown_or_other_network_kernel_evidence_never_requests_reconnection(self):
        for evidence in ({"addresses": "unknown address format"},
                         {"addresses": KERNEL_ADDR.replace("10.0.2.16", "10.0.2.17")},
                         {"rules": KERNEL_RULES.replace("0x10064", "0x10065")},
                         {"rules": "unknown rule format"}, {"routes": "unknown route format"},
                         {"routes": ""}):
            with self.subTest(evidence=evidence):
                self.assertIsNone(self.missing(**evidence))

    def test_ipv6_only_validated_disconnected_or_unknown_wifi_is_not_a_repair_candidate(self):
        for snapshot in (associated_wifi(address="fec0::1234/64"), associated_wifi(validated=True),
                         associated_wifi().replace("WIFI CONNECTED", "WIFI DISCONNECTED"),
                         associated_wifi().replace("InterfaceName: wlan0", "Unrecognized: wlan0"),
                         associated_wifi().replace("INTERNET&NOT_METERED", "INTERNET"), NO_NETWORK,
                         connected(transport="CELLULAR")):
            with self.subTest(snapshot=snapshot):
                self.assertIsNone(network.unvalidated_wifi(snapshot))

    def test_network_history_and_a_second_live_wifi_cannot_trigger_radio_reset(self):
        snapshot = associated_wifi()
        history = "Network Requests:\n  " + snapshot.split("Current Networks:\n")[1]
        self.assertIsNone(network.unvalidated_wifi(NO_NETWORK + history))
        self.assertIsNone(network.unvalidated_wifi(snapshot + snapshot.split("Current Networks:\n")[1]))


class HostDnsTest(unittest.TestCase):
    def resolve(self, upstream, system):
        with tempfile.TemporaryDirectory() as directory:
            paths = tuple(Path(directory) / name for name in ("upstream", "system"))
            for path, content in zip(paths, (upstream, system)):
                if content is not None:
                    path.write_text(content)
            return network.host_dns(paths)

    def test_uses_host_upstream_not_loopback_stub(self):
        self.assertEqual("168.63.129.16", self.resolve(
            "nameserver 168.63.129.16\n", "nameserver 127.0.0.53\n"))

    def test_non_systemd_host_and_private_upstream_are_supported(self):
        self.assertEqual("192.168.1.1", self.resolve(None, "nameserver 192.168.1.1 # router\n"))

    def test_rejects_non_routable_and_invalid_addresses_and_deduplicates(self):
        self.assertEqual("10.1.2.3,10.1.2.4,10.1.2.5", self.resolve(None, "\n".join(
            "nameserver " + value for value in ("garbage", "127.0.0.1", "::1", "0.0.0.0",
                "169.254.1.1", "224.0.0.1", "10.1.2.3", "10.1.2.3", "10.1.2.4", "10.1.2.5", "10.1.2.6"))))

    def test_missing_upstream_fails_instead_of_guessing_public_dns(self):
        with self.assertRaisesRegex(RuntimeError, "No upstream"):
            self.resolve(None, "nameserver 127.0.0.53\n")


class NetworkPreparationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.logs = Path(self.directory.name)
        self.now = 0
        # 失败CI的真实前置：SIM已加载，旧全局订阅键仍为null，但Wi-Fi可联网。
        self.responses = {
            SIM: ["LOADED"], SUB: ["null"], DATA: ["1"],
            WIFI: ["Wi-Fi is enabled"], CONNECTIVITY: [connected()],
        }
        self.effects = {}

    def shell(self, serial, *args, timeout=15):
        self.assertEqual(SERIAL, serial)
        self.assertGreater(timeout, 0)
        self.assertLessEqual(timeout, 15)
        if args in self.effects:
            self.effects[args]()
        values = self.responses.get(args, [""])
        value = values.pop(0) if len(values) > 1 else values[0]
        if isinstance(value, Exception):
            raise value
        return value() if callable(value) else value

    def sleep(self, seconds):
        self.now += seconds

    def run_prepare(self, failure=None, timeout=4):
        with patch.object(network, "shell", side_effect=self.shell) as shell, \
                patch.object(network.time, "monotonic", side_effect=lambda: self.now), \
                patch.object(network.time, "sleep", side_effect=self.sleep), \
                redirect_stdout(io.StringIO()):
            if failure:
                with self.assertRaisesRegex(RuntimeError, failure):
                    network.prepare_network(SERIAL, self.logs, timeout=timeout)
            else:
                network.prepare_network(SERIAL, self.logs, timeout=timeout)
            return [call(*value.args) for value in shell.call_args_list]

    def test_ready_wifi_does_not_depend_on_legacy_subscription_or_mobile_data(self):
        calls = self.run_prepare()
        self.assertEqual([
            call(SERIAL, *WIFI), call(SERIAL, *CONNECTIVITY),
        ], calls)
        self.assertEqual("Wi-Fi is enabled\n", (self.logs / "wifi-before-tests.log").read_text())
        self.assertIn("VALIDATED", (self.logs / "connectivity-before-tests.log").read_text())

    def test_sim_absent_or_subscription_unset_does_not_block_valid_wifi(self):
        for sim, sub in (("ABSENT", "-1"), ("NOT_READY", "null"), ("", "")):
            with self.subTest(sim=sim, sub=sub):
                self.responses[SIM] = [sim]
                self.responses[SUB] = [sub]
                calls = self.run_prepare()
                self.assertNotIn(call(SERIAL, *SUB), calls)
                self.assertNotIn(call(SERIAL, "svc", "data", "disable"), calls)

    def test_waits_for_wifi_to_replace_cellular_default_without_disabling_data(self):
        self.responses[WIFI] = ["Wi-Fi is enabling", "Wi-Fi is enabled"]
        self.responses[CONNECTIVITY] = [connected(transport="CELLULAR"), connected()]
        calls = self.run_prepare()
        self.assertEqual(2, calls.count(call(SERIAL, *CONNECTIVITY)))
        self.assertNotIn(call(SERIAL, "svc", "data", "disable"), calls)
        self.assertNotIn(call(SERIAL, "svc", "wifi", "disable"), calls)

    def test_initial_none_waits_for_an_actual_validated_network(self):
        self.responses[CONNECTIVITY] = [NO_NETWORK, connected("INTERNET&NOT_METERED"), connected()]
        calls = self.run_prepare()
        self.assertEqual(3, calls.count(call(SERIAL, *CONNECTIVITY)))

    def test_zero_exit_from_wifi_command_without_effect_fails(self):
        self.responses[WIFI] = ["Wi-Fi is disabled"]
        self.run_prepare("Verified active unmetered")
        self.assertEqual("Wi-Fi is disabled\n", (self.logs / "wifi-before-tests.log").read_text())

    def test_missing_wifi_service_cannot_pass_even_with_stale_capabilities(self):
        self.responses[WIFI] = ["Can't find service: wifi"]
        self.run_prepare("Verified active unmetered")

    def test_cellular_or_ethernet_default_cannot_pass_even_if_unmetered_and_validated(self):
        for transport in ("CELLULAR", "ETHERNET", "VPN"):
            with self.subTest(transport=transport):
                self.now = 0
                self.responses[CONNECTIVITY] = [connected(transport=transport)]
                self.run_prepare("Verified active unmetered")

    def test_metered_unvalidated_or_offline_default_fails_and_keeps_diagnostics(self):
        for caps in ("INTERNET&VALIDATED", "INTERNET&NOT_METERED", "NOT_METERED&VALIDATED"):
            with self.subTest(caps=caps):
                self.now = 0
                self.responses[CONNECTIVITY] = [connected(caps)]
                self.run_prepare("Verified active unmetered")
                self.assertIn(caps, (self.logs / "connectivity-before-tests.log").read_text())

    def test_network_never_becomes_ready_uses_bounded_timeout(self):
        self.responses[CONNECTIVITY] = [NO_NETWORK]
        calls = self.run_prepare("Verified active unmetered")
        self.assertEqual(4, self.now)
        self.assertEqual(4, calls.count(call(SERIAL, *CONNECTIVITY)))

    def test_adb_failure_is_not_suppressed_or_retried(self):
        for command in (("svc", "wifi", "enable"), WIFI, CONNECTIVITY):
            with self.subTest(command=command):
                self.responses[WIFI] = ["Wi-Fi is disabled"]
                self.responses[command] = [subprocess.TimeoutExpired("adb", 15)]
                with self.assertRaises(subprocess.TimeoutExpired):
                    self.run_prepare()
                self.responses.pop(command)

    def fault(self, *, healthy_after_reconnect=True):
        self.phase = "fault"
        self.responses.update({
            WIFI: [lambda: "Wi-Fi is disabled" if self.phase == "disabled" else "Wi-Fi is enabled"],
            CONNECTIVITY: [lambda: NO_NETWORK if self.phase == "disabled" else associated_wifi(
                netid=102 if self.phase == "reconnected" else 100,
                active=102 if self.phase == "reconnected" else 101,
                validated=self.phase == "reconnected" and healthy_after_reconnect)],
            ADDR: [KERNEL_ADDR], ROUTES: [KERNEL_LOCAL], RULES: [KERNEL_RULES],
        })
        self.effects[DISABLE] = lambda: setattr(self, "phase", "disabled")
        self.effects[ENABLE] = lambda: setattr(self, "phase", "reconnected")

    def test_missing_routes_reconnect_once_and_require_real_validated_wifi(self):
        self.fault()
        calls = self.run_prepare(timeout=90)
        self.assertEqual(1, calls.count(call(SERIAL, *DISABLE)))
        self.assertEqual(1, calls.count(call(SERIAL, *ENABLE)))
        self.assertGreaterEqual(self.now, 30)
        self.assertLess(self.now, 90)
        self.assertIn("missing=connected,default", (self.logs / "wifi-route-recovery.log").read_text())
        for name in ("wifi", "connectivity", "addr", "routes", "rules"):
            self.assertTrue((self.logs / f"wifi-route-recovery-{name}.log").is_file())
        self.assertIn("VALIDATED", (self.logs / "connectivity-before-tests.log").read_text())

    def test_healthy_routes_with_dns_failure_do_not_reconnect(self):
        self.fault()
        self.responses[ROUTES] = [KERNEL_LOCAL + KERNEL_DIRECT + KERNEL_DEFAULT]
        calls = self.run_prepare("Verified active unmetered", timeout=60)
        self.assertNotIn(call(SERIAL, *DISABLE), calls)
        self.assertNotIn(call(SERIAL, *ENABLE), calls)
        self.assertEqual(60, self.now)
        for name in ("addr", "routes", "rules"):
            self.assertTrue((self.logs / f"ipv4-{name}-before-tests.log").is_file())

    def test_one_reconnection_cannot_restart_timeout_or_bypass_validation(self):
        self.fault(healthy_after_reconnect=False)
        calls = self.run_prepare("Verified active unmetered", timeout=90)
        self.assertEqual(90, self.now)
        self.assertEqual(1, calls.count(call(SERIAL, *DISABLE)))
        self.assertEqual(1, calls.count(call(SERIAL, *ENABLE)))
        self.assertNotIn("VALIDATED", (self.logs / "connectivity-before-tests.log").read_text())

    def test_disable_must_release_the_old_network_before_reenable(self):
        self.fault()
        checks = []

        def connectivity():
            if self.phase == "disabled":
                checks.append(self.now)
                return associated_wifi() if len(checks) < 3 else NO_NETWORK
            return associated_wifi(netid=102, active=102, validated=True) if self.phase == "reconnected" else associated_wifi()

        def enable():
            self.assertGreaterEqual(len(checks), 3)
            self.phase = "reconnected"

        self.responses[CONNECTIVITY] = [connectivity]
        self.effects[ENABLE] = enable
        self.run_prepare(timeout=90)

    def test_ignored_disable_fails_within_original_deadline_without_reenable(self):
        self.fault()
        self.effects.pop(DISABLE)
        calls = self.run_prepare("Wi-Fi disabled and old network released", timeout=60)
        self.assertEqual(60, self.now)
        self.assertEqual(1, calls.count(call(SERIAL, *DISABLE)))
        self.assertNotIn(call(SERIAL, *ENABLE), calls)

    def test_wifi_validated_while_collecting_routes_is_not_restarted(self):
        self.fault()
        self.effects[RULES] = lambda: setattr(self, "phase", "reconnected")
        calls = self.run_prepare(timeout=60)
        self.assertNotIn(call(SERIAL, *DISABLE), calls)
        self.assertNotIn(call(SERIAL, *ENABLE), calls)
        self.assertFalse((self.logs / "wifi-route-recovery.log").exists())

    def test_changed_or_temporarily_healthy_wifi_gets_a_new_startup_grace_period(self):
        self.fault()

        def connectivity():
            if self.phase == "disabled":
                return NO_NETWORK
            if self.phase == "reconnected":
                return associated_wifi(netid=102, active=102, validated=True)
            # 中间的已验证Wi-Fi尚未成为默认网络，随后同一项再次失效。
            return associated_wifi(validated=15 <= self.now < 20)

        reset_times = []
        self.responses[CONNECTIVITY] = [connectivity]
        self.effects[DISABLE] = lambda: (reset_times.append(self.now), setattr(self, "phase", "disabled"))
        self.run_prepare(timeout=90)
        self.assertGreaterEqual(reset_times[0], 50)

    def test_unknown_or_ipv6_only_wifi_never_resets_and_retains_final_kernel_diagnostics(self):
        for snapshot in (NO_NETWORK, associated_wifi(address="fec0::1234/64"),
                         associated_wifi().replace("LinkAddresses:", "UnknownAddresses:")):
            with self.subTest(snapshot=snapshot):
                self.now = 0
                self.responses[CONNECTIVITY] = [snapshot]
                calls = self.run_prepare("Verified active unmetered", timeout=40)
                self.assertNotIn(call(SERIAL, *DISABLE), calls)
                self.assertTrue((self.logs / "ipv4-routes-before-tests.log").is_file())

    def test_route_diagnostic_adb_failure_is_not_ignored_or_retried(self):
        for command in (ADDR, ROUTES, RULES, DISABLE):
            with self.subTest(command=command):
                self.now = 0
                self.fault()
                self.responses[command] = [subprocess.CalledProcessError(1, "adb")]
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_prepare(timeout=60)

    def test_physical_device_is_never_modified(self):
        with patch.object(network, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "restricted"):
                network.prepare_network("phone-serial", self.logs)
            shell.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
