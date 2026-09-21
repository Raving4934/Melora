package com.leyu.melora.ui.discover

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverLayoutTest {

    @Test
    fun topCardStripKeepsTheOneOneTwoTwoGeometry() {
        assertEquals(175, DISCOVER_TOP_CARD_HEIGHT_DP)
        assertEquals(83, discoverStackCardHeightDp())
    }

    @Test
    fun dateInfoUsesOneSnapshotAtTheMonthBoundary() {
        val timeZone = TimeZone.getTimeZone("UTC")

        assertEquals(
            DiscoverDateInfo(day = "31", month = "Dec"),
            discoverDateInfo(utcDate(2026, Calendar.DECEMBER, 31, 23, 59, 59), timeZone),
        )
        assertEquals(
            DiscoverDateInfo(day = "01", month = "Jan"),
            discoverDateInfo(utcDate(2027, Calendar.JANUARY, 1, 0, 0, 0), timeZone),
        )
    }

    private fun utcDate(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ) = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }.time
}
