package com.leyu.melora.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `selects highest stable android release with apk and ignores fpk`() {
        val releases = JSONArray()
            .put(release("v9.9.9", asset("Melora.fpk", "https://example.test/Melora.fpk")))
            .put(release("android-v1.3.0", asset("Melora.apk", "https://example.test/android.fpk?download=1")))
            .put(release("android-v1.2.0", asset("Melora.fpk", "https://example.test/android.fpk")))
            .put(release("android-v1.1.0", asset("Melora-arm64.apk", "https://example.test/android.apk")))
            .put(release("android-v1.0.9", asset("Melora.apk", "https://example.test/older.apk")))

        val selected = UpdateChecker.selectAndroidRelease(releases)

        assertEquals("android-v1.1.0", selected?.tagName)
        assertEquals("https://example.test/android.apk", selected?.downloadUrl)
        assertTrue(UpdateChecker.isVersionNewer(selected?.version.orEmpty(), "1.0.0"))
    }

    @Test
    fun `ignores draft prerelease and android release without apk`() {
        val releases = JSONArray()
            .put(release("android-v2.0.0", asset("Melora.apk", "https://example.test/draft.apk"), draft = true))
            .put(release("android-v1.9.0", asset("Melora.apk", "https://example.test/beta.apk"), prerelease = true))
            .put(release("android-v1.8.0", asset("Melora.fpk", "https://example.test/release.fpk")))

        assertNull(UpdateChecker.selectAndroidRelease(releases))
    }

    @Test
    fun `follows github repository pagination without trusting the link target`() {
        val linkHeader = """<https://api.github.com/repos/Raving4934/Melora/releases?per_page=100&page=2>; rel="next", <https://api.github.com/repos/Raving4934/Melora/releases?per_page=100&page=4>; rel="last"""

        assertEquals(2, UpdateChecker.nextPageFromLink(linkHeader, currentPage = 1))
        assertNull(UpdateChecker.nextPageFromLink(linkHeader, currentPage = 2))
        assertEquals(
            2,
            UpdateChecker.nextPageFromLink(
                "<https://api.github.com/repositories/1300192/releases?per_page=100&page=2>; rel=\"next\"",
                currentPage = 1,
            ),
        )
        assertNull(
            UpdateChecker.nextPageFromLink(
                "<https://api.github.com/repos/Raving4934/Melora/releases?page=2>; rel=\"last\"",
                currentPage = 1,
            ),
        )
        assertNull(
            UpdateChecker.nextPageFromLink(
                "<https://api.github.com/repos/other/repo/releases?per_page=100&page=2>; rel=\"next\"",
                currentPage = 1,
            ),
        )
        assertNull(
            UpdateChecker.nextPageFromLink(
                "<https://example.test/repos/Raving4934/Melora/releases?page=2>; rel=\"next\"",
                currentPage = 1,
            ),
        )
    }

    @Test
    fun `compares numeric versions without treating missing patch as newer`() {
        assertTrue(UpdateChecker.isVersionNewer("android-v1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isVersionNewer("android-v1.2", "1.2.0"))
        assertFalse(UpdateChecker.isVersionNewer("android-v0.1.0", "1.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "android-v1.0"))
    }

    private fun release(
        tag: String,
        asset: JSONObject,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("html_url", "https://example.test/$tag")
        .put("body", "notes")
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("assets", JSONArray().put(asset))

    private fun asset(name: String, url: String): JSONObject = JSONObject()
        .put("name", name)
        .put("browser_download_url", url)
}
