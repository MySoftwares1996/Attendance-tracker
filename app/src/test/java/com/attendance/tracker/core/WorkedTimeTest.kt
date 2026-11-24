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

    @Test fun averageSkipsDaysOff() {
        assertEquals(8 * 3600L + 30 * 60, WorkedTime.averageSec(listOf(0, 8 * 3600L, 9 * 3600L, 0)))
        assertNull(WorkedTime.averageSec(listOf(0, 0)))
    }
}
