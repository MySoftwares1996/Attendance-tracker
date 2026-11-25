package com.attendance.tracker.data

import com.attendance.tracker.AttendanceController
import java.time.LocalDate
import java.time.MonthDay

/**
 * Holidays = fixed-date public holidays (switchable) + the user's own company / festival list.
 * Festivals whose date changes every year (Diwali, Holi, Eid, Pongal…) are added by the user,
 * so no date is ever guessed.
 */
object Holidays {
    private val NATIONAL = mapOf(
        MonthDay.of(1, 26) to "Republic Day",
        MonthDay.of(8, 15) to "Independence Day",
        MonthDay.of(10, 2) to "Gandhi Jayanti",
    )

    fun name(date: LocalDate): String? {
        val s = AttendanceController.settings
        s.customHolidays[date.toString()]?.let { return it }
        val md = MonthDay.from(date)
        if (s.holNational) NATIONAL[md]?.let { return it }
        if (s.holNewYear && md == MonthDay.of(1, 1)) return "New Year"
        if (s.holMayDay && md == MonthDay.of(5, 1)) return "May Day"
        if (s.holChristmas && md == MonthDay.of(12, 25)) return "Christmas"
        return null
    }

    /** Upcoming holidays in the next [days] days (for the Setup page). */
    fun upcoming(from: LocalDate = LocalDate.now(), days: Long = 400): List<Pair<LocalDate, String>> =
        (0 until days).mapNotNull { i -> val d = from.plusDays(i); name(d)?.let { d to it } }
}
