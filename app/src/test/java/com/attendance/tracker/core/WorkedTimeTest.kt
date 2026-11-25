package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkedTimeTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private fun inAt(t: Long) = WorkedTime.Punch(t, true)
    private fun outAt(t: Long) = WorkedTime.Punch(t, false)

    @Test fun sumsAllStaysExcludingTimeOutside() {
        // 10:00 IN, 13:00 OUT (lunch outside), 13:45 IN, 19:30 OUT → 3h + 5h45 = 8h45
        val p = listOf(inAt(10 * h), outAt(13 * h), inAt(13 * h + 45 * m), outAt(19 * h + 30 * m))
        assertEquals((8 * 60 + 45) * 60L, WorkedTime.workedSec(p, 24 * h))
    }

    @Test fun openStayCountsUntilNow() {
        val p = listOf(inAt(9 * h))
        assertEquals(3 * 3600L, WorkedTime.workedSec(p, 12 * h))
    }

    @Test fun strayOutAndDuplicateInIgnored() {
        val p = listOf(outAt(1 * h), inAt(9 * h), inAt(9 * h + 5 * m), outAt(17 * h))
        assertEquals(8 * 3600L, WorkedTime.workedSec(p, 24 * h))
    }

    @Test fun dutyTimeOutsideCountsWhenMergedAsStay() {
        // IN 10:00, OUT 14:00 (client visit), duty 14:00→15:30 recorded as a stay, IN 15:30, OUT 19:00
        val p = listOf(inAt(10 * h), inAt(14 * h), outAt(14 * h), inAt(15 * h + 30 * m), outAt(15 * h + 30 * m), outAt(19 * h))
        assertEquals(9 * 3600L, WorkedTime.workedSec(p, 24 * h))
    }

    @Test fun lunchAlwaysDeducted() {
        val a = 60 * 60L
        // In 10:00 → out 19:30 with 45 min lunch outside: 8h45 in office − 15m unused = 8h30
        assertEquals(510 * 60L, WorkedTime.netSec(525 * 60L, 0, 45 * 60L, a))
        // Same day, lunch not taken at all: 9h30 in office − 60m = 8h30
        assertEquals(510 * 60L, WorkedTime.netSec(570 * 60L, 0, 0, a))
        // Long lunch 75 min (already outside office time): nothing more deducted
        assertEquals(510 * 60L, WorkedTime.netSec(510 * 60L, 0, 75 * 60L, a))
        // On-duty counts; early morning never goes negative
        assertEquals(90 * 60L, WorkedTime.netSec(120 * 60L, 30 * 60L, 0, a))
        assertEquals(0L, WorkedTime.netSec(20 * 60L, 0, 0, a))
    }

    @Test fun dayLengthIsNetPlusLunch() {
        val s = WorkSchedule(netMinutes = 510, maxLunchMinutes = 60)
        assertEquals(570, s.requiredMinutes)
    }

    @Test fun averageSkipsDaysOff() {
        assertEquals(8 * 3600L + 30 * 60, WorkedTime.averageSec(listOf(0, 8 * 3600L, 9 * 3600L, 0)))
        assertNull(WorkedTime.averageSec(listOf(0, 0)))
    }

    @Test fun liveNetCountsUpFromArrivalAndLandsOnTarget() {
        val a = 3600L; val t = 510 * 60L
        // 6 minutes in, no lunch yet: shows 6m, not 0m.
        assertEquals(360L, WorkedTime.liveNetSec(360, 0, 0, a, t))
        // Plateau while the untaken lunch hour is absorbed.
        assertEquals(t, WorkedTime.liveNetSec(t + 1800, 0, 0, a, t))
        // At logout (target + allowance in office) it equals the final rule.
        assertEquals(WorkedTime.netSec(t + a, 0, 0, a), WorkedTime.liveNetSec(t + a, 0, 0, a, t))
        // Full lunch taken: same as the final rule all day.
        assertEquals(4 * 3600L, WorkedTime.liveNetSec(4 * 3600, 0, 3600, a, t))
    }
}
