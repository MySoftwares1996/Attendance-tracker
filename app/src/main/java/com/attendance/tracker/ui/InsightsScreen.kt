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

    return InsightsData(
        streak, best,
        MonthReports.build(YearMonth.now()), MonthReports.build(YearMonth.now().minusMonths(1)),
        byDay, Insights.avgMinutes(firstIns.map { it.second }), Insights.avgMinutes(lastOuts), topGate,
        AttendanceController.commute(today), avg, fastest, bestDep, commutes.size,
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
                            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
                            Spacer(Modifier.height(4.dp))
                            Text(value, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, maxLines = 1)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (d.commuteDays == 0) MiniAction("Set home to measure commute") { onOpenSetup("home") }
        }
        Spacer(Modifier.height(NavBarClearance))
    }
}

@Composable
private fun StreakCard(d: InsightsData) {
    val c = Soft.colors
    val target = AttendanceController.settings.settings.value.schedule.netMinutes
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔥", fontSize = 40.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${d.streak} day${if (d.streak == 1) "" else "s"} in a row", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text("with your full ${target / 60}h ${"%02d".format(target % 60)}m net · best ${d.bestStreak}",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        if (d.streak == 0) Text("Your streak starts the next working day you complete your hours.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

@Composable
private fun PayCard(r: MonthReport, onOpenSalary: () -> Unit) {
    val c = Soft.colors
    val month = r.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    SoftCard(onClick = onOpenSalary) {
        CardHeader(painterResource(R.drawable.ic_rupee), "$month take-home", "Estimate \u00b7 tap for full breakdown", c.success, c.successSoft)
        Spacer(Modifier.height(12.dp))
        val p = r.payslip()
        if (p == null) {
            Text("Set up your salary and deductions in the Salary tab to see your take-home pay.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        } else {
            Text("\u20B9 %,.0f".format(p.net), style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
            Text("Gross \u20B9 %,.0f \u2212 deductions \u20B9 %,.0f".format(p.gross, p.totalDeductions),
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun MonthCompareCard(d: InsightsData) {
    val c = Soft.colors
    val thisAvg = d.thisMonth.avgSec
    val lastAvg = d.lastMonth.avgSec
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_clock), "This month", "Net hours per day")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(thisAvg?.let { fmtDuration(it) } ?: "—", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            Spacer(Modifier.width(10.dp))
            if (thisAvg != null && lastAvg != null) {
                val diff = thisAvg - lastAvg
                Pill((if (diff >= 0) "+" else "−") + fmtDuration(kotlin.math.abs(diff)) + " vs last month",
                    if (diff >= 0) c.success else c.warning, if (diff >= 0) c.successSoft else c.warningSoft)
            }
        }
        Spacer(Modifier.height(8.dp))
        InfoLine("Days present", "${d.thisMonth.presentDays}")
        InfoLine("Overtime this month", fmtDuration(d.thisMonth.overtimeSec), Color(0xFF6A4FE8))
        d.avgArrival?.let { InfoLine("Usual arrival", clockMin(it)) }
        d.avgLogout?.let { InfoLine("Usual logout", clockMin(it)) }
    }
}

@Composable
private fun ArrivalCard(d: InsightsData) {
    val c = Soft.colors
    if (d.arrivalByDay.isEmpty()) return
    val days = DayOfWeek.entries.filter { it in d.arrivalByDay.keys }
    val minA = d.arrivalByDay.values.min(); val maxA = d.arrivalByDay.values.max()
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_clock), "Arrival by weekday", "Last 60 days · taller = later", Color(0xFF6A4FE8), Color(0x1F6A4FE8))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { day ->
                val v = d.arrivalByDay.getValue(day)
                val f = if (maxA == minA) 0.6f else 0.3f + 0.7f * (v - minA).toFloat() / (maxA - minA)
                val a by animateFloatAsState(f, tween(800), label = "arr$day")
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(clockMin(v).substringBefore(" "), fontSize = 10.sp, color = c.textSecondary)
                    Box(Modifier.fillMaxWidth().fillMaxHeight(a * 0.8f).clip(RoundedCornerShape(8.dp))
                        .background(if (v == maxA && maxA - minA >= 15) c.warning else c.accent))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { Text(it.getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp, color = c.textSecondary) }
        }
        val avg = d.avgArrival
        val latest = d.arrivalByDay.maxByOrNull { it.value }
        if (avg != null && latest != null && latest.value - avg >= 10) {
            Text("${latest.key.getDisplayName(TextStyle.FULL, Locale.getDefault())}s you arrive ${latest.value - avg} min later than usual.",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        }
    }
}

@Composable
private fun CommuteCard(d: InsightsData, onSetup: () -> Unit) {
    val c = Soft.colors
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_target), "Commute", "Home → office", c.warning, c.warningSoft)
        Spacer(Modifier.height(12.dp))
        if (!AttendanceController.settings.hasHome) {
            Text("Mark your home once, and the app notes when you leave home and reach the office.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            SoftButton("Set my home", onSetup)
            return@SoftCard
        }
        val zone = ZoneId.systemDefault()
        fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        d.commuteToday?.let { (min, left) ->
            Text("$min min today", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text("Left home ${clockMin(at(left))}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
        }
        if (d.commuteDays == 0) {
            Text("No trips measured yet. Your first commute appears after you next leave home for the office.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        } else {
            d.commuteAvg?.let { InfoLine("Average (${d.commuteDays} trips)", "$it min") }
            d.commuteFastest?.let { (day, m) -> InfoLine("Fastest", "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${day.dayOfMonth} · $m min") }
            d.bestDeparture?.let { (dep, m) -> InfoLine("Best time to leave", "${clockMin(dep)}–${clockMin(dep + 15)} · ~$m min", c.success) }
        }
        Spacer(Modifier.height(6.dp))
        MiniAction("Change home", onSetup)
    }
}
