#!/usr/bin/env python3
"""Offline tests for the public repository boundary."""

from __future__ import annotations

from pathlib import Path
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

    def test_xml_security_configuration_identifiers_are_not_network_endpoints(self) -> None:
        identifiers = [
            b"http://apache.org/xml/features/disallow-doctype-decl",
            b"http://xml.org/sax/features/external-general-entities",
            b"http://xml.org/sax/features/external-parameter-entities",
            b"http://apache.org/xml/features/nonvalidating/load-external-dtd",
            b"http://javax.xml.XMLConstants/property/accessExternalDTD",
            b"http://javax.xml.XMLConstants/property/accessExternalSchema",
        ]
        name = "android/app/src/main/java/com/example/Parser.kt"
        for uri in identifiers:
            with self.subTest(uri=uri):
                self.assertEqual(violations(name, b'factory.setFeature("' + uri + b'", false)'), [])
                for suffix in [b"/audio.mp3", b"?download=1", b"#audio", b"Extra"]:
                    self.assertIn(
                        "insecure HTTP endpoint in production source",
                        violations(name, b'val endpoint = "' + uri + suffix + b'"'),
                    )
        self.assertIn(
            "insecure HTTP endpoint in production source",
            violations(name, b'val feature = "' + identifiers[0] + b'"; val url = "http://apache.org/audio.mp3"'),
        )

    def test_ttml_namespace_identifiers_are_not_network_endpoints(self) -> None:
        name = "android/app/src/main/java/com/leyu/melora/playback/EmbeddedLyrics.kt"
        content = (Path(__file__).resolve().parents[2] / name).read_bytes()
        self.assertEqual(violations(name, content), [])
        self.assertIn(
            "insecure HTTP endpoint in production source",
            violations(name, content + b'\nval endpoint = "http://www.w3.org/ns/ttml/audio.mp3"'),
        )

    def test_real_lyric_parser_keeps_xml_security_identifiers_without_exempting_file(self) -> None:
        name = "android/app/src/main/java/com/leyu/melora/playback/LyricParser.kt"
        content = (Path(__file__).resolve().parents[2] / name).read_bytes()
        self.assertEqual(violations(name, content), [])
        self.assertIn(
            "insecure HTTP endpoint in production source",
            violations(name, content + b'\nval endpoint = "http://media.example.invalid/audio.mp3"'),
        )

    def test_placeholder_names_are_allowed(self) -> None:
        self.assertEqual(violations("README.md", b"GH_TOKEN GITHUB_TOKEN PUBLIC_TOKEN"), [])

if __name__ == "__main__":
    unittest.main(verbosity=2)
