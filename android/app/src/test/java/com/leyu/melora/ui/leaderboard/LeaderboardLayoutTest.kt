package com.leyu.melora.ui.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardLayoutTest {
    @Test
    fun phoneAndNarrowPaneKeepFeaturedBoardsInOneColumn() {
        listOf(-40f, 0f, 288f, 328f, 430f, 624f, 655f).forEach { width ->
            assertEquals(1, leaderboardFeaturedColumnCount(width))
        }
    }

    @Test
    fun featuredColumnThresholdReservesFullCardsAndAllGaps() {
        assertEquals(2, leaderboardFeaturedColumnCount(656f))
        assertEquals(2, leaderboardFeaturedColumnCount(991f))
        assertEquals(3, leaderboardFeaturedColumnCount(992f))
    }

    @Test
    fun adjustedLargeContentFitsAllThreeFeaturedBoards() {
        assertEquals(3, leaderboardFeaturedColumnCount((1_280 - 208 - 32).toFloat()))
        assertEquals(3, leaderboardFeaturedColumnCount(3_072f))
    }

    @Test
    fun phoneContentKeepsThreeGenreColumns() {
        assertEquals(3, leaderboardGenreColumnCount(328f))
        assertEquals(3, leaderboardGenreColumnCount(430f))
    }

    @Test
    fun widerContentAddsGenreColumnsWithoutASixColumnCap() {
        assertEquals(4, leaderboardGenreColumnCount(552f))
        assertEquals(5, leaderboardGenreColumnCount(692f))
        assertEquals(6, leaderboardGenreColumnCount(832f))
        assertEquals(6, leaderboardGenreColumnCount(971f))
        assertEquals(7, leaderboardGenreColumnCount(972f))
        assertEquals(8, leaderboardGenreColumnCount(1_200f))
        assertEquals(22, leaderboardGenreColumnCount(3_072f))
    }

    @Test
    fun narrowOrNegativeContentNeverProducesFewerThanThreeColumns() {
        assertEquals(3, leaderboardGenreColumnCount(0f))
        assertEquals(3, leaderboardGenreColumnCount(-40f))
    }
}
