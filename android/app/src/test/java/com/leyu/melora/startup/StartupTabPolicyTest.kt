package com.leyu.melora.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTabPolicyTest {
    @Test
    fun enabledRestoreKeepsEveryLastTabOnANewLaunch() {
        for (tab in 0..7) {
            assertEquals(tab, resolveStartupTab(null, tab, restoreLastPage = true, hasSavedInstanceState = false))
        }
    }

    @Test
    fun disabledRestoreDefaultsOnlyANewLaunchToLocalSongs() {
        for (tab in 0..7) {
            assertEquals(5, resolveStartupTab(null, tab, restoreLastPage = false, hasSavedInstanceState = false))
        }
    }

    @Test
    fun allStartupPagesApplyOnlyOnFreshLaunchWithoutAnExplicitTab() {
        assertEquals((0..6).toList(), StartupPage.entries.map { it.tab })
        for (page in StartupPage.entries) {
            assertEquals(page.tab, resolveStartupTab(null, 7, restoreLastPage = false, hasSavedInstanceState = false, startupPage = page))
            assertEquals(7, resolveStartupTab(null, 7, restoreLastPage = true, hasSavedInstanceState = false, startupPage = page))
            assertEquals(7, resolveStartupTab(null, 7, restoreLastPage = false, hasSavedInstanceState = true, startupPage = page))
            assertEquals(2, resolveStartupTab(2, 7, restoreLastPage = false, hasSavedInstanceState = false, startupPage = page))
        }
    }

    @Test
    fun explicitNavigationWinsRegardlessOfRestoreSettingOrSavedState() {
        for (tab in 0..7) {
            for (restore in listOf(false, true)) {
                for (savedState in listOf(false, true)) {
                    assertEquals(tab, resolveStartupTab(tab, 5, restore, savedState))
                }
            }
        }
    }

    @Test
    fun activityRecreationKeepsTheCurrentTabEvenWhenRestoreIsDisabled() {
        for (tab in 0..7) {
            for (restore in listOf(false, true)) {
                assertEquals(tab, resolveStartupTab(null, tab, restore, hasSavedInstanceState = true))
            }
        }
    }
}
