package com.attendance.tracker.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Upcoming breaks: 1–2 leave days that join a holiday and the weekend into a longer time off. */
object LongWeekends {
    data class Break(val leaveDays: List<LocalDate>, val from: LocalDate, val to: LocalDate, val holiday: String?) {
        val totalDays: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
    }

    fun find(today: LocalDate, horizonDays: Long, isOff: (LocalDate) -> Boolean, holidayName: (LocalDate) -> String?, max: Int = 6): List<Break> {
        val out = mutableListOf<Break>()
        for (i in 1..horizonDays) {
            val start = today.plusDays(i)
            for (n in 1..2) {
                val leave = (0 until n).map { start.plusDays(it.toLong()) }
                if (leave.any(isOff)) continue
                var a = start; while (isOff(a.minusDays(1))) a = a.minusDays(1)
                var b = leave.last(); while (isOff(b.plusDays(1))) b = b.plusDays(1)
                if (!a.isAfter(today)) continue
                val hol = generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.firstNotNullOfOrNull(holidayName)
                val br = Break(leave, a, b, hol)
                if (hol != null && br.totalDays >= n + 2 && out.none { it.from == a && it.to == b } && out.none { o -> o.leaveDays.any { it in leave } }) out += br
            }
        }
        return out.sortedBy { it.from }.take(max)
    }
}

/** The note stored with a leave day: "LEAVE:CL", "LEAVE:SL:H" (half) followed by " · reason". */
object LeaveNote {
    data class Parsed(val code: String, val half: Boolean, val reason: String)

    fun format(code: String, half: Boolean, reason: String): String = "LEAVE:$code${if (half) ":H" else ""} · ${reason.trim()}"

    fun parse(note: String?): Parsed? {
        if (note == null || !note.startsWith("LEAVE:")) return null
        val head = note.substringBefore(" · ").removePrefix("LEAVE:")
        return Parsed(head.substringBefore(":"), head.endsWith(":H"), note.substringAfter(" · ", "").trim())
    }

    /** Days used: half days count 0.5. */
    fun used(days: List<Parsed>): Map<String, Double> = days.groupBy { it.code }.mapValues { (_, l) -> l.sumOf { if (it.half) 0.5 else 1.0 } }
}

/** What a past day needs from HR, if anything. */
object Regularisation {
    fun issue(absentUnmarked: Boolean, hasIn: Boolean, hasOut: Boolean, workedSec: Long, targetSec: Long, manualPunches: Int): String? = when {
        absentUnmarked -> "Absent"
        hasIn && !hasOut -> "Missed OUT"
        workedSec in 1 until targetSec -> "Short by ${(targetSec - workedSec) / 3600}h ${"%02d".format(((targetSec - workedSec) % 3600) / 60)}m"
        manualPunches > 0 -> "Manual punch"
        else -> null
    }
}
