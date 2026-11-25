package com.attendance.tracker.ui

import com.attendance.tracker.permissionHistory
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.core.DayMark
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayTitle = DateTimeFormatter.ofPattern("EEEE, d MMMM")

/** Mark a day as leave / sick / WFH / holiday / half day, or back to a normal working day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayMarkDialog(day: LocalDate, onDismiss: () -> Unit) {
    val c = Soft.colors
    var selected by remember { mutableStateOf(AttendanceController.dayMark(day)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(day.format(dayTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mark this day as", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<DayMark?>(null) + DayMark.entries).forEach { m ->
                        val sel = m == selected
                        Text(
                            m?.label ?: "Working day",
                            Modifier.clip(Soft.pillShape)
                                .background(if (sel) c.accent else c.cardMuted)
                                .pressScale { selected = m }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            color = if (sel) Color.White else c.textPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Text(
                    when (selected) {
                        null -> "Normal day: late arrival and short hours are tracked."
                        DayMark.HALF_DAY -> "Half day: your hours to work are halved."
                        DayMark.PRESENT -> "Present: counted as a full day worked, even with no punches (e.g. before you used this app)."
                        DayMark.ABSENT -> "Absent: counted as absent (loss of pay), even if punches were recorded."
                        else -> "Not counted as late or absent, and left out of your average hours."
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { AttendanceController.setDayMark(day, selected); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

private fun hm(min: Int): String = when {
    min <= 0 -> "0m"
    min % 60 == 0 -> "${min / 60}h"
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${min % 60}m"
}

/**
 * Permission: a short leave (e.g. leaving early after lunch) that counts as worked time,
 * up to your monthly allowance. Logout moves earlier by the same amount.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionDialog(day: LocalDate, onDismiss: () -> Unit) {
    val c = Soft.colors
    val settings = AttendanceController.settings
    var allowance by remember { mutableIntStateOf(settings.permissionMonthMin) }
    val usedElsewhere = remember { settings.permissionUsedInMonth(day.toString(), except = day.toString()) }
    val left = (allowance - usedElsewhere).coerceAtLeast(0)
    var minutes by remember { mutableIntStateOf(AttendanceController.permissionMin(day)) }
    if (minutes > left) minutes = left
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission · " + if (day == LocalDate.now()) "today" else day.format(DateTimeFormatter.ofPattern("d MMM"))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Counts as worked time and brings your logout earlier by the same amount.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 30, 60, 90, 120, 180, 240).filter { it == 0 || it <= left || it == minutes }.forEach { m ->
                        val sel = m == minutes
                        Text(
                            if (m == 0) "None" else hm(m),
                            Modifier.clip(Soft.pillShape).background(if (sel) c.accent else c.cardMuted)
                                .pressScale { minutes = m }.padding(horizontal = 14.dp, vertical = 9.dp),
                            color = if (sel) Color.White else c.textPrimary, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.accentSoft).padding(14.dp)) {
                    Text("This month: ${hm(usedElsewhere + minutes)} used of ${hm(allowance)} · ${hm((left - minutes).coerceAtLeast(0))} left",
                        style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    if (minutes > 0 && left - minutes <= 0) Text("\u26A0 This uses the last of your permission for this month.",
                        style = MaterialTheme.typography.bodySmall, color = c.danger)
                    val hist = remember { AttendanceController.permissionHistory(java.time.YearMonth.from(day)) +
                        AttendanceController.permissionHistory(java.time.YearMonth.from(day).minusMonths(1)) }
                    if (hist.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("History", style = MaterialTheme.typography.labelMedium, color = c.accent)
                        hist.take(8).forEach { (d, m) ->
                            Text("${d.format(DateTimeFormatter.ofPattern("EEE d MMM"))} \u00b7 ${hm(m)}", style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Monthly allowance", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.accent)
                        MiniAction("−") { allowance = (allowance - 30).coerceAtLeast(0) }
                        Text(hm(allowance), Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        MiniAction("+") { allowance = (allowance + 30).coerceAtMost(24 * 60) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.permissionMonthMin = allowance
                AttendanceController.setPermission(day, minutes); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

/**
 * Fill in the days before you started using the app: every working day in the range with
 * no punches and no mark becomes Present (or Absent). Each day can still be changed later.
 */
@Composable
fun MarkPastDaysDialog(initialFrom: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val yesterday = LocalDate.now().minusDays(1)
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(yesterday) }
    var present by remember { mutableStateOf(true) }
    fun pick(d: LocalDate, set: (LocalDate) -> Unit) =
        android.app.DatePickerDialog(ctx, { _, y, m, dd -> set(LocalDate.of(y, m + 1, dd)) }, d.year, d.monthValue - 1, d.dayOfMonth)
            .apply { datePicker.maxDate = System.currentTimeMillis() - 86_400_000L }.show()
    val fmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark past days") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("For days before you used the app. Only working days with no punches and no mark are changed; holidays and weekends are skipped.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("From" to from, "To" to to).forEach { (label, d) ->
                        Column(
                            Modifier.weight(1f).clip(Soft.tileShape).background(c.accentSoft)
                                .pressScale { if (label == "From") pick(from) { from = it } else pick(to) { to = it } }.padding(12.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent)
                            Text(d.format(fmt), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(48.dp).clip(Soft.pillShape).background(c.cardMuted).padding(4.dp)) {
                    listOf(true to "Present", false to "Absent").forEach { (v, label) ->
                        val sel = v == present
                        Box(
                            Modifier.weight(1f).fillMaxWidth().height(40.dp).clip(Soft.pillShape)
                                .background(if (sel) (if (v) c.success else c.danger) else c.cardMuted)
                                .pressScale { present = v },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) Color.White else c.textSecondary, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Text("Tip: mark all as Present, then tap single days in the calendar to change them to Absent or Leave.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (to.isBefore(from)) { Toast.makeText(ctx, "“To” is before “From”", Toast.LENGTH_SHORT).show(); return@TextButton }
                val n = AttendanceController.markEmptyDays(from, to, if (present) DayMark.PRESENT else DayMark.ABSENT)
                Toast.makeText(ctx, "$n day${if (n == 1) "" else "s"} marked ${if (present) "Present" else "Absent"}", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Mark") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

/** Add an IN / OUT the phone missed, with a reason. Saved as a clearly labelled manual punch. */
@Composable
fun ManualPunchDialog(initialDay: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    var day by remember { mutableStateOf(initialDay) }
    var isIn by remember { mutableStateOf(true) }
    val nowT = LocalDateTime.now()
    var minutes by remember { mutableIntStateOf(if (day == nowT.toLocalDate()) nowT.hour * 60 + nowT.minute else 9 * 60) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add missed punch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Date: tap to pick any past day (not the future)
                Column(
                    Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.accentSoft)
                        .pressScale {
                            android.app.DatePickerDialog(ctx, { _, y, m, d -> day = LocalDate.of(y, m + 1, d) },
                                day.year, day.monthValue - 1, day.dayOfMonth).apply {
                                datePicker.maxDate = System.currentTimeMillis()
                            }.show()
                        }
                        .padding(16.dp),
                ) {
                    Text("Date", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    Text(if (day == LocalDate.now()) "Today \u00b7 " + day.format(dayTitle) else day.format(dayTitle),
                        style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                }
                Row(Modifier.fillMaxWidth().height(48.dp).clip(Soft.pillShape).background(c.cardMuted).padding(4.dp)) {
                    listOf(true to "IN", false to "OUT").forEach { (v, label) ->
                        val sel = v == isIn
                        Box(
                            Modifier.weight(1f).fillMaxWidth().height(40.dp).clip(Soft.pillShape)
                                .background(if (sel) (if (v) c.success else c.danger) else c.cardMuted)
                                .pressScale { isIn = v },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) Color.White else c.textSecondary, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.accentSoft)
                        .pressScale {
                            TimePickerDialog(ctx, { _, h, m -> minutes = h * 60 + m }, minutes / 60, minutes % 60, false).show()
                        }
                        .padding(16.dp),
                ) {
                    Text("Time", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    Text(
                        "%d:%02d %s".format(if ((minutes / 60) % 12 == 0) 12 else (minutes / 60) % 12, minutes % 60, if (minutes < 720) "AM" else "PM"),
                        style = MaterialTheme.typography.headlineSmall, color = c.textPrimary,
                    )
                }
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text("Reason (required)") },
                    placeholder = { Text("e.g. Phone battery died") },
                    shape = RoundedCornerShape(18.dp), singleLine = false, maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = c.card, unfocusedContainerColor = c.cardMuted, cursorColor = c.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Saved as “Manual” so it's never confused with automatic punches.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Start)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = day.atStartOfDay(ZoneId.systemDefault()).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
                when {
                    reason.isBlank() -> Toast.makeText(ctx, "Please add a reason", Toast.LENGTH_SHORT).show()
                    at > System.currentTimeMillis() -> Toast.makeText(ctx, "That time is in the future", Toast.LENGTH_SHORT).show()
                    else -> { AttendanceController.addManualPunch(isIn, at, reason); onDismiss() }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

@Composable
fun MiniAction(label: String, onClick: () -> Unit) {
    val c = Soft.colors
    Text(
        label,
        Modifier.clip(Soft.pillShape).background(c.accentSoft).pressScale(onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium, color = c.accent, maxLines = 1, softWrap = false,
    )
}

/** Correct the time of an automatic or manual punch; the original time and reason are kept in the record. */
@Composable
fun EditPunchDialog(e: com.attendance.tracker.data.AttendanceEvent, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val zone = ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(e.timeMs).atZone(zone).toLocalDate()
    val t0 = java.time.Instant.ofEpochMilli(e.timeMs).atZone(zone).toLocalTime()
    var minutes by remember { mutableIntStateOf(t0.hour * 60 + t0.minute) }
    var reason by remember { mutableStateOf("") }
    fun clk(m: Int) = "%d:%02d %s".format(if ((m / 60) % 12 == 0) 12 else (m / 60) % 12, m % 60, if (m < 720) "AM" else "PM")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct ${if (e.type == com.attendance.tracker.data.EventType.CHECK_IN) "IN" else "OUT"} time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recorded at ${clk(t0.hour * 60 + t0.minute)} on ${day.format(dayTitle)}.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Column(Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.accentSoft)
                    .pressScale { TimePickerDialog(ctx, { _, h, m -> minutes = h * 60 + m }, minutes / 60, minutes % 60, false).show() }.padding(16.dp)) {
                    Text("Correct time", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    Text(clk(minutes), style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp), maxLines = 2)
                Text("The original time stays in the record as “Edited from …”.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = day.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
                when {
                    reason.isBlank() -> Toast.makeText(ctx, "Please add a reason", Toast.LENGTH_SHORT).show()
                    at > System.currentTimeMillis() -> Toast.makeText(ctx, "That time is in the future", Toast.LENGTH_SHORT).show()
                    else -> { AttendanceController.editPunch(e, at, reason); onDismiss() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}
