package com.leyu.melora.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseManifestTest {
    @Test
    fun parsesTheSingleSourceManifestAndFiltersBuildOnlyEntries() {
        val manifest = LicenseManifest.parse(
            """
            {
              "licenseTexts": {
                "Apache-2.0": {"name":"Apache License 2.0", "text":"apache"},
                "MIT": {"name":"MIT License", "text":"mit"}
              },
              "entries": [
                {
                  "id":"runtime",
                  "name":"Runtime",
                  "scope":"android-runtime",
                  "showInAbout":true,
                  "licenseIds":["Apache-2.0"],
                  "description":"runtime dependency",
                  "notices":["notice"],
                  "sourcePaths":["android/app/build.gradle.kts"]
                },
                {
                  "id":"build-tool",
                  "name":"Build tool",
                  "scope":"build-tool",
                  "showInAbout":false,
                  "licenseIds":["MIT"],
                  "description":"not packaged",
                  "notices":[]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("runtime"), manifest.aboutEntries.map(LicenseEntry::id))
        assertEquals("apache", manifest.textFor("Apache-2.0").text)
        assertEquals(listOf("android/app/build.gradle.kts"), manifest.entries.first().sourcePaths)
        assertTrue(manifest.entries.first().showInAbout)
        assertFalse(manifest.entries.last().showInAbout)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEntryThatReferencesAnUnknownLicense() {
        LicenseManifest.parse(
            """
            {
              "licenseTexts": {"MIT": {"name":"MIT", "text":"mit"}},
              "entries": [{
                "id":"broken",
                "name":"Broken",
                "scope":"android-runtime",
                "showInAbout":true,
                "licenseIds":["Unknown"],
                "description":"broken",
                "notices":[]
              }]
            }
            """.trimIndent(),
        )
    }
}
