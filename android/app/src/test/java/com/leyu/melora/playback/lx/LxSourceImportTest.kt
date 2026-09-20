package com.leyu.melora.playback.lx

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class LxSourceImportTest {
    @Test
    fun urlPolicyAllowsHttpAndHttpsWithoutCredentialsOrFragment() {
        assertTrue(LxSourceUrlPolicy.validate("https://example.test/source.js").isHttps)
        assertEquals("http", LxSourceUrlPolicy.validate("http://example.test/source.js").scheme)
        assertThrows(RuntimeException::class.java) { LxSourceUrlPolicy.validate("ftp://example.test/source.js") }
        assertThrows(RuntimeException::class.java) { LxSourceUrlPolicy.validate("https://user:pass@example.test/source.js") }
        assertThrows(RuntimeException::class.java) { LxSourceUrlPolicy.validate("https://example.test/source.js#fragment") }
    }

    @Test
    fun redirectPolicyRejectsHttpsDowngradeAndAllowsHttpContinuationOrUpgrade() {
        val secure = LxSourceUrlPolicy.validate("https://example.test/path/old.js")
        assertEquals(
            "https://example.test/new.js",
            LxSourceUrlPolicy.resolveRedirect(secure, "../new.js").toString(),
        )
        assertThrows(RuntimeException::class.java) {
            LxSourceUrlPolicy.resolveRedirect(secure, "http://example.test/new.js")
        }

        val cleartext = LxSourceUrlPolicy.validate("http://example.test/path/old.js")
        assertEquals(
            "http://example.test/new.js",
            LxSourceUrlPolicy.resolveRedirect(cleartext, "../new.js").toString(),
        )
        assertEquals(
            "https://example.test/new.js",
            LxSourceUrlPolicy.resolveRedirect(cleartext, "https://example.test/new.js").toString(),
        )
    }

    @Test
    fun boundedReaderRejectsActualPayloadOverLimit() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(MAX_IMPORTED_SCRIPT_BYTES + 1))
                .readBoundedLxSourceText(MAX_IMPORTED_SCRIPT_BYTES)
        }
        assertTrue(error.message.orEmpty().contains("512 KiB"))
        assertThrows(IllegalArgumentException::class.java) {
            requireLxSourceContentLength(MAX_IMPORTED_SCRIPT_BYTES.toLong() + 1, MAX_IMPORTED_SCRIPT_BYTES)
        }
    }

    @Test
    fun utf8NulAndHtmlValidationAreApplied() {
        assertEquals("你好", decodeLxSourceUtf8("你好".toByteArray()))
        assertEquals("const ok = true", decodeLxSourceUtf8("\uFEFFconst ok = true".toByteArray()))
        assertThrows(CharacterCodingException::class.java) {
            decodeLxSourceUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateImportedScriptCode("const value = '\u0000'")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateLxSourceDocument("<!doctype html><html></html>", MAX_IMPORTED_SCRIPT_BYTES)
        }
        assertThrows(IllegalArgumentException::class.java) {
            rejectHtmlContentType("text/html; charset=utf-8")
        }
    }

    @Test
    fun onlineFileNameIsStablePerLinkAndDistinctAcrossLinks() {
        val firstUrl = "https://one.example.test/files/source.js".toHttpUrl()
        val secondUrl = "https://two.example.test/files/source.js".toHttpUrl()
        val first = onlineSourceFileName(firstUrl)

        assertEquals(first, onlineSourceFileName(firstUrl))
        assertNotEquals(first, onlineSourceFileName(secondUrl))
        assertTrue(first.startsWith("source-"))
        assertTrue(first.endsWith(".js"))
        assertTrue(onlineSourceFileName("https://example.test/".toHttpUrl()).startsWith("online-source-"))
    }

    @Test
    fun bundleValidationRejectsCanonicalNameCollisionsBeforeImport() {
        val bundle = JSONObject().put("scripts", JSONArray()
            .put(JSONObject().put("name", "dir/source.js").put("code", "// first"))
            .put(JSONObject().put("name", "source.js").put("code", "// second")))

        val error = assertThrows(IllegalArgumentException::class.java) {
            parseLxSourceDocument(bundle.toString(), "bundle.json", MAX_LOCAL_SOURCE_DOCUMENT_BYTES)
        }
        assertTrue(error.message.orEmpty().contains("重名脚本"))
    }

    @Test
    fun bundleValidationRejectsSourceCountsOverTheLimit() {
        val scripts = JSONArray().apply {
            repeat(MAX_IMPORTED_SOURCE_COUNT + 1) { index ->
                put(JSONObject().put("name", "source-$index.js").put("code", "// source $index"))
            }
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            parseLxSourceDocument(
                JSONObject().put("scripts", scripts).toString(),
                "bundle.json",
                MAX_LOCAL_SOURCE_DOCUMENT_BYTES,
            )
        }
        assertTrue(error.message.orEmpty().contains("$MAX_IMPORTED_SOURCE_COUNT"))
    }

    @Test
    fun documentParserCanonicalizesNamesAndPreservesSingleScriptFallback() {
        val bundle = JSONObject().put("scripts", JSONArray()
            .put(JSONObject().put("name", "nested/source name").put("code", "// bundle")))
        assertEquals(
            listOf("source_name.js" to "// bundle"),
            parseLxSourceDocument(bundle.toString(), "ignored.json", MAX_LOCAL_SOURCE_DOCUMENT_BYTES),
        )
        assertEquals(
            listOf("single_name.js" to "// source"),
            parseLxSourceDocument("// source", "nested/single name", MAX_IMPORTED_SCRIPT_BYTES),
        )
    }
}
