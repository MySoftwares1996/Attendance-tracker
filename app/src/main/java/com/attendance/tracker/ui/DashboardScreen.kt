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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
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
import com.attendance.tracker.core.DayPlan
import com.attendance.tracker.core.DayPlanner
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkSchedule
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.service.TrackingService
import com.attendance.tracker.permissions.PermissionsManager
import kotlinx.coroutines.delay
import java.time.Duration
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
            plan = st.plan,
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

        Notices(
            show = PermissionsManager.autostartNeeded() && !PermissionsManager.autostartConfirmed(ctx) && permTick >= 0,
            icon = painterResource(R.drawable.ic_system_update),
            text = "Turn on Autostart so your phone never stops auto-punch", action = "Open",
            tint = c.warning, bg = c.warningSoft,
        ) {
            PermissionsManager.openAutostartSettings(ctx)
            PermissionsManager.setAutostartConfirmed(ctx)
            permTick++
        }

        HealthLine(st.trackingOn, st.lastAcceptedMs, st.lastFix?.timeMs, now)

        // "Was this lunch?" — answered here or from the notification
        st.pendingLunch?.let { (outMs, inMs) ->
            SoftCard(color = c.warningSoft, padding = 16.dp) {
                Text("Were you at lunch?", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    "Out ${fmtTime(outMs)} \u2192 back ${fmtTime(inMs)} (${(inMs - outMs) / 60_000} min)",
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Yes, lunch", { AttendanceController.confirmPendingLunch() }, Modifier.weight(1f),
                        colors = c.warning.copy(alpha = 0.85f) to c.warning)
                    SoftGhostButton("No, work", { AttendanceController.dismissPendingLunch() }, Modifier.weight(1f),
                        tint = c.textSecondary, background = c.card)
                }
            }
        }

        // Leave / WFH / holiday for today
        var markOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                st.plan?.dayOff?.let { "Today: $it" } ?: if (AttendanceController.dayMark(java.time.LocalDate.now()) != null) "Today: Half day" else "Taking leave or working from home today?",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
            MiniAction(if (st.plan?.dayOff != null) "Change" else "Mark today") { markOpen = true }
        }
        if (markOpen) DayMarkDialog(java.time.LocalDate.now()) { markOpen = false }

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
        LunchCard(st.lunch.active, st.lunch.startedAtMs, now, sc, st.plan) { msg ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }

        // ---------- punches ----------
        PunchesCard(st.today.punches, now)

        // ---------- average worked hours ----------
        val stats = remember(st.today.punches.size, now / 60_000) { AttendanceController.workedStats(now) }
        WorkedHoursCard(stats)

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
        val plan = st.plan
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                if ((plan?.extraLunchSec ?: 0L) > 0) "Logout (+${fmtDuration(plan!!.extraLunchSec)} lunch)" else "Expected logout",
                fmtClock(plan?.expectedEnd), painterResource(R.drawable.ic_gate), c.accent, c.accentSoft, Modifier.weight(1f),
            )
            StatTile(
                "Overtime", fmtDuration(t[WorkStatus.OVERTIME.name] ?: 0),
                painterResource(R.drawable.ic_clock), Color(0xFF6A4FE8), Color(0x1F6A4FE8), Modifier.weight(1f),
            )
        }
        if (plan?.late == true) {
            Text(
                "Arrived late at ${fmtClock(plan.firstIn)}" + (plan.latestStart?.let { " · on time until ${fmtClock(it)}" } ?: ""),
                Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.warningSoft).padding(14.dp),
                style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
            )
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
            val edge = st.edgeDistanceM
            InfoLine("Presence", when (st.presence) {
                Presence.INSIDE -> "Inside premises"
                Presence.OUTSIDE -> "Outside premises"
                Presence.UNKNOWN -> "Detecting…"
            }, when (st.presence) {
                Presence.INSIDE -> c.success
                Presence.OUTSIDE -> c.danger
                Presence.UNKNOWN -> c.textSecondary
            })
            InfoLine(
                "Office boundary",
                edge?.let { if (it <= 0) "${(-it).toInt()} m inside" else "${it.toInt()} m outside" } ?: "—",
                if (edge == null) c.textSecondary else if (edge <= 0) c.success else c.warning,
            )
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

/** Live proof that auto-punch is working: when the last location reading arrived. */
@Composable
private fun HealthLine(tracking: Boolean, lastAcceptedMs: Long?, lastFixMs: Long?, nowMs: Long) {
    val c = Soft.colors
    val last = listOfNotNull(lastAcceptedMs, lastFixMs).maxOrNull()
    val age = last?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }
    val (text, color) = when {
        !tracking -> "Auto-punch is paused" to c.danger
        age == null -> "Auto-punch on · waiting for first GPS reading" to c.warning
        age < 120 -> "Auto-punch active · location checked ${if (age < 60) "${age}s" else "${age / 60}m"} ago" to c.success
        age < 600 -> "Auto-punch active · last reading ${age / 60}m ago" to c.warning
        else -> "No location for ${age / 60} min — check GPS / battery settings" to c.danger
    }
    Row(
        Modifier.fillMaxWidth().clip(Soft.pillShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, tracking && (age ?: 999) < 120, 7.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
    }
}

/** Average worked hours per day + last 7 days. */
@Composable
private fun WorkedHoursCard(stats: AttendanceController.WorkedStats) {
    val c = Soft.colors
    val purple = Color(0xFF6A4FE8)
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardHeader(painterResource(R.drawable.ic_clock), "Worked hours", "Average per day \u00b7 last 30 days", purple, purple.copy(alpha = 0.12f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stats.avgSec?.let { fmtDuration(it) } ?: "\u2014",
                style = MaterialTheme.typography.displaySmall, color = c.textPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Text("per day", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            stats.avgSec?.let { avg ->
                val diff = avg - stats.targetSec
                val (txt, fg, bg) = if (diff >= 0) Triple("+${fmtDuration(diff)} vs ${fmtDuration(stats.targetSec)} target", c.success, c.successSoft)
                else Triple("\u2212${fmtDuration(-diff)} vs ${fmtDuration(stats.targetSec)} target", c.warning, c.warningSoft)
                Pill(txt, fg, bg)
            }
            Text(
                if (stats.daysCounted == 0) "No full days yet" else "${stats.daysCounted} day${if (stats.daysCounted == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
        }

        // Last 7 days bar chart with target line
        Spacer(Modifier.height(20.dp))
        val maxSec = (stats.last7.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(stats.targetSec).coerceAtLeast(1L)
        val chartH = 110.dp
        Box(Modifier.fillMaxWidth().height(chartH)) {
            val targetFrac = stats.targetSec.toFloat() / maxSec
            Row(Modifier.fillMaxWidth().height(chartH), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                stats.last7.forEachIndexed { i, (_, sec) ->
                    val isToday = i == stats.last7.lastIndex
                    val frac by androidx.compose.animation.core.animateFloatAsState(
                        (sec.toFloat() / maxSec).coerceIn(0f, 1f), androidx.compose.animation.core.tween(900), label = "bar$i",
                    )
                    val color = when {
                        sec == 0L -> c.cardMuted
                        isToday -> c.accent
                        sec >= stats.targetSec -> c.success
                        else -> c.warning
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(if (sec == 0L) 0.04f else frac.coerceAtLeast(0.04f))
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(color)
                        )
                    }
                }
            }
            // target line
            Box(
                Modifier.fillMaxWidth().padding(bottom = chartH * targetFrac).align(Alignment.BottomStart)
                    .height(1.5.dp).background(c.textSecondary.copy(alpha = 0.5f))
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.last7.forEachIndexed { i, (d, sec) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (i == stats.last7.lastIndex) "Today" else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == stats.last7.lastIndex) c.accent else c.textSecondary,
                    )
                    Text(
                        if (sec == 0L) "\u2014" else "${sec / 3600}h${if ((sec % 3600) / 60 > 0) "${(sec % 3600) / 60}" else ""}",
                        style = MaterialTheme.typography.labelSmall, color = c.textPrimary,
                    )
                }
            }
        }
        stats.weekAvgSec?.let {
            Spacer(Modifier.height(12.dp))
            InfoLine("This week average", fmtDuration(it))
        }
        InfoLine("Today so far", fmtDuration(stats.todaySec), c.accent)
    }
}

/** Every automatic IN / OUT of the day with gate and time spent inside. */
@Composable
private fun PunchesCard(punches: List<com.attendance.tracker.data.AttendanceEvent>, nowMs: Long) {
    val c = Soft.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardHeader(painterResource(R.drawable.ic_gate), "Punches today", "Recorded automatically at the gate")
            }
            Pill("${punches.size}", c.accent, c.accentSoft)
        }
        Spacer(Modifier.height(12.dp))
        var addOpen by remember { mutableStateOf(false) }
        if (punches.isEmpty()) {
            Text(
                "No punches yet. Your first IN is recorded as soon as you enter the office.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
        }
        if (addOpen) ManualPunchDialog(java.time.LocalDate.now()) { addOpen = false }
        punches.forEachIndexed { i, e ->
            val isIn = e.type == com.attendance.tracker.data.EventType.CHECK_IN
            val gate = Regex("via (.+?)( \\(|$| ·)").find(e.detail)?.groupValues?.get(1)
            val approx = e.detail.contains("approximate")
            val manual = e.detail.startsWith(AttendanceController.MANUAL_PREFIX)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isIn) "IN" else "OUT",
                    Modifier.width(52.dp).clip(Soft.pillShape).background(if (isIn) c.successSoft else c.dangerSoft)
                        .padding(vertical = 5.dp),
                    color = if (isIn) c.success else c.danger, style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (approx) "~" else "") + fmtTime(e.timeMs), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(
                        if (manual) e.detail.removePrefix(AttendanceController.MANUAL_PREFIX)
                        else (gate ?: "Office boundary") + (e.accuracyM?.let { " · ±${it.toInt()} m" } ?: "") + if (approx) " · approximate" else "",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2,
                    )
                }
                if (manual) {
                    Pill("Manual", c.warning, c.warningSoft)
                    Spacer(Modifier.width(4.dp))
                    Text("\u2715", Modifier.clip(CircleShape).pressScale { AttendanceController.deleteManualPunch(e) }.padding(8.dp),
                        color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
                if (isIn) {
                    val outMs = punches.getOrNull(i + 1)?.timeMs
                    val dur = ((outMs ?: nowMs) - e.timeMs) / 1000
                    Text(
                        if (outMs == null) "inside ${fmtDuration(dur)}" else fmtDuration(dur),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (outMs == null) c.success else c.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MiniAction("+ Add missed punch") { addOpen = true }
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
private fun StatusHero(status: WorkStatus, tracking: Boolean, officeName: String?, nowMs: Long, plan: DayPlan?) {
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

        // Workday progress: actual start → expected logout (incl. extra lunch)
        Spacer(Modifier.height(22.dp))
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), java.time.ZoneId.systemDefault())
        val start = plan?.start
        val end = plan?.expectedEnd
        val white = Color.White.copy(alpha = 0.85f)
        if (plan != null && start != null && end != null && plan.workDay) {
            val total = Duration.between(start, end).seconds.coerceAtLeast(1)
            val done = Duration.between(start, now).seconds.coerceIn(0, total)
            SoftBar(done.toFloat() / total, Color.White, Color.White.copy(alpha = 0.25f), height = 6.dp)
            Spacer(Modifier.height(8.dp))
            val overtime = DayPlanner.overtimeSec(plan, now)
            Row {
                Text("In ${fmtClock(start)}", color = white, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        overtime > 0 -> "+${fmtDuration(overtime)} overtime"
                        now < start -> "Starts ${fmtClock(start)}"
                        else -> "${fmtDuration(total - done)} left"
                    },
                    color = white, style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Text("Out ${fmtClock(end)}", color = white, style = MaterialTheme.typography.labelMedium)
            }
            if (plan.extraLunchSec > 0) {
                Text(
                    "Logout extended +${fmtDuration(plan.extraLunchSec)} for extra lunch",
                    Modifier.padding(top = 10.dp).clip(Soft.pillShape).background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
            }
        } else if (plan != null && plan.workDay && plan.flexible) {
            Text(
                "Your day starts when you arrive · on time until ${fmtClock(plan.latestStart)}",
                color = white, style = MaterialTheme.typography.labelMedium,
            )
        } else if (plan?.dayOff != null) {
            Text("Marked as ${plan.dayOff} \u00b7 enjoy your day", color = white, style = MaterialTheme.typography.labelMedium)
        } else {
            Text("No work scheduled now", color = white, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LunchCard(active: Boolean, startedAtMs: Long, nowMs: Long, sc: WorkSchedule, plan: DayPlan?, onError: (String) -> Unit) {
    val c = Soft.colors
    // Allowance is per day: earlier lunches today count against it.
    val earlier = ((plan?.lunchTakenSec ?: 0L) - if (active) ((nowMs - startedAtMs) / 1000) else 0).coerceAtLeast(0)
    val secs = if (active) ((nowMs - startedAtMs) / 1000).coerceAtLeast(0) + earlier else 0L
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
                                if (over) "Over by ${fmtDuration(secs - maxSecs)} · logout moves to ${fmtClock(plan?.expectedEnd)}"
                                else "of ${sc.maxLunchMinutes} min · since ${fmtTime(startedAtMs)}",
                                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                            )
                        }
                    } else {
                        Column {
                            Text(
                                "Take it any time · ${sc.maxLunchMinutes} min allowed today",
                                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                            )
                            val taken = plan?.lunchTakenSec ?: 0L
                            if (taken > 0) Text(
                                "Taken today ${fmtDuration(taken)}" +
                                    ((plan?.extraLunchSec ?: 0L).takeIf { it > 0 }?.let { " · +${fmtDuration(it)} added to logout" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if ((plan?.extraLunchSec ?: 0L) > 0) c.warning else c.textSecondary,
                            )
                        }
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
