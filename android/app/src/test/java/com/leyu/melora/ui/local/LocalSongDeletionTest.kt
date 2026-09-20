package com.leyu.melora.ui.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSongDeletionTest {
    @Test
    fun android10AuthorizationRetriesTheSameUriBeforeMarkingItDeleted() {
        val first = target("1")
        val second = target("2")
        val operations = FakeOperations(
            outcomes = mapOf(first to LocalDeletionAttempt.NeedsAuthorization("recoverable-1"), second to LocalDeletionAttempt.Deleted),
            retryResults = mapOf(first to true),
        )
        val deletion = LocalSongDeletion(operations)

        val awaiting = deletion.start(listOf(first, second))
        assertEquals(
            LocalDeletionRequest.Recoverable(first, "recoverable-1"),
            (awaiting as LocalDeletionStep.Awaiting).request,
        )
        assertTrue(deletion.isBusy)

        val finished = deletion.onAuthorizationResult(approved = true) as LocalDeletionStep.Finished
        assertEquals(listOf(first, second), finished.result.deleted)
        assertTrue(finished.result.failed.isEmpty())
        assertEquals(listOf(first, first, second), operations.deleteCalls)
        assertFalse(deletion.isBusy)
    }

    @Test
    fun android10BatchProcessesMultipleRecoverableItemsInOriginalOrder() {
        val first = target("1")
        val second = target("2")
        val third = target("3")
        val operations = FakeOperations(
            outcomes = mapOf(
                first to LocalDeletionAttempt.NeedsAuthorization("recoverable-1"),
                second to LocalDeletionAttempt.NeedsAuthorization("recoverable-2"),
                third to LocalDeletionAttempt.Deleted,
            ),
            retryResults = mapOf(first to true, second to true),
        )
        val deletion = LocalSongDeletion(operations)

        val firstRequest = deletion.start(listOf(first, second, third)) as LocalDeletionStep.Awaiting
        assertEquals(first, firstRequest.request.targets.single())
        val secondRequest = deletion.onAuthorizationResult(true) as LocalDeletionStep.Awaiting
        assertEquals(second, secondRequest.request.targets.single())
        val finished = deletion.onAuthorizationResult(true) as LocalDeletionStep.Finished

        assertEquals(listOf(first, second, third), finished.result.deleted)
        assertEquals(listOf(first, first, second, second, third), operations.deleteCalls)
    }

    @Test
    fun cancellingAuthorizationDoesNotReportAnyUnattemptedTargetAsDeleted() {
        val first = target("1")
        val second = target("2")
        val operations = FakeOperations(
            outcomes = mapOf(first to LocalDeletionAttempt.NeedsAuthorization("recoverable-1"), second to LocalDeletionAttempt.Deleted),
        )
        val deletion = LocalSongDeletion(operations)

        deletion.start(listOf(first, second))
        val cancelled = deletion.onAuthorizationResult(approved = false) as LocalDeletionStep.Finished

        assertTrue(cancelled.result.cancelled)
        assertTrue(cancelled.result.deleted.isEmpty())
        assertEquals(listOf(first, second), cancelled.result.failed)
        assertEquals(listOf(first), operations.deleteCalls)
    }

    @Test
    fun partialFailuresRemainVisibleWithoutInflatingDeletedCount() {
        val first = target("1")
        val second = target("2")
        val third = target("3")
        val operations = FakeOperations(
            outcomes = mapOf(
                first to LocalDeletionAttempt.Deleted,
                second to LocalDeletionAttempt.Failed,
                third to LocalDeletionAttempt.Deleted,
            ),
        )
        val finished = LocalSongDeletion(operations).start(listOf(first, second, third)) as LocalDeletionStep.Finished

        assertEquals(listOf(first, third), finished.result.deleted)
        assertEquals(listOf(second), finished.result.failed)
        assertFalse(finished.result.cancelled)
    }

    @Test
    fun duplicateIdsOrUrisAreDeletedOnlyOnceAndBusyRepeatIsIgnored() {
        val first = target("1", "content://media/1")
        val sameId = target("1", "content://media/other")
        val sameUri = target("2", "content://media/1")
        val second = target("2", "content://media/2")
        val operations = FakeOperations(
            outcomes = mapOf(first to LocalDeletionAttempt.NeedsAuthorization("recoverable-1"), second to LocalDeletionAttempt.Deleted),
            retryResults = mapOf(first to true),
        )
        val deletion = LocalSongDeletion(operations)

        deletion.start(listOf(first, sameId, sameUri, second))
        assertEquals(LocalDeletionStep.Busy, deletion.start(listOf(first)))
        val finished = deletion.onAuthorizationResult(true) as LocalDeletionStep.Finished

        assertEquals(listOf(first, second), finished.result.deleted)
        assertEquals(listOf(first, first, second), operations.deleteCalls)
    }

    @Test
    fun android11SystemRequestOnlyDeletesAfterSystemConfirmation() {
        val first = target("1", "content://media/1")
        val second = target("2", "content://media/2")
        val operations = FakeOperations(
            supportsSystemDeleteRequest = true,
            mediaStoreTargets = setOf(first, second),
        )
        val deletion = LocalSongDeletion(operations)

        val awaiting = deletion.start(listOf(first, second)) as LocalDeletionStep.Awaiting
        assertEquals(LocalDeletionRequest.System(listOf(first, second), "system-request"), awaiting.request)
        assertTrue(operations.deleteCalls.isEmpty())

        val cancelled = deletion.onAuthorizationResult(false) as LocalDeletionStep.Finished
        assertTrue(cancelled.result.cancelled)
        assertTrue(cancelled.result.deleted.isEmpty())

        val confirmedDeletion = LocalSongDeletion(operations)
        val confirmed = confirmedDeletion.start(listOf(first, second)) as LocalDeletionStep.Awaiting
        assertEquals(LocalDeletionRequest.System(listOf(first, second), "system-request"), confirmed.request)
        val finished = confirmedDeletion.onAuthorizationResult(true) as LocalDeletionStep.Finished
        assertEquals(listOf(first, second), finished.result.deleted)
    }

    @Test
    fun authorizationRetryFailureKeepsFileOutOfDeletedResults() {
        val item = target("1")
        val operations = FakeOperations(
            outcomes = mapOf(item to LocalDeletionAttempt.NeedsAuthorization("retry")),
            retryResults = mapOf(item to false),
        )
        val deletion = LocalSongDeletion(operations)
        deletion.start(listOf(item))
        val result = (deletion.onAuthorizationResult(true) as LocalDeletionStep.Finished).result
        assertTrue(result.deleted.isEmpty())
        assertEquals(listOf(item), result.failed)
        assertEquals(listOf(item, item), operations.deleteCalls)
        assertEquals(LocalDeletionStep.Ignored, deletion.onAuthorizationResult(true))
        assertEquals(listOf(item, item), operations.deleteCalls)
    }

    @Test
    fun cancellingAfterPartialSuccessPreservesCompletedResults() {
        val done = target("1")
        val pending = target("2")
        val operations = FakeOperations(outcomes = mapOf(
            done to LocalDeletionAttempt.Deleted,
            pending to LocalDeletionAttempt.NeedsAuthorization("retry"),
        ))
        val deletion = LocalSongDeletion(operations)
        deletion.start(listOf(done, pending))
        val result = (deletion.onAuthorizationResult(false) as LocalDeletionStep.Finished).result
        assertEquals(listOf(done), result.deleted)
        assertEquals(listOf(pending), result.failed)
        assertTrue(result.cancelled)
    }

    @Test
    fun deniedAgainAfterAuthorizationDoesNotLoopOrDropRemainingItems() {
        val denied = target("1")
        val other = target("2")
        val operations = FakeOperations(outcomes = mapOf(
            denied to LocalDeletionAttempt.NeedsAuthorization("retry"),
            other to LocalDeletionAttempt.Deleted,
        ))
        val deletion = LocalSongDeletion(operations)
        deletion.start(listOf(denied, other))
        val result = (deletion.onAuthorizationResult(true) as LocalDeletionStep.Finished).result
        assertEquals(listOf(other), result.deleted)
        assertEquals(listOf(denied), result.failed)
        assertEquals(listOf(denied, denied, other), operations.deleteCalls)
    }

    @Test
    fun systemRequestCreationFailureDoesNotLoseRemainingDirectFiles() {
        val media = target("1", "content://media/1")
        val direct = target("2")
        val operations = FakeOperations(
            supportsSystemDeleteRequest = true,
            mediaStoreTargets = setOf(media),
            outcomes = mapOf(direct to LocalDeletionAttempt.Deleted),
            systemRequest = null,
        )
        val result = (LocalSongDeletion(operations).start(listOf(media, direct)) as LocalDeletionStep.Finished).result
        assertEquals(listOf(direct), result.deleted)
        assertEquals(listOf(media), result.failed)
    }

    private class FakeOperations(
        override val supportsSystemDeleteRequest: Boolean = false,
        private val mediaStoreTargets: Set<LocalDeletionTarget> = emptySet(),
        private val outcomes: Map<LocalDeletionTarget, LocalDeletionAttempt<String>> = emptyMap(),
        private val retryResults: Map<LocalDeletionTarget, Boolean> = emptyMap(),
        private val systemRequest: String? = "system-request",
    ) : LocalDeletionOperations<String> {
        val deleteCalls = mutableListOf<LocalDeletionTarget>()
        override fun isMediaStore(target: LocalDeletionTarget): Boolean = target in mediaStoreTargets

        override fun delete(target: LocalDeletionTarget): LocalDeletionAttempt<String> {
            deleteCalls += target
            val repeated = deleteCalls.count { it == target } > 1
            if (repeated && target in retryResults) {
                return if (retryResults[target] == true) LocalDeletionAttempt.Deleted else LocalDeletionAttempt.Failed
            }
            return outcomes[target] ?: LocalDeletionAttempt.Failed
        }

        override fun createSystemDeleteRequest(targets: List<LocalDeletionTarget>): String? = systemRequest
    }

    private fun target(id: String, uri: String = "file:///music/$id.mp3") = LocalDeletionTarget(id, uri)
}
