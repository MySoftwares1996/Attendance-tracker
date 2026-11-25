package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class StatusResolverTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private fun at(h: Int, m: Int = 0) = monday.atTime(h, m)

    private fun plan(
        now: LocalDateTime, s: WorkSchedule,
        firstIn: LocalDateTime? = null, lunchSec: Long = 0,
    ) = DayPlanner.plan(now, s, { d -> firstIn?.takeIf { it.toLocalDate() == d } }, { d -> if (d == now.toLocalDate()) lunchSec else 0 })

    private fun status(now: LocalDateTime, s: WorkSchedule, p: Presence, firstIn: LocalDateTime? = null,
                       lunchSec: Long = 0, lunch: LunchState = LunchState(), nowMs: Long = 0) =
        StatusResolver.resolve(now, nowMs, plan(now, s, firstIn, lunchSec), s, p, lunch)

    // ---------------- fixed hours ----------------
    private val fixed = WorkSchedule(startMin = 9 * 60, endMin = 18 * 60, flexible = false)

    @Test fun fixedBasicStatuses() {
        assertEquals(WorkStatus.IN_OFFICE, status(at(10), fixed, Presence.INSIDE, at(9)))
        assertEquals(WorkStatus.OUTSIDE_OFFICE, status(at(10), fixed, Presence.OUTSIDE, at(9)))
        assertEquals(WorkStatus.NOT_STARTED, status(at(8), fixed, Presence.OUTSIDE))
        assertEquals(WorkStatus.OVERTIME, status(at(20), fixed, Presence.INSIDE, at(9)))
        assertEquals(WorkStatus.DAY_COMPLETE, status(at(20), fixed, Presence.OUTSIDE, at(9)))
        assertEquals(WorkStatus.OUTSIDE_WORKING_HOURS, status(at(23, 30), fixed, Presence.INSIDE, at(9)))
        assertEquals(WorkStatus.OUTSIDE_WORKING_HOURS, status(at(10).minusDays(1), fixed, Presence.INSIDE)) // Sunday
    }

    @Test fun extraLunchExtendsFixedLogout() {
        // 60 min allowed, took 85 → logout 18:25
        val p = plan(at(18, 10), fixed, at(9), lunchSec = 85 * 60)
        assertEquals(25 * 60L, p.extraLunchSec)
        assertEquals(at(18, 25), p.expectedEnd)
        assertEquals(WorkStatus.IN_OFFICE, status(at(18, 10), fixed, Presence.INSIDE, at(9), 85 * 60))
        assertEquals(WorkStatus.OVERTIME, status(at(18, 30), fixed, Presence.INSIDE, at(9), 85 * 60))
    }

    @Test fun nightShiftBelongsToPreviousDay() {
        val n = WorkSchedule(startMin = 22 * 60, endMin = 6 * 60, workDays = setOf(DayOfWeek.MONDAY), flexible = false)
        assertEquals(WorkStatus.IN_OFFICE, status(at(23), n, Presence.INSIDE, at(22)))
        val tue3 = at(3).plusDays(1)
        val p = DayPlanner.plan(tue3, n, { d -> if (d == monday) at(22) else null }, { 0 })
        assertEquals(monday, p.date)
        assertEquals(WorkStatus.IN_OFFICE, StatusResolver.resolve(tue3, 0, p, n, Presence.INSIDE, LunchState()))
        assertTrue(StatusResolver.isWorkingTime(tue3, n))
        assertFalse(StatusResolver.isWorkingTime(at(3), n))
    }

    // ---------------- flexible hours ----------------
    private val flex = WorkSchedule(flexible = true, earliestStartMin = 8 * 60, latestStartMin = 11 * 60,
        netMinutes = 8 * 60, maxLunchMinutes = 60, overtimeUntilMin = 23 * 60)

    @Test fun flexibleLogoutFollowsArrival() {
        assertEquals(at(18), plan(at(12), flex, at(9)).expectedEnd)
        assertEquals(at(19), plan(at(12), flex, at(10)).expectedEnd)
        assertEquals(at(20), plan(at(12), flex, at(11)).expectedEnd)
        // No arrival window: an early arrival starts the day right then.
        assertEquals(at(16, 30), plan(at(12), flex, at(7, 30)).expectedEnd)
    }

    @Test fun flexibleNeverLate() {
        assertEquals(WorkStatus.NOT_STARTED, status(at(9, 30), flex, Presence.OUTSIDE))
        assertEquals(WorkStatus.NOT_STARTED, status(at(14), flex, Presence.OUTSIDE))
        assertFalse(plan(at(15), flex, at(13)).late)
        assertEquals(at(22), plan(at(15), flex, at(13)).expectedEnd)
    }

    @Test fun flexibleExtraLunchAndOvertimeUntil11pm() {
        // Arrive 10:00, lunch 90 min (30 extra) → logout 19:30
        val p = plan(at(15), flex, at(10), lunchSec = 90 * 60)
        assertEquals(at(19, 30), p.expectedEnd)
        assertEquals(WorkStatus.IN_OFFICE, status(at(19, 15), flex, Presence.INSIDE, at(10), 90 * 60))
        assertEquals(WorkStatus.OVERTIME, status(at(22, 45), flex, Presence.INSIDE, at(10), 90 * 60))
        assertEquals(WorkStatus.OUTSIDE_WORKING_HOURS, status(at(23, 1), flex, Presence.INSIDE, at(10), 90 * 60))
        assertEquals(3 * 3600L + 15 * 60, DayPlanner.overtimeSec(plan(at(22, 45), flex, at(10), 90 * 60), at(22, 45)))
        assertEquals(3 * 3600L + 30 * 60, DayPlanner.overtimeSec(plan(at(23, 30), flex, at(10), 90 * 60), at(23, 30)))
    }

    @Test fun dayOffBeatsEverything() {
        val p = plan(at(11), flex, null).copy(workDay = false, dayOff = "Leave")
        assertEquals(WorkStatus.DAY_OFF, StatusResolver.resolve(at(11), 0, p, flex, Presence.INSIDE, LunchState()))
        assertNotNull(StatusResolver.canStartLunch(at(11), p, flex, LunchState()))
    }

    @Test fun onDutyCountsAsWorking() {
        val p = plan(at(15), flex, at(10))
        assertEquals(WorkStatus.ON_DUTY, StatusResolver.resolve(at(15), 0, p, flex, Presence.OUTSIDE, LunchState(), onDuty = true))
        assertEquals(WorkStatus.IN_OFFICE, StatusResolver.resolve(at(15), 0, p, flex, Presence.INSIDE, LunchState(), onDuty = true))
    }

    @Test fun lunchStatuses() {
        assertEquals(WorkStatus.ON_LUNCH,
            status(at(13), flex, Presence.OUTSIDE, at(10), lunch = LunchState(true, 0), nowMs = 30 * 60_000L))
        assertEquals(WorkStatus.LUNCH_EXCEEDED,
            status(at(13), flex, Presence.OUTSIDE, at(10), lunch = LunchState(true, 0), nowMs = 61 * 60_000L))
    }

    @Test fun lunchRules() {
        assertNull(StatusResolver.canStartLunch(at(13), plan(at(13), flex, at(10)), flex, LunchState()))
        assertNotNull(StatusResolver.canStartLunch(at(9), plan(at(9), flex), flex, LunchState())) // not arrived
        assertNotNull(StatusResolver.canStartLunch(at(20), plan(at(20), fixed, at(9)), fixed, LunchState())) // day over
        val strict = fixed.copy(restrictLunchToWindow = true)
        assertNotNull(StatusResolver.canStartLunch(at(10), plan(at(10), strict, at(9)), strict, LunchState()))
        assertNull(StatusResolver.canStartLunch(at(13, 15), plan(at(13, 15), strict, at(9)), strict, LunchState()))
    }
}
