package com.attendance.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.attendance.tracker.core.DayMark
import com.attendance.tracker.core.DayPlan
import com.attendance.tracker.core.DayPlanner
import com.attendance.tracker.core.LocationSample
import com.attendance.tracker.core.LunchState
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.PresenceEngine
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.data.AttendanceDb
import com.attendance.tracker.data.EventType
import com.attendance.tracker.data.SettingsRepository
import com.attendance.tracker.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.attendance.tracker.AttendanceController.LeaveDay
import com.attendance.tracker.AttendanceController.LeaveType
import com.attendance.tracker.AttendanceController.LongWeekend
import com.attendance.tracker.AttendanceController.RegItem
import com.attendance.tracker.AttendanceController.ReportDiff
import com.attendance.tracker.AttendanceController.Trail
import com.attendance.tracker.AttendanceController.TrailPoint

// Features built on top of AttendanceController, kept here so the controller itself stays small:
// GPS trails, regularisation, long weekends, permission history, rest days, weekly report,
// leave and the company-report comparison.

internal fun AttendanceController.trailDir() = java.io.File(app.filesDir, "trails").apply { mkdirs() }

internal fun AttendanceController.saveTrail(punchMs: Long, kind: String, gate: String?) = runCatching {
    val pts = trail.filter { it.timeMs >= punchMs - 4 * 60_000 }
    val arr = org.json.JSONArray()
    pts.forEach { arr.put(org.json.JSONArray().put(it.lat).put(it.lng).put(it.accuracyM.toDouble()).put(it.timeMs)) }
    val o = org.json.JSONObject().put("punch", punchMs).put("kind", kind).put("gate", gate ?: "").put("pts", arr)
    java.io.File(trailDir(), "$punchMs.json").writeText(o.toString())
    // keep two months
    val old = System.currentTimeMillis() - 62L * 86_400_000
    trailDir().listFiles().orEmpty().filter { it.lastModified() < old }.forEach { it.delete() }
}

/** The GPS points recorded around the punch at [punchMs], if saved. */
fun AttendanceController.trailFor(punchMs: Long): Trail? = runCatching {
    val o = org.json.JSONObject(java.io.File(trailDir(), "$punchMs.json").readText())
    val a = o.getJSONArray("pts")
    Trail(o.getLong("punch"), o.getString("kind"), o.optString("gate").ifBlank { null },
        (0 until a.length()).map { i -> a.getJSONArray(i).let { TrailPoint(it.getDouble(0), it.getDouble(1), it.getDouble(2).toFloat(), it.getLong(3)) } })
}.getOrNull()

/** Days of [month] (up to yesterday) that need HR action: absent, short, manual punches, early leave. */
fun AttendanceController.regularisationList(month: java.time.YearMonth): List<RegItem> {
    val r = com.attendance.tracker.report.MonthReports.build(month)
    val today = LocalDate.now()
    fun hm(sec: Long) = "${sec / 3600}h ${"%02d".format((sec % 3600) / 60)}m"
    return r.days.filter { it.date.isBefore(today) }.mapNotNull { d ->
        val inT = d.firstInMs?.let { clock(it) } ?: "\u2014"
        val outT = d.lastOutMs?.let { clock(it) } ?: "\u2014"
        val manual = d.punches.count { it.detail.startsWith(MANUAL_PREFIX) }
        val target = netTargetSec(d.date)
        val issue = com.attendance.tracker.core.Regularisation.issue(
            absentUnmarked = d.state == com.attendance.tracker.report.DayState.ABSENT && d.mark == null,
            hasIn = d.firstInMs != null, hasOut = d.lastOutMs != null,
            workedSec = d.workedSec, targetSec = target, manualPunches = manual,
        ) ?: return@mapNotNull null
        val detail = when (issue) {
            "Absent" -> "No punches recorded"
            "Missed OUT" -> "IN $inT, no OUT recorded"
            "Manual punch" -> "$manual manual punch${if (manual > 1) "es" else ""} \u00b7 IN $inT \u00b7 OUT $outT"
            else -> "IN $inT \u00b7 OUT $outT \u00b7 net ${hm(d.workedSec)}"
        }
        RegItem(d.date, issue, detail)
    }
}

fun AttendanceController.regularisationText(items: List<RegItem>): String {
    val f = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    return "Attendance regularisation request\n\n" + items.joinToString("\n") { "\u2022 ${it.date.format(f)}: ${it.issue} (${it.detail})" } +
        "\n\nKindly regularise the above days."
}

/** Best upcoming breaks: 1–2 leave days that join a holiday and the weekend into 4+ days off. */
fun AttendanceController.longWeekends(horizonDays: Long = 200): List<LongWeekend> {
    val today = LocalDate.now()
    val wd = settings.settings.value.schedule.workDays
    val marks = db.dayMarks(today.toString(), today.plusDays(horizonDays + 10).toString())
    fun off(d: LocalDate) = d.dayOfWeek !in wd || com.attendance.tracker.data.Holidays.name(d) != null || marks[d.toString()]?.fullDayOff == true
    return com.attendance.tracker.core.LongWeekends.find(today, horizonDays, ::off, com.attendance.tracker.data.Holidays::name)
        .map { LongWeekend(it.leaveDays, it.from, it.to, it.holiday) }
}

/** Permission per day in [month], newest first. */
fun AttendanceController.permissionHistory(month: java.time.YearMonth): List<Pair<LocalDate, Int>> =
    settings.permissions.filterKeys { it.startsWith(month.toString()) }.map { LocalDate.parse(it.key) to it.value }.sortedByDescending { it.first }

/** Leave, holiday or a non-working day: GPS can sleep (the geofence still wakes it at the office). */
fun AttendanceController.isRestDay(nowMs: Long = System.currentTimeMillis()): Boolean {
    val p = plan(nowMs)
    return !p.workDay || p.dayOff != null
}

internal fun AttendanceController.weeklyReport(today: LocalDate) {
    val monday = today.with(java.time.DayOfWeek.MONDAY)
    val days = generateSequence(monday) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
    var present = 0; var total = 0L
    days.forEach { d ->
        val ev = db.events(d.atStartOfDay(zone()).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli())
        if (ev.any { it.type == EventType.CHECK_IN }) { present++; total += netWorkedSec(ev, ev.maxOf { it.timeMs }, d) }
    }
    val avg = if (present > 0) total / present else 0L
    val perm = settings.permissionUsedInMonth(today.toString())
    val reg = regularisationList(java.time.YearMonth.from(today)).count { !it.date.isBefore(monday) }
    val leave = leavesIn(today.year).count { !it.date.isBefore(monday) && !it.date.isAfter(today) }
    notify(4205, "Your week: $present day${if (present == 1) "" else "s"} present \u00b7 avg ${avg / 3600}h ${"%02d".format((avg % 3600) / 60)}m",
        "Permission this month ${perm / 60}h ${perm % 60}m \u00b7 leave this week $leave" + if (reg > 0) " \u00b7 $reg day${if (reg == 1) "" else "s"} to regularise" else " \u00b7 nothing to regularise", private = true)
}

/** Working days from [from] to [to] (weekends and holidays skipped). */
fun AttendanceController.leaveWorkingDays(from: LocalDate, to: LocalDate): List<LocalDate> {
    val wd = settings.settings.value.schedule.workDays
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
        .filter { it.dayOfWeek in wd && com.attendance.tracker.data.Holidays.name(it) == null }.toList()
}

/** Applies leave: marks each working day (half day = Half day mark). Note = "CL · reason". */
fun AttendanceController.applyLeave(from: LocalDate, to: LocalDate, type: LeaveType, half: Boolean, reason: String): Int {
    val days = leaveWorkingDays(from, to)
    days.forEach { d ->
        val mark = if (half && type != LeaveType.UNPAID) DayMark.HALF_DAY else type.mark
        db.setDayMarkWithNote(d.toString(), mark, com.attendance.tracker.core.LeaveNote.format(type.code, half, reason))
    }
    com.attendance.tracker.service.CalendarSync.syncSoon(app)
    refresh()
    return days.size
}

fun AttendanceController.cancelLeave(day: LocalDate) { db.setDayMark(day.toString(), null); refresh(); com.attendance.tracker.service.CalendarSync.syncSoon(app) }

/** Leaves applied in [year] (from their notes). */
fun AttendanceController.leavesIn(year: Int): List<LeaveDay> = db.marksWithNotes("$year-01-01", "$year-12-31").mapNotNull { (day, _, note) ->
    val p = com.attendance.tracker.core.LeaveNote.parse(note) ?: return@mapNotNull null
    val type = LeaveType.entries.firstOrNull { it.code == p.code } ?: return@mapNotNull null
    LeaveDay(LocalDate.parse(day), type, p.half, p.reason)
}

/** Used and left per type this year (half days count 0.5). */
fun AttendanceController.leaveBalance(year: Int = LocalDate.now().year): Map<LeaveType, Pair<Double, Double>> {
    val used = leavesIn(year).groupBy { it.type }.mapValues { (_, l) -> l.sumOf { if (it.half) 0.5 else 1.0 } }
    val q = settings.leaveQuota
    val compOff = compOffDays(year).sumOf { it.second }
    return LeaveType.entries.filter { it != LeaveType.UNPAID }.associateWith { t ->
        val u = used[t] ?: 0.0
        val quota = if (t == LeaveType.COMP_OFF) compOff else (q[t.code] ?: 0.0)
        u to (quota - u)
    }
}

/**
 * Comp-off earned in [year]: days you worked on a weekly off or holiday.
 * A full day's net earns 1 comp-off, at least half of it earns 0.5.
 */
fun AttendanceController.compOffDays(year: Int): List<Pair<LocalDate, Double>> {
    val wd = settings.settings.value.schedule.workDays
    val from = LocalDate.of(year, 1, 1); val to = LocalDate.of(year + 1, 1, 1)
    val byDay = db.events(from.atStartOfDay(zone()).toInstant().toEpochMilli(), to.atStartOfDay(zone()).toInstant().toEpochMilli())
        .groupBy { java.time.Instant.ofEpochMilli(it.timeMs).atZone(zone()).toLocalDate() }
    return byDay.mapNotNull { (d, ev) ->
        val offDay = d.dayOfWeek !in wd || com.attendance.tracker.data.Holidays.name(d) != null
        if (!offDay || d == LocalDate.now() || ev.none { it.type == EventType.CHECK_IN }) return@mapNotNull null
        val net = netWorkedSec(ev, ev.maxOf { it.timeMs }, d)
        val target = netTargetSec(d)
        when { net >= target -> d to 1.0; net >= target / 2 -> d to 0.5; else -> null }
    }.sortedBy { it.first }
}

/** Value of unused earned leave if encashed: basic per day (basic ÷ 26) × days left. */
fun AttendanceController.leaveEncashment(): Pair<Double, Double>? {
    val r = settings.salaryRules
    if (r.monthlyGross <= 0) return null
    val elLeft = (leaveBalance()[LeaveType.EARNED]?.second ?: 0.0).coerceAtLeast(0.0)
    val perDay = r.monthlyGross * r.basicPercent / 100 / 26
    return elLeft to elLeft * perDay
}

/** On-duty trips with distance for [month]: (date, reason, km). */
fun AttendanceController.travelClaims(month: java.time.YearMonth): List<Triple<LocalDate, String, Double>> {
    val from = month.atDay(1).atStartOfDay(zone()).toInstant().toEpochMilli()
    val to = month.plusMonths(1).atDay(1).atStartOfDay(zone()).toInstant().toEpochMilli()
    val km = Regex("""([0-9]+(?:\.[0-9]+)?) km""")
    return db.events(from, to).filter { it.type == EventType.DUTY_END }.mapNotNull { e ->
        val k = km.find(e.detail)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val reason = e.detail.removePrefix("Back from duty \u00b7 ").substringBefore(" \u00b7 ")
        Triple(java.time.Instant.ofEpochMilli(e.timeMs).atZone(zone()).toLocalDate(), reason, k)
    }
}

fun AttendanceController.leaveRequestText(from: LocalDate, to: LocalDate, type: LeaveType, half: Boolean, reason: String, days: Int): String {
    val f = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    val range = if (from == to) from.format(f) else "${from.format(f)} to ${to.format(f)}"
    return "Leave request \u2013 ${type.label}\n" +
        "Date: $range" + (if (half) " (half day)" else "") + "\n" +
        "Working days: ${if (half) "0.5" else "$days"}\n" +
        (if (reason.isNotBlank()) "Reason: ${reason.trim()}\n" else "") + "\nKindly approve."
}

/** Day-by-day comparison of the company report with this app's own records. */
fun AttendanceController.compareReport(rows: List<com.attendance.tracker.core.CompanyReport.Row>): List<ReportDiff> {
    // Remember the company totals so History can tick the days that match.
    settings.companyTotals = settings.companyTotals + rows.filter { it.worked }.associate { it.date.toString() to it.totalMin }
    return rows.sortedBy { it.date }.map { r ->
        val ev = db.events(r.date.atStartOfDay(zone()).toInstant().toEpochMilli(), r.date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli())
        // Same basis as the company total: time inside (and on duty), no lunch rule.
        val app = if (ev.any { it.type == EventType.CHECK_IN }) (presenceSec(ev, ev.maxOf { it.timeMs }).let { it.first + it.second } / 60).toInt() else null
        ReportDiff(r.date, r.status, r.totalMin, app, db.dayMark(r.date.toString()))
    }
}

/** Adds the report's days that have no records in the app (IN, outside time as a break, OUT, and marks). */
fun AttendanceController.importReportDays(rows: List<com.attendance.tracker.core.CompanyReport.Row>): Int {
    val events = mutableListOf<Triple<Long, EventType, String>>()
    val marks = mutableMapOf<String, String>()
    val existingMarks = db.dayMarks("0000-01-01", "9999-12-31")
    var days = 0
    rows.filter { !it.date.isAfter(LocalDate.now().minusDays(1)) }.forEach { r ->
        val ev = db.events(r.date.atStartOfDay(zone()).toInstant().toEpochMilli(), r.date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli())
        if (ev.any { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT }) return@forEach
        fun at(min: Int) = r.date.atStartOfDay(zone()).plusMinutes(min.toLong()).toInstant().toEpochMilli()
        val src = MANUAL_PREFIX + "Imported from company report"
        if (r.worked && r.outMin > r.inMin) {
            events += Triple(at(r.inMin), EventType.CHECK_IN, src)
            if (r.outsideMin > 0) {
                val ls = maxOf(13 * 60 + 30, r.inMin + 1).coerceAtMost(r.outMin - r.outsideMin - 1)
                events += Triple(at(ls), EventType.LUNCH_START, "Imported \u00b7 outside time (time of day estimated)")
                events += Triple(at(ls + r.outsideMin), EventType.LUNCH_END, "Imported")
            }
            events += Triple(at(r.outMin), EventType.CHECK_OUT, src)
            days++
        }
        val mark = when (r.status) { "AA" -> DayMark.ABSENT; "HH" -> DayMark.HOLIDAY; "XA", "AX" -> DayMark.HALF_DAY; else -> null }
        if (mark != null && r.date.toString() !in existingMarks) { marks[r.date.toString()] = mark.name; if (!r.worked) days++ }
    }
    if (events.isEmpty() && marks.isEmpty()) return 0
    com.attendance.tracker.backup.BackupManager.saveLocal(app, "before-import")
    db.importBatch(events, marks)
    com.attendance.tracker.backup.BackupManager.backupSoon(app)
    refresh()
    return days
}
