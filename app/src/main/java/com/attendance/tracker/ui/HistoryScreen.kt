package com.attendance.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import com.attendance.tracker.report.DayReport
import com.attendance.tracker.report.DayState
import com.attendance.tracker.report.MonthReport
import com.attendance.tracker.report.MonthReports
import com.attendance.tracker.report.ReportExporter
import java.time.YearMonth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.data.AttendanceEvent
import com.attendance.tracker.data.EventType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("EEEE")
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun HistoryScreen() {
    val c = Soft.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by AttendanceController.state.collectAsStateWithLifecycle()
    var calendarMode by rememberSaveable { mutableStateOf(true) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    val report = remember(month, st.today, st.status) { MonthReports.build(month) }
    fun dayData(d: LocalDate): Triple<LocalDate, com.attendance.tracker.DaySummary, List<AttendanceEvent>> {
        val zone = ZoneId.systemDefault()
        val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return Triple(d, AttendanceController.summary(d), AttendanceController.db.events(from, to).filter { it.type != EventType.STATUS })
    }
    val days = remember(st.today, st.status, calendarMode) {
        if (calendarMode) emptyList()
        else (0L until 14L).map { LocalDate.now().minusDays(it) }.map { dayData(it) }
            .filter { it.third.isNotEmpty() || it.second.secondsByStatus.isNotEmpty() }
    }
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 6.dp, start = 2.dp)) {
                Text(if (calendarMode) "Month view & reports" else "Last 14 days", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Text("History", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().height(40.dp).clip(Soft.pillShape).background(c.cardMuted).padding(3.dp)) {
                listOf(true to "Calendar", false to "Days").forEach { (v, label) ->
                    val sel = v == calendarMode
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(Soft.pillShape).background(if (sel) c.card else c.cardMuted)
                            .pressScale { calendarMode = v },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (sel) c.accent else c.textSecondary, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        if (calendarMode) {
            item { MonthCalendar(report, selected, onMonth = { month = it }, onSelect = { selected = it }) }
            item {
                val (d, sum, ev) = remember(selected, st.today, st.status) { dayData(selected) }
                key(selected) { DayCard(d, sum, ev, expandedByDefault = true) }
            }
            item {
                var pastOpen by remember { mutableStateOf(false) }
                val importer = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { u ->
                    if (u != null) android.widget.Toast.makeText(ctx,
                        com.attendance.tracker.backup.BackupManager.importRecords(ctx, u), android.widget.Toast.LENGTH_LONG).show()
                }
                if (pastOpen) MarkPastDaysDialog(month.atDay(1).coerceAtMost(LocalDate.now().minusDays(1))) { pastOpen = false }
                SoftCard(onClick = { pastOpen = true }) {
                    Text("Mark past days", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Days before you started using the app: set them Present or Absent in one go.",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    MiniAction("⬇ Import records file") { importer.launch(arrayOf("application/json", "application/octet-stream", "text/*", "*/*")) }
                }
            }
            item { MonthSummary(report, onPdf = { ReportExporter.sharePdf(ctx, report) }, onCsv = { ReportExporter.shareCsv(ctx, report) }) }
        } else {
            if (days.isEmpty()) item {
                SoftCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBubble(painterResource(R.drawable.ic_clock), c.accent, c.accentSoft, size = 64.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                        Text("Your attendance will appear once tracking starts.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
            }
            items(days, key = { it.first.toString() }) { (day, sum, events) ->
                DayCard(day, sum, events, expandedByDefault = day == LocalDate.now())
            }
        }
        item { Spacer(Modifier.height(NavBarClearance)) }
    }
}

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
private fun dayColors(state: DayState): Pair<Color, Color> {
    val c = Soft.colors
    return when (state) {
        DayState.OK -> c.successSoft to c.success
        DayState.SHORT -> c.warningSoft to c.warning
        DayState.LATE, DayState.ABSENT -> c.dangerSoft to c.danger
        DayState.LEAVE -> Color(0x268E63E0) to Color(0xFF8E63E0)
        DayState.TODAY -> c.accent to Color.White
        DayState.OFF, DayState.FUTURE -> Color.Transparent to c.textSecondary
    }
}

@Composable
private fun MonthCalendar(r: MonthReport, selected: LocalDate, onMonth: (YearMonth) -> Unit, onSelect: (LocalDate) -> Unit) {
    val c = Soft.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\u2039", Modifier.clip(CircleShape).pressScale { onMonth(r.month.minusMonths(1)) }.padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.headlineSmall, color = c.accent)
            Text(r.month.format(monthTitle), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary, textAlign = TextAlign.Center)
            val canNext = r.month < YearMonth.now()
            Text("\u203A", Modifier.clip(CircleShape).then(if (canNext) Modifier.pressScale { onMonth(r.month.plusMonths(1)) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.headlineSmall, color = if (canNext) c.accent else c.hairline)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            java.time.DayOfWeek.entries.forEach {
                Text(it.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()), Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
        }
        Spacer(Modifier.height(6.dp))
        val lead = r.month.atDay(1).dayOfWeek.value - 1
        val cells: List<DayReport?> = List(lead) { null } + r.days
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val (bg, fg) = dayColors(d.state)
                            val isSel = d.date == selected
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(bg)
                                    .then(if (isSel) Modifier.border(2.dp, c.accent, RoundedCornerShape(12.dp)) else Modifier)
                                    .pressScale { onSelect(d.date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = fg)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(c.success, "Full"); Legend(c.warning, "Short"); Legend(c.danger, "Absent"); Legend(Color(0xFF8E63E0), "Leave / holiday")
        }
    }
}

@Composable
private fun MonthSummary(r: MonthReport, onPdf: () -> Unit, onCsv: () -> Unit) {
    val c = Soft.colors
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_clock), "${r.month.format(monthTitle)} summary")
        Spacer(Modifier.height(10.dp))
        InfoLine("Days present", "${r.presentDays}")
        InfoLine("Average per day", r.avgSec?.let { fmtDuration(it) } ?: "\u2014")
        InfoLine("Total worked", fmtDuration(r.totalSec))
        InfoLine("Overtime", fmtDuration(r.overtimeSec))
        InfoLine("Leave days", "${r.leaveDays}")
        if (r.holidayDays > 0) InfoLine("Holidays", "${r.holidayDays}")
        if (r.absentDays > 0) InfoLine("Absent days", "${r.absentDays}", c.danger)
        if (r.permissionMin > 0) InfoLine("Permission used", fmtDuration(r.permissionMin * 60L))
        r.payslip()?.let { p -> InfoLine("Take-home (estimate)", "\u20B9 %,.0f".format(p.net), c.success) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Share PDF", onPdf, Modifier.weight(1f))
            SoftGhostButton("Excel (CSV)", onCsv, Modifier.weight(1f))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DayCard(day: LocalDate, sum: com.attendance.tracker.DaySummary, events: List<AttendanceEvent>, expandedByDefault: Boolean) {
    val c = Soft.colors
    var expanded by remember { mutableStateOf(expandedByDefault) }
    var markOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var permOpen by remember { mutableStateOf(false) }
    val mark = remember(day, markOpen) { AttendanceController.dayMark(day) }
    val perm = remember(day, permOpen) { AttendanceController.permissionMin(day) }
    if (permOpen) PermissionDialog(day) { permOpen = false }
    if (markOpen) DayMarkDialog(day) { markOpen = false }
    if (addOpen) ManualPunchDialog(day) { addOpen = false }
    val t = sum.secondsByStatus
    val office = t[WorkStatus.IN_OFFICE.name] ?: 0
    val away = t[WorkStatus.OUTSIDE_OFFICE.name] ?: 0
    val lunch = (t[WorkStatus.ON_LUNCH.name] ?: 0) + (t[WorkStatus.LUNCH_EXCEEDED.name] ?: 0)
    val overtime = t[WorkStatus.OVERTIME.name] ?: 0
    SoftCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (day == LocalDate.now()) "Today" else day.format(dayFmt), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(day.format(dateFmt), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            if (mark != null) Pill(mark.label, Color(0xFF8E63E0), Color(0x1F8E63E0))
            else Pill("${fmtTime(sum.firstInMs)} → ${fmtTime(sum.lastOutMs)}", c.accent, c.accentSoft)
        }
        Spacer(Modifier.height(14.dp))
        // Proportional day bar: office / lunch / away
        val total = (office + away + lunch + overtime).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(Soft.pillShape).background(c.cardMuted)) {
            if (office > 0) Box(Modifier.weight(office.toFloat() / total).fillMaxHeight().background(c.success))
            if (lunch > 0) Box(Modifier.weight(lunch.toFloat() / total).fillMaxHeight().background(c.warning))
            if (away > 0) Box(Modifier.weight(away.toFloat() / total).fillMaxHeight().background(c.danger))
            if (overtime > 0) Box(Modifier.weight(overtime.toFloat() / total).fillMaxHeight().background(Color(0xFF6A4FE8)))
            if (office + lunch + away + overtime == 0L) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(c.success, "Office ${fmtDuration(office)}")
            Legend(c.warning, "Lunch ${fmtDuration(lunch)}")
            Legend(c.danger, "Away ${fmtDuration(away)}")
        }
        if (overtime > 0) {
            Spacer(Modifier.height(6.dp))
            Legend(Color(0xFF6A4FE8), "Overtime ${fmtDuration(overtime)}")
        }
        if (perm > 0) {
            Spacer(Modifier.height(6.dp))
            Legend(Color(0xFF0E8C8C), "Permission ${fmtDuration(perm * 60L)} (counted as worked)")
        }
        AnimatedVisibility(expanded && !day.isAfter(LocalDate.now()), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 16.dp)) {
                events.forEachIndexed { i, e -> TimelineRow(e, last = i == events.lastIndex) }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniAction("+ Missed punch") { addOpen = true }
                    MiniAction(if (mark == null) "Mark day" else "Change mark") { markOpen = true }
                    MiniAction(if (perm > 0) "Permission ${fmtDuration(perm * 60L)}" else "+ Permission") { permOpen = true }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Soft.colors.textSecondary)
    }
}

@Composable
private fun TimelineRow(e: AttendanceEvent, last: Boolean) {
    val c = Soft.colors
    val dot = when (e.type) {
        EventType.CHECK_IN -> c.success
        EventType.CHECK_OUT -> c.danger
        EventType.LUNCH_START, EventType.LUNCH_END -> c.warning
        else -> c.accent
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(fmtTime(e.timeMs), Modifier.width(50.dp).padding(top = 1.dp), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(dot))
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(c.hairline))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 14.dp)) {
            Text(e.type.label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            val acc = e.accuracyM?.let { " · ±${it.toInt()} m" } ?: ""
            Text(e.detail + acc, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}
