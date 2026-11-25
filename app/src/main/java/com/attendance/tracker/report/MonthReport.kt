package com.attendance.tracker.report

import com.attendance.tracker.AttendanceController
import com.attendance.tracker.core.DayMark
import com.attendance.tracker.core.WorkedTime
import com.attendance.tracker.data.AttendanceEvent
import com.attendance.tracker.data.EventType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class DayState { OK, SHORT, LATE, ABSENT, LEAVE, OFF, TODAY, FUTURE }

data class DayReport(
    val date: LocalDate,
    val firstInMs: Long?,
    val lastOutMs: Long?,
    val workedSec: Long,
    val lunchSec: Long,
    val overtimeSec: Long,
    val late: Boolean,
    val mark: DayMark?,
    val state: DayState,
    val punches: List<AttendanceEvent>,
    /** Public / company holiday name, if any. */
    val holiday: String? = null,
)

data class MonthReport(
    val month: YearMonth,
    val days: List<DayReport>,
    val presentDays: Int,
    val avgSec: Long?,
    val totalSec: Long,
    val overtimeSec: Long,
    val lateDays: Int,
    val leaveDays: Int,
    val targetSec: Long,
    val holidayDays: Int = 0,
    /** Days you're expected to work this month (work days minus holidays) — basis for one day's pay. */
    val workingDays: Int = 0,
    val absentDays: Int = 0,
    val halfDays: Int = 0,
) {
    /** This month's payslip with all earnings and deductions, or null when no salary is set. */
    fun payslip(): com.attendance.tracker.core.Payslip? {
        val r = AttendanceController.settings.salaryRules
        if (r.monthlyGross <= 0.0) return null
        return com.attendance.tracker.core.PayCalculator.payslip(r, workingDays, overtimeSec, absentDays, halfDays)
    }

}

/** Builds the month view / report from the attendance log. */
object MonthReports {
    fun build(month: YearMonth, nowMs: Long = System.currentTimeMillis()): MonthReport {
        val zone = ZoneId.systemDefault()
        val db = AttendanceController.db
        val sched = AttendanceController.settings.settings.value.schedule
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val first = month.atDay(1); val last = month.atEndOfMonth()
        val events = db.events(first.atStartOfDay(zone).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            .groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }
        val marks = db.dayMarks(first.toString(), last.toString())

        val days = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val ev = events[date].orEmpty()
            val punches = ev.filter { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT }
            val close = if (date == today) nowMs else ev.maxOfOrNull { it.timeMs } ?: 0L
            val worked = if (punches.isEmpty()) 0L else AttendanceController.netWorkedSec(ev, close, date)
            var lunch = 0L; var open: Long? = null
            ev.forEach {
                if (it.type == EventType.LUNCH_START) open = it.timeMs
                if (it.type == EventType.LUNCH_END) { open?.let { s -> lunch += (it.timeMs - s) / 1000 }; open = null }
            }
            val mark = marks[date.toString()]
            val holiday = if (mark == null) com.attendance.tracker.data.Holidays.name(date) else null
            val required = (if (mark == DayMark.HALF_DAY) sched.netMinutes / 2 else sched.netMinutes) * 60L
            val firstIn = punches.firstOrNull { it.type == EventType.CHECK_IN }?.timeMs
            val lastOut = punches.lastOrNull { it.type == EventType.CHECK_OUT }?.timeMs
            val late = false // no arrival deadline: the day starts whenever you arrive
            val workDay = date.dayOfWeek in sched.workDays && holiday == null
            val state = when {
                date.isAfter(today) -> DayState.FUTURE
                mark?.fullDayOff == true || (holiday != null && worked == 0L) -> DayState.LEAVE
                worked == 0L && date == today -> DayState.TODAY
                worked == 0L -> if (workDay) DayState.ABSENT else DayState.OFF
                late -> DayState.LATE
                date == today -> DayState.TODAY
                worked < required -> DayState.SHORT
                else -> DayState.OK
            }
            DayReport(date, firstIn, lastOut, worked, lunch, if (worked > 0) (worked - required).coerceAtLeast(0) else 0,
                late, mark, state, punches, holiday)
        }
        val workedDays = days.filter { it.workedSec > 0 }
        return MonthReport(
            month = month, days = days,
            presentDays = workedDays.size,
            avgSec = WorkedTime.averageSec(days.map { it.workedSec }),
            totalSec = workedDays.sumOf { it.workedSec },
            overtimeSec = days.sumOf { it.overtimeSec },
            lateDays = days.count { it.late },
            leaveDays = days.count { it.mark?.fullDayOff == true },
            targetSec = sched.netMinutes * 60L,
            holidayDays = days.count { it.holiday != null && it.date.dayOfWeek in sched.workDays },
            workingDays = days.count { it.date.dayOfWeek in sched.workDays && it.holiday == null },
            absentDays = days.count { it.state == DayState.ABSENT },
            halfDays = days.count { it.mark == DayMark.HALF_DAY },
        )
    }
}
