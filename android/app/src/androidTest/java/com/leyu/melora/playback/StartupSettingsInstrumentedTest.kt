package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.startup.resolveStartupTab
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真实持久化验证只使用测试专属 prefs，不覆盖应用或用户的设置文件。 */
@RunWith(AndroidJUnit4::class)
class StartupSettingsInstrumentedTest {
    private val prefsField = MeloraSettings.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
    private val initializedField = MeloraSettings.javaClass.getDeclaredField("initialized").apply { isAccessible = true }
    private lateinit var context: StartupContext
    private var previousPrefs: Any? = null
    private var previousInitialized = false
    private lateinit var previousStates: List<Pair<MutableStateFlow<Any>, Any>>

    @Before
    @Suppress("UNCHECKED_CAST")
    fun setUp() {
        context = StartupContext(InstrumentationRegistry.getInstrumentation().targetContext)
        previousPrefs = prefsField.get(null)
        previousInitialized = initializedField.getBoolean(null)
        previousStates = MeloraSettings.javaClass.declaredFields
            .filter { it.type == MutableStateFlow::class.java }
            .map { field ->
                val state = field.apply { isAccessible = true }.get(null) as MutableStateFlow<Any>
                state to state.value
            }
        prefsField.set(null, context.settingsPrefs)
        initializedField.setBoolean(null, false)
    }

    @After
    fun tearDown() {
        prefsField.set(null, previousPrefs)
        initializedField.setBoolean(null, previousInitialized)
        previousStates.forEach { (state, value) -> state.value = value }
        context.cleanup()
    }

    @Test
    fun legacyPreferencesWithoutTheSwitchDefaultToEnabledAndKeepTheLastTab() {
        assertTrue(context.settingsPrefs.edit().putInt(MeloraSettings.KEY_LAST_TAB, 4).commit())
        MeloraSettings.init(context)

        assertTrue(MeloraSettings.restoreLastPage.value)
        assertEquals(4, MeloraSettings.lastTab.value)
        assertEquals(4, initialTab(hasSavedState = false))
    }

    @Test
    fun booleanUpdatesAndNavigationSurviveSettingsReinitialization() {
        MeloraSettings.init(context)
        for (enabled in listOf(false, true)) {
            MeloraSettings.updateRestoreLastPage(enabled)
            MeloraSettings.updateLastTab(2)
            assertEquals(enabled, context.settingsPrefs.all[MeloraSettings.KEY_RESTORE_LAST_PAGE])
            assertEquals(2, context.settingsPrefs.getInt(MeloraSettings.KEY_LAST_TAB, -1))
            MeloraSettings.restoreLastPage.value = !enabled
            MeloraSettings.lastTab.value = 5
            initializedField.setBoolean(null, false)
            MeloraSettings.init(context)

            assertEquals(enabled, MeloraSettings.restoreLastPage.value)
            assertEquals(2, MeloraSettings.lastTab.value)
            assertEquals(if (enabled) 2 else 5, initialTab(hasSavedState = false))
            assertEquals(2, initialTab(hasSavedState = true))
        }
    }

    @Test
    fun backupImportPersistsBothBooleanValuesAndTheLastTabTogether() {
        MeloraSettings.init(context)
        for (enabled in listOf(false, true)) {
            MeloraSettings.updateRestoreLastPage(enabled)
            MeloraSettings.updateLastTab(7)
            val backup = JSONObject(BackupSettings.collect().toString())
            MeloraSettings.updateRestoreLastPage(!enabled)
            MeloraSettings.updateLastTab(5)
            val prepared = BackupSettings.prepare(backup, emptySet(), emptySet()).first
            BackupSettings.apply(prepared)

            assertEquals(enabled, context.settingsPrefs.all[MeloraSettings.KEY_RESTORE_LAST_PAGE])
            assertEquals(7, context.settingsPrefs.getInt(MeloraSettings.KEY_LAST_TAB, -1))
            MeloraSettings.restoreLastPage.value = !enabled
            MeloraSettings.lastTab.value = 5
            MeloraSettings.reloadAfterRestore()
            assertEquals(enabled, MeloraSettings.restoreLastPage.value)
            assertEquals(7, MeloraSettings.lastTab.value)
        }
    }

    @Test
    fun importingAnOldBackupDoesNotResetOrRemoveTheSwitch() {
        MeloraSettings.init(context)
        for (enabled in listOf(false, true)) {
            MeloraSettings.updateRestoreLastPage(enabled)
            val prepared = BackupSettings.prepare(
                JSONObject().put("lastTab", 3), emptySet(), emptySet(),
            ).first
            assertFalse(prepared.has("restoreLastPage"))
            BackupSettings.apply(prepared)
            MeloraSettings.reloadAfterRestore()

            assertEquals(enabled, context.settingsPrefs.all[MeloraSettings.KEY_RESTORE_LAST_PAGE])
            assertEquals(enabled, MeloraSettings.restoreLastPage.value)
            assertEquals(3, MeloraSettings.lastTab.value)
        }
    }

    @Test
    fun audioCacheCapacityDefaultsToOneGigabyteAndPersistsManualValues() {
        MeloraSettings.init(context)
        assertEquals(1024, MeloraSettings.maxCacheMb.value)
        for (mb in listOf(256, 8704)) {
            MeloraSettings.updateMaxCacheMb(mb)
            assertEquals(mb, context.settingsPrefs.getInt(MeloraSettings.KEY_MAX_CACHE_MB, -1))
            MeloraSettings.maxCacheMb.value = 1024
            initializedField.setBoolean(null, false)
            MeloraSettings.init(context)
            assertEquals(mb, MeloraSettings.maxCacheMb.value)
        }
    }

    private fun initialTab(hasSavedState: Boolean): Int = resolveStartupTab(
        explicitTab = null,
        lastTab = MeloraSettings.lastTab.value,
        restoreLastPage = MeloraSettings.restoreLastPage.value,
        hasSavedInstanceState = hasSavedState,
    )

    private class StartupContext(base: Context) : ContextWrapper(base) {
        private val prefix = "isolated-startup-settings-tests.${UUID.randomUUID()}."
        val settingsPrefs: SharedPreferences
            get() = getSharedPreferences(MeloraSettings.PREFS, Context.MODE_PRIVATE)

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)

        fun cleanup() {
            // 同步此前 apply 写入后只删除这个测试实例的设置文件。
            check(settingsPrefs.edit().commit())
            super.deleteSharedPreferences(prefix + MeloraSettings.PREFS)
        }
    }
}
