package com.leyu.melora.playback

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupScheduleTest {
    @Test
    fun scheduleUsesFullDaySinceSuccessAndHandlesClockRollback() {
        val hour = 60 * 60 * 1000L
        assertEquals(0L, backupDelay(0L, 24 * hour))
        assertEquals(12 * hour, backupDelay(2 * hour, 14 * hour))
        assertEquals(0L, backupDelay(hour, 25 * hour))
        assertEquals(0L, backupDelay(3 * hour, 2 * hour))
    }

    @Test
    fun partialBackupDoesNotRepeatCompletedOrSupersededDestination() {
        val selected = setOf(BackupDestination.LOCAL, BackupDestination.NETWORK)
        assertEquals(listOf(BackupDestination.NETWORK), pendingBackupTargets(selected, selected) { it == BackupDestination.LOCAL })
        val stillDue = setOf(BackupDestination.NETWORK)
        assertEquals(listOf(BackupDestination.NETWORK), pendingBackupTargets(selected, stillDue) { false })
        assertEquals(emptyList<BackupDestination>(), pendingBackupTargets(selected, stillDue) { it == BackupDestination.NETWORK })
    }

    @Test
    fun localPruningRecognizesOnlyManagedNames() {
        val id = BackupFileId(1_000L, "deadbeef", UUID.randomUUID())
        assertEquals(id, BackupFileId.parseLocalName(id.localName))
        assertNull(BackupFileId.parseLocalName(id.networkName))
        assertNull(BackupFileId.parseLocalName("melora-local-backup-1000-deadbeef.json"))
        assertNull(BackupFileId.parseLocalName("${id.localName}.txt"))
    }
}
