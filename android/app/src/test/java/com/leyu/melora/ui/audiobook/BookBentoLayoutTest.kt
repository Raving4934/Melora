package com.leyu.melora.ui.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class BookBentoLayoutTest {
    @Test fun phoneAndTabletShortcutsStayCompactAndTwoByTwo() {
        assertEquals(85f, bookShortcutCardWidth(328f, 138f, 360f), 0f)
        assertEquals(136f, bookShortcutCardWidth(430f, 138f, 462f), 0f)
        assertEquals(160f, bookShortcutCardWidth(832f, 138f, 800f), 0f)
        assertEquals(160f, bookShortcutCardWidth(1040f, 138f, 800f), 0f)
    }

    @Test fun compactPhoneLandscapeKeepsOriginalEqualColumns() {
        assertEquals(265f, bookShortcutCardWidth(688f, 138f, 360f), 0f)
        assertEquals(265f, bookShortcutCardWidth(688f, 138f, 599f), 0f)
        assertEquals(160f, bookShortcutCardWidth(688f, 138f, 600f), 0f)
    }
}
