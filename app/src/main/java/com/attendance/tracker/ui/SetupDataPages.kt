package com.attendance.tracker.ui

import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateListOf
import com.attendance.tracker.core.GeoMath
import com.attendance.tracker.core.LatLng
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.Gate
import com.attendance.tracker.core.OfficeConfig
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.data.AppSettings
import com.attendance.tracker.service.GeofenceManager
import com.attendance.tracker.service.LocationCapture
import com.attendance.tracker.service.TrackingService
import com.attendance.tracker.update.UpdateManager
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.util.Locale
import java.util.UUID

// Setup pages: reset, auto-punch health and backup.

// ───────────────────────────── pay ─────────────────────────────

// ───────────────────────────── reset ─────────────────────────────

@Composable
internal fun ResetStep(onWholeReset: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val bm = com.attendance.tracker.backup.BackupManager
    var mode by remember { mutableIntStateOf(1) } // 0 all, 1 before today, 2 dates
    val keepToday = mode == 1
    val today = remember { java.time.LocalDate.now() }
    var from by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var to by remember { mutableStateOf(today.minusDays(1).coerceAtLeast(today.withDayOfMonth(1))) }
    val zoneR = java.time.ZoneId.systemDefault()
    fun pickDate(d: java.time.LocalDate, set: (java.time.LocalDate) -> Unit) =
        android.app.DatePickerDialog(ctx, { _, y, m, dd -> set(java.time.LocalDate.of(y, m + 1, dd)) }, d.year, d.monthValue - 1, d.dayOfMonth)
            .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    val dayFmtR = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
    var confirm by remember { mutableStateOf<String?>(null) } // "records" or "all"
    var tick by remember { mutableIntStateOf(0) }
    val todayStart = remember { java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val stats = remember(mode, from, to, tick) {
        if (mode == 2) AttendanceController.db.recordStatsRange(from.atStartOfDay(zoneR).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zoneR).toInstant().toEpochMilli())
        else AttendanceController.db.recordStats(if (keepToday) todayStart else null)
    }
    val allStats = remember(tick) { AttendanceController.db.recordStats(null) }
    val hasBackup = bm.backupUri(ctx) != null

    StepTitle("Reset", "Remove test records, or start the app over.")

    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Delete), "Clear attendance records", "Keeps your office, gates and hours",
            c.warning, c.warningSoft)
        Spacer(Modifier.height(14.dp))
        Choices(listOf("All" to 0, "Before today" to 1, "Dates" to 2), mode) { mode = it }
        if (mode == 2) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("From" to from, "To" to to).forEach { (label, d) ->
                    Column(
                        Modifier.weight(1f).clip(Soft.tileShape).background(c.accentSoft)
                            .pressScale { if (label == "From") pickDate(from) { from = it; if (to.isBefore(it)) to = it } else pickDate(to) { to = it; if (it.isBefore(from)) from = it } }
                            .padding(12.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent)
                        Text(d.format(dayFmtR), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (stats.first == 0 && stats.second == 0) "Nothing to delete."
            else "Deletes ${stats.first} punch${if (stats.first == 1) "" else "es"} across ${stats.second} day${if (stats.second == 1) "" else "s"}, " +
                "plus lunches, on-duty, leave marks and totals" + when (mode) {
                    1 -> ". Today's records stay."
                    2 -> " from ${from.format(dayFmtR)} to ${to.format(dayFmtR)}. Other days stay."
                    else -> ". History will be empty."
                },
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        SoftButton("Delete records", { confirm = "records" }, enabled = stats.first > 0 || stats.second > 0,
            colors = c.warning.copy(alpha = 0.85f) to c.warning, icon = rememberVectorPainter(Icons.Filled.Delete))
    }

    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Delete), "Reset the whole app", "Records and setup", c.danger, c.dangerSoft)
        Spacer(Modifier.height(10.dp))
        Text("Deletes all ${allStats.first} punches and your office map, gates and hours. Tracking stops and setup starts again from step 1. App permissions stay.",
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        SoftGhostButton("Reset everything", { confirm = "all" }, tint = c.danger, background = c.dangerSoft)
    }

    if (hasBackup) Text(
        "Your Google Drive backup updates a minute after any change, so it will soon contain the cleared data. Use \u201cBack up first\u201d to keep a copy there too.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
    )

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "all") "Reset everything?" else "Delete these records?") },
            text = {
                Text(
                    (if (what == "all") "All records and your whole setup will be deleted."
                    else "${stats.first} punches across ${stats.second} days will be deleted" + when (mode) {
                        1 -> " (today's stay)."
                        2 -> " (${from.format(dayFmtR)} \u2013 ${to.format(dayFmtR)})."
                        else -> "."
                    }) + " A copy is saved on the phone first (Backup \u2192 On-phone copies \u2192 Before last reset).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "all") {
                        AttendanceController.resetEverything()
                        Toast.makeText(ctx, "App reset. Let's set it up again.", Toast.LENGTH_LONG).show()
                        confirm = null
                        onWholeReset()
                    } else {
                        if (mode == 2) AttendanceController.clearRange(from, to) else AttendanceController.clearRecords(keepToday)
                        Toast.makeText(ctx, "Records deleted", Toast.LENGTH_SHORT).show()
                        confirm = null
                        tick++
                    }
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = {
                Row {
                    if (hasBackup) TextButton(onClick = {
                        val ok = bm.backupNow(ctx)
                        Toast.makeText(ctx, if (ok) "Backed up \u2014 now you can delete" else "Backup failed", Toast.LENGTH_SHORT).show()
                    }) { Text("Back up first") }
                    TextButton(onClick = { confirm = null }) { Text("Cancel") }
                }
            },
            shape = RoundedCornerShape(28.dp), containerColor = c.card,
        )
    }
}

// ───────────────────────────── health check ─────────────────────────────

@Composable
internal fun HealthStep(open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<com.attendance.tracker.health.HealthReport?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    LaunchedEffect(tick) { report = com.attendance.tracker.health.HealthCheck.run(ctx) }
    val pm = com.attendance.tracker.permissions.PermissionsManager
    fun go(i: android.content.Intent) { runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    fun fix(f: com.attendance.tracker.health.Fix) {
        when (f) {
            com.attendance.tracker.health.Fix.APP_SETTINGS, com.attendance.tracker.health.Fix.BACKGROUND -> go(pm.appSettingsIntent(ctx))
            com.attendance.tracker.health.Fix.LOCATION_ON -> go(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            com.attendance.tracker.health.Fix.BATTERY -> go(pm.batteryIntent(ctx))
            com.attendance.tracker.health.Fix.NOTIFICATIONS -> go(pm.notificationSettingsIntent(ctx))
            com.attendance.tracker.health.Fix.UNUSED -> go(pm.unusedAppIntent(ctx))
            com.attendance.tracker.health.Fix.AUTOSTART -> { pm.openAutostartSettings(ctx); pm.setAutostartConfirmed(ctx) }
            com.attendance.tracker.health.Fix.OFFICE -> open(Page.OFFICE)
            com.attendance.tracker.health.Fix.GATES -> open(Page.GATES)
            com.attendance.tracker.health.Fix.DATE_SETTINGS -> go(android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
            com.attendance.tracker.health.Fix.ACK_GAPS -> {
                // Battery is already unrestricted: mark past gaps as seen (new ones still show up).
                AttendanceController.settings.gapsAcknowledgedMs = System.currentTimeMillis()
                android.widget.Toast.makeText(ctx, "Noted \u2014 new gaps will still be reported", android.widget.Toast.LENGTH_SHORT).show()
                tick++
            }
            com.attendance.tracker.health.Fix.START_TRACKING -> {
                AttendanceController.settings.userPaused = false
                runCatching { TrackingService.start(ctx) }
                tick++
            }
        }
    }

    StepTitle("Auto-punch health", "Everything automatic IN/OUT depends on, checked live.")
    val r = report
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val score = r?.score ?: 0
            val col = when { score >= 85 -> c.success; score >= 60 -> c.warning; else -> c.danger }
            ProgressRing(score / 100f, col, col.copy(alpha = 0.15f), Modifier.size(84.dp), stroke = 9.dp) {
                Text(if (r == null) "\u2026" else "$score", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    when { r == null -> "Checking\u2026"; r.problems == 0 -> "All good"; else -> "${r.problems} thing${if (r.problems == 1) "" else "s"} to fix" },
                    style = MaterialTheme.typography.titleLarge, color = c.textPrimary,
                )
                Text("Score out of 100", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
    r?.let { rep ->
        SoftCard(padding = 12.dp) {
            rep.checks.sortedBy { it.level.ordinal.let { o -> listOf(3, 1, 0, 2)[o] } }.forEach { ch ->
                val (sym, col) = when (ch.level) {
                    com.attendance.tracker.health.Level.OK -> "\u2713" to c.success
                    com.attendance.tracker.health.Level.WARN -> "!" to c.warning
                    com.attendance.tracker.health.Level.FAIL -> "\u2715" to c.danger
                    com.attendance.tracker.health.Level.TIP -> "i" to c.accent
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                        Text(sym, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(ch.detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    val f = ch.fix
                    if (f != null && ch.level != com.attendance.tracker.health.Level.OK) {
                        Spacer(Modifier.width(8.dp))
                        Text(if (f == com.attendance.tracker.health.Fix.ACK_GAPS) "Got it" else "Fix", Modifier.clip(Soft.pillShape).background(col).pressScale { fix(f) }.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    SoftGhostButton("Re-check", { tick++ })
    SoftButton("Share diagnostics", { scope.launch { com.attendance.tracker.health.HealthCheck.shareDiagnostics(ctx) } })
    Text("The diagnostics file lists your setup and the last 7 days of punches, so a problem can be traced. Share it only with someone you trust.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
}

// ───────────────────────────── backup ─────────────────────────────

@Composable
internal fun BackupStep() {
    if (AppLock.locked) { LockedCard("Backup"); return }
    val ctx = LocalContext.current
    val c = Soft.colors
    val bm = com.attendance.tracker.backup.BackupManager
    var uri by remember { mutableStateOf(bm.backupUri(ctx)) }
    var last by remember { mutableStateOf(bm.lastBackupMs(ctx)) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { u ->
        if (u != null) {
            val ok = bm.setBackupUri(ctx, u)
            uri = bm.backupUri(ctx); last = bm.lastBackupMs(ctx)
            Toast.makeText(ctx, if (ok) "Backed up" else "Couldn't write the backup there", Toast.LENGTH_LONG).show()
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { u -> if (u != null) restoreUri = u }

    StepTitle("Backup", "Keep your attendance history, office map and gates safe.")
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_shield), "Backup file on Google Drive", "Updated a minute after every change", c.success, c.successSoft)
        Spacer(Modifier.height(12.dp))
        if (uri == null) {
            Text("Tap below, choose Google Drive in the file picker, pick a folder and tap Save.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            SoftButton("Choose Google Drive location", { create.launch("attendance-backup.json") })
        } else {
            InfoLine("Last backup", if (last > 0) "${fmtTime(last)} \u00b7 ${java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" else "\u2014",
                if (last > 0) c.success else c.warning)
            Spacer(Modifier.height(10.dp))
            SoftButton("Back up now", {
                val ok = bm.backupNow(ctx); last = bm.lastBackupMs(ctx)
                Toast.makeText(ctx, if (ok) "Backed up" else "Backup failed \u2014 choose the location again", Toast.LENGTH_LONG).show()
            }, colors = c.success.copy(alpha = 0.85f) to c.success)
            Spacer(Modifier.height(8.dp))
            SoftGhostButton("Change location", { create.launch("attendance-backup.json") })
        }
    }
    var copies by remember { mutableStateOf(bm.localCopies(ctx)) }
    var restoreFile by remember { mutableStateOf<java.io.File?>(null) }
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_system_update), "Restore")
        Spacer(Modifier.height(10.dp))
        Text("Pick your backup file (e.g. from Google Drive). It's fully checked first; nothing changes unless it's complete, " +
            "and a copy of the current data is saved on the phone so you can undo.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        SoftGhostButton("Restore from backup file", { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
        val importer = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { u ->
            if (u != null) {
                val msg = bm.importRecords(ctx, u); copies = bm.localCopies(ctx)
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
        Spacer(Modifier.height(8.dp))
        SoftGhostButton("Import past records or settings", { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
        Spacer(Modifier.height(8.dp))
        SoftGhostButton("Export settings only (for a new phone)", {
            runCatching {
                val f = bm.exportSettingsFile(ctx)
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                ctx.startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share settings").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(ctx, "Couldn't export settings", Toast.LENGTH_SHORT).show() }
        })
        bm.preRestoreCopy(ctx)?.let { f ->
            Spacer(Modifier.height(8.dp))
            SoftGhostButton("Undo last restore", { restoreFile = f })
        }
    }
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_shield), "On-phone copies", "Daily, and before every restore or reset", c.success, c.successSoft)
        Spacer(Modifier.height(6.dp))
        if (copies.isEmpty()) Text("The first copy is made automatically within a minute of any change.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        copies.take(8).forEach { f ->
            val label = when {
                f.name.startsWith("before-restore") -> "Before last restore"
                f.name.startsWith("before-reset") -> "Before last reset"
                f.name.startsWith("before-import") -> "Before last import"
                else -> f.name.removePrefix("auto-").removeSuffix(".json")
            }
            Row(Modifier.fillMaxWidth().clip(Soft.tileShape).pressScale { restoreFile = f }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text("Saved ${fmtTime(f.lastModified())} · ${java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Text("Restore", style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("On-phone copies are removed if the app is uninstalled — keep the Google Drive file too.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    SoftCard {
        Text("What's backed up", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Text("Everything: every IN/OUT with its GPS position, lunch, on duty, home trips, day marks and permission; " +
            "office map, gates, home location, hours, holidays, salary and all settings. " +
            "Backed up a minute after every punch or change, plus nightly. Android's own Google backup also covers it.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }

    // Confirm with a preview of what the backup holds (checked before anything changes).
    val pending: Pair<String, Result<com.attendance.tracker.backup.BackupManager.Info>>? = remember(restoreUri, restoreFile) {
        restoreUri?.let { "file" to bm.inspect(ctx, it) } ?: restoreFile?.let { it.name to bm.inspect(it) }
    }
    pending?.let { (_, info) ->
        val close = { restoreUri = null; restoreFile = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (info.isSuccess) "Restore this backup?" else "Can't restore this file") },
            text = {
                info.fold({ i ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine("Made on", if (i.createdAt > 0) "${fmtTime(i.createdAt)} · ${java.time.Instant.ofEpochMilli(i.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" else "—")
                        InfoLine("Records", "${i.events}")
                        InfoLine("Day marks", "${i.marks}")
                        InfoLine("Office map", if (i.hasOffice) "Yes · ${i.gates} gate${if (i.gates == 1) "" else "s"}" else "No")
                        InfoLine("Settings", "${i.settings}")
                        InfoLine("Checksum", if (i.verified) "Verified ✓" else "Older format (no checksum)", if (i.verified) c.success else c.warning)
                        Spacer(Modifier.height(6.dp))
                        Text("This replaces the data on this phone. A copy of the current data is saved first, so you can undo.",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }, { e -> Text(e.message ?: "This file can't be used. Nothing was changed.") })
            },
            confirmButton = {
                if (info.isSuccess) TextButton(onClick = {
                    val f = restoreFile; val u = restoreUri
                    // Undoing a restore must not overwrite the copy it is restoring from.
                    val err = if (f != null) bm.restore(ctx, f, saveCurrent = f.name != "before-restore.json") else if (u != null) bm.restore(ctx, u) else null
                    close(); copies = bm.localCopies(ctx)
                    Toast.makeText(ctx, err ?: "Restored ✓ everything verified", Toast.LENGTH_LONG).show()
                }) { Text("Restore") }
                else TextButton(onClick = close) { Text("OK") }
            },
            dismissButton = { if (info.isSuccess) TextButton(onClick = close) { Text("Cancel") } },
            shape = RoundedCornerShape(28.dp), containerColor = c.card,
        )
    }
}
