package com.leyu.melora.playback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdateValidationTest {
    private val installed = ApkIdentity("com.leyu.melora", 15, setOf("signer-a", "signer-b"))

    @Test fun onlySamePackageNewerVersionAndExactNonemptySignerSetPass() {
        validateApkIdentity(installed, installed.copy(versionCode = 16, signers = setOf("signer-b", "signer-a")))
        listOf(
            installed.copy(packageName = "other", versionCode = 16),
            installed.copy(versionCode = 14),
            installed.copy(versionCode = 15),
            installed.copy(versionCode = 16, signers = emptySet()),
            installed.copy(versionCode = 16, signers = setOf("signer-a")),
            installed.copy(versionCode = 16, signers = setOf("signer-a", "different")),
        ).forEach { candidate ->
            assertTrue(runCatching { validateApkIdentity(installed, candidate) }.exceptionOrNull() is IllegalStateException)
        }
        assertTrue(runCatching {
            validateApkIdentity(installed.copy(signers = emptySet()), installed.copy(versionCode = 16, signers = emptySet()))
        }.isFailure)
    }

    @Test fun knownAndUnknownLengthReportBytesWithoutInventingTotal() {
        listOf(500_000L, null).forEach { total ->
            val reports = mutableListOf<Pair<Long, Long?>>()
            val output = ByteArrayOutputStream()
            val bytes = copyUpdateApk(ByteArrayInputStream(ByteArray(500_000)), output, total, {}, { count, size -> reports += count to size })
            assertEquals(500_000L, bytes)
            assertEquals(500_000, output.size())
            assertEquals(0L to total, reports.first())
            assertEquals(500_000L to total, reports.last())
            assertTrue(reports.all { it.second == total })
        }
    }

    @Test fun emptyTruncatedAndOversizedTransfersAreRejected() {
        listOf(0 to null, 0 to 0L, 4 to 8L, 8 to 4L).forEach { (bytes, total) ->
            assertTrue(runCatching {
                copyUpdateApk(ByteArrayInputStream(ByteArray(bytes)), ByteArrayOutputStream(), total, {}, { _, _ -> })
            }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun cancellationStopsCopyBeforeCompletion() {
        var checks = 0
        val output = ByteArrayOutputStream()
        val failure = runCatching {
            copyUpdateApk(ByteArrayInputStream(ByteArray(500_000)), output, 500_000, {
                if (++checks == 4) throw CancellationException("cancel")
            }, { _, _ -> })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(output.size() < 500_000)
    }
}
