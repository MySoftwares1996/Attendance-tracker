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
        schedule: WorkSchedule,
        presence: Presence,
        lunch: LunchState,
    ): WorkStatus {
        if (!isWorkingTime(now, schedule)) return WorkStatus.OUTSIDE_WORKING_HOURS
        if (lunch.active) {
            val mins = (nowMs - lunch.startedAtMs) / 60_000
            return if (mins >= schedule.maxLunchMinutes) WorkStatus.LUNCH_EXCEEDED else WorkStatus.ON_LUNCH
        }
        return when (presence) {
            Presence.INSIDE -> WorkStatus.IN_OFFICE
            Presence.OUTSIDE -> WorkStatus.OUTSIDE_OFFICE
            Presence.UNKNOWN -> WorkStatus.UNKNOWN
        }
    }

    /** Returns an error message if lunch cannot be declared now, otherwise null. */
    fun canStartLunch(now: LocalDateTime, schedule: WorkSchedule, lunch: LunchState): String? = when {
        lunch.active -> "Lunch break already started"
        !isWorkingTime(now, schedule) -> "Lunch can only be declared during working hours"
        schedule.restrictLunchToWindow && !isLunchWindow(now, schedule) ->
            "Lunch can only be declared between ${fmt(schedule.lunchStartMin)} and ${fmt(schedule.lunchEndMin)}"
        else -> null
    }

    fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
