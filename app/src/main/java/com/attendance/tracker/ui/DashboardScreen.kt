package com.attendance.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.DayPlan
import com.attendance.tracker.core.DayTimeline
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.core.WorkedTime
import com.attendance.tracker.data.AttendanceEvent
import com.attendance.tracker.data.EventType
import com.attendance.tracker.health.HealthCheck
import com.attendance.tracker.health.HealthReport
import com.attendance.tracker.permissions.PermissionsManager
import com.attendance.tracker.security.TrustChecks
import com.attendance.tracker.service.TrackingService
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val DUTY_REASONS = listOf("Client visit", "Bank", "Site work", "Training", "Delivery", "Other official work")

private fun clock12(t: LocalDateTime?): String = t?.let {
    "%d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM")
} ?: "—"

private fun clock12(ms: Long?): String = ms?.let { clock12(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())) } ?: "—"

private fun hms(sec: Long): String = "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)

/** Everything the Today screen shows, recomputed every second from the live state. */
private data class LiveDay(
    val now: LocalDateTime,
    /** Time in office + on duty right now (lunch excluded), ticking live. */
    val workedSec: Long,
    /** Net worked = presence − lunch (at least the allowance). */
    val netSec: Long,
    val netTargetSec: Long,
    /** Lunch that is / will be deducted today and how much of it was actually taken. */
    val lunchDeductSec: Long,
    val lunchTakenSec: Long,
    val segments: List<DayTimeline.Segment>,
    val totals: Map<DayTimeline.Kind, Long>,
    val dayProgress: Float,
    val toGoSec: Long?,
    val overtimeSec: Long,
)

private fun liveDay(nowMs: Long, plan: DayPlan?, timeline: List<AttendanceEvent>): LiveDay {
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val segs = DayTimeline.segments(AttendanceController.timelineEvents(timeline), nowMs)
    val tot = DayTimeline.totals(segs)
    val office = tot[DayTimeline.Kind.OFFICE] ?: 0L
    val duty = tot[DayTimeline.Kind.DUTY] ?: 0L
    val lunch = tot[DayTimeline.Kind.LUNCH] ?: 0L
    val worked = office + duty
    val today = now.toLocalDate()
    val allowance = AttendanceController.lunchAllowanceSec(today)
    val net = WorkedTime.netSec(office, duty, lunch, allowance)
    val start = plan?.start
    val end = plan?.expectedEnd
    val progress = if (start != null && end != null) {
        val total = Duration.between(start, end).seconds.coerceAtLeast(1)
        (Duration.between(start, now).seconds.toFloat() / total).coerceIn(0f, 1f)
    } else 0f
    val toGo = end?.let { Duration.between(now, it).seconds }?.takeIf { it > 0 && start != null }
    val cutoff = plan?.cutoff ?: now
    val ot = end?.let { if (now.isAfter(it)) Duration.between(it, minOf(now, cutoff)).seconds.coerceAtLeast(0) else 0L } ?: 0L
    return LiveDay(now, worked, net, AttendanceController.netTargetSec(today), maxOf(lunch, allowance), lunch,
        segs, tot, progress, toGo, ot)
}

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

    var health by remember { mutableStateOf<HealthReport?>(null) }
    LaunchedEffect(permTick, st.trackingOn) {
        while (true) { health = runCatching { HealthCheck.run(ctx) }.getOrNull(); delay(60_000) }
    }

    val fine = remember(permTick) { granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    fun startTracking() {
        when {
            !fine -> {
                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                fgLauncher.launch(perms.toTypedArray())
            }
            settings.office == null -> onOpenSetup()
            else -> { AttendanceController.settings.userPaused = false; TrackingService.start(ctx) }
        }
    }

    val plan = st.plan
    val live = liveDay(now, plan, st.today.timeline)
    val stats = remember(st.today.punches.size, now / 60_000) { AttendanceController.workedStats(now) }
    val usualArrival = remember(LocalDate.now()) { AttendanceController.usualArrivalMin() }

    var markOpen by remember { mutableStateOf(false) }
    var punchOpen by remember { mutableStateOf(false) }
    var dutyOpen by remember { mutableStateOf(false) }
    if (markOpen) DayMarkDialog(LocalDate.now()) { markOpen = false }
    if (punchOpen) ManualPunchDialog(LocalDate.now()) { punchOpen = false }
    if (dutyOpen) DutyReasonDialog(onPick = { AttendanceController.confirmPendingDuty(it); dutyOpen = false }, onDismiss = { dutyOpen = false })

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── header ──
        Row(Modifier.padding(top = 18.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(live.now.format(dateFmt), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Text(greeting(live.now.hour), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%d:%02d:%02d".format(if (live.now.hour % 12 == 0) 12 else live.now.hour % 12, live.now.minute, live.now.second),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary,
                )
                Text(if (live.now.hour < 12) "AM" else "PM", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
        }

        // ── paused / setup problems ──
        AnimatedVisibility(!st.trackingOn, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.dangerSoft).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(c.danger, true, 7.dp)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Auto-punch is paused", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text("Your IN and OUT won't be recorded", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Row(
                    Modifier.clip(Soft.pillShape).background(c.accent).pressScale { startTracking() }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(rememberVectorPainter(Icons.Filled.PlayArrow), null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        health?.let { h ->
            AnimatedVisibility(st.trackingOn && h.score < 85, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                NoticeCard(
                    rememberVectorPainter(Icons.Filled.Warning),
                    "Auto-punch health ${h.score}/100 · ${h.problems} thing${if (h.problems == 1) "" else "s"} to fix",
                    "Fix", c.warning, c.warningSoft,
                ) { UiChrome.setupPage = "check"; onOpenSetup() }
            }
        }
        if (PermissionsManager.autostartNeeded() && !PermissionsManager.autostartConfirmed(ctx) && permTick >= 0) {
            NoticeCard(painterResource(R.drawable.ic_system_update), "Turn on Autostart so your phone never stops auto-punch", "Open",
                c.warning, c.warningSoft) {
                PermissionsManager.openAutostartSettings(ctx); PermissionsManager.setAutostartConfirmed(ctx); permTick++
            }
        }

        // ── questions waiting for you ──
        st.pendingDutyOut?.let { outMs ->
            QuestionCard(
                title = "You left at ${clock12(outMs)} · official work?",
                text = "On duty time counts as worked hours.",
                yes = "On duty", no = "Lunch / personal", tint = Color(0xFF6A4FE8),
                onYes = { dutyOpen = true }, onNo = { AttendanceController.dismissPendingDuty() },
            )
        }
        st.pendingLunch?.let { (outMs, inMs) ->
            QuestionCard(
                title = "Were you at lunch?",
                text = "Out ${clock12(outMs)} → back ${clock12(inMs)} (${(inMs - outMs) / 60_000} min)",
                yes = "Yes, lunch", no = "No, work", tint = c.warning,
                onYes = { AttendanceController.confirmPendingLunch() }, onNo = { AttendanceController.dismissPendingLunch() },
            )
        }

        // ── live hero ──
        LiveHero(st.status, st.trackingOn, settings.office?.name, plan, live, st.today.punches.firstOrNull { it.type == EventType.CHECK_IN }?.timeMs)

        // ── quick actions ──
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.lunch.active) ActionChip("✓  Back from lunch", c.success, c.successSoft) { AttendanceController.endLunch() }
            else ActionChip("🍴  Lunch", c.warning, c.warningSoft) {
                AttendanceController.startLunch()?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            }
            if (st.duty != null) ActionChip("✓  End duty", Color(0xFF6A4FE8), Color(0x1F6A4FE8)) { AttendanceController.endDuty() }
            else ActionChip("💼  On duty", Color(0xFF6A4FE8), Color(0x1F6A4FE8)) { dutyOpen = true }
            ActionChip("📅  ${if (plan?.dayOff != null) plan.dayOff else "Mark today"}", c.accent, c.accentSoft) { markOpen = true }
            ActionChip("✎  Missed punch", c.accent, c.accentSoft) { punchOpen = true }
            ActionChip("↪  Share today", c.success, c.successSoft) {
                runCatching { ctx.startActivity(AttendanceController.shareIntent(AttendanceController.summaryText(LocalDate.now()))) }
            }
        }

        // ── active lunch / duty ──
        if (st.lunch.active) ActiveCard(
            "On lunch since ${clock12(st.lunch.startedAtMs)}",
            lunchLine(plan, settings.schedule.maxLunchMinutes),
            hms((now - st.lunch.startedAtMs) / 1000), c.warning, c.warningSoft, "I'm back",
        ) { AttendanceController.endLunch() }
        st.duty?.let { (since, reason) ->
            ActiveCard(
                "On duty · $reason", "Since ${clock12(since)} · counts as worked time",
                hms((now - since) / 1000), Color(0xFF6A4FE8), Color(0x1F6A4FE8), "End duty",
            ) { AttendanceController.endDuty() }
        }

        // ── live analysis ──
        LiveAnalysis(live, plan, st, stats, usualArrival, settings.schedule.maxLunchMinutes, settings.schedule.workDays)

        // ── punches ──
        PunchesCard(st.today.punches, now) { punchOpen = true }

        // ── week ──
        WeekCard(stats)

        // ── location ──
        LocationCard(st, settings.accuracy.maxAccuracyM, now)

        UpdateBanner()
        Spacer(Modifier.height(NavBarClearance))
    }
}

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

private fun lunchLine(plan: DayPlan?, maxMin: Int): String {
    val taken = plan?.lunchTakenSec ?: 0
    val left = maxMin * 60L - taken
    return if (left >= 0) "${fmtDuration(left)} left of $maxMin min" else "Over by ${fmtDuration(-left)} · added to your logout"
}

// ───────────────────────────── hero ─────────────────────────────

@Composable
private fun LiveHero(status: WorkStatus, tracking: Boolean, officeName: String?, plan: DayPlan?, live: LiveDay, firstInMs: Long?) {
    val (g1, g2) = statusGradient(status)
    val a = animatedColor(g1, "heroA")
    val b = animatedColor(g2, "heroB")
    val white = Color.White
    val soft = Color.White.copy(alpha = 0.8f)
    Column(
        Modifier.fillMaxWidth()
            .shadow(26.dp, Soft.cardShape, ambientColor = b.copy(alpha = 0.45f), spotColor = b.copy(alpha = 0.45f))
            .clip(Soft.cardShape).background(Brush.linearGradient(listOf(a, b))).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(white, tracking, 7.dp)
            AnimatedContent(status, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "st") { s ->
                Text(if (s == WorkStatus.DAY_OFF && plan?.dayOff != null) plan.dayOff else s.label,
                    color = white, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.weight(1f))
            Text(officeName ?: "No office set", color = soft, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ring = progress through the working day (arrival → expected logout)
            val p by animateFloatAsState(live.dayProgress, tween(800), label = "ring")
            Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 12.dp.toPx()
                    val d = size.minDimension - sw
                    val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                    drawArc(Color.White.copy(alpha = 0.22f), 0f, 360f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(Color.White, -90f, 360f * p, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hms(live.workedSec), color = white, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                    Text("in office", color = soft, style = MaterialTheme.typography.labelMedium)
                    Text("net ${fmtDuration(live.netSec)} / ${fmtDuration(live.netTargetSec)}", color = white, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("IN", firstInMs?.let { clock12(it) } ?: "—", white, soft)
                HeroStat(
                    "LOGOUT",
                    clock12(plan?.expectedEnd),
                    white, soft,
                    sub = when {
                        live.overtimeSec > 0 -> "+${fmtDuration(live.overtimeSec)} overtime"
                        live.toGoSec != null -> "in ${fmtDuration(live.toGoSec)}"
                        else -> null
                    },
                )
                if ((plan?.extraLunchSec ?: 0L) > 0) HeroStat("EXTRA LUNCH", "+${fmtDuration(plan!!.extraLunchSec)}", white, soft)
            }
        }
        Spacer(Modifier.height(12.dp))
        val hint = when {
            plan?.dayOff != null -> "Marked as ${plan.dayOff} · enjoy your day"
            plan == null || !plan.workDay -> "No work scheduled today"
            plan.start == null -> "Your day starts when you arrive"
            else -> null
        }
        if (hint != null) Text(
            hint,
            Modifier.clip(Soft.pillShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 7.dp),
            color = white, style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun HeroStat(label: String, value: String, fg: Color, soft: Color, sub: String? = null) {
    Column {
        Text(label, color = soft, style = MaterialTheme.typography.labelSmall)
        Text(value, color = fg, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        if (sub != null) Text(sub, color = soft, style = MaterialTheme.typography.labelSmall)
    }
}

// ───────────────────────────── live analysis ─────────────────────────────

private val KIND_ORDER = listOf(DayTimeline.Kind.OFFICE, DayTimeline.Kind.LUNCH, DayTimeline.Kind.DUTY, DayTimeline.Kind.OUTSIDE)

@Composable
private fun kindColor(k: DayTimeline.Kind): Color {
    val c = Soft.colors
    return when (k) {
        DayTimeline.Kind.OFFICE -> c.success
        DayTimeline.Kind.LUNCH -> c.warning
        DayTimeline.Kind.DUTY -> Color(0xFF6A4FE8)
        DayTimeline.Kind.OUTSIDE -> c.danger
    }
}

private fun kindLabel(k: DayTimeline.Kind) = when (k) {
    DayTimeline.Kind.OFFICE -> "In office"
    DayTimeline.Kind.LUNCH -> "Lunch"
    DayTimeline.Kind.DUTY -> "On duty"
    DayTimeline.Kind.OUTSIDE -> "Outside"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveAnalysis(
    live: LiveDay, plan: DayPlan?, st: com.attendance.tracker.LiveState, stats: AttendanceController.WorkedStats,
    usualArrival: Int?, maxLunchMin: Int, workDays: Set<java.time.DayOfWeek>,
) {
    val c = Soft.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live analysis", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Row(
                Modifier.clip(Soft.pillShape).background(c.dangerSoft).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(c.danger, true, 5.dp)
                Text("LIVE", style = MaterialTheme.typography.labelSmall, color = c.danger)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Timeline bar: first IN → expected logout (or now, if later)
        val segs = live.segments
        if (segs.isEmpty()) {
            Text("Your day's timeline appears after your first IN.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        } else {
            val startMs = segs.first().startMs
            val endPlanMs = plan?.expectedEnd?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
            val nowMs = segs.last().endMs
            val spanEnd = maxOf(nowMs, endPlanMs)
            val span = (spanEnd - startMs).coerceAtLeast(1).toFloat()
            val track = c.cardMuted
            val colors = segs.map { kindColor(it.kind) }
            Canvas(Modifier.fillMaxWidth().height(18.dp).clip(Soft.pillShape)) {
                drawRect(track)
                segs.forEachIndexed { i, s ->
                    val x0 = (s.startMs - startMs) / span * size.width
                    val x1 = (s.endMs - startMs) / span * size.width
                    drawRect(colors[i], Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
                }
                // "now" marker
                val xn = (nowMs - startMs) / span * size.width
                drawRect(Color.White, Offset((xn - 1.5f).coerceAtLeast(0f), 0f), Size(3f, size.height))
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(clock12(startMs), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                Text("now", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(if (endPlanMs > nowMs) clock12(endPlanMs) else clock12(nowMs), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KIND_ORDER.forEach { k ->
                    val sec = live.totals[k] ?: 0L
                    if (sec > 0 || k == DayTimeline.Kind.OFFICE) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(kindColor(k)))
                        Spacer(Modifier.width(6.dp))
                        Text("${kindLabel(k)} ${fmtDuration(sec)}", style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val insights = mutableListOf<Triple<String, String, Color>>()
        if (plan?.dayOff == null && segs.isNotEmpty()) {
            val left = (live.netTargetSec - live.netSec).coerceAtLeast(0)
            insights += Triple("\u2705",
                "Net worked ${fmtDuration(live.netSec)} of ${fmtDuration(live.netTargetSec)}" +
                    (if (left > 0) " \u00b7 ${fmtDuration(left)} to go" else " \u00b7 target reached") +
                    "\n${fmtDuration(live.lunchDeductSec)} lunch deducted" + if (live.lunchTakenSec == 0L) " (even if not taken)" else "",
                if (left == 0L) c.success else c.accent)
        }
        // 1. pace / finish
        when {
            plan?.dayOff != null -> {}
            live.overtimeSec > 0 && st.presence == Presence.INSIDE ->
                insights += Triple("⏱", "Overtime: +${fmtDuration(live.overtimeSec)} so far", Color(0xFF6A4FE8))
            live.toGoSec != null ->
                insights += Triple("⏱", "Finish at ${clock12(plan?.expectedEnd)} · ${fmtDuration(live.toGoSec)} to go", c.accent)
            plan?.start == null && plan?.workDay == true ->
                insights += Triple("\u23F1", "Day not started \u00b7 logout = arrival + ${fmtDuration(stats.targetSec)} net + ${AttendanceController.settings.settings.value.schedule.maxLunchMinutes}m lunch", c.textSecondary)
        }
        // 2. away right now
        if (st.status == WorkStatus.OUTSIDE_OFFICE) {
            val lastOut = st.today.punches.lastOrNull()?.takeIf { it.type == EventType.CHECK_OUT }?.timeMs
            if (lastOut != null) insights += Triple("🚶", "Outside for ${fmtDuration((System.currentTimeMillis() - lastOut) / 1000)} · not counted as worked", c.danger)
        }
        // 3. arrival vs usual
        val firstIn = st.today.punches.firstOrNull { it.type == EventType.CHECK_IN }?.timeMs
        if (firstIn != null && usualArrival != null) {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(firstIn), ZoneId.systemDefault())
            val diff = (t.hour * 60 + t.minute) - usualArrival
            val usual = clock12(LocalDate.now().atStartOfDay().plusMinutes(usualArrival.toLong()))
            insights += when {
                diff <= -5 -> Triple("🌅", "Arrived ${-diff} min earlier than your usual $usual", c.success)
                diff >= 5 -> Triple("🌅", "Arrived $diff min later than your usual $usual", c.warning)
                else -> Triple("🌅", "Arrived right on your usual time ($usual)", c.success)
            }
        }
        // commute
        AttendanceController.commute(LocalDate.now())?.let { (min, left) ->
            insights += Triple("\uD83D\uDE97", "Commute today $min min \u00b7 left home ${clock12(left)}", c.textSecondary)
        }
        // 4. lunch
        if (plan?.start != null && plan.dayOff == null) {
            val taken = plan.lunchTakenSec
            insights += when {
                taken == 0L && !st.lunch.active -> Triple("🍴", "Lunch not taken yet · $maxLunchMin min available", c.textSecondary)
                taken <= maxLunchMin * 60L -> Triple("🍴", "Lunch ${fmtDuration(taken)} · ${fmtDuration(maxLunchMin * 60L - taken)} left", c.warning)
                else -> Triple("🍴", "Lunch over by ${fmtDuration(taken - maxLunchMin * 60L)} · logout moved later", c.danger)
            }
        }
        // 5. week progress
        val monday = LocalDate.now().minusDays((LocalDate.now().dayOfWeek.value - 1).toLong())
        val week = stats.last7.filter { !it.first.isBefore(monday) }
        val weekWorked = week.sumOf { it.second }
        val weekTarget = week.count { it.first.dayOfWeek in workDays } * stats.targetSec
        if (weekTarget > 0) insights += Triple("📈",
            "This week ${fmtDuration(weekWorked)} of ${fmtDuration(weekTarget)} (${(weekWorked * 100 / weekTarget)}%)", c.accent)
        // 6. average
        stats.avgSec?.let { insights += Triple("⚖", "30-day average ${fmtDuration(it)} net per day · target ${fmtDuration(stats.targetSec)}",
            if (it >= stats.targetSec) c.success else c.warning) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            insights.forEach { (icon, text, color) ->
                Row(
                    Modifier.fillMaxWidth().clip(Soft.tileShape).background(color.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(icon, Modifier.width(26.dp), style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                }
            }
        }
    }
}

// ───────────────────────────── small cards ─────────────────────────────

@Composable
private fun ActionChip(label: String, fg: Color, bg: Color, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(Soft.pillShape).background(bg).pressScale(onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
    )
}

@Composable
private fun QuestionCard(title: String, text: String, yes: String, no: String, tint: Color, onYes: () -> Unit, onNo: () -> Unit) {
    val c = Soft.colors
    SoftCard(color = tint.copy(alpha = 0.10f), padding = 16.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(yes, onYes, Modifier.weight(1f), colors = tint.copy(alpha = 0.85f) to tint)
            SoftGhostButton(no, onNo, Modifier.weight(1f), tint = c.textSecondary, background = c.card)
        }
    }
}

@Composable
private fun ActiveCard(title: String, sub: String, timer: String, tint: Color, bg: Color, action: String, onAction: () -> Unit) {
    val c = Soft.colors
    SoftCard(color = bg, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Text(timer, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = tint)
        }
        Spacer(Modifier.height(10.dp))
        SoftButton(action, onAction, colors = tint.copy(alpha = 0.85f) to tint)
    }
}

@Composable
private fun DutyReasonDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Soft.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("On duty — what for?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time out on official work counts as worked hours.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                DUTY_REASONS.forEach { r ->
                    Text(r, Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.cardMuted).pressScale { onPick(r) }.padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp), containerColor = c.card,
    )
}

/** Every IN / OUT of the day with gate, verified badge and time spent inside. */
@Composable
private fun PunchesCard(punches: List<AttendanceEvent>, nowMs: Long, onAdd: () -> Unit) {
    val c = Soft.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardHeader(painterResource(R.drawable.ic_gate), "Punches today", "Recorded automatically at the gate") }
            Pill("${punches.size}", c.accent, c.accentSoft)
        }
        Spacer(Modifier.height(10.dp))
        if (punches.isEmpty()) {
            Text("No punches yet. Your first IN is recorded as soon as you enter the office.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
        punches.forEachIndexed { i, e ->
            val isIn = e.type == EventType.CHECK_IN
            val manual = e.detail.startsWith(AttendanceController.MANUAL_PREFIX)
            val verified = e.detail.contains(TrustChecks.VERIFIED)
            val unverified = e.detail.contains(TrustChecks.NOT_VERIFIED)
            val gate = Regex("via (.+?)( \\(|$| ·)").find(e.detail)?.groupValues?.get(1)
            val approx = e.detail.contains("approximate")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isIn) "IN" else "OUT",
                    Modifier.width(50.dp).clip(Soft.pillShape).background(if (isIn) c.successSoft else c.dangerSoft).padding(vertical = 5.dp),
                    color = if (isIn) c.success else c.danger, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (approx) "~" else "") + clock12(e.timeMs), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(
                        when {
                            manual -> e.detail.removePrefix(AttendanceController.MANUAL_PREFIX)
                            else -> (gate ?: "Office boundary") + (e.accuracyM?.let { " · ±${it.toInt()} m" } ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2,
                    )
                    when {
                        manual -> Text("Manual", style = MaterialTheme.typography.labelSmall, color = c.warning)
                        verified -> Text("✓ Verified", style = MaterialTheme.typography.labelSmall, color = c.success)
                        unverified -> Text("⚠ " + e.detail.substringAfter(TrustChecks.NOT_VERIFIED),
                            style = MaterialTheme.typography.labelSmall, color = c.warning, maxLines = 2)
                    }
                }
                if (isIn) {
                    val outMs = punches.getOrNull(i + 1)?.timeMs
                    val dur = ((outMs ?: nowMs) - e.timeMs) / 1000
                    Text(if (outMs == null) "inside\n${hms(dur)}" else fmtDuration(dur),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (outMs == null) c.success else c.textSecondary, textAlign = TextAlign.End)
                }
                if (manual) Text("✕", Modifier.clip(CircleShape).pressScale { AttendanceController.deleteManualPunch(e) }.padding(8.dp),
                    color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        MiniAction("+ Add missed punch", onAdd)
    }
}

/** Last 7 days, scaled so a full day fills ~80% of the height, with the target line. */
@Composable
private fun WeekCard(stats: AttendanceController.WorkedStats) {
    val c = Soft.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CardHeader(painterResource(R.drawable.ic_clock), "Last 7 days", "Net hours per day (lunch deducted)", Color(0xFF6A4FE8), Color(0x1F6A4FE8)) }
            stats.avgSec?.let { Pill("avg ${fmtDuration(it)}", c.accent, c.accentSoft) }
        }
        Spacer(Modifier.height(18.dp))
        val chartH = 120.dp
        val maxSec = maxOf(stats.last7.maxOfOrNull { it.second } ?: 0L, stats.targetSec).coerceAtLeast(1L) * 1.1f
        val targetFrac = (stats.targetSec / maxSec).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(chartH)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                stats.last7.forEachIndexed { i, (_, sec) ->
                    val isToday = i == stats.last7.lastIndex
                    val f by animateFloatAsState((sec / maxSec).coerceIn(0f, 1f), tween(900), label = "w$i")
                    val color = when {
                        sec == 0L -> c.cardMuted
                        isToday -> c.accent
                        sec >= stats.targetSec -> c.success
                        else -> c.warning
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (sec > 0) Text("${sec / 3600}:${"%02d".format((sec % 3600) / 60)}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                        Box(Modifier.fillMaxWidth().fillMaxHeight(if (sec == 0L) 0.03f else (f * 0.85f).coerceAtLeast(0.05f))
                            .clip(RoundedCornerShape(8.dp)).background(color))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(bottom = chartH * targetFrac * 0.85f)
                .height(1.5.dp).background(c.textSecondary.copy(alpha = 0.45f)))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.last7.forEachIndexed { i, (d, _) ->
                Text(
                    if (i == stats.last7.lastIndex) "Today" else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                    Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, fontSize = 10.sp,
                    color = if (i == stats.last7.lastIndex) c.accent else c.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Line = your ${fmtDuration(stats.targetSec)} net daily target", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

@Composable
private fun LocationCard(st: com.attendance.tracker.LiveState, maxAcc: Float, nowMs: Long) {
    val c = Soft.colors
    SoftCard(padding = 16.dp) {
        val acc = st.lastFix?.accuracyM?.takeIf { it < 1e6f }
        val (q, qc, qbg) = when {
            acc == null -> Triple("Waiting", c.textSecondary, c.cardMuted)
            acc <= 10f -> Triple("Excellent", c.success, c.successSoft)
            acc <= 25f -> Triple("Good", c.success, c.successSoft)
            acc <= maxAcc -> Triple("Fair", c.warning, c.warningSoft)
            else -> Triple("Weak", c.danger, c.dangerSoft)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardHeader(painterResource(R.drawable.ic_target), "Location", acc?.let { "GPS ±${it.toInt()} m" } ?: "No GPS reading yet")
            }
            Pill(q, qc, qbg)
        }
        Spacer(Modifier.height(8.dp))
        val edge = st.edgeDistanceM
        InfoLine("Office boundary", edge?.let { if (it <= 0) "${(-it).toInt()} m inside" else "${it.toInt()} m outside" } ?: "—",
            if (edge == null) c.textSecondary else if (edge <= 0) c.success else c.warning)
        InfoLine("Office Wi-Fi", if (AttendanceController.onOfficeWifi) "Connected" else "Not connected",
            if (AttendanceController.onOfficeWifi) c.success else c.textSecondary)
        val last = listOfNotNull(st.lastAcceptedMs, st.lastFix?.timeMs).maxOrNull()
        InfoLine("Last check", last?.let { "${((nowMs - it) / 1000).coerceAtLeast(0)}s ago" } ?: "—")
        st.lastGateUsed?.let { InfoLine("Last gate used", it) }
    }
}
