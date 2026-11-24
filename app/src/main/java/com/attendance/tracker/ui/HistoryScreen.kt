package com.attendance.tracker.ui

import androidx.compose.foundation.background
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
    val st by AttendanceController.state.collectAsStateWithLifecycle()
    val days = remember(st.today, st.status) {
        val zone = ZoneId.systemDefault()
        (0L until 14L).map { LocalDate.now().minusDays(it) }.map { d ->
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            Triple(d, AttendanceController.summary(d), AttendanceController.db.events(from, to)
                .filter { it.type != EventType.STATUS })
        }.filter { it.third.isNotEmpty() || it.second.secondsByStatus.isNotEmpty() }
    }
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.padding(top = 20.dp, start = 4.dp)) {
                Text("Last 14 days", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Text("History", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            }
        }
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
        item { Spacer(Modifier.height(NavBarClearance)) }
    }
}

@Composable
private fun DayCard(day: LocalDate, sum: com.attendance.tracker.DaySummary, events: List<AttendanceEvent>, expandedByDefault: Boolean) {
    val c = Soft.colors
    var expanded by remember { mutableStateOf(expandedByDefault) }
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
            Pill("${fmtTime(sum.firstInMs)} → ${fmtTime(sum.lastOutMs)}", c.accent, c.accentSoft)
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
        AnimatedVisibility(expanded && events.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 16.dp)) {
                events.forEachIndexed { i, e -> TimelineRow(e, last = i == events.lastIndex) }
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
