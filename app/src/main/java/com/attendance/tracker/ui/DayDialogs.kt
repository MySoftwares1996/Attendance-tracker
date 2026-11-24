package com.attendance.tracker.ui

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

/** Add an IN / OUT the phone missed, with a reason. Saved as a clearly labelled manual punch. */
@Composable
fun ManualPunchDialog(day: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    var isIn by remember { mutableStateOf(true) }
    val nowT = LocalDateTime.now()
    var minutes by remember { mutableIntStateOf(if (day == nowT.toLocalDate()) nowT.hour * 60 + nowT.minute else 9 * 60) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add missed punch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(day.format(dayTitle), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
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
        style = MaterialTheme.typography.labelMedium, color = c.accent,
    )
}

@Composable
fun SpacerH(h: Int) = Spacer(Modifier.height(h.dp))
