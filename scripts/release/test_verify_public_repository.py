#!/usr/bin/env python3
"""Offline tests for the public repository boundary."""

from __future__ import annotations

import unittest

from verify_public_repository import violations

class PublicBoundaryTest(unittest.TestCase):
    def test_public_android_sources_gradle_and_workflows_are_allowed(self) -> None:
        for name in [
            "android/app/src/main/java/com/example/MainActivity.kt",
            "android/app/src/main/java/com/example/Fixture.java",
            "android/app/build.gradle.kts",
            "android/gradlew",
            ".github/workflows/android-release.yml",
            "README.md",
        ]:
            self.assertEqual(violations(name, b"public source"), [], name)

    def test_signing_and_runtime_material_is_rejected(self) -> None:
        for name in [
            "android/app/release.jks",
            "android/app/local.properties",
            "android/app/build/outputs/apk/release/app-release.apk",
            ".superpowers/plans/release.md",
            "data/melora.db",
            "runtime/session.json",
        ]:
            self.assertTrue(violations(name, b""), name)

    def test_tokens_and_keys_are_rejected_without_exposing_values(self) -> None:
        for value in [
            b"ghp_" + b"x" * 36,
            b"github_pat_" + b"y" * 50,
            b"-----BEGIN " + b"PRIVATE KEY-----",
            b"AKIA" + b"A" * 16,
        ]:
            self.assertEqual(violations("README.md", value), ["credential-like material"])

    def test_mocked_test_responses_are_allowed(self) -> None:
        content = b'{"url":"http://cdn.test/song.flac","format":"flac","fixtureId":"sample"}'
        self.assertEqual(
            violations("android/app/src/test/java/com/example/ResolverTest.kt", content),
            [],
        )

    def test_music_sdk_and_metadata_are_not_false_positives(self) -> None:
        self.assertEqual(
            violations(
                "android/tools/sdk/src/musicSdk/kw/songList.js",
                b'http://wapi.kuwo.cn/list?format=json MUSIC_9.0.5.0_W1',
            ),
            [],
        )
        self.assertEqual(
            violations(
                "android/app/src/main/assets/licenses/manifest.json",
                b"Apache License http://www.apache.org/licenses/",
            ),
            [],
        )

    def test_literal_external_http_in_android_production_is_rejected(self) -> None:
        self.assertIn(
            "insecure HTTP endpoint in production source",
            violations(
                "android/app/src/main/java/com/example/Resolver.kt",
                b'val endpoint = "http://media.example.invalid/audio.mp3"',
            ),
        )

    def test_placeholder_names_are_allowed(self) -> None:
        self.assertEqual(violations("README.md", b"GH_TOKEN GITHUB_TOKEN PUBLIC_TOKEN"), [])

if __name__ == "__main__":
    unittest.main(verbosity=2)
