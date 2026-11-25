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
            "Status-bar pill after each punch, on lunch, near logout and in overtime",
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
        CardHeader(rememberVectorPainter(Icons.Filled.Notifications), "Punch sound")
        Spacer(Modifier.height(6.dp))
        var sound by remember { mutableStateOf(AttendanceController.settings.punchSound) }
        SoftSwitchRow(
            "Sound & vibration on IN / OUT",
            null,
            sound,
        ) { v -> sound = v; AttendanceController.settings.punchSound = v }
    }
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
