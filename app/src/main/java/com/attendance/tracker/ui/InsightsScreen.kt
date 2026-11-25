package com.attendance.tracker.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.Insights
import com.attendance.tracker.data.EventType
import com.attendance.tracker.data.Holidays
import com.attendance.tracker.report.MonthReport
import com.attendance.tracker.report.MonthReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private data class InsightsData(
    val streak: Int,
    val bestStreak: Int,
    val thisMonth: MonthReport,
    val lastMonth: MonthReport,
    /** weekday → average arrival (minutes after midnight) */
    val arrivalByDay: Map<DayOfWeek, Int>,
    val avgArrival: Int?,
    val avgLogout: Int?,
    val topGate: Pair<String, Int>?,
    val commuteToday: Pair<Long, Long>?,
    val commuteAvg: Long?,
    val commuteFastest: Pair<LocalDate, Long>?,
    val bestDeparture: Pair<Int, Long>?,
    val commuteDays: Int,
    /** Last 13 weeks: week start → (average net seconds, average arrival minutes), null when no data. */
    val weeks: List<Triple<LocalDate, Long?, Int?>> = emptyList(),
)

private fun clockMin(min: Int): String =
    "%d:%02d %s".format(if ((min / 60) % 12 == 0) 12 else (min / 60) % 12, min % 60, if (min < 720) "AM" else "PM")

private fun load(): InsightsData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val sched = AttendanceController.settings.settings.value.schedule
    val from = today.minusDays(120)
    val events = AttendanceController.db.events(from.atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        .groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }
    fun minOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    // Streak of working days that reached the net target (newest first)
    val streakDays = (0L..120L).map { today.minusDays(it) }.mapNotNull { d ->
        val ev = events[d].orEmpty()
        val working = d.dayOfWeek in sched.workDays && Holidays.name(d) == null && AttendanceController.dayMark(d)?.fullDayOff != true
        val hasPunch = ev.any { it.type == EventType.CHECK_IN }
        val net = if (hasPunch) AttendanceController.netWorkedSec(ev, if (d == today) System.currentTimeMillis() else ev.maxOf { it.timeMs }, d) else 0L
        val met = net >= AttendanceController.netTargetSec(d)
        when {
            d == today -> if (met) true else null // today counts once it's reached
            !working -> null
            else -> met
        }.let { listOf(it) }
    }.flatten()
    val (streak, best) = Insights.streaks(streakDays)

    // Arrival / logout times (last 60 days)
    val recent = events.filterKeys { !it.isBefore(today.minusDays(60)) }
    val firstIns = recent.mapNotNull { (d, ev) -> ev.firstOrNull { it.type == EventType.CHECK_IN }?.let { d to minOf(it.timeMs) } }
    val lastOuts = recent.filterKeys { it != today }.mapNotNull { (_, ev) -> ev.lastOrNull { it.type == EventType.CHECK_OUT }?.let { minOf(it.timeMs) } }
    val byDay = firstIns.groupBy({ it.first.dayOfWeek }, { it.second }).mapValues { (_, v) -> v.sum() / v.size }

    // Gates
    val gateRe = Regex("via (.+?)( \\(|$| ·)")
    val gates = recent.values.flatten().filter { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT }
        .mapNotNull { gateRe.find(it.detail)?.groupValues?.get(1) }
    val topGate = gates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { it.key to (it.value * 100 / gates.size) }

    // Commute (last 60 days)
    val commutes = (0L..60L).map { today.minusDays(it) }.mapNotNull { d -> AttendanceController.commute(d)?.let { d to it } }
    val avg = if (commutes.isEmpty()) null else commutes.sumOf { it.second.first } / commutes.size
    val fastest = commutes.minByOrNull { it.second.first }?.let { it.first to it.second.first }
    val bestDep = Insights.bestDeparture(commutes.map { minOf(it.second.second) to it.second.first })

    // Weekly trend (13 weeks): average net and arrival from the month reports.
    val reports = (0L..3L).map { YearMonth.now().minusMonths(it) }.mapNotNull { runCatching { MonthReports.build(it) }.getOrNull() }
    val allDays = reports.flatMap { it.days }.filter { it.workedSec > 0 && it.date != today }
    val monday0 = today.with(DayOfWeek.MONDAY)
    val weeks = (12L downTo 0L).map { w ->
        val ws = monday0.minusWeeks(w)
        val inWeek = allDays.filter { !it.date.isBefore(ws) && it.date.isBefore(ws.plusWeeks(1)) }
        Triple(ws, inWeek.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.workedSec } / l.size },
            inWeek.mapNotNull { it.firstInMs }.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { minOf(it) } / l.size })
    }

    return InsightsData(
        streak, best,
        MonthReports.build(YearMonth.now()), MonthReports.build(YearMonth.now().minusMonths(1)),
        byDay, Insights.avgMinutes(firstIns.map { it.second }), Insights.avgMinutes(lastOuts), topGate,
        AttendanceController.commute(today), avg, fastest, bestDep, commutes.size, weeks,
    )
}

@Composable
fun InsightsScreen(onOpenSetup: (String) -> Unit) {
    val c = Soft.colors
    var data by remember { mutableStateOf<InsightsData?>(null) }
    LaunchedEffect(Unit) { data = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(top = 6.dp, start = 2.dp)) {
            Text("Patterns from your own attendance", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Text("Insights", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        } else {
            // Take-home first (tap → Salary), then everything else as small tiles, two per row.
            d.thisMonth.payslip()?.let { p ->
                SoftCard(onClick = { onOpenSetup("salary") }) {
                    Text("${d.thisMonth.month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM"))} take-home (estimate)",
                        style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Text("\u20B9 %,.0f".format(p.net), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                    Text("Salary \u20B9 %,.0f \u2212 deductions \u20B9 %,.0f \u00b7 tap for details".format(p.gross, p.totalDeductions),
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
            val m = d.thisMonth; val lm = d.lastMonth
            val tiles = buildList {
                add(Triple("\uD83D\uDD25 Streak", "${d.streak} day${if (d.streak == 1) "" else "s"}", "full hours in a row \u00b7 best ${d.bestStreak}"))
                add(Triple("\uD83D\uDCC5 This month", m.avgSec?.let { fmtDuration(it) } ?: "\u2014", "avg net \u00b7 ${m.presentDays} days present"))
                add(Triple("\u23F1 Overtime", fmtDuration(m.overtimeSec), "this month"))
                val diff = if (m.avgSec != null && lm.avgSec != null) m.avgSec - lm.avgSec else null
                add(Triple("\u2194 vs last month", diff?.let { (if (it >= 0) "+" else "\u2212") + fmtDuration(kotlin.math.abs(it)) } ?: "\u2014",
                    lm.avgSec?.let { "last month avg ${fmtDuration(it)}" } ?: "no data last month"))
                add(Triple("\uD83C\uDF05 Usual arrival", d.avgArrival?.let { clockMin(it) } ?: "\u2014",
                    d.avgLogout?.let { "usually leave ${clockMin(it)}" } ?: "last 60 days"))
                if (d.commuteAvg != null || d.commuteToday != null) add(Triple("\uD83D\uDE97 Commute",
                    (d.commuteToday?.first ?: d.commuteAvg)?.let { "$it min" } ?: "\u2014",
                    "avg ${d.commuteAvg ?: "\u2014"} min" + (d.bestDeparture?.let { " \u00b7 best leave ${clockMin(it.first)}" } ?: "")))
                d.topGate?.let { (g, pct) -> add(Triple("\uD83D\uDEAA Top gate", g, "$pct% of punches")) }
                add(Triple("\uD83C\uDFD6 Leave & absent", "${m.leaveDays} / ${m.absentDays}", "leave / absent this month"))
            }
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, value, sub) ->
                        Column(Modifier.weight(1f).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .background(c.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(value, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (d.commuteDays == 0) MiniAction("Set home to measure commute") { onOpenSetup("home") }
            GoalsCard(d.thisMonth)
            TrendCard(d)
        }
        Spacer(Modifier.height(NavBarClearance))
    }
}

/** Net hours per week (last 13 weeks) against the target, and how arrival time is moving. */
@Composable
private fun TrendCard(d: InsightsData) {
    val c = Soft.colors
    val pts = d.weeks.filter { it.second != null }
    if (pts.size < 2) return
    val target = AttendanceController.netTargetSec(LocalDate.now()).toFloat()
    SoftCard {
        Text("Trend \u00b7 last 13 weeks", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Text("Average net per day, each week \u00b7 dashed line = target", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        val maxV = (pts.maxOf { it.second!! }.toFloat()).coerceAtLeast(target) * 1.1f
        val minV = (pts.minOf { it.second!! }.toFloat()).coerceAtMost(target) * 0.85f
        val line = c.accent; val tgt = c.success
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            fun x(i: Int) = i * size.width / (d.weeks.size - 1).coerceAtLeast(1)
            fun y(v: Float) = size.height - (v - minV) / (maxV - minV) * size.height
            drawLine(tgt, androidx.compose.ui.geometry.Offset(0f, y(target)), androidx.compose.ui.geometry.Offset(size.width, y(target)), 3f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            var prev: androidx.compose.ui.geometry.Offset? = null
            d.weeks.forEachIndexed { i, w ->
                val v = w.second ?: return@forEachIndexed
                val o = androidx.compose.ui.geometry.Offset(x(i), y(v.toFloat()))
                prev?.let { drawLine(line, it, o, 5f) }
                drawCircle(line, 7f, o)
                prev = o
            }
        }
        val arr = d.weeks.mapNotNull { it.third }
        if (arr.size >= 2) {
            Spacer(Modifier.height(8.dp))
            val delta = arr.last() - arr.first()
            Text("Arrival: ${clockMin(arr.first())} \u2192 ${clockMin(arr.last())}" + when {
                delta <= -5 -> " \u00b7 ${-delta} min earlier \uD83D\uDC4D"
                delta >= 5 -> " \u00b7 $delta min later"
                else -> " \u00b7 steady"
            }, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        }
    }
}

/** Personal goals for this month: arrive by a time, and no short days. */
@Composable
private fun GoalsCard(m: MonthReport) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val c = Soft.colors
    val s = AttendanceController.settings
    var goalMin by remember { mutableStateOf(s.goalArrivalMin) }
    val worked = m.days.filter { it.firstInMs != null }
    val onTime = if (goalMin < 0) 0 else worked.count { d ->
        java.time.Instant.ofEpochMilli(d.firstInMs!!).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute } <= goalMin
    }
    val shortDays = m.days.count { it.state == com.attendance.tracker.report.DayState.SHORT }
    SoftCard {
        Text("Goals \u00b7 ${m.month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM"))}", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (goalMin < 0) "\uD83C\uDFAF Arrive by \u2014 (tap Set)" else "\uD83C\uDFAF Arrive by ${clockMin(goalMin)}",
                    style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                if (goalMin >= 0) Text("$onTime of ${worked.size} days so far", style = MaterialTheme.typography.bodySmall,
                    color = if (onTime == worked.size) c.success else c.warning)
            }
            MiniAction(if (goalMin < 0) "Set" else "Change") {
                val init = if (goalMin < 0) 9 * 60 + 45 else goalMin
                android.app.TimePickerDialog(ctx, { _, h, mm -> goalMin = h * 60 + mm; s.goalArrivalMin = goalMin }, init / 60, init % 60, false).show()
            }
        }
        if (goalMin >= 0 && worked.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(c.cardMuted)) {
                Box(Modifier.fillMaxWidth(onTime.toFloat() / worked.size).height(6.dp).background(c.success))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (shortDays == 0) "\u2705 No short days this month" else "\u26A0 $shortDays short day${if (shortDays == 1) "" else "s"} this month \u2014 goal: none",
            style = MaterialTheme.typography.bodyLarge, color = if (shortDays == 0) c.success else c.warning)
    }
}
