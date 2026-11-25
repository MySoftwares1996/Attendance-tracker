package com.attendance.tracker.ui

import com.attendance.tracker.importReportDays
import com.attendance.tracker.compareReport
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.core.CompanyReport
import java.time.format.DateTimeFormatter

private val dFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private fun hm(min: Int) = "${min / 60}:%02d".format(min % 60)

/**
 * Compare the company attendance report with the app: paste the report (or pick the saved page / CSV),
 * see the day-by-day difference, and add any days the app has no record of.
 */
@Composable
fun CompanyCompareDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    var text by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<CompanyReport.Row>?>(null) }
    var tick by remember { mutableStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) runCatching { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } }
            .onSuccess { text = it; rows = CompanyReport.parse(it) }
            .onFailure { Toast.makeText(ctx, "Couldn't read that file", Toast.LENGTH_SHORT).show() }
    }
    val diffs = remember(rows, tick) { rows?.let { AttendanceController.compareReport(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compare with company report") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (diffs == null) {
                    Text("Open your company attendance page, select the table, copy it and paste here. Or pick a saved page / CSV file.",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("11-Nov-2026  GS  XX  10.08  21.04  1.26  0  9.3 …") },
                        shape = RoundedCornerShape(16.dp))
                    MiniAction("📄 Pick file") { pick.launch(arrayOf("text/*", "application/*", "*/*")) }
                } else if (diffs.isEmpty()) {
                    Text("No report rows found. Copy the whole table including the dates (like 11-Nov-2026).",
                        style = MaterialTheme.typography.bodyMedium, color = c.danger)
                } else {
                    val worked = diffs.filter { it.companyMin > 0 }
                    val missing = diffs.count { it.appMin == null && it.companyMin > 0 }
                    val off = worked.count { it.appMin != null && kotlin.math.abs(it.appMin - it.companyMin) > 10 }
                    Text("${diffs.size} days · ${off} differ by more than 10 min · $missing not in the app",
                        style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Row { listOf("Day" to 1.4f, "Company" to 1f, "App" to 1f, "Diff" to 0.9f).forEach { (h, w) ->
                        Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, color = c.accent) } }
                    diffs.forEach { d ->
                        val diff = d.appMin?.let { it - d.companyMin }
                        val bad = d.companyMin > 0 && (d.appMin == null || kotlin.math.abs(diff ?: 0) > 10)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (bad) c.warningSoft else c.cardMuted).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(d.date.format(dFmt), Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            Text(if (d.companyMin > 0) hm(d.companyMin) else d.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            Text(d.appMin?.let { hm(it) } ?: (d.mark?.label ?: "—"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                            Text(diff?.let { (if (it >= 0) "+" else "−") + hm(kotlin.math.abs(it)) } ?: "", Modifier.weight(0.9f),
                                style = MaterialTheme.typography.bodySmall, color = if (bad) c.danger else c.success)
                        }
                    }
                    Text("Both are time inside the office (first in \u2192 last out minus time outside); the app's lunch rule is not applied here. Matching days get a \u2713 in History.",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        },
        confirmButton = {
            when {
                diffs == null -> TextButton(onClick = { rows = CompanyReport.parse(text) }, enabled = text.isNotBlank()) { Text("Compare") }
                diffs.any { it.appMin == null && (it.companyMin > 0 || it.status in setOf("AA", "HH", "XA")) && it.mark == null } -> TextButton(onClick = {
                    val n = AttendanceController.importReportDays(rows.orEmpty())
                    Toast.makeText(ctx, "$n day${if (n == 1) "" else "s"} added from the report", Toast.LENGTH_LONG).show()
                    tick++
                }) { Text("Add missing days") }
                else -> TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { TextButton(onClick = { if (diffs != null) { rows = null } else onDismiss() }) { Text(if (diffs != null) "Back" else "Close") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}
