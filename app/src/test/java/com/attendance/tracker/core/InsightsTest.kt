package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    @Test fun streaksSkipNonWorkingDays() {
        // newest first: met, (Sun), met, met, missed, met, met, met
        val (cur, best) = Insights.streaks(listOf(true, null, true, true, false, true, true, true))
        assertEquals(3, cur); assertEquals(3, best)
        assertEquals(0 to 2, Insights.streaks(listOf(false, true, true)))
    }

    @Test fun commute() {
        val h = 3_600_000L; val m = 60_000L
        assertEquals(34L, Insights.commuteMinutes(listOf(7 * h, 9 * h + 24 * m), 9 * h + 58 * m))
        assertNull(Insights.commuteMinutes(listOf(10 * h), 9 * h))           // left after arriving
        assertNull(Insights.commuteMinutes(listOf(2 * h), 9 * h))            // 7 h is not a commute
    }

    @Test fun bestDeparture() {
        val s = listOf(9 * 60 + 5 to 22L, 9 * 60 + 10 to 24L, 9 * 60 + 35 to 40L, 9 * 60 + 40 to 38L, 8 * 60 to 30L)
        assertEquals(9 * 60 to 23L, Insights.bestDeparture(s))
    }

    @Test fun parseHolidayList() {
        val list = Insights.parseHolidays(
            """
            2027-01-14 Pongal
            26/01/2027, Republic Day
            15 Aug 2027 Independence Day
            nonsense line
            02-10-2027
            """.trimIndent()
        )
        assertEquals(4, list.size)
        assertEquals(LocalDate.of(2027, 1, 14) to "Pongal", list[0])
        assertEquals(LocalDate.of(2027, 1, 26) to "Republic Day", list[1])
        assertEquals(LocalDate.of(2027, 8, 15) to "Independence Day", list[2])
        assertEquals(LocalDate.of(2027, 10, 2) to "Holiday", list[3])
    }
}
