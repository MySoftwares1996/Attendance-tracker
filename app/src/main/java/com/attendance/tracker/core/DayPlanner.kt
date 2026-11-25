package com.attendance.tracker.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Today's working-day plan, computed from the schedule and what actually happened
 * (arrival time, lunch taken).
 */
data class DayPlan(
    val date: LocalDate,
    val workDay: Boolean,
    val flexible: Boolean,
    /** When the working day starts. Flexible: actual arrival (null until you arrive). */
    val start: LocalDateTime?,
    /** Flexible only: arriving after this is late. */
    val latestStart: LocalDateTime?,
    /** Logout time before any lunch extension. */
    val baseEnd: LocalDateTime?,
    /** Logout time including extra lunch. */
    val expectedEnd: LocalDateTime?,
    /** After this the day is over (overtime stops counting). */
    val cutoff: LocalDateTime,
    val firstIn: LocalDateTime?,
    val late: Boolean,
    val lunchTakenSec: Long,
    /** Lunch beyond the allowed maximum; added to the logout time. */
    val extraLunchSec: Long,
    /** Set when the day is marked as leave / holiday / WFH etc. */
    val dayOff: String? = null,
)

object DayPlanner {

    private fun LocalDate.at(min: Int): LocalDateTime = atStartOfDay().plusMinutes(min.toLong())

    /**
     * @param firstCheckIn first office entry on a given date (or null)
     * @param lunchTakenSec total lunch seconds taken on a given date (including an active lunch)
     */
    fun plan(
        now: LocalDateTime,
        s: WorkSchedule,
        firstCheckIn: (LocalDate) -> LocalDateTime?,
        lunchTakenSec: (LocalDate) -> Long,
    ): DayPlan {
        val today = now.toLocalDate()
        if (!s.flexible && s.startMin > s.endMin) {
            // Night shift: early-morning hours belong to the shift that began yesterday.
            val y = fixedPlan(today.minusDays(1), s, firstCheckIn, lunchTakenSec)
            if (y.workDay && now < y.cutoff) return y
        }
        return if (s.flexible) flexiblePlan(today, s, firstCheckIn, lunchTakenSec)
        else fixedPlan(today, s, firstCheckIn, lunchTakenSec)
    }

    private fun extra(lunchSec: Long, s: WorkSchedule) = (lunchSec - s.maxLunchMinutes * 60L).coerceAtLeast(0)

    private fun fixedPlan(
        date: LocalDate, s: WorkSchedule,
        firstCheckIn: (LocalDate) -> LocalDateTime?, lunchTakenSec: (LocalDate) -> Long,
    ): DayPlan {
        val start = date.at(s.startMin)
        val night = s.startMin > s.endMin
        val baseEnd = if (night) date.plusDays(1).at(s.endMin) else date.at(s.endMin)
        val lunch = lunchTakenSec(date)
        val ex = extra(lunch, s)
        val end = baseEnd.plusSeconds(ex)
        val cutoff = if (night) end.plusHours(4) else maxOf(date.at(s.overtimeUntilMin), end)
        val firstIn = firstCheckIn(date)
        return DayPlan(
            date = date, workDay = date.dayOfWeek in s.workDays, flexible = false,
            start = start, latestStart = null, baseEnd = baseEnd, expectedEnd = end, cutoff = cutoff,
            firstIn = firstIn, late = firstIn != null && firstIn.isAfter(start),
            lunchTakenSec = lunch, extraLunchSec = ex,
        )
    }

    /** Flexible day: starts the moment you first arrive; no arrival deadline and no "late". */
    private fun flexiblePlan(
        date: LocalDate, s: WorkSchedule,
        firstCheckIn: (LocalDate) -> LocalDateTime?, lunchTakenSec: (LocalDate) -> Long,
    ): DayPlan {
        val firstIn = firstCheckIn(date)
        val start = firstIn
        val lunch = lunchTakenSec(date)
        val ex = extra(lunch, s)
        val baseEnd = start?.plusMinutes(s.requiredMinutes.toLong())
        val end = baseEnd?.plusSeconds(ex)
        val cutoff = maxOf(date.at(s.overtimeUntilMin), end ?: date.at(s.overtimeUntilMin))
        return DayPlan(
            date = date, workDay = date.dayOfWeek in s.workDays, flexible = true,
            start = start, latestStart = null, baseEnd = baseEnd, expectedEnd = end, cutoff = cutoff,
            firstIn = firstIn, late = false,
            lunchTakenSec = lunch, extraLunchSec = ex,
        )
    }

    /** Seconds of overtime so far (time past the expected logout). */
    fun overtimeSec(plan: DayPlan, now: LocalDateTime): Long {
        val end = plan.expectedEnd ?: return 0
        if (now <= end) return 0
        return Duration.between(end, minOf(now, plan.cutoff)).seconds
    }
}
