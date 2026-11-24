package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupOriginTest {
    @Test
    fun readsOnlyTheMetadataFromATruncatedBackup() {
        val origin = BackupOrigin("小米 Redmi \"K80\"", "0.2.3")
        val prefix = """{"backupOrigin":${origin.toJson()},"version":1,"library":{"playlists":["""
        assertEquals(origin, readBackupOrigin(prefix))
    }

    @Test
    fun readsMetadataAfterOtherBackupHeaderFields() {
        val prefix = """
            {
                "version": 1,
                "exportedAt": 1700000000000,
                "backupOrigin": {"appVersion": "0.2.3", "model": "Pixel 9"},
                "scripts": [
        """.trimIndent()
        assertEquals(BackupOrigin("Pixel 9", "0.2.3"), readBackupOrigin("\uFEFF" + prefix))
    }

    @Test
    fun leavesOldOrNestedMetadataUnlabeled() {
        assertNull(readBackupOrigin("""{"version":1,"settings":{}}"""))
        assertNull(readBackupOrigin("""{"settings":{"backupOrigin":{"model":"Pixel 9","appVersion":"0.2.3"}}}"""))
    }

    @Test
    fun leavesIncompleteOrInvalidMetadataUnlabeled() {
        val truncated = """{"backupOrigin":{"model":"Pixel 9","appVersion":"0.2"""
        assertNull(readBackupOrigin(truncated))
        assertNull(readBackupOrigin("""{"backupOrigin":{"model":"Pixel\u202e 9","appVersion":"0.2.3"}}"""))
        assertNull(readBackupOrigin("""{"backupOrigin":{"model":"Pixel 9","appVersion":"0.\n2.3"}}"""))
        assertNull(readBackupOrigin("""{"backupOrigin":{"model":"Pixel 9","appVersion":3}}"""))
        assertNull(readBackupOrigin("""{"backupOrigin":{"model":"${"x".repeat(97)}","appVersion":"0.2.3"}}"""))
    }
}
