import unittest
from verify_public_repository import violations


class PublicBoundaryTest(unittest.TestCase):
    def test_public_product_code_is_allowed(self):
        for name in ['apps/server/main.go', 'apps/web/src/App.tsx', 'README.md', '.github/workflows/release.yml']:
            self.assertEqual(violations(name, b'public source'), [])

    def test_private_source_and_build_files_are_rejected(self):
        for name in ['android/README.md', 'copied/Private.kt', 'app/build.gradle.kts', 'app/Private.java', 'gradlew', '.github/workflows/android-release.yml']:
            self.assertTrue(violations(name, b''), name)

    def test_signing_and_binary_material_is_rejected(self):
        for name in ['key.jks', 'key.p12', 'key.keystore', 'release.apk', 'release.aab', 'mapping.txt', 'app/local.properties']:
            self.assertTrue(violations(name, b''), name)

    def test_tokens_and_keys_are_rejected_without_exposing_values(self):
        for value in [b'ghp_' + b'x' * 36, b'github_pat_' + b'y' * 50, b'-----BEGIN ' + b'PRIVATE KEY-----']:
            self.assertEqual(violations('README.md', value), ['credential-like material'])

    def test_placeholder_names_are_allowed(self):
        self.assertEqual(violations('README.md', b'GH_TOKEN MELORA_DISTRIBUTION_TOKEN'), [])


if __name__ == '__main__':
    unittest.main()
