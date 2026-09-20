package com.leyu.melora.playback

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupDocumentTest {
    @Test
    fun malformedNestedLibraryEntriesAreRejectedInsteadOfBecomingAnEmptyLibrary() {
        for (library in listOf(
            JSONObject().put("favorites", JSONArray().put(42)),
            JSONObject().put("recents", JSONArray().put(JSONObject().put("name", "missing-id"))),
            JSONObject().put("favoritePlaylists", JSONArray().put(JSONObject().put("id", "x"))),
            JSONObject().put("favoriteArtists", JSONArray().put(JSONObject())),
            JSONObject().put("searchHistory", JSONArray().put(JSONObject())),
            JSONObject().put("playlists", JSONArray().put(JSONObject().put("id", "x").put("name", "list"))),
        )) rejected { parseBackupDocument(JSONObject().put("library", library).toString()) }
    }

    @Test
    fun invalidScriptsAndCanonicalNameCollisionsFailBeforeRestore() {
        for (scripts in listOf(
            JSONArray().put(JSONObject().put("fileName", "broken.js")),
            JSONArray().put(JSONObject().put("code", " ")),
            JSONArray().put(JSONObject().put("code", "// source").put("enabled", JSONObject())),
            JSONArray().put(script("dir/a.js")).put(script("a.js")),
            JSONArray().put(script("bad-origin.js").put("originUrl", "ftp://example.test/source.js")),
            JSONArray().put(script("bad-origin-type.js").put("originUrl", JSONObject())),
        )) rejected { parseBackupDocument(JSONObject().put("scripts", scripts).toString()) }
    }

    @Test
    fun linkedSourceOriginsRoundTripWhileLegacyBackupsRemainReadable() {
        val linked = script("linked.js").put("originUrl", "https://example.test/source.js")
        val parsed = parseBackupDocument(JSONObject().put("scripts", JSONArray().put(linked)).toString())
        assertEquals("https://example.test/source.js", parsed.scripts!!.single().originUrl)

        val legacy = parseBackupDocument(
            JSONObject().put("scripts", JSONArray().put(script("legacy.js"))).toString(),
        )
        assertNull(legacy.scripts!!.single().originUrl)
    }

    @Test
    fun legacySectionsAndNumericSongIdsRemainReadable() {
        val library = JSONObject().put("favorites", JSONArray().put(
            JSONObject().put("source", "kw").put("songmid", 42).put("name", "test")))
        for (value in listOf(library, library.toString())) {
            val result = parseBackupDocument(JSONObject().put("library", value)
                .put("scripts", JSONArray().put(JSONObject().put("code", "// legacy"))).toString())
            assertEquals("42", JSONObject(result.library!!).getJSONArray("favorites").getJSONObject(0).optString("songmid"))
            assertEquals(BackupScript("restored.js", "// legacy", true), result.scripts!!.single())
        }
    }

    @Test
    fun wrongVersionsAndNonBackupDocumentsAreRejected() {
        for (text in listOf("{}", "[]", "{\"version\":true,\"library\":{}}", "{\"settings\":{}} trailing", "{\"scripts\":null}")) {
            rejected { parseBackupDocument(text) }
        }
    }

    @Test
    fun nestingIsRejectedBeforeJsonTreeConstruction() {
        val deep = "{\"library\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        rejected { parseBackupDocument(deep) }
        // Quotes/braces in script text are not JSON structure.
        val code = "// " + "{[}".repeat(500)
        assertEquals(code, parseBackupDocument(JSONObject().put("scripts", JSONArray().put(script("x.js", code))).toString()).scripts!!.single().code)
    }

    @Test
    fun denseTinyObjectsAreRejectedBeforeAllocatingTheJsonTree() {
        val text = "{\"settings\":{\"unused\":[" + "{},".repeat(260_000) + "{}]}}"
        try { parseBackupDocument(text); fail("dense input accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("结构条目")) }
    }

    @Test
    fun inputLimitAppliesDuringReadEvenWithoutAProviderLength() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { consumed++; return 'x'.code }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                consumed += len; buffer.fill('x'.code.toByte(), off, off + len); return len
            }
        }
        rejected { stream.readBackupText(limit = 1024) }
        assertEquals(1025, consumed)
        assertEquals("hello", ByteArrayInputStream("hello".toByteArray()).readBackupText(limit = 5))
    }

    @Test
    fun invalidUtf8IsNotSilentlyReplacedInSourceCode() {
        try { ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)).readBackupText(); fail("expected invalid UTF-8") }
        catch (_: CharacterCodingException) { }
    }

    @Test
    fun exportEncodingIsBoundedAndProducesValidRoundTripJson() {
        val root = JSONObject().put("text", "中文\n\"\\test").put("array", JSONArray().put(true).put(JSONObject.NULL).put(1.5))
        val encoded = boundedBackupJson(root, 1024)
        assertEquals(root.getString("text"), JSONObject(encoded).getString("text"))
        rejected { boundedBackupJson(root, 10) }
    }

    @Test
    fun completeSettingsCatalogIncludesPreviouslyMissingPreferences() {
        val settings = BackupSettings.collect()
        for (name in listOf("playlistTagId", "playlistTagName", "playlistSort", "lyricBackground", "notificationLyrics")) assertTrue(name, settings.has(name))
        assertFalse(settings.has("unknownObjectSetting"))
        assertFalse(settings.has("unknownArraySetting"))
        assertFalse(settings.has("followSystemTheme"))
    }

    @Test
    fun unknownFieldsAreIgnoredWhileKnownSettingsRemainReadable() {
        val parsed = parseBackupDocument(JSONObject().put("settings", JSONObject()
            .put("unknownObjectSetting", JSONObject())
            .put("unknownArraySetting", JSONArray())
            .put("showExitButton", false)
            .put("dataChannel", "app")).toString())
        val prepared = BackupSettings.prepare(parsed.settings!!, emptySet(), emptySet()).first

        assertFalse(prepared.has("unknownObjectSetting"))
        assertFalse(prepared.has("unknownArraySetting"))
        assertEquals(false, prepared.getBoolean("showExitButton"))
        assertEquals("app", prepared.getString("dataChannel"))
    }

    @Test
    fun everyPortableSettingsStateHasExactlyOneExportField() {
        val fields = MeloraSettings::class.java.declaredFields.filter {
            kotlinx.coroutines.flow.MutableStateFlow::class.java.isAssignableFrom(it.type)
        }
        assertEquals(fields.map { it.name }.toString(), fields.size, BackupSettings.collect().length())
    }

    @Test
    fun invalidSettingsRejectTheWholeDocumentWithoutPublishingEarlierFields() {
        val before = MeloraSettings.showExitButton.value
        for ((key, value) in listOf("downloadConcurrentTasks" to -1, "lastTab" to 30, "lyricAlpha" to 200, "localFolders" to JSONArray().put(42))) {
            val invalid = JSONObject().put("showExitButton", !before).put(key, value)
            rejected { parseBackupDocument(JSONObject().put("settings", invalid).toString()) }
            assertEquals(before, MeloraSettings.showExitButton.value)
        }
    }

    private fun script(name: String, code: String = "// script") = JSONObject().put("fileName", name).put("code", code)
    private fun rejected(block: () -> Any?) {
        try { block(); fail("invalid backup accepted") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
}
