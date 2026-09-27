package com.leyu.melora.ui.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardLayoutTest {
    @Test
    fun phoneContentKeepsThreeGenreColumns() {
        assertEquals(3, leaderboardGenreColumnCount(328f))
        assertEquals(3, leaderboardGenreColumnCount(430f))
    }

    @Test
    fun widerContentAddsColumnsFromFourThroughSix() {
        assertEquals(4, leaderboardGenreColumnCount(552f))
        assertEquals(5, leaderboardGenreColumnCount(692f))
        assertEquals(6, leaderboardGenreColumnCount(832f))
        assertEquals(6, leaderboardGenreColumnCount(1_200f))
    }

    @Test
    fun narrowOrNegativeContentNeverProducesFewerThanThreeColumns() {
        assertEquals(3, leaderboardGenreColumnCount(0f))
        assertEquals(3, leaderboardGenreColumnCount(-40f))
    }
}
