package com.leyu.melora.playback.lx

import org.junit.Assert.assertEquals
import org.junit.Test

class LxScriptStorePolicyTest {
    @Test
    fun metadataParserProvidesOneCanonicalViewForStoreAndEngine() {
        val metadata = parseLxScriptMetadata(
            """
            // @name First source
            // @version 1.2.3
            // @description  Test description
            // @author Melora
            // @homepage https://example.test/source
            // @name Ignored duplicate
            """.trimIndent(),
        )

        assertEquals("First source", metadata["name"])
        assertEquals("Test description", metadata["description"])
        assertEquals("1.2.3", metadata["version"])
        assertEquals("Melora", metadata["author"])
        assertEquals("https://example.test/source", metadata["homepage"])
    }

    @Test
    fun enablingOneSourceDisablesEveryOtherSource() {
        assertEquals(
            setOf("source-a.js"),
            singleSelectEnabledIds(
                installedIds = listOf("source-a.js", "source-b.js", "source-c.js"),
                currentlyEnabledIds = listOf("source-b.js"),
                targetId = "source-a.js",
                targetEnabled = true,
            ),
        )
    }

    @Test
    fun disablingTheLastEnabledSourceLeavesAllSourcesDisabled() {
        assertEquals(
            emptySet<String>(),
            singleSelectEnabledIds(
                installedIds = listOf("source-a.js", "source-b.js"),
                currentlyEnabledIds = listOf("source-a.js"),
                targetId = "source-a.js",
                targetEnabled = false,
            ),
        )
    }

    @Test
    fun disablingASourceDoesNotEnableAnotherSource() {
        assertEquals(
            setOf("source-b.js"),
            singleSelectEnabledIds(
                installedIds = listOf("source-a.js", "source-b.js"),
                currentlyEnabledIds = listOf("source-a.js", "source-b.js"),
                targetId = "source-a.js",
                targetEnabled = false,
            ),
        )
    }

    @Test
    fun missingTargetIsANoOpAndPreservesExistingEnabledSources() {
        val installed = listOf("source-a.js", "source-b.js")
        val enabled = listOf("source-a.js")

        assertEquals(
            setOf("source-a.js"),
            singleSelectEnabledIds(installed, enabled, "deleted.js", targetEnabled = true),
        )
        assertEquals(
            setOf("source-a.js"),
            singleSelectEnabledIds(installed, enabled, "deleted.js", targetEnabled = false),
        )
    }

    @Test
    fun sequentialBackupRestoreKeepsOnlyTheLastEnabledEntry() {
        val installed = listOf("source-a.js", "source-b.js", "source-c.js")
        var enabled = emptySet<String>()

        enabled = singleSelectEnabledIds(installed, enabled, "source-a.js", true)
        enabled = singleSelectEnabledIds(installed, enabled, "source-b.js", true)
        enabled = singleSelectEnabledIds(installed, enabled, "source-c.js", false)

        assertEquals(setOf("source-b.js"), enabled)
    }
}
