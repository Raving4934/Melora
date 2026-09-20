package com.leyu.melora.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun drawerDeepLinkStartsOnTheRequestedPageWithoutAHomeFrame() {
        for (page in listOf(SettingsSubPage.Basic, SettingsSubPage.CustomSource, SettingsSubPage.Playback,
            SettingsSubPage.Download, SettingsSubPage.DesktopLyrics, SettingsSubPage.Others, SettingsSubPage.About)) {
            assertEquals(page, initialSettingsSubPage(page, requestSeq = 1))
            assertEquals(page, initialSettingsSubPage(page, requestSeq = 20))
        }
    }

    @Test
    fun noNavigationRequestAlwaysStartsAtSettingsHome() {
        assertEquals(SettingsSubPage.None, initialSettingsSubPage(SettingsSubPage.Playback, requestSeq = 0))
        assertEquals(SettingsSubPage.None, initialSettingsSubPage(null, requestSeq = 0))
    }

    @Test
    fun explicitHomeRequestDoesNotRestoreAnOldSubpage() {
        assertEquals(SettingsSubPage.None, initialSettingsSubPage(null, requestSeq = 3))
        assertEquals(SettingsSubPage.None, initialSettingsSubPage(SettingsSubPage.None, requestSeq = 3))
    }
}
