#!/usr/bin/env python3
"""Lightweight offline checks for the single APK license manifest."""

from __future__ import annotations

import fnmatch
import json
import re
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "android/app/src/main/assets/licenses/manifest.json"
ASSET_ROOT = ROOT / "android/app/src/main/assets"
APP_BUILD = ROOT / "android/app/build.gradle.kts"
SDK_BUILD = ROOT / "android/tools/sdk/build.mjs"
ABOUT = ROOT / "android/app/src/main/java/com/leyu/melora/ui/settings/AboutDialogs.kt"
PACKAGE_LOCK = ROOT / "android/tools/sdk/package-lock.json"


@unittest.skipUnless(MANIFEST_PATH.is_file(), "Android tree is not integrated into the public repository yet")
class LicenseManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        cls.entries = cls.manifest["entries"]
        cls.by_id = {entry["id"]: entry for entry in cls.entries}

    def test_manifest_is_the_about_page_source_and_contains_full_license_texts(self) -> None:
        self.assertEqual(self.manifest["schemaVersion"], 1)
        self.assertEqual(len(self.by_id), len(self.entries))
        self.assertIn("licenses/manifest.json", self.manifest["policy"]["replacementAsset"])
        self.assertIn("legalComments:none", self.manifest["policy"]["bundleLegalComments"])
        self.assertIn("/META-INF/{AL2.0,LGPL2.1}", self.manifest["policy"]["metaInfExclusions"])

        for entry in self.entries:
            self.assertTrue(entry["licenseIds"], entry["id"])
            self.assertTrue(entry["description"], entry["id"])
            self.assertTrue(entry["notices"], entry["id"])
            for license_id in entry["licenseIds"]:
                text = self.manifest["licenseTexts"][license_id]["text"]
                self.assertGreater(len(text), 100, f"short license text: {entry['id']} -> {license_id}")

    def test_every_manifest_path_and_packaged_asset_exists(self) -> None:
        for entry in self.entries:
            for relative in entry.get("sourcePaths", []):
                self.assertTrue((ROOT / relative).exists(), f"missing source evidence: {relative}")
            for relative in entry.get("projectPaths", []):
                self.assertTrue((ROOT / relative).exists(), f"missing project evidence: {relative}")
            for relative in entry.get("assetPaths", []):
                self.assertTrue((ASSET_ROOT / relative).is_file(), f"missing packaged asset: {relative}")

        self.assertTrue(MANIFEST_PATH.is_file())
        self.assertTrue((ASSET_ROOT / "sdk/music-sdk.js").is_file())

    def test_about_page_reads_manifest_without_a_second_static_license_list(self) -> None:
        source = ABOUT.read_text(encoding="utf-8")
        self.assertIn("LicenseManifest.load(context)", source)
        self.assertNotIn("LicenseItem(", source)
        self.assertIn("乐屿 · Melora 遵循自由开源精神开发，特此致敬并列举项目所依赖的核心开源基础库：", source)

    def test_declared_runtime_dependencies_are_covered(self) -> None:
        patterns = [pattern for entry in self.entries for pattern in entry.get("artifactPatterns", [])]
        dependencies = declared_runtime_dependencies()
        self.assertIn("androidx.core:core-ktx", dependencies)
        self.assertIn("dev.chrisbanes.haze:haze", dependencies)
        self.assertIn("io.coil-kt.coil3:coil-compose", dependencies)
        self.assertIn("com.squareup.okhttp3:okhttp", dependencies)
        self.assertIn("project:quickjs-android", dependencies)
        for coordinate in dependencies:
            if coordinate.startswith("project:"):
                self.assertTrue(
                    any(coordinate.removeprefix("project:") in path for entry in self.entries for path in entry.get("projectPaths", [])),
                    coordinate,
                )
            else:
                self.assertTrue(any(fnmatch.fnmatchcase(coordinate, pattern) for pattern in patterns), coordinate)

    def test_resolved_dependency_verification_is_a_build_gate(self) -> None:
        source = APP_BUILD.read_text(encoding="utf-8")
        self.assertIn("incoming.resolutionResult.allComponents", source)
        self.assertIn('"releaseRuntimeClasspath"', source)
        self.assertIn('tasks.named("preBuild") { dependsOn(verifyBundledLicenses) }', source)
        self.assertIn("check(missing.isEmpty())", source)
        self.assertIn("modules.toSet() == reviewed", source)
        self.assertGreater(len(self.manifest["resolvedModules"]), 100)
        self.assertEqual(len(self.manifest["resolvedModules"]), len(set(self.manifest["resolvedModules"])))

    def test_js_package_lock_is_covered(self) -> None:
        package_entries = [entry for entry in self.entries if entry.get("packages")]
        package_patterns = [(pattern, entry) for entry in package_entries for pattern in entry["packages"]]
        bundle = (ASSET_ROOT / "sdk/music-sdk.js").read_text(encoding="utf-8")
        package_lock = json.loads(PACKAGE_LOCK.read_text(encoding="utf-8"))
        runtime_packages = []
        for package_path, metadata in package_lock["packages"].items():
            if not package_path.startswith("node_modules/"):
                continue
            package_name = package_path.removeprefix("node_modules/")
            matches = [entry for pattern, entry in package_patterns if fnmatch.fnmatchcase(package_name, pattern)]
            self.assertTrue(matches, package_name)
            if not metadata.get("dev", False):
                runtime_packages.append(package_name)
                self.assertTrue(any(entry["scope"] == "packaged-js" for entry in matches), package_name)
                self.assertIn(f"node_modules/{package_name}", bundle, package_name)

        expected_runtime_packages = sorted(
            package
            for entry in self.entries
            if entry.get("scope") == "packaged-js"
            for package in entry.get("packages", [])
            if "*" not in package
        )
        self.assertEqual(sorted(runtime_packages), expected_runtime_packages)

    def test_packaging_and_bundle_checks_point_to_the_manifest(self) -> None:
        app_build = APP_BUILD.read_text(encoding="utf-8")
        sdk_build = SDK_BUILD.read_text(encoding="utf-8")
        self.assertIn('resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"', app_build)
        self.assertIn("assets/licenses/manifest.json", app_build)
        self.assertIn("legalComments: 'none'", sdk_build)
        self.assertIn("licenseManifestFile", sdk_build)
        self.assertIn("validateLicenseManifest", sdk_build)


def version_catalog() -> dict[str, str]:
    catalog = (ROOT / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
    return {
        alias: f"{group}:{artifact}"
        for alias, group, artifact in re.findall(
            r"^([A-Za-z0-9-]+)\s*=\s*\{\s*group\s*=\s*\"([^\"]+)\",\s*name\s*=\s*\"([^\"]+)\"",
            catalog,
            flags=re.MULTILINE,
        )
    }


def declared_runtime_dependencies() -> set[str]:
    aliases = version_catalog()
    coordinates: set[str] = set()
    for gradle_file in (ROOT / "android/app/build.gradle.kts", ROOT / "android/quickjs-android/build.gradle.kts"):
        for line in gradle_file.read_text(encoding="utf-8").splitlines():
            if not re.search(r"\b(?:implementation|api|runtimeOnly|debugImplementation)\s*\(", line):
                continue
            for accessor in re.findall(r"libs(?:\.[A-Za-z0-9_]+)+", line):
                alias = accessor.removeprefix("libs.").replace(".", "-")
                if alias in aliases:
                    coordinates.add(aliases[alias])
            for project in re.findall(r'project\(\":([^\"]+)\"\)', line):
                coordinates.add(f"project:{project}")
    return coordinates



if __name__ == "__main__":
    unittest.main()
