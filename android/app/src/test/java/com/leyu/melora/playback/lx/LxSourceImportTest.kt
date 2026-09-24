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

    @Test
    fun completeJsonMustBeAnObjectWithAScriptsArray() {
        listOf(
            "{\"ordinary\":true}",
            "[1,2]",
            "\"plain text\"",
            "42",
        ).forEach(::assertRejectedDocument)
    }

    @Test
    fun scriptsMustBeANonEmptyArrayOfValidObjects() {
        listOf(
            "{\"scripts\":null}",
            "{\"scripts\":\"not-an-array\"}",
            "{\"scripts\":1}",
            "{\"scripts\":{}}",
            "{\"scripts\":[]}",
            "{\"scripts\":[{\"code\":\"// valid\"},\"not-an-object\"]}",
            "{\"scripts\":[{\"code\":\"// valid\"},{}]}",
            "{\"scripts\":[{\"code\":\"// valid\"},{\"code\":17}]}",
            "{\"scripts\":[{\"code\":\"// valid\"},{\"code\":null}]}",
            "{\"scripts\":[{\"code\":\"// valid\"},{\"code\":\"\"}]}",
            "{\"scripts\":[{\"code\":\"// valid\"},{\"code\":\"   \"}]}",
        ).forEach(::assertRejectedDocument)
    }

    @Test
    fun providedBundleNameMustBeAStringButMissingOrEmptyNamesUseIndexedFallbacks() {
        listOf(
            "{\"scripts\":[{\"name\":7,\"code\":\"// source\"}]}",
            "{\"scripts\":[{\"name\":null,\"code\":\"// source\"}]}",
        ).forEach(::assertRejectedDocument)

        assertEquals(
            listOf("source-0.js" to "// missing", "source-1.js" to "// empty"),
            parseLxSourceDocument(
                "{\"scripts\":[{\"code\":\"// missing\"},{\"name\":\"\",\"code\":\"// empty\"}]}",
                "ignored.json",
                MAX_LOCAL_SOURCE_DOCUMENT_BYTES,
            ),
        )
    }

    @Test
    fun maximumAllowedBundleCountIsAccepted() {
        val scripts = JSONArray().apply {
            repeat(MAX_IMPORTED_SOURCE_COUNT) { index ->
                put(JSONObject().put("name", "source-$index.js").put("code", "// source $index"))
            }
        }

        assertEquals(
            MAX_IMPORTED_SOURCE_COUNT,
            parseLxSourceDocument(
                JSONObject().put("scripts", scripts).toString(),
                "bundle.json",
                MAX_LOCAL_SOURCE_DOCUMENT_BYTES,
            ).size,
        )
    }

    @Test
    fun jsonLookingPrefixesFollowedByJsRemainRawScripts() {
        val rawObjectBlock = "{ const value = 1; }\nconst marker = true;"
        val rawArrayExpression = "[];\nconst marker = true;"
        val bundleJsonFollowedByJs =
            "{\"scripts\":[{\"code\":\"// bundle\"}]}\nconst marker = true;"

        assertEquals(
            listOf("object.js" to rawObjectBlock),
            parseLxSourceDocument(rawObjectBlock, "object.js", MAX_IMPORTED_SCRIPT_BYTES),
        )
        assertEquals(
            listOf("array.js" to rawArrayExpression),
            parseLxSourceDocument(rawArrayExpression, "array.js", MAX_IMPORTED_SCRIPT_BYTES),
        )
        assertEquals(
            listOf("bundle.js" to bundleJsonFollowedByJs),
            parseLxSourceDocument(bundleJsonFollowedByJs, "bundle.js", MAX_IMPORTED_SCRIPT_BYTES),
        )
    }

    @Test
    fun leadingBomIsRemovedByBoundedReaderBeforeBundleParsing() {
        val bundle = JSONObject().put("scripts", JSONArray()
            .put(JSONObject().put("code", "// source")))
        val text = ByteArrayInputStream("\uFEFF${bundle}".toByteArray(Charsets.UTF_8))
            .readBoundedLxSourceText(MAX_LOCAL_SOURCE_DOCUMENT_BYTES)

        assertEquals(
            listOf("source-0.js" to "// source"),
            parseLxSourceDocument(text, "bundle.json", MAX_LOCAL_SOURCE_DOCUMENT_BYTES),
        )
    }

    @Test
    fun unquotedJavaScriptIsNotMisclassifiedByLenientJsonTokener() {
        val code = "globalThis.loadSource()"
        assertEquals(listOf("source.js" to code), parseLxSourceDocument(code, "source.js", MAX_IMPORTED_SCRIPT_BYTES))
    }

    private fun assertRejectedDocument(document: String) {
        assertThrows(IllegalArgumentException::class.java) {
            parseLxSourceDocument(document, "fallback.js", MAX_LOCAL_SOURCE_DOCUMENT_BYTES)
        }
    }
}
