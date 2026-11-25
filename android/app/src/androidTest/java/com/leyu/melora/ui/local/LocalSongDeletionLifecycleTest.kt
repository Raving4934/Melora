package com.leyu.melora.ui.local

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真正重建 Activity，使用虚拟 URI 与便于观察的授权返回，避免删设备文件。 */
@RunWith(AndroidJUnit4::class)
class LocalSongDeletionLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val token = System.nanoTime().toString()
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$name-local-deletion-$token", mode)
    }
    private val controllerFields = listOf("appContext", "lastSavedQueue").map { name ->
        PlaybackController.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }
    @Suppress("UNCHECKED_CAST")
    private val mutablePlayerState = PlaybackController.javaClass.getDeclaredField("_state").apply {
        isAccessible = true
    }.get(PlaybackController) as MutableStateFlow<PlayerUiState>
    private lateinit var controllerValues: List<Any?>
    private lateinit var controllerState: PlayerUiState
    private lateinit var consentIntent: PendingIntent

    @Before fun saveFixtureState() {
        instrumentation.runOnMainSync {
            controllerValues = controllerFields.map { it.get(PlaybackController) }
            controllerState = PlaybackController.state.value
        }
        consentIntent = PendingIntent.getActivity(
            context, 41, Intent(context, ComponentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @After fun restoreFixtureState() {
        instrumentation.runOnMainSync {
            controllerFields.zip(controllerValues).forEach { (field, value) -> field.set(PlaybackController, value) }
            mutablePlayerState.value = controllerState
        }
        consentIntent.cancel()
    }

    @Test fun confirmedSystemBatchIsConsumedByTheSameOwnerAfterActivityRecreation() =
        recreateAndApprove(systemDelete = true)

    @Test fun recoverableGrantRetriesTheSameFileAfterActivityRecreation() =
        recreateAndApprove(systemDelete = false)

    @Test fun authorizationRestoredWithoutAProcessBatchDoesNotRepeatAnAndroid10Delete() = runBlocking {
        val state = SavedStateHandle()
        val operations = FixtureOperations(systemDelete = false)
        val before = LocalSongDeletionViewModel(context, state, operations)
        val target = target("restored")
        instrumentation.runOnMainSync { before.start(listOf(target)) }
        awaitAuthorization(before)
        instrumentation.runOnMainSync { assertSame(operations.request, before.takeAuthorizationRequest()) }

        val replacement = LocalSongDeletionViewModel(context, state, operations)
        assertEquals(LocalDeletionStep.RestoredAuthorization, replacement.step.value)
        assertEquals(listOf(false), state.keys().map { state.get<Boolean>(it) })
        instrumentation.runOnMainSync {
            replacement.onAuthorizationResult(approved = true)
            replacement.onAuthorizationResult(approved = true)
        }
        assertEquals(LocalDeletionStep.Ignored, replacement.step.value)
        assertTrue(state.keys().isEmpty())
        assertEquals(listOf(target), operations.attempts)
        assertEquals("授权已确认，请重新选择要删除的文件", PlaybackController.state.value.message)
    }

    private fun recreateAndApprove(systemDelete: Boolean) = runBlocking {
        val operations = FixtureOperations(systemDelete)
        val state = SavedStateHandle()
        val factory = viewModelFactory {
            initializer { LocalSongDeletionViewModel(context, state, operations) }
        }
        val targets = if (systemDelete) listOf(target("first"), target("second")) else listOf(target("retry"))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var before: LocalSongDeletionViewModel
            scenario.onActivity { activity ->
                before = ViewModelProvider.create(activity, factory)[LocalSongDeletionViewModel::class]
                before.start(targets)
            }
            awaitAuthorization(before)
            scenario.onActivity {
                assertSame(operations.request, before.takeAuthorizationRequest())
                assertNull(before.takeAuthorizationRequest())
            }

            scenario.recreate()
            scenario.onActivity { activity ->
                val retained = ViewModelProvider.create(activity, factory)[LocalSongDeletionViewModel::class]
                assertSame(before, retained)
                assertEquals(LocalDeletionStep.WaitingForSystem, retained.step.value)
                retained.onAuthorizationResult(approved = true)
                retained.onAuthorizationResult(approved = true)
            }
            val done = withTimeout(5_000) { before.step.first { it is LocalDeletionStep.Finished } } as LocalDeletionStep.Finished
            assertEquals(targets, done.result.deleted)
            assertTrue(done.result.failed.isEmpty())
            assertTrue(state.keys().isEmpty())
            if (systemDelete) {
                assertEquals(listOf(targets), operations.systemBatches)
                assertTrue(operations.attempts.isEmpty())
            } else assertEquals(targets + targets, operations.attempts)
        }
    }

    private suspend fun awaitAuthorization(viewModel: LocalSongDeletionViewModel) {
        withTimeout(5_000) { viewModel.step.first { it is LocalDeletionStep.Awaiting } }
    }

    private fun target(id: String) = LocalDeletionTarget("fixture-$token-$id", "content://fixture.local-deletion/$token/$id")

    private inner class FixtureOperations(systemDelete: Boolean) : LocalDeletionOperations<IntentSenderRequest> {
        val request = IntentSenderRequest.Builder(consentIntent.intentSender).build()
        val attempts = mutableListOf<LocalDeletionTarget>()
        val systemBatches = mutableListOf<List<LocalDeletionTarget>>()
        override val supportsSystemDeleteRequest = systemDelete
        override fun isMediaStore(target: LocalDeletionTarget) = true

        override suspend fun delete(target: LocalDeletionTarget): LocalDeletionAttempt<IntentSenderRequest> {
            attempts += target
            return if (attempts.size == 1) LocalDeletionAttempt.NeedsAuthorization(request) else LocalDeletionAttempt.Deleted
        }

        override fun createSystemDeleteRequest(targets: List<LocalDeletionTarget>): IntentSenderRequest {
            systemBatches += targets
            return request
        }
    }
}
