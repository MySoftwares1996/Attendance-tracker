package com.attendance.tracker.ui

import com.attendance.tracker.leavesIn
import com.attendance.tracker.leaveWorkingDays
import com.attendance.tracker.leaveRequestText
import com.attendance.tracker.leaveBalance
import com.attendance.tracker.cancelLeave
import com.attendance.tracker.applyLeave
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.AttendanceController.LeaveType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val lFmt = DateTimeFormatter.ofPattern("EEE, d MMM")
private fun days(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(v)

/** Apply leave: type, dates, half day, reason → marks the days and offers to share the request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApplyLeaveDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val today = LocalDate.now()
    var type by remember { mutableStateOf(LeaveType.CASUAL) }
    var from by remember { mutableStateOf(today.plusDays(1)) }
    var to by remember { mutableStateOf(today.plusDays(1)) }
    var half by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val balance = remember { AttendanceController.leaveBalance() }
    val workDays = AttendanceController.leaveWorkingDays(from, to)
    val count = if (half) 0.5 else workDays.size.toDouble()
    val left = balance[type]?.second
    fun pick(d: LocalDate, set: (LocalDate) -> Unit) =
        android.app.DatePickerDialog(ctx, { _, y, m, dd -> set(LocalDate.of(y, m + 1, dd)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply leave") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LeaveType.entries.forEach { t ->
                        val sel = t == type
                        val bal = balance[t]?.second
                        Text(
                            t.label + (bal?.let { " · ${days(it)} left" } ?: ""),
                            Modifier.clip(Soft.pillShape).background(if (sel) c.accent else c.cardMuted)
                                .pressScale { type = t }.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (sel) Color.White else c.textPrimary, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("From" to from, "To" to to).forEach { (label, d) ->
                        Column(
                            Modifier.weight(1f).clip(Soft.tileShape).background(c.accentSoft)
                                .pressScale {
                                    if (label == "From") pick(from) { from = it; if (to.isBefore(it)) to = it }
                                    else pick(to) { to = if (it.isBefore(from)) from else it }
                                }.padding(12.dp),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent)
                            Text(d.format(lFmt), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        }
                    }
                }
                if (from == to && type != LeaveType.UNPAID) SoftSwitchRow("Half day", null, half) { half = it }
                OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text("Reason (optional)") },
                    shape = RoundedCornerShape(16.dp), maxLines = 3)
                Text(
                    when {
                        workDays.isEmpty() -> "No working days in this range (weekends / holidays)."
                        left != null && count > left -> "${days(count)} day(s) — more than your ${days(left)} ${type.label.lowercase()} left."
                        type == LeaveType.UNPAID -> "${days(count)} working day(s) · deducted from salary."
                        else -> "${days(count)} working day(s) · ${days((left ?: 0.0) - count)} left after this."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (left != null && count > left) c.danger else c.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = workDays.isNotEmpty(), onClick = {
                val n = AttendanceController.applyLeave(from, to, type, half, reason)
                val text = AttendanceController.leaveRequestText(from, to, type, half, reason, n)
                Toast.makeText(ctx, "Leave applied for ${days(count)} day(s)", Toast.LENGTH_SHORT).show()
                runCatching { ctx.startActivity(AttendanceController.shareIntent(text)) }
                onDismiss()
            }) { Text("Apply & share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

/** Leave balances for the year, upcoming leave (tap to cancel) and yearly quotas. */
@Composable
fun LeavesCard() {
    val ctx = LocalContext.current
    val c = Soft.colors
    var tick by remember { mutableIntStateOf(0) }
    var applyOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    if (applyOpen) ApplyLeaveDialog { applyOpen = false; tick++ }
    val balance = remember(tick) { AttendanceController.leaveBalance() }
    val upcoming = remember(tick) {
        AttendanceController.leavesIn(LocalDate.now().year).filter { !it.date.isBefore(LocalDate.now()) } +
            AttendanceController.leavesIn(LocalDate.now().year + 1)
    }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Leaves ${LocalDate.now().year}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            MiniAction("+ Apply leave") { applyOpen = true }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            balance.forEach { (t, ub) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.cardMuted).padding(10.dp)) {
                    Text(t.code, style = MaterialTheme.typography.labelMedium, color = c.accent)
                    Text("${days(ub.second)} left", style = MaterialTheme.typography.titleSmall, color = if (ub.second < 0) c.danger else c.textPrimary)
                    Text("${days(ub.first)} used", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Upcoming · tap to cancel", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            upcoming.take(10).forEach { l ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressScale {
                    AttendanceController.cancelLeave(l.date); tick++
                    Toast.makeText(ctx, "Leave on ${l.date.format(lFmt)} cancelled", Toast.LENGTH_SHORT).show()
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.date.format(lFmt), Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    Text(l.type.label + (if (l.half) " (half)" else "") + (if (l.reason.isNotBlank()) " · ${l.reason}" else ""),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("✕", style = MaterialTheme.typography.labelLarge, color = c.danger)
                }
            }
        }
        LongWeekendList()
        Spacer(Modifier.height(8.dp))
        Text(if (editOpen) "Hide yearly quota" else "Change yearly quota", Modifier.clip(Soft.pillShape).pressScale { editOpen = !editOpen }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = c.accent)
        if (editOpen) {
            val q = remember(tick) { AttendanceController.settings.leaveQuota }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("CL", "SL", "EL").forEach { code ->
                    var v by remember(tick) { mutableStateOf(days(q[code] ?: 0.0)) }
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(v, { s ->
                            v = s.filter { it.isDigit() || it == '.' }
                            v.toDoubleOrNull()?.let { d -> AttendanceController.settings.leaveQuota = AttendanceController.settings.leaveQuota + (code to d) }
                        }, label = { Text(code) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    }
                }
            }
            TextButton(onClick = { tick++ }) { Text("Update balances") }
        }
    }
}
