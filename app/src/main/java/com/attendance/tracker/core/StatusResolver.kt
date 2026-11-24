package com.attendance.tracker.core

import java.time.LocalDateTime

object StatusResolver {

    /** True if [t] falls inside working hours. Supports night shifts (end < start). */
    fun isWorkingTime(t: LocalDateTime, s: WorkSchedule): Boolean {
        val m = t.hour * 60 + t.minute
        return if (s.startMin < s.endMin) {
            t.dayOfWeek in s.workDays && m >= s.startMin && m < s.endMin
        } else if (s.startMin > s.endMin) {
            (m >= s.startMin && t.dayOfWeek in s.workDays) ||
                (m < s.endMin && t.dayOfWeek.minus(1) in s.workDays)
        } else {
            false
        }
    }

    fun isLunchWindow(t: LocalDateTime, s: WorkSchedule): Boolean {
        val m = t.hour * 60 + t.minute
        return if (s.lunchStartMin <= s.lunchEndMin) m >= s.lunchStartMin && m < s.lunchEndMin
        else m >= s.lunchStartMin || m < s.lunchEndMin
    }

    fun resolve(
        now: LocalDateTime,
        nowMs: Long,
        plan: DayPlan,
        schedule: WorkSchedule,
        presence: Presence,
        lunch: LunchState,
    ): WorkStatus {
        if (!plan.workDay || now >= plan.cutoff) return WorkStatus.OUTSIDE_WORKING_HOURS
        if (lunch.active) {
            val mins = (nowMs - lunch.startedAtMs) / 60_000
            // The allowance is per day, so earlier lunches today count too.
            val over = mins >= schedule.maxLunchMinutes || plan.lunchTakenSec >= schedule.maxLunchMinutes * 60L
            return if (over) WorkStatus.LUNCH_EXCEEDED else WorkStatus.ON_LUNCH
        }
        val start = plan.start
        if (start == null) {
            // Flexible day and you haven't arrived yet.
            val ls = plan.latestStart
            return if (ls != null && now >= ls) WorkStatus.LATE else WorkStatus.NOT_STARTED
        }
        if (now < start) return WorkStatus.NOT_STARTED
        val end = plan.expectedEnd
        if (end != null && now >= end) {
            return if (presence == Presence.INSIDE) WorkStatus.OVERTIME else WorkStatus.DAY_COMPLETE
        }
        return when (presence) {
            Presence.INSIDE -> WorkStatus.IN_OFFICE
            Presence.OUTSIDE -> WorkStatus.OUTSIDE_OFFICE
            Presence.UNKNOWN -> WorkStatus.UNKNOWN
        }
    }

    /** Returns an error message if lunch cannot be declared now, otherwise null. */
    fun canStartLunch(now: LocalDateTime, plan: DayPlan, schedule: WorkSchedule, lunch: LunchState): String? {
        val start = plan.start
        return when {
            lunch.active -> "Lunch break already started"
            !plan.workDay || now >= plan.cutoff -> "Lunch can only be declared on a working day"
            start == null -> "Reach the office first — your day starts when you arrive"
            now < start -> "Working hours haven't started yet"
            plan.expectedEnd != null && now >= plan.expectedEnd -> "Your working day is already complete"
            schedule.restrictLunchToWindow && !isLunchWindow(now, schedule) ->
                "Lunch can only be declared between ${fmt(schedule.lunchStartMin)} and ${fmt(schedule.lunchEndMin)}"
            else -> null
        }
    }

    fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
