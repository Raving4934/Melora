package com.leyu.melora.ui.playlist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistPageRequestsTest {
    @Test fun cachedBackgroundRefreshCannotOverlapPageThree() = runBlocking {
        val requests = PlaylistPageRequests()
        val response = CompletableDeferred<Unit>()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            val request = checkNotNull(requests.begin(PlaylistLoad.Initial))
            try { response.await() } finally { requests.finish(request) }
        }
        assertNull(requests.begin(PlaylistLoad.More))
        assertNull(requests.begin(PlaylistLoad.Refresh))
        response.complete(Unit)
        refresh.await()
        assertNotNull(requests.begin(PlaylistLoad.More))
    }

    @Test fun inFlightMoreCannotBeResetByPullRefresh() {
        val requests = PlaylistPageRequests()
        val more = checkNotNull(requests.begin(PlaylistLoad.More))
        assertNull(requests.begin(PlaylistLoad.Refresh))
        assertNull(requests.begin(PlaylistLoad.Initial))
        assertTrue(requests.isCurrent(more))
        requests.finish(more)
        assertNotNull(requests.begin(PlaylistLoad.Refresh))
    }

    @Test fun cancelledRequestReleasesTheOnlyLoadingState() = runBlocking {
        val requests = PlaylistPageRequests()
        val response = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            val request = checkNotNull(requests.begin(PlaylistLoad.Refresh))
            try { response.await() } finally { requests.finish(request) }
        }
        job.cancelAndJoin()
        assertNull(requests.active)
        assertNotNull(requests.begin(PlaylistLoad.More))
    }

    @Test fun oldFinallyCannotReleaseANewerRequest() {
        val requests = PlaylistPageRequests()
        val old = checkNotNull(requests.begin(PlaylistLoad.More))
        requests.finish(old)
        val current = checkNotNull(requests.begin(PlaylistLoad.Refresh))
        requests.finish(old)
        assertFalse(requests.isCurrent(old))
        assertTrue(requests.isCurrent(current))
    }

    @Test fun leavingFilterRevokesOldResponseAndCannotBeReopened() {
        val requests = PlaylistPageRequests()
        val old = checkNotNull(requests.begin(PlaylistLoad.More))
        requests.close()
        assertFalse(requests.isCurrent(old))
        assertNull(requests.begin(PlaylistLoad.Initial))
        val newFilter = PlaylistPageRequests()
        val current = checkNotNull(newFilter.begin(PlaylistLoad.Initial))
        requests.finish(old)
        assertTrue(newFilter.isCurrent(current))
    }
}
