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


def connected(capabilities="INTERNET&NOT_METERED&VALIDATED"):
    return ("Active default network: 100\nCurrent Networks:\n"
            "  NetworkAgentInfo{network{100} nc{[ Transports: WIFI Capabilities: "
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
        self.responses = {
            SIM: ["LOADED"], SUB: ["1"], DATA: ["0"],
            WIFI: ["Wi-Fi is disabled"], CONNECTIVITY: [NO_NETWORK, connected()],
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

    def test_waits_for_subscription_before_disabling_data_and_reads_back_result(self):
        self.responses[SIM] = ["NOT_READY", "LOADED", "LOADED"]
        self.responses[SUB] = ["-1", "-1", "1"]
        self.responses[DATA] = ["1", "0"]
        calls = self.run_prepare()
        disable = call(SERIAL, "svc", "data", "disable")
        self.assertEqual(3, calls[:calls.index(disable)].count(call(SERIAL, *SUB)))
        wifi_disable = call(SERIAL, "svc", "wifi", "disable")
        self.assertEqual(2, calls[:calls.index(wifi_disable)].count(call(SERIAL, *DATA)))
        self.assertEqual("0\n", (self.logs / "mobile-data-before-tests.log").read_text())

    def test_initial_none_does_not_mean_wifi_has_finished_disabling(self):
        self.responses[WIFI] = ["Wi-Fi is disabling", "Wi-Fi is disabled"]
        self.responses[CONNECTIVITY] = [NO_NETWORK, NO_NETWORK, connected()]
        calls = self.run_prepare()
        enabled_at = calls.index(call(SERIAL, "svc", "wifi", "enable"))
        self.assertEqual(2, calls[:enabled_at].count(call(SERIAL, *WIFI)))

    def test_zero_exit_from_data_command_without_effect_fails(self):
        self.responses[DATA] = ["1"]
        calls = self.run_prepare("Mobile data disabled")
        self.assertNotIn(call(SERIAL, "svc", "wifi", "enable"), calls)

    def test_missing_subscription_never_sends_data_command(self):
        self.responses[SUB] = ["-1"]
        calls = self.run_prepare("subscription ready")
        self.assertNotIn(call(SERIAL, "svc", "data", "disable"), calls)

    def test_missing_wifi_service_does_not_pass_on_initial_none(self):
        self.responses[WIFI] = ["Can't find service: wifi"]
        self.responses[CONNECTIVITY] = [NO_NETWORK]
        calls = self.run_prepare("Wi-Fi disabled")
        self.assertNotIn(call(SERIAL, "svc", "wifi", "enable"), calls)

    def test_metered_or_unvalidated_default_fails_and_keeps_diagnostics(self):
        for caps in ("INTERNET&VALIDATED", "INTERNET&NOT_METERED"):
            with self.subTest(caps=caps):
                self.now = 0
                self.responses[CONNECTIVITY] = [NO_NETWORK, connected(caps)]
                self.run_prepare("Verified active unmetered")
                self.assertIn(caps, (self.logs / "connectivity-before-tests.log").read_text())

    def test_adb_failure_is_not_suppressed_or_retried(self):
        self.responses[SIM] = [subprocess.TimeoutExpired("adb", 15)]
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_prepare()

    def test_physical_device_is_never_modified(self):
        with patch.object(network, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "restricted"):
                network.prepare_network("phone-serial", self.logs)
            shell.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
