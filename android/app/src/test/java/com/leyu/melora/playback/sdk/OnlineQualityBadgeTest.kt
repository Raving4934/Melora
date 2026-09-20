package com.leyu.melora.playback.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineQualityBadgeTest {
    @Test
    fun listCapabilitiesChooseHighestDeclaredBadgeWithoutResolvingAUrl() {
        val song = OnlineSong(
            JSONObject()
                .put("source", "kw")
                .put("songmid", "1")
                .put("name", "Song")
                .put("_types", JSONObject().put("320k", JSONObject()).put("flac24bit", JSONObject())),
        )

        assertEquals(listOf("320k", "flac24bit").toSet(), song.qualitys.toSet())
        assertEquals("HR", song.bestQualityBadge)
    }

    @Test
    fun masterAndLegacyTypesArrayUseTheSharedBadgeVocabulary() {
        assertEquals("MASTER", qualityBadgeForCapabilities(listOf("flac", "master")))
        val legacy = OnlineSong(
            JSONObject()
                .put("source", "tx")
                .put("songmid", "2")
                .put("name", "Song")
                .put("types", JSONArray().put(JSONObject().put("type", "320k"))),
        )
        assertEquals("HQ", legacy.bestQualityBadge)
        assertNull(qualityBadgeForQuality("192k"))
    }
}
