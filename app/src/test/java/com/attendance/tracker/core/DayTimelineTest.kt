package com.attendance.tracker.core

import com.attendance.tracker.core.DayTimeline.Ev
import com.attendance.tracker.core.DayTimeline.Event
import com.attendance.tracker.core.DayTimeline.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineTest {
    private val h = 3_600_000L
    private val m = 60_000L

    @Test fun fullDay() {
        val ev = listOf(
            Event(10 * h, Ev.IN),
            Event(13 * h, Ev.OUT), Event(13 * h, Ev.LUNCH_START), Event(13 * h + 45 * m, Ev.LUNCH_END), Event(13 * h + 45 * m, Ev.IN),
            Event(15 * h, Ev.OUT), Event(15 * h, Ev.DUTY_START), Event(16 * h, Ev.DUTY_END), Event(16 * h, Ev.IN),
            Event(19 * h, Ev.OUT),
        )
        val segs = DayTimeline.segments(ev, 19 * h + 30 * m)
        assertEquals(listOf(Kind.OFFICE, Kind.LUNCH, Kind.OFFICE, Kind.DUTY, Kind.OFFICE, Kind.OUTSIDE), segs.map { it.kind })
        val t = DayTimeline.totals(segs)
        assertEquals((3 * 60 + 75 + 180) * 60L, t[Kind.OFFICE])
        assertEquals(45 * 60L, t[Kind.LUNCH])
        assertEquals(60 * 60L, t[Kind.DUTY])
        assertEquals(30 * 60L, t[Kind.OUTSIDE])
    }

    @Test fun liveDayGrowsToNow() {
        val segs = DayTimeline.segments(listOf(Event(9 * h, Ev.IN)), 11 * h)
        assertEquals(1, segs.size); assertEquals(2 * 3600L, segs[0].seconds)
    }

    @Test fun nothingBeforeFirstIn() {
        assertTrue(DayTimeline.segments(listOf(Event(8 * h, Ev.OUT)), 9 * h).isEmpty())
    }
}
