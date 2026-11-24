package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class StatusResolverTest {
    private val s = WorkSchedule(startMin = 9 * 60, endMin = 18 * 60)
    private val monday10 = LocalDateTime.of(2026, 11, 23, 10, 0)

    @Test fun workingHours() {
        assertTrue(StatusResolver.isWorkingTime(monday10, s))
        assertFalse(StatusResolver.isWorkingTime(monday10.withHour(8).withMinute(59), s))
        assertFalse(StatusResolver.isWorkingTime(monday10.withHour(18), s))
        assertFalse(StatusResolver.isWorkingTime(monday10.minusDays(1), s)) // Sunday
    }

    @Test fun nightShiftSpansMidnight() {
        val n = WorkSchedule(startMin = 22 * 60, endMin = 6 * 60, workDays = setOf(DayOfWeek.MONDAY))
        assertTrue(StatusResolver.isWorkingTime(monday10.withHour(23), n))
        assertTrue(StatusResolver.isWorkingTime(monday10.plusDays(1).withHour(3), n)) // Tue 03:00
        assertFalse(StatusResolver.isWorkingTime(monday10.withHour(3), n)) // Mon 03:00 belongs to Sunday
    }

    @Test fun resolveStatuses() {
        val ms = 0L
        assertEquals(WorkStatus.IN_OFFICE, StatusResolver.resolve(monday10, ms, s, Presence.INSIDE, LunchState()))
        assertEquals(WorkStatus.OUTSIDE_OFFICE, StatusResolver.resolve(monday10, ms, s, Presence.OUTSIDE, LunchState()))
        assertEquals(WorkStatus.OUTSIDE_WORKING_HOURS,
            StatusResolver.resolve(monday10.withHour(20), ms, s, Presence.INSIDE, LunchState()))
        assertEquals(WorkStatus.ON_LUNCH,
            StatusResolver.resolve(monday10, 30 * 60_000L, s, Presence.OUTSIDE, LunchState(true, 0)))
        assertEquals(WorkStatus.LUNCH_EXCEEDED,
            StatusResolver.resolve(monday10, 61 * 60_000L, s, Presence.OUTSIDE, LunchState(true, 0)))
    }

    @Test fun lunchRules() {
        assertNull(StatusResolver.canStartLunch(monday10, s, LunchState()))
        assertNotNull(StatusResolver.canStartLunch(monday10.withHour(20), s, LunchState()))
        val strict = s.copy(restrictLunchToWindow = true)
        assertNotNull(StatusResolver.canStartLunch(monday10, strict, LunchState()))
        assertNull(StatusResolver.canStartLunch(monday10.withHour(13).withMinute(15), strict, LunchState()))
    }
}
