package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class PlanningTest {
    // Mon–Sat work week, Christmas on Friday 25 Dec 2026.
    private val xmas = LocalDate.of(2026, 12, 25)
    private fun off(d: LocalDate) = d.dayOfWeek == DayOfWeek.SUNDAY || d == xmas
    private fun hol(d: LocalDate) = if (d == xmas) "Christmas" else null

    @Test fun suggestsBridgeDayForChristmas() {
        val list = LongWeekends.find(LocalDate.of(2026, 12, 1), 60, ::off, ::hol)
        val b = list.first()
        assertEquals(listOf(LocalDate.of(2026, 12, 26)), b.leaveDays)          // take Saturday
        assertEquals(LocalDate.of(2026, 12, 25), b.from)                        // Fri (holiday)
        assertEquals(LocalDate.of(2026, 12, 27), b.to)                          // … to Sunday
        assertEquals("Christmas", b.holiday)
    }

    @Test fun noSuggestionsWithoutHolidays() {
        assertTrue(LongWeekends.find(LocalDate.of(2026, 1, 1), 60, { it.dayOfWeek == DayOfWeek.SUNDAY }, { null }).isEmpty())
    }

    @Test fun leaveNoteRoundTrip() {
        val n = LeaveNote.format("SL", true, " fever ")
        val p = LeaveNote.parse(n)!!
        assertEquals("SL", p.code); assertTrue(p.half); assertEquals("fever", p.reason)
        assertNull(LeaveNote.parse("something else"))
        assertEquals(mapOf("CL" to 1.5), LeaveNote.used(listOf(LeaveNote.Parsed("CL", false, ""), LeaveNote.Parsed("CL", true, ""))))
    }

    @Test fun regularisationIssues() {
        val t = 510 * 60L
        assertEquals("Absent", Regularisation.issue(true, false, false, 0, t, 0))
        assertEquals("Missed OUT", Regularisation.issue(false, true, false, 3600, t, 0))
        assertEquals("Short by 0h 52m", Regularisation.issue(false, true, true, t - 52 * 60, t, 0))
        assertEquals("Manual punch", Regularisation.issue(false, true, true, t, t, 1))
        assertNull(Regularisation.issue(false, true, true, t + 60, t, 0))
    }
}
