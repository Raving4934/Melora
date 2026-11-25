package com.leyu.melora.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricPermissionTest {
    @Test fun savedOverlayIsDisabledWhenPermissionWasRevoked() = withSetting(true) {
        assertFalse(reconcileDesktopLyricSetting { false })
        assertFalse(MeloraSettings.showDesktopLyrics.value)

        // Returning later with permission does not enable a setting the app already turned off.
        assertFalse(reconcileDesktopLyricSetting { true })
        assertFalse(MeloraSettings.showDesktopLyrics.value)
    }

    @Test fun anExplicitRequestEnablesTheOverlayOnlyAfterGrant() = withSetting(false) {
        assertFalse(reconcileDesktopLyricSetting(requested = true) { false })
        assertFalse(MeloraSettings.showDesktopLyrics.value)

        assertTrue(reconcileDesktopLyricSetting(requested = true) { true })
        assertTrue(MeloraSettings.showDesktopLyrics.value)
        assertTrue(reconcileDesktopLyricSetting { true })
    }

    @Test fun disablingDoesNotQueryPermissionOrLeaveTheSettingEnabled() = withSetting(true) {
        assertFalse(reconcileDesktopLyricSetting(requested = false) { error("关闭时无需查询权限") })
        assertFalse(MeloraSettings.showDesktopLyrics.value)
    }

    private fun withSetting(enabled: Boolean, test: () -> Unit) {
        val setting = MeloraSettings.showDesktopLyrics
        val original = setting.value
        try {
            setting.value = enabled
            test()
        } finally {
            setting.value = original
        }
    }
}
