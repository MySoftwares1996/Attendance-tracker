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

// Setup page: advanced options.

// ───────────────────────────── advanced ─────────────────────────────

@Composable
internal fun AdvancedStep(s: AppSettings) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val sc = s.schedule
    val acc = s.accuracy
    fun pick(initial: Int, onPicked: (Int) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, false).show()

    StepTitle("Advanced", "You normally don't need to change these.")
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Settings), "Appearance")
        Spacer(Modifier.height(10.dp))
        var tm by remember { mutableIntStateOf(AttendanceController.settings.themeMode) }
        Choices(listOf("Phone" to 0, "Light" to 1, "Dark" to 2, "7PM\u20137AM" to 3), tm) { tm = it; AttendanceController.settings.themeMode = it }
        Spacer(Modifier.height(6.dp))
        Text("\u201c7PM\u20137AM\u201d switches to dark automatically in the evening.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Notifications), "Reminders & automation")
        Spacer(Modifier.height(6.dp))
        val st = AttendanceController.settings
        var a1 by remember { mutableStateOf(st.autoLunch) }
        var a2 by remember { mutableStateOf(st.remindBeforeLogout) }
        var a3 by remember { mutableStateOf(st.remindHoursDone) }
        var a4 by remember { mutableStateOf(st.remindLunchOver) }
        SoftSwitchRow("Ask \u201cWas this lunch?\u201d after a break", null, a1) { a1 = it; st.autoLunch = it }
        SoftSwitchRow("Remind 15 min before logout", null, a2) { a2 = it; st.remindBeforeLogout = it }
        SoftSwitchRow("Tell me when hours are complete", null, a3) { a3 = it; st.remindHoursDone = it }
        SoftSwitchRow("Lunch reminders", null, a4) { a4 = it; st.remindLunchOver = it }
        var a5 by remember { mutableStateOf(st.askDuty) }
        var a6 by remember { mutableStateOf(st.autoSummary) }
        var early by remember { mutableStateOf(st.earlyLeaveAlarm) }
        SoftSwitchRow("Alarm if I leave early", null, early) { early = it; st.earlyLeaveAlarm = it }
        SoftSwitchRow("Ask \u201cOfficial work?\u201d when I go out", null, a5) { a5 = it; st.askDuty = it }
        SoftSwitchRow("End-of-day summary", null, a6) { a6 = it; st.autoSummary = it }
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Notifications), "Live Updates")
        Spacer(Modifier.height(6.dp))
        var live by remember { mutableStateOf(AttendanceController.settings.liveUpdates) }
        val allowed = remember { com.attendance.tracker.service.LiveNotification.liveUpdatesAllowed(ctx) }
        SoftSwitchRow(
            "Show Live Updates",
            "In the office every 20 min, the last 30 min before logout, after each punch; always while outside in working hours, on lunch and in overtime",
            live,
        ) {
            live = it; AttendanceController.settings.liveUpdates = it
            com.attendance.tracker.service.TrackingService.refreshNotification(ctx)
        }
        Text(
            when (allowed) {
                null -> "Your Android version shows this as a normal live notification. The status-bar chip needs Android 16."
                true -> "Allowed by Android \u2713"
                false -> "Android has Live Updates turned off for this app. Turn on \u201cLive updates\u201d (and \u201cShow Live Updates on Live Alerts\u201d) in the app's notification settings."
            },
            style = MaterialTheme.typography.bodySmall, color = if (allowed == false) c.warning else c.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        SoftGhostButton("Open notification settings", {
            runCatching { ctx.startActivity(com.attendance.tracker.permissions.PermissionsManager.notificationSettingsIntent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Notifications), "Alerts")
        Spacer(Modifier.height(6.dp))
        val st2 = AttendanceController.settings
        // IN / OUT: off, silent, or with the chime (vibrate only while Do Not Disturb is on).
        var punchMode by remember { mutableStateOf(if (!st2.punchAlertsOn) 0 else if (!st2.punchSound) 1 else 2) }
        Text("IN / OUT alerts", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        Choices(listOf("Off" to 0, "Silent" to 1, "Sound" to 2), punchMode) { v ->
            punchMode = v; st2.punchAlertsOn = v > 0; st2.punchSound = v == 2
        }
        Text("Punches are always recorded. With sound, it vibrates only while Do Not Disturb is on (e.g. in a meeting).",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        var m1 by remember { mutableStateOf(st2.morningCheckOn) }
        var m2 by remember { mutableStateOf(st2.missedOutCheckOn) }
        var m3 by remember { mutableStateOf(st2.remindWeekly) }
        var m4 by remember { mutableStateOf(st2.remindMonthly) }
        SoftSwitchRow("Morning check (no IN by 10:30)", null, m1) { m1 = it; st2.morningCheckOn = it }
        SoftSwitchRow("Missed OUT yesterday", null, m2) { m2 = it; st2.missedOutCheckOn = it }
        SoftSwitchRow("Weekly report (Saturday)", null, m3) { m3 = it; st2.remindWeekly = it }
        SoftSwitchRow("Monthly summary (1st)", null, m4) { m4 = it; st2.remindMonthly = it }
        Text("Reports with pay or permission details stay hidden on the lock screen until you unlock.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    MoreOptionsCard()
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_shield), "IN / OUT detection")
        Spacer(Modifier.height(10.dp))
        // One simple choice instead of raw GPS numbers. Fake-GPS apps are always blocked.
        val level = when { acc.confirmSamples <= 2 -> 0; acc.confirmSamples >= 4 -> 2; else -> 1 }
        Choices(listOf("Faster" to 0, "Balanced" to 1, "Safer" to 2), level) { v ->
            val (m, n) = when (v) { 0 -> 50f to 2; 2 -> 30f to 4; else -> 35f to 3 }
            persist(ctx, current().copy(accuracy = current().accuracy.copy(maxAccuracyM = m, confirmSamples = n, rejectMock = true)))
        }
        Spacer(Modifier.height(6.dp))
        Text("Balanced is recommended. Faster punches sooner at the gate; Safer waits for more GPS readings.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

/** Work locations, calendar sync, fingerprint lock, break nudges and travel rate. */
@Composable
private fun MoreOptionsCard() {
    val ctx = LocalContext.current
    val c = Soft.colors
    val st = AttendanceController.settings
    var sites by remember { mutableStateOf(st.workSites) }
    var siteName by remember { mutableStateOf("") }
    var cal by remember { mutableStateOf(st.calendarSync) }
    var lock by remember { mutableStateOf(st.appLock) }
    var nudge by remember { mutableStateOf(st.breakNudge) }
    var rate by remember { mutableStateOf(if (st.kmRate > 0) "%.0f".format(st.kmRate) else "") }
    val calPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        cal = res.values.all { it }; st.calendarSync = cal
        if (cal) com.attendance.tracker.service.CalendarSync.syncSoon(ctx)
        else Toast.makeText(ctx, "Calendar permission is needed for this", Toast.LENGTH_SHORT).show()
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.LocationOn), "Other work locations", "Client sites or a second office: time there counts as On duty")
        Spacer(Modifier.height(8.dp))
        sites.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDCCD ${s.name} \u00b7 ${s.radiusM.toInt()} m", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Text("Remove", Modifier.clip(Soft.pillShape).pressScale { sites = sites - s; st.workSites = sites }.padding(6.dp),
                    style = MaterialTheme.typography.labelMedium, color = c.danger)
            }
        }
        SoftField("Site name (e.g. Client – Hitech City)", siteName, keyboard = KeyboardType.Text) { siteName = it }
        Spacer(Modifier.height(8.dp))
        SoftGhostButton("Add where I am now", {
            val fix = AttendanceController.state.value.lastFix
            when {
                siteName.isBlank() -> Toast.makeText(ctx, "Type a name first", Toast.LENGTH_SHORT).show()
                fix == null || System.currentTimeMillis() - fix.timeMs > 5 * 60_000 -> Toast.makeText(ctx, "Waiting for a fresh GPS fix \u2014 try again in a moment", Toast.LENGTH_SHORT).show()
                else -> {
                    sites = sites + com.attendance.tracker.data.SettingsRepository.WorkSite(siteName.trim(), fix.lat, fix.lng, 150.0)
                    st.workSites = sites; siteName = ""
                    Toast.makeText(ctx, "Site saved", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Settings), "More")
        Spacer(Modifier.height(6.dp))
        SoftSwitchRow("Add leave & holidays to my calendar", null, cal) { v ->
            if (v && !com.attendance.tracker.service.CalendarSync.permitted(ctx))
                calPerm.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
            else { cal = v; st.calendarSync = v; if (v) com.attendance.tracker.service.CalendarSync.syncSoon(ctx) }
        }
        SoftSwitchRow("Fingerprint lock for Salary & Backup", null, lock) { lock = it; st.appLock = it }
        SoftSwitchRow("Remind me to take a break every 2 hours", null, nudge) { nudge = it; st.breakNudge = it }
        Spacer(Modifier.height(6.dp))
        SoftField("Travel claim rate (\u20B9 per km, for On duty trips)", rate) { v ->
            rate = v.filter { it.isDigit() || it == '.' }; st.kmRate = rate.toFloatOrNull() ?: 0f
        }
    }
}
