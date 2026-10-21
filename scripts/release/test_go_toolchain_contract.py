"""Keep Go runtime pins aligned across CI, releases, containers, and npm tooling."""

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GO_VERSION = "1.27.3"
WORKFLOW_PATHS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/release.yml",
)


class GoToolchainContractTest(unittest.TestCase):
    def test_local_module_prefers_the_same_toolchain_as_ci(self) -> None:
        module = (ROOT / "apps/server/go.mod").read_text()
        self.assertRegex(module, rf"(?m)^toolchain go{re.escape(GO_VERSION)}$")

    def test_each_setup_go_step_uses_the_pinned_patch(self) -> None:
        for path in WORKFLOW_PATHS:
            with self.subTest(workflow=path.name):
                workflow = path.read_text()
                setup_steps = re.findall(r"(?m)^      - uses: actions/setup-go@", workflow)
                versions = re.findall(
                    r"(?m)^\s+go-version:\s*['\"]?([0-9]+\.[0-9]+\.[0-9]+)['\"]?\s*$",
                    workflow,
                )
                self.assertTrue(setup_steps, f"No setup-go steps in {path}")
                self.assertEqual(len(versions), len(setup_steps), path)
                self.assertEqual(set(versions), {GO_VERSION}, path)

    def test_ci_runs_this_contract_test_as_a_gate(self) -> None:
        ci_workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertRegex(
            ci_workflow,
            r"(?m)^      - name: Test Go toolchain contract\n"
            r"        run: python3 -B scripts/release/test_go_toolchain_contract\.py$",
        )

    def test_docker_builder_uses_the_pinned_patch(self) -> None:
        dockerfile = (ROOT / "Dockerfile").read_text()
        versions = re.findall(r"(?m)^FROM golang:([^\s]+)", dockerfile)
        self.assertEqual(versions, [f"{GO_VERSION}-bookworm"])

    def test_npm_vulnerability_entrypoint_uses_the_pinned_patch(self) -> None:
        package = json.loads((ROOT / "package.json").read_text())
        command = package["scripts"]["vuln:server"]
        match = re.search(r"(?:^|\s)GOTOOLCHAIN=go([0-9]+\.[0-9]+\.[0-9]+)(?:\s|$)", command)
        self.assertIsNotNone(match, "vuln:server must explicitly pin its Go toolchain")
        self.assertEqual(match.group(1), GO_VERSION)
        self.assertIn("govulncheck@v1.9.0", command)


if __name__ == "__main__":
    unittest.main()
