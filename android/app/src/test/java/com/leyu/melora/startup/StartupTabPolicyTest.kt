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
