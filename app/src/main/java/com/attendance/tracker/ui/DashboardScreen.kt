package com.attendance.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkSchedule
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.service.TrackingService
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@SuppressLint("BatteryLife")
@Composable
fun DashboardScreen(onOpenSetup: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val st by AttendanceController.state.collectAsStateWithLifecycle()
    val settings by AttendanceController.settings.settings.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val fine = remember(permTick) { granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val background = remember(permTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val batteryOk = remember(permTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permTick++
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            Toast.makeText(ctx, "Precise location is required for accurate attendance", Toast.LENGTH_LONG).show()
        }
    }
    fun requestForeground() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        fgLauncher.launch(perms.toTypedArray())
    }

    val sc = settings.schedule
    val dt = LocalDateTime.now()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- greeting ----------
        Column(Modifier.padding(top = 20.dp, start = 4.dp)) {
            Text(dt.format(dateFmt), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Text(greeting(dt.hour), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }

        // ---------- status hero ----------
        StatusHero(
            status = st.status,
            tracking = st.trackingOn,
            officeName = settings.office?.name,
            nowMs = now,
            schedule = sc,
        )

        // ---------- setup notices ----------
        Notices(
            show = !fine, icon = painterResource(R.drawable.ic_target),
            text = "Allow precise location to start", action = "Allow",
            tint = c.accent, bg = c.accentSoft,
        ) { requestForeground() }
        Notices(
            show = fine && !background, icon = rememberVectorPainter(Icons.Filled.LocationOn),
            text = "Set location to “Allow all the time”", action = "Allow",
            tint = c.accent, bg = c.accentSoft,
        ) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        Notices(
            show = !batteryOk, icon = rememberVectorPainter(Icons.Filled.Warning),
            text = "Turn off battery limits so tracking never pauses", action = "Fix",
            tint = c.warning, bg = c.warningSoft,
        ) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
            permTick++
        }
        Notices(
            show = settings.office == null, icon = painterResource(R.drawable.ic_gate),
            text = "Add your office, gates and hours", action = "Set up",
            tint = c.accent, bg = c.accentSoft,
        ) { onOpenSetup() }

        UpdateBanner()

        // ---------- tracking button ----------
        AnimatedContent(st.trackingOn, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "trk") { on ->
            if (on) {
                SoftGhostButton(
                    "Pause tracking", {
                        AttendanceController.settings.userPaused = true
                        TrackingService.stop(ctx)
                    },
                    tint = c.textSecondary, background = c.cardMuted,
                )
            } else {
                SoftButton(
                    "Start tracking",
                    {
                        if (!fine) requestForeground()
                        else if (settings.office == null) onOpenSetup()
                        else {
                            AttendanceController.settings.userPaused = false
                            TrackingService.start(ctx)
                        }
                    },
                    icon = rememberVectorPainter(Icons.Filled.PlayArrow),
                )
            }
        }

        // ---------- lunch ----------
        LunchCard(st.lunch.active, st.lunch.startedAtMs, now, sc) { msg ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }

        // ---------- today stats ----------
        SectionTitle("Today", Modifier.padding(top = 4.dp))
        val t = st.today.secondsByStatus
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("First in", fmtTime(st.today.firstInMs), painterResource(R.drawable.ic_gate), c.success, c.successSoft, Modifier.weight(1f))
            StatTile("Last out", fmtTime(st.today.lastOutMs), painterResource(R.drawable.ic_gate), c.danger, c.dangerSoft, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("In office", fmtDuration(t[WorkStatus.IN_OFFICE.name] ?: 0), painterResource(R.drawable.ic_clock), c.accent, c.accentSoft, Modifier.weight(1f))
            StatTile("Away (work hrs)", fmtDuration(t[WorkStatus.OUTSIDE_OFFICE.name] ?: 0), painterResource(R.drawable.ic_clock), c.warning, c.warningSoft, Modifier.weight(1f))
        }

        // ---------- location quality ----------
        SectionTitle("Location", Modifier.padding(top = 4.dp))
        SoftCard {
            val acc = st.lastFix?.accuracyM?.takeIf { it < 1e6f }
            val (q, qc, qbg) = when {
                acc == null -> Triple("Waiting", c.textSecondary, c.cardMuted)
                acc <= 10f -> Triple("Excellent", c.success, c.successSoft)
                acc <= 25f -> Triple("Good", c.success, c.successSoft)
                acc <= settings.accuracy.maxAccuracyM -> Triple("Fair", c.warning, c.warningSoft)
                else -> Triple("Weak", c.danger, c.dangerSoft)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    CardHeader(painterResource(R.drawable.ic_target), "GPS signal", acc?.let { "± ${it.toInt()} m accuracy" } ?: "No fix yet")
                }
                Pill(q, qc, qbg)
            }
            Spacer(Modifier.height(14.dp))
            val radius = settings.office?.radiusM
            val dist = st.distanceM
            InfoLine("Presence", when (st.presence) {
                Presence.INSIDE -> "Inside premises"
                Presence.OUTSIDE -> "Outside premises"
                Presence.UNKNOWN -> "Detecting…"
            }, when (st.presence) {
                Presence.INSIDE -> c.success
                Presence.OUTSIDE -> c.danger
                Presence.UNKNOWN -> c.textSecondary
            })
            InfoLine("Distance from office", dist?.let { "${it.toInt()} m" } ?: "—")
            if (radius != null && dist != null) {
                SoftBar(
                    progress = (1f - (dist / (radius * 2)).toFloat()).coerceIn(0.03f, 1f),
                    color = if (dist <= radius) c.success else c.warning,
                    track = c.cardMuted, modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            InfoLine("At gate", st.nearGate ?: "—")
            InfoLine("Last gate used", st.lastGateUsed ?: "—")
            InfoLine("Last reliable fix", fmtTimeSec(st.lastAcceptedMs))
            AnimatedVisibility(st.lastReject != null) {
                Text(
                    "Skipped a reading: ${st.lastReject ?: ""}",
                    Modifier.padding(top = 8.dp).clip(Soft.tileShape).background(c.warningSoft).padding(12.dp).fillMaxWidth(),
                    color = c.textPrimary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(NavBarClearance))
    }
}

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

@Composable
private fun Notices(
    show: Boolean, icon: androidx.compose.ui.graphics.painter.Painter, text: String, action: String,
    tint: Color, bg: Color, onClick: () -> Unit,
) {
    AnimatedVisibility(show, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        NoticeCard(icon, text, action, tint, bg, onClick)
    }
}

@Composable
private fun StatusHero(status: WorkStatus, tracking: Boolean, officeName: String?, nowMs: Long, schedule: WorkSchedule) {
    val (g1, g2) = statusGradient(status)
    val a = animatedColor(g1, "heroA")
    val b = animatedColor(g2, "heroB")
    val shape = Soft.cardShape
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(26.dp, shape, ambientColor = b.copy(alpha = 0.45f), spotColor = b.copy(alpha = 0.45f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(a, b)))
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Color.White, tracking, 8.dp)
            Text(
                if (tracking) "LIVE" else "PAUSED",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                fmtTime(nowMs),
                Modifier.clip(Soft.pillShape).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 5.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(18.dp))
        AnimatedContent(status, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "statusText") { s ->
            Column {
                Text(statusShort(s), color = Color.White, style = MaterialTheme.typography.displaySmall)
                Text(s.label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                painterResource(R.drawable.ic_gate), null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(officeName ?: "Office not set up", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }

        // Workday progress
        Spacer(Modifier.height(22.dp))
        val dt = LocalDateTime.now()
        val m = dt.hour * 60 + dt.minute
        val span = ((schedule.endMin - schedule.startMin + 1440) % 1440).coerceAtLeast(1)
        val elapsed = ((m - schedule.startMin + 1440) % 1440)
        val inShift = StatusResolver.isWorkingTime(dt, schedule)
        val progress = if (inShift) elapsed.toFloat() / span else if (elapsed in 0..span) 1f else 0f
        SoftBar(progress, Color.White, Color.White.copy(alpha = 0.25f), height = 6.dp)
        Spacer(Modifier.height(8.dp))
        Row {
            Text(StatusResolver.fmt(schedule.startMin), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (inShift) "${fmtDuration(((span - elapsed).toLong()) * 60)} left" else "Work hours",
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(StatusResolver.fmt(schedule.endMin), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LunchCard(active: Boolean, startedAtMs: Long, nowMs: Long, sc: WorkSchedule, onError: (String) -> Unit) {
    val c = Soft.colors
    val secs = if (active) ((nowMs - startedAtMs) / 1000).coerceAtLeast(0) else 0L
    val maxSecs = sc.maxLunchMinutes * 60L
    val over = active && secs >= maxSecs
    val ringColor = animatedColor(if (over) c.danger else c.warning, "lunchRing")
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = if (active) secs.toFloat() / maxSecs else 0f,
                color = ringColor, track = c.warningSoft,
                modifier = Modifier.size(88.dp), stroke = 9.dp,
            ) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.ic_lunch), null, tint = ringColor, modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Lunch break", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                AnimatedContent(active, label = "lunchTxt") { on ->
                    if (on) {
                        Column {
                            Text(
                                fmtDuration(secs),
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (over) c.danger else c.textPrimary,
                            )
                            Text(
                                if (over) "Over by ${fmtDuration(secs - maxSecs)}" else "of ${sc.maxLunchMinutes} min · since ${fmtTime(startedAtMs)}",
                                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                            )
                        }
                    } else {
                        Text(
                            "${StatusResolver.fmt(sc.lunchStartMin)} – ${StatusResolver.fmt(sc.lunchEndMin)} · up to ${sc.maxLunchMinutes} min",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(active, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "lunchBtn") { on ->
            if (on) {
                SoftButton(
                    "I'm back from lunch", { AttendanceController.endLunch() },
                    colors = c.success.copy(alpha = 0.85f) to c.success,
                )
            } else {
                SoftGhostButton(
                    "Start lunch break",
                    { AttendanceController.startLunch()?.let(onError) },
                    tint = c.warning, background = c.warningSoft,
                    icon = painterResource(R.drawable.ic_lunch),
                )
            }
        }
    }
}
