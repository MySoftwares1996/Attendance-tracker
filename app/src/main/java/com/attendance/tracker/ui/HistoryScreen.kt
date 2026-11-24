package com.attendance.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.data.AttendanceEvent
import com.attendance.tracker.data.EventType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")

@Composable
fun HistoryScreen(modifier: Modifier) {
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
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Attendance history", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        if (days.isEmpty()) item { Text("No attendance recorded yet.") }
        items(days, key = { it.first.toString() }) { (day, sum, events) ->
            val t = sum.secondsByStatus
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(day.format(dayFmt), style = MaterialTheme.typography.titleMedium)
                    Text("In ${fmtTime(sum.firstInMs)} · Out ${fmtTime(sum.lastOutMs)}")
                    Text(
                        "Office ${fmtDuration(t[WorkStatus.IN_OFFICE.name] ?: 0)} · " +
                            "Outside ${fmtDuration(t[WorkStatus.OUTSIDE_OFFICE.name] ?: 0)} · " +
                            "Lunch ${fmtDuration((t[WorkStatus.ON_LUNCH.name] ?: 0) + (t[WorkStatus.LUNCH_EXCEEDED.name] ?: 0))}",
                        fontSize = 13.sp
                    )
                    events.forEach { EventRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: AttendanceEvent) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(fmtTime(e.timeMs), Modifier.width(52.dp), fontWeight = FontWeight.Medium, fontSize = 13.sp)
        val acc = e.accuracyM?.let { " (±${it.toInt()} m)" } ?: ""
        Text("${e.type.label}: ${e.detail}$acc", fontSize = 13.sp)
    }
}
