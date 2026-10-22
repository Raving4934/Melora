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
NO_NETWORK = "Active default network: none\nCurrent Networks:\n"


def connected(capabilities="INTERNET&NOT_METERED&VALIDATED", transport="WIFI"):
    return ("Active default network: 100\nCurrent Networks:\n"
            "  NetworkAgentInfo{network{100} nc{[ Transports: " + transport + " Capabilities: "
            + capabilities + " ]}}\n")


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

    def shell(self, serial, *args):
        self.assertEqual(SERIAL, serial)
        values = self.responses.get(args, [""])
        value = values.pop(0) if len(values) > 1 else values[0]
        if isinstance(value, Exception):
            raise value
        return value

    def sleep(self, seconds):
        self.now += seconds

    def run_prepare(self, failure=None):
        with patch.object(network, "shell", side_effect=self.shell) as shell, \
                patch.object(network.time, "monotonic", side_effect=lambda: self.now), \
                patch.object(network.time, "sleep", side_effect=self.sleep), \
                redirect_stdout(io.StringIO()):
            if failure:
                with self.assertRaisesRegex(RuntimeError, failure):
                    network.prepare_network(SERIAL, self.logs, timeout=4)
            else:
                network.prepare_network(SERIAL, self.logs, timeout=4)
            return shell.call_args_list

    def test_ready_wifi_does_not_depend_on_legacy_subscription_or_mobile_data(self):
        calls = self.run_prepare()
        self.assertEqual([
            call(SERIAL, "svc", "wifi", "enable"),
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
                self.responses[command] = [subprocess.TimeoutExpired("adb", 15)]
                with self.assertRaises(subprocess.TimeoutExpired):
                    self.run_prepare()
                self.responses.pop(command)

    def test_physical_device_is_never_modified(self):
        with patch.object(network, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "restricted"):
                network.prepare_network("phone-serial", self.logs)
            shell.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
