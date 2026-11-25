package com.attendance.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.report.DayState
import com.attendance.tracker.report.MonthReports
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.cos

private val rFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** Days this month that need HR action, with one tap to share a regularisation request. */
@Composable
fun RegularisationCard(month: YearMonth, refreshKey: Any) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val items = rememberInBackground(month, refreshKey) { AttendanceController.regularisationList(month) } ?: emptyList()
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Needs regularisation", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(if (items.isEmpty()) "Nothing to fix this month ✓" else "${items.size} day${if (items.size == 1) "" else "s"} to raise with HR",
                    style = MaterialTheme.typography.bodySmall, color = if (items.isEmpty()) c.success else c.warning)
            }
            if (items.isNotEmpty()) MiniAction("Share all") {
                runCatching { ctx.startActivity(AttendanceController.shareIntent(AttendanceController.regularisationText(items))) }
            }
        }
        items.forEach { it ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.warningSoft)
                .pressScale { runCatching { ctx.startActivity(AttendanceController.shareIntent(AttendanceController.regularisationText(listOf(it)))) } }
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${it.date.format(rFmt)} · ${it.issue}", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(it.detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text("Share", style = MaterialTheme.typography.labelMedium, color = c.accent)
            }
        }
    }
}

/** The whole year at a glance: leave, holidays, absent and half days in colour. */
@Composable
fun YearCalendarDialog(year: Int, onDismiss: () -> Unit) {
    val c = Soft.colors
    val months = rememberInBackground(year) {
        (1..12).map { m -> YearMonth.of(year, m) }.filter { !it.isAfter(YearMonth.now().plusMonths(12)) }
            .map { ym -> ym to runCatching { MonthReports.build(ym) }.getOrNull() }
    }
    fun color(s: DayState, half: Boolean): Color = when {
        half -> Color(0xFFF59F00)
        s == DayState.OK -> Color(0xFF12B886)
        s == DayState.SHORT -> Color(0xFFF59F00).copy(alpha = 0.6f)
        s == DayState.ABSENT -> Color(0xFFF0506E)
        s == DayState.LEAVE -> Color(0xFF8E63E0)
        else -> Color.Transparent
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$year at a glance") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Worked" to Color(0xFF12B886), "Leave / holiday" to Color(0xFF8E63E0), "Absent" to Color(0xFFF0506E), "Half / short" to Color(0xFFF59F00))
                        .forEach { (l, col) -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(4.dp))
                            Text(l, style = MaterialTheme.typography.labelSmall, color = c.textSecondary) } }
                }
                if (months == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                months.orEmpty().chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (ym, r) ->
                            Column(Modifier.weight(1f)) {
                                Text(ym.format(DateTimeFormatter.ofPattern("MMMM")), style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
                                val lead = ym.atDay(1).dayOfWeek.value - 1
                                val cells: List<com.attendance.tracker.report.DayReport?> = List(lead) { null } + (r?.days ?: emptyList())
                                cells.chunked(7).forEach { wk ->
                                    Row {
                                        (0 until 7).forEach { i ->
                                            val d = wk.getOrNull(i)
                                            Box(Modifier.weight(1f).aspectRatio(1f).padding(1.dp).clip(RoundedCornerShape(3.dp))
                                                .background(if (d == null) Color.Transparent else color(d.state, d.mark == com.attendance.tracker.core.DayMark.HALF_DAY).let { if (it == Color.Transparent) c.cardMuted else it }),
                                                contentAlignment = Alignment.Center) {
                                                if (d != null) Text("${d.date.dayOfMonth}", fontSize = 7.sp, color = c.textPrimary, textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                }
                                r?.let { Text("${it.presentDays}P · ${it.leaveDays}L · ${it.absentDays}A", style = MaterialTheme.typography.labelSmall, color = c.textSecondary) }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

/** GPS points around a punch, drawn against the office outline and gates (proof of the crossing time). */
@Composable
fun TrailDialog(punchMs: Long, onDismiss: () -> Unit) {
    val c = Soft.colors
    val t = remember(punchMs) { AttendanceController.trailFor(punchMs) }
    val office = AttendanceController.settings.settings.value.office
    val tf = DateTimeFormatter.ofPattern("h:mm:ss a")
    fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(tf)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (t == null) "No GPS trail" else "${t.kind} at ${hm(t.punchMs)}" + (t.gate?.let { " · $it" } ?: "")) },
        text = {
            if (t == null || office == null || t.points.isEmpty()) {
                Text("GPS points are saved for punches from this version onwards.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val outline = if (office.hasPolygon) office.polygon.map { it.lat to it.lng } else
                    (0 until 36).map { i -> val a = Math.toRadians(i * 10.0)
                        (office.lat + office.radiusM * kotlin.math.sin(a) / 111_195.0) to (office.lng + office.radiusM * cos(a) / (111_195.0 * cos(Math.toRadians(office.lat)))) }
                val all = outline + t.points.map { it.lat to it.lng } + office.gates.map { it.lat to it.lng }
                val minLat = all.minOf { it.first }; val maxLat = all.maxOf { it.first }
                val minLng = all.minOf { it.second }; val maxLng = all.maxOf { it.second }
                val kx = cos(Math.toRadians(office.lat))
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(c.cardMuted)) {
                    val pad = 24f
                    val spanX = ((maxLng - minLng) * kx).coerceAtLeast(1e-6); val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
                    val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
                    fun p(lat: Double, lng: Double) = Offset((pad + (lng - minLng) * kx * scale).toFloat(), (size.height - pad - (lat - minLat) * scale).toFloat())
                    val path = Path().apply { outline.forEachIndexed { i, (la, ln) -> val o = p(la, ln); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
                    drawPath(path, Color(0x2212B886)); drawPath(path, Color(0xFF12B886), style = Stroke(3f))
                    office.gates.forEach { g -> drawCircle(Color(0xFF5B6CFF), 10f, p(g.lat, g.lng)) }
                    t.points.forEachIndexed { i, pt ->
                        val o = p(pt.lat, pt.lng)
                        if (i > 0) { val prev = t.points[i - 1]; drawLine(Color(0x88F59F00), p(prev.lat, prev.lng), o, 3f) }
                        drawCircle(if (pt.timeMs <= t.punchMs) Color(0xFFF59F00) else Color(0xFFF0506E), 6f, o)
                    }
                }
                Text("Green: office · blue: gates · orange: points up to the punch · red: after it. ${t.points.size} GPS points, ${hm(t.points.first().timeMs)} – ${hm(t.points.last().timeMs)}.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

/** Upcoming long-weekend ideas from holidays and your work days. */
@Composable
fun LongWeekendList() {
    val c = Soft.colors
    val list = rememberInBackground(Unit) { AttendanceController.longWeekends() } ?: emptyList()
    if (list.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text("Long-weekend ideas", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    val f = DateTimeFormatter.ofPattern("EEE d MMM")
    list.forEach { w ->
        Text("🌴 Take ${w.leaveDays.joinToString(" + ") { it.format(f) }} → ${w.totalDays} days off (${w.from.format(f)} – ${w.to.format(f)}${w.holiday?.let { ", $it" } ?: ""})",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
    }
}
