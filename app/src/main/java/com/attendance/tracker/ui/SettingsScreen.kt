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

internal enum class Page { SUMMARY, OFFICE, GATES, HOURS, LUNCH, UPDATES, ADVANCED, WIFI, BACKUP, CHECK, RESET, HOLIDAYS, HOME }

internal val WIZARD = listOf(Page.OFFICE, Page.GATES, Page.HOURS, Page.LUNCH)

internal data class CaptureUi(val label: String, val secondsLeft: Int, val total: Int, val best: Float?)

/** Saves immediately and keeps tracking / geofences in sync. */
internal fun persist(ctx: Context, s: AppSettings, officeMoved: Boolean = false, gatesChanged: Boolean = false) {
    AttendanceController.settings.save(s)
    if (officeMoved) AttendanceController.onOfficeChanged() else AttendanceController.refresh()
    if (gatesChanged && !officeMoved) GeofenceManager.register(ctx)
    val st = AttendanceController.settings
    if (s.office != null && !st.trackingEnabled && !st.userPaused && AttendanceController.hasFineLocation(ctx)) {
        runCatching { TrackingService.start(ctx) }
    }
}

internal fun current() = AttendanceController.settings.settings.value

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val c = Soft.colors
    val scope = rememberCoroutineScope()
    val settings by AttendanceController.settings.settings.collectAsStateWithLifecycle()

    // First run (no office yet) → guided wizard. Afterwards → simple summary.
    var wizardStep by rememberSaveable { mutableIntStateOf(if (settings.office == null) 0 else -1) }
    var page by rememberSaveable { mutableStateOf(Page.SUMMARY) }
    var capture by remember { mutableStateOf<CaptureUi?>(null) }

    fun doCapture(label: String, onDone: (Double, Double, Float) -> Unit) {
        if (!AttendanceController.hasFineLocation(ctx)) {
            Toast.makeText(ctx, "Allow location first", Toast.LENGTH_LONG).show(); return
        }
        if (capture != null) return
        scope.launch {
            val r = LocationCapture.capture(ctx) { left, best -> capture = CaptureUi(label, left, 20, best) }
            capture = null
            if (r == null) Toast.makeText(ctx, "Couldn't get GPS. Step outside and try again.", Toast.LENGTH_LONG).show()
            else onDone(r.point.lat, r.point.lng, r.point.accuracyM)
        }
    }

    val inWizard = wizardStep >= 0
    val shown = if (inWizard) WIZARD[wizardStep] else page
    BackHandler(enabled = (inWizard && wizardStep > 0) || (!inWizard && page != Page.SUMMARY)) {
        if (inWizard) { wizardStep-- } else { page = Page.SUMMARY }
    }

    // A location shared from Google Maps: ask what it is for, then open that page.
    val incoming = UiChrome.sharedLink
    if (incoming != null && UiChrome.sharedTarget == null) {
        AlertDialog(
            onDismissRequest = { UiChrome.sharedLink = null },
            title = { Text("Use this location for") },
            text = { Text("Location shared from Google Maps.", color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    UiChrome.sharedTarget = "office"
                    if (inWizard) wizardStep = 0 else page = Page.OFFICE
                }) { Text("Office") }
            },
            dismissButton = {
                TextButton(enabled = settings.office != null, onClick = {
                    UiChrome.sharedTarget = "gate"
                    if (inWizard) wizardStep = 1 else page = Page.GATES
                }) { Text("Gate") }
            },
            shape = RoundedCornerShape(28.dp), containerColor = c.card,
        )
    }
    LaunchedEffect(incoming) { if (incoming == null) UiChrome.sharedTarget = null }

    // Opened from the health notice on Today
    LaunchedEffect(UiChrome.setupPage) {
        if (UiChrome.setupPage == "pay") { UiChrome.setupPage = null; UiChrome.requestTab = 4 }
        val target = when (UiChrome.setupPage) {
            "check" -> Page.CHECK; "home" -> Page.HOME; "holidays" -> Page.HOLIDAYS; else -> null
        }
        if (target != null) { wizardStep = -1; page = target; UiChrome.setupPage = null }
    }

    // Map pages are full screen: hide the bottom tabs and draw under the status bar.
    val fullScreen = shown == Page.OFFICE || shown == Page.GATES
    LaunchedEffect(fullScreen) { UiChrome.fullScreen = fullScreen }
    DisposableEffect(Unit) { onDispose { UiChrome.fullScreen = false } }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 10 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 10 })
            },
            label = "setupPage",
        ) { p ->
            if (p == Page.OFFICE || p == Page.GATES) {
                val stepLabel = if (inWizard) "Step ${wizardStep + 1} of ${WIZARD.size}" else null
                val showBack = !inWizard || wizardStep > 0
                val back: () -> Unit = { if (inWizard) { if (wizardStep > 0) wizardStep-- } else { page = Page.SUMMARY } }
                val next: () -> Unit = { if (inWizard) { wizardStep++ } else { page = Page.SUMMARY } }
                if (p == Page.OFFICE) OfficeStep(settings, inWizard, stepLabel, showBack, back, next)
                else GatesStep(settings, capture != null, ::doCapture, inWizard, stepLabel, showBack, back, next)
                return@AnimatedContent
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                if (inWizard) {
                    WizardHeader(wizardStep, WIZARD.size)
                } else if (p != Page.SUMMARY) {
                    BackRow { page = Page.SUMMARY }
                }
                when (p) {
                    Page.SUMMARY -> Summary(settings) { page = it }
                    Page.OFFICE, Page.GATES -> Unit
                    Page.HOURS -> HoursStep(settings)
                    Page.LUNCH -> LunchStep(settings)
                    Page.UPDATES -> SoftCard { UpdateControls() }
                    Page.ADVANCED -> AdvancedStep(settings)
                    Page.WIFI -> WifiStep()
                    Page.CHECK -> HealthStep { page = it }
                    Page.RESET -> ResetStep(onWholeReset = { wizardStep = 0; page = Page.SUMMARY })
                    Page.HOLIDAYS -> HolidaysStep()
                    Page.HOME -> HomeStep()
                    Page.BACKUP -> BackupStep()
                }
                if (inWizard) {
                    val canNext = p != Page.OFFICE || settings.office != null
                    val last = wizardStep == WIZARD.lastIndex
                    SoftButton(
                        when {
                            last -> "Finish"
                            p == Page.GATES && settings.office?.gates.isNullOrEmpty() -> "Skip for now"
                            else -> "Next"
                        },
                        {
                            if (last) {
                                wizardStep = -1; page = Page.SUMMARY
                                Toast.makeText(ctx, "All set! Attendance is now automatic.", Toast.LENGTH_LONG).show()
                            } else wizardStep++
                        },
                        enabled = canNext,
                        icon = rememberVectorPainter(if (last) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight),
                    )
                    if (wizardStep > 0) {
                        Text(
                            "Back", Modifier.fillMaxWidth().clip(Soft.pillShape).pressScale { wizardStep-- }.padding(12.dp),
                            textAlign = TextAlign.Center, color = c.textSecondary, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.height(NavBarClearance + 70.dp))
            }
        }

        // Capture progress floats above the nav bar
        val cap = capture
        AnimatedVisibility(
            cap != null,
            modifier = if (fullScreen)
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 72.dp)
            else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 100.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (cap != null) SoftCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(
                        progress = 1f - cap.secondsLeft.toFloat() / cap.total,
                        color = c.accent, track = c.accentSoft, modifier = Modifier.size(48.dp), stroke = 5.dp,
                    ) { Text("${cap.secondsLeft}", style = MaterialTheme.typography.labelLarge, color = c.accent) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Saving ${cap.label}…", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text("Please stand still · GPS ±${cap.best?.toInt() ?: "–"} m",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
        }
    }
}

// ───────────────────────────── headers ─────────────────────────────

@Composable
internal fun WizardHeader(step: Int, total: Int) {
    val c = Soft.colors
    Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
        Text("Step ${step + 1} of $total", style = MaterialTheme.typography.labelLarge, color = c.accent)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                val col = animatedColor(if (i <= step) c.accent else c.hairline, "wiz$i")
                Box(Modifier.weight(1f).height(6.dp).clip(Soft.pillShape).background(col))
            }
        }
    }
}

@Composable
internal fun BackRow(onBack: () -> Unit) {
    val c = Soft.colors
    Row(
        Modifier.clip(Soft.pillShape).pressScale(onBack).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Setup", style = MaterialTheme.typography.labelLarge, color = c.accent)
    }
}

@Composable
internal fun StepTitle(title: String, subtitle: String) {
    val c = Soft.colors
    Column(Modifier.padding(start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
    }
}

// ───────────────────────────── summary ─────────────────────────────

@Composable
internal fun Summary(s: AppSettings, open: (Page) -> Unit) {
    val c = Soft.colors
    Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
        Text("Setup", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Text("Tap anything to change it. Changes save automatically.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
    // Auto-punch readiness: one score, and one tap to the health check that fixes the weakest item.
    val ctxH = LocalContext.current
    val health = rememberInBackground(Unit) { kotlinx.coroutines.runBlocking { com.attendance.tracker.health.HealthCheck.run(ctxH) } }
    health?.let { h ->
        val col = when { h.score >= 90 -> c.success; h.score >= 70 -> c.warning; else -> c.danger }
        val worst = h.checks.firstOrNull { it.level == com.attendance.tracker.health.Level.FAIL }
            ?: h.checks.firstOrNull { it.level == com.attendance.tracker.health.Level.WARN }
        SoftCard(onClick = { open(Page.CHECK) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${h.score}", style = MaterialTheme.typography.headlineMedium, color = col)
                Text("/100", Modifier.padding(start = 2.dp, end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Column(Modifier.weight(1f)) {
                    Text("Auto-punch readiness", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(worst?.let { "Fix: ${it.title}" } ?: "Everything auto-punch needs is in place \u2713",
                        style = MaterialTheme.typography.bodySmall, color = if (worst == null) c.success else c.warning, maxLines = 2)
                }
                if (worst != null) Text("Fix", style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        }
    }
    val o = s.office
    val sc = s.schedule
    val ctxH = LocalContext.current
    var health by remember { mutableStateOf<com.attendance.tracker.health.HealthReport?>(null) }
    LaunchedEffect(Unit) { health = runCatching { com.attendance.tracker.health.HealthCheck.run(ctxH) }.getOrNull() }
    SoftCard(padding = 8.dp) {
        val h = health
        SummaryRow(painterResource(R.drawable.ic_shield), "Auto-punch health",
            h?.let { if (it.problems == 0) "${it.score}/100 \u00b7 all good" else "${it.score}/100 \u00b7 ${it.problems} to fix" } ?: "Checking\u2026",
            (h?.score ?: 100) >= 85) { open(Page.CHECK) }
    }
    SoftCard(padding = 8.dp) {
        SummaryRow(painterResource(R.drawable.ic_gate), "Office",
            o?.let { if (it.hasPolygon) "${it.name} · area drawn on map" else "${it.name} · ${sizeLabel(it.radiusM)} circle — tap to draw exact area" }
                ?: "Not set", o?.hasPolygon == true) { open(Page.OFFICE) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_target), "Gates",
            o?.gates?.takeIf { it.isNotEmpty() }?.joinToString { it.name } ?: "None added", !o?.gates.isNullOrEmpty()) { open(Page.GATES) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_clock), "Work hours",
            "${hoursLabel(sc.netMinutes).removeSuffix(" per day")} net + ${sc.maxLunchMinutes}m lunch · ${daysLabel(sc.workDays)}", true) { open(Page.HOURS) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_lunch), "Lunch", "${sc.maxLunchMinutes} min per day", true) { open(Page.LUNCH) }
        Divider()
        val stg = AttendanceController.settings
        val nextHol = remember { com.attendance.tracker.data.Holidays.upcoming(days = 120).firstOrNull() }
        SummaryRow(rememberVectorPainter(Icons.Filled.DateRange), "Holidays",
            nextHol?.let { "Next: ${it.second} \u00b7 ${it.first.dayOfMonth} ${it.first.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())}" }
                ?: "Add your company's holiday list", true) { open(Page.HOLIDAYS) }
        Divider()
        SummaryRow(rememberVectorPainter(Icons.Filled.Home), "Home (commute)",
            if (stg.hasHome) "Set \u00b7 commute is measured" else "Optional \u00b7 measure travel time", stg.hasHome) { open(Page.HOME) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_rupee), "Salary & deductions",
            stg.salaryRules.let { r -> if (r.monthlyGross > 0) "\u20B9 %,.0f / month \u00b7 opens the Salary tab".format(r.monthlyGross) else "Optional \u00b7 opens the Salary tab" },
            stg.salaryRules.monthlyGross > 0) { UiChrome.requestTab = 4 }
        Divider()
        val wifi = AttendanceController.settings.officeWifi
        SummaryRow(painterResource(R.drawable.ic_target), "Office Wi-Fi",
            if (wifi.isEmpty()) "Optional \u00b7 improves accuracy indoors" else wifi.joinToString { it.ssid }, true) { open(Page.WIFI) }
    }
    SoftCard(padding = 8.dp) {
        SummaryRow(painterResource(R.drawable.ic_system_update), "App updates", "Version ${UpdateManager.currentVersionName}", true) { open(Page.UPDATES) }
        Divider()
        val ctxS = LocalContext.current
        val last = com.attendance.tracker.backup.BackupManager.lastBackupMs(ctxS)
        SummaryRow(painterResource(R.drawable.ic_shield), "Backup",
            if (last > 0) "Last backup ${fmtTime(last)} \u00b7 ${java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}"
            else "Save to Google Drive \u00b7 restore on a new phone",
            last > 0) { open(Page.BACKUP) }
        Divider()
        SummaryRow(rememberVectorPainter(Icons.Filled.Settings), "Advanced", "Reminders, alerts, appearance, accuracy", true) { open(Page.ADVANCED) }
    }
    SoftCard(padding = 8.dp) {
        SummaryRow(rememberVectorPainter(Icons.Filled.Delete), "Reset", "Delete test records or start over", true) { open(Page.RESET) }
    }
}

@Composable
internal fun SummaryRow(icon: Painter, title: String, value: String, ok: Boolean, onClick: () -> Unit) {
    val c = Soft.colors
    Row(
        Modifier.fillMaxWidth().clip(Soft.tileShape).pressScale(onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, if (ok) c.accent else c.warning, if (ok) c.accentSoft else c.warningSoft, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(value, style = MaterialTheme.typography.bodySmall, color = if (ok) c.textSecondary else c.warning, maxLines = 2)
        }
        Icon(rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight), null, tint = c.textSecondary)
    }
}

@Composable
internal fun Divider() = Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(Soft.colors.hairline))

internal fun sizeLabel(r: Double) = when {
    r <= 60 -> "Small (${r.toInt()} m)"
    r <= 120 -> "Medium (${r.toInt()} m)"
    r <= 220 -> "Large (${r.toInt()} m)"
    else -> "${r.toInt()} m"
}

internal fun hoursLabel(min: Int) = if (min % 60 == 0) "${min / 60} h per day" else "${min / 60}.${(min % 60) * 10 / 60} h per day"

internal val MON_FRI = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
internal val MON_SAT = MON_FRI + DayOfWeek.SATURDAY

internal fun daysLabel(d: Set<DayOfWeek>) = when (d) {
    MON_FRI -> "Mon–Fri"
    MON_SAT -> "Mon–Sat"
    DayOfWeek.entries.toSet() -> "Every day"
    else -> DayOfWeek.entries.filter { it in d }.joinToString(" ") { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
}

// ───────────────────────────── step 1: office ─────────────────────────────

@Composable
internal fun OfficeStep(
    s: AppSettings, wizard: Boolean, stepLabel: String?, showBack: Boolean, onBack: () -> Unit, onNext: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val o = s.office
    val corners = remember { mutableStateListOf<LatLng>().apply { addAll(o?.polygon ?: emptyList()) } }
    var name by remember { mutableStateOf(o?.name ?: "My Office") }
    var linkOpen by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<LatLng?>(null) }
    var focusSeq by remember { mutableIntStateOf(0) }
    val shared = UiChrome.sharedLink.takeIf { UiChrome.sharedTarget == "office" }
    // Start in finger-drawing mode when there's no shape yet.
    var drawMode by remember { mutableStateOf(corners.isEmpty()) }
    var boxRequest by remember { mutableIntStateOf(0) }

    fun saveArea() {
        if (corners.size < 3) return
        val oldGates = current().office?.gates ?: emptyList()
        val shape = OfficeConfig.fromPolygon(name.ifBlank { "Office" }, corners.toList(), emptyList())
        // Gates near the new outline move onto it, so they stay on the boundary.
        val gates = oldGates.map { g ->
            val (snap, d) = shape.nearestOnBoundary(g.lat, g.lng)
            if (d <= 60.0) g.copy(lat = snap.lat, lng = snap.lng) else g
        }
        persist(ctx, current().copy(office = shape.copy(gates = gates)), officeMoved = true)
    }

    MapScaffold(
        title = "Draw your office",
        stepLabel = stepLabel, showBack = showBack, onBack = onBack,
        onPasteLink = { linkOpen = true },
        primaryLabel = if (wizard) "Next" else "Done",
        primaryEnabled = s.office != null,
        onPrimary = onNext,
        map = {
            OfficeMap(
                modifier = Modifier.fillMaxSize(),
                corners = corners.toList(),
                gates = o?.gates ?: emptyList(),
                editableCorners = true,
                fullScreen = true,
                focus = focus, focusSeq = focusSeq,
                // Taps only add corners while building a shape by tapping (never on top of a finished one).
                onTap = { p -> if (!drawMode && corners.size < 3) { corners.add(p); saveArea() } },
                onCornerMoved = { i, p -> if (i in corners.indices) { corners[i] = p; saveArea() } },
                drawMode = drawMode,
                boxRequest = boxRequest,
                onDrawn = { pts -> corners.clear(); corners.addAll(pts); saveArea(); drawMode = false },
            )
        },
    ) {
        val area = GeoMath.areaM2(corners)
        // Two simple ways to make the shape
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("\u270F\uFE0F  Draw", drawMode, Modifier.weight(1f)) { drawMode = !drawMode }
            ModeButton("\u25A2  Quick box", false, Modifier.weight(1f)) { drawMode = false; boxRequest++ }
            if (corners.isNotEmpty()) ModeButton("Clear", false, Modifier.weight(0.7f)) { corners.clear(); drawMode = true }
        }
        Text(
            when {
                drawMode -> "Trace around your office with one finger \u2014 one continuous line. Lift your finger to finish."
                corners.size >= 3 -> "${areaLabel(area)} \u00b7 saved \u2713  Drag any white dot to adjust."
                focus != null -> "Found it! Tap Draw and trace around the pin, or tap Quick box."
                else -> "Zoom to your office, then tap Draw and trace around it \u2014 or tap Quick box."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when { drawMode -> c.accent; corners.size >= 3 -> c.success; else -> c.textSecondary },
        )
        SoftField("Office name", name, keyboard = KeyboardType.Text) {
            name = it
            current().office?.let { off -> persist(ctx, current().copy(office = off.copy(name = it.ifBlank { "Office" }))) }
        }
    }
    if (linkOpen || shared != null) {
        MapLinkDialog(
            title = "Find your office",
            initialText = shared,
            onFound = { p ->
                focus = p; focusSeq++; linkOpen = false; UiChrome.sharedLink = null
                drawMode = true
                Toast.makeText(ctx, "Location found \u2014 trace around it with your finger", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { linkOpen = false; UiChrome.sharedLink = null },
        )
    }
}

/** Full-screen map with a floating top bar and a bottom panel. */
@Composable
internal fun MapScaffold(
    title: String, stepLabel: String?, showBack: Boolean, onBack: () -> Unit,
    onPasteLink: (() -> Unit)? = null,
    primaryLabel: String, primaryEnabled: Boolean, onPrimary: () -> Unit,
    map: @Composable () -> Unit,
    panel: @Composable ColumnScope.() -> Unit,
) {
    val c = Soft.colors
    Box(Modifier.fillMaxSize()) {
        map()
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Box(
                    Modifier.size(46.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(c.card).pressScale(onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), "Back", tint = c.textPrimary) }
                Spacer(Modifier.width(10.dp))
            }
            Column(
                Modifier.shadow(10.dp, Soft.pillShape).clip(Soft.pillShape).background(c.card)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                if (stepLabel != null) Text(stepLabel, style = MaterialTheme.typography.labelSmall, color = c.accent)
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            }
        }
        if (onPasteLink != null) {
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 72.dp)
                    .shadow(10.dp, Soft.pillShape).clip(Soft.pillShape).background(c.accent)
                    .pressScale(onPasteLink).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("\uD83D\uDD17  Paste Google Maps link", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().imePadding().padding(12.dp)
                .shadow(24.dp, Soft.cardShape, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(Soft.cardShape).background(c.card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            panel()
            SoftButton(
                primaryLabel, onPrimary, enabled = primaryEnabled,
                icon = rememberVectorPainter(if (primaryLabel == "Done") Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight),
            )
        }
    }
}

@Composable
internal fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Soft.colors
    val bg = animatedColor(if (selected) c.accent else c.cardMuted, "modeBg")
    val fg = animatedColor(if (selected) Color.White else c.textPrimary, "modeFg")
    Box(modifier.height(46.dp).clip(Soft.pillShape).background(bg).pressScale(onClick), contentAlignment = Alignment.Center) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

internal fun areaLabel(m2: Double): String {
    val acres = m2 / 4046.86
    return if (acres >= 0.25) "%.2f acres".format(acres) else "${m2.toInt()} m²"
}

// ───────────────────────────── step 2: gates ─────────────────────────────

@Composable
internal fun GatesStep(
    s: AppSettings, busy: Boolean, capture: (String, (Double, Double, Float) -> Unit) -> Unit,
    wizard: Boolean, stepLabel: String?, showBack: Boolean, onBack: () -> Unit, onNext: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val o = s.office
    var renaming by remember { mutableStateOf<Gate?>(null) }
    var linkOpen by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<LatLng?>(null) }
    var focusSeq by remember { mutableIntStateOf(0) }
    val shared = UiChrome.sharedLink.takeIf { UiChrome.sharedTarget == "gate" }

    /**
     * Adds a gate snapped onto the office boundary. Taps further than [maxSnapM] from the boundary
     * are ignored (returns false) unless [allowOff] — then the gate stays where it was given.
     */
    fun addGate(lat: Double, lng: Double, maxSnapM: Double, allowOff: Boolean = false): Boolean {
        val off = current().office ?: return false
        val (snap, dist) = off.nearestOnBoundary(lat, lng)
        val at = when {
            dist <= maxSnapM -> snap
            allowOff -> LatLng(lat, lng)
            else -> return false
        }
        val used = off.gates.map { it.name }.toSet()
        val name = GATE_NAMES.firstOrNull { it !in used } ?: "Gate ${off.gates.size + 1}"
        val g = Gate(UUID.randomUUID().toString(), name, at.lat, at.lng, 25.0)
        persist(ctx, current().copy(office = off.copy(gates = off.gates + g)), gatesChanged = true)
        Toast.makeText(ctx, "\u2713 ${g.name} added", Toast.LENGTH_SHORT).show()
        renaming = g // one-tap naming
        return true
    }

    fun moveGate(i: Int, p: LatLng) {
        val off = current().office ?: return
        val g = off.gates.getOrNull(i) ?: return
        val (snap, _) = off.nearestOnBoundary(p.lat, p.lng)
        persist(ctx, current().copy(office = off.copy(gates = off.gates.map { if (it.id == g.id) it.copy(lat = snap.lat, lng = snap.lng) else it })),
            gatesChanged = true)
    }

    if (o == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            Text("Draw your office first.", color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            SoftButton("Back", onBack)
        }
        return
    }
    MapScaffold(
        title = "Mark your gates",
        stepLabel = stepLabel, showBack = showBack, onBack = onBack,
        onPasteLink = { linkOpen = true },
        primaryLabel = if (wizard) (if (o.gates.isEmpty()) "Skip for now" else "Next") else "Done",
        primaryEnabled = true,
        onPrimary = onNext,
        map = {
            OfficeMap(
                modifier = Modifier.fillMaxSize(),
                corners = o.polygon,
                gates = o.gates,
                editableCorners = false,
                fullScreen = true,
                focus = focus, focusSeq = focusSeq,
                onTap = { p ->
                    if (!addGate(p.lat, p.lng, maxSnapM = 40.0)) {
                        Toast.makeText(ctx, "Tap on the blue boundary line where the gate is", Toast.LENGTH_SHORT).show()
                    }
                },
                editableGates = true,
                onGateMoved = { i, p -> moveGate(i, p) },
            )
        },
    ) {
        Text(
            if (o.gates.isEmpty()) "Tap on the blue boundary line where each gate is \u2014 it snaps onto the line."
            else "Drag a green gate to move it along the line. Tap a name to rename.",
            style = MaterialTheme.typography.bodyMedium, color = if (o.gates.isEmpty()) c.accent else c.textSecondary,
        )
        if (o.gates.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                o.gates.forEach { g ->
                    Row(
                        Modifier.clip(Soft.pillShape).background(c.successSoft).padding(start = 14.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g.name, Modifier.pressScale { renaming = g }.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge, color = c.success)
                        RoundIcon(rememberVectorPainter(Icons.Filled.Close), "Remove") {
                            val off = current().office ?: return@RoundIcon
                            persist(ctx, current().copy(office = off.copy(gates = off.gates.filter { it.id != g.id })), gatesChanged = true)
                        }
                    }
                }
            }
        }
        SoftGhostButton(
            "Add gate where I'm standing",
            { capture("gate") { lat, lng, _ -> addGate(lat, lng, maxSnapM = 50.0, allowOff = true) } },
            icon = rememberVectorPainter(Icons.Filled.LocationOn), enabled = !busy,
        )
    }

    if (linkOpen || shared != null) {
        MapLinkDialog(
            title = "Gate from Google Maps",
            initialText = shared,
            onFound = { p ->
                focus = p; focusSeq++; linkOpen = false; UiChrome.sharedLink = null
                addGate(p.lat, p.lng, maxSnapM = 60.0, allowOff = true)
            },
            onDismiss = { linkOpen = false; UiChrome.sharedLink = null },
        )
    }

    renaming?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.name) }
        fun saveName(n: String) {
            val off = current().office
            if (off != null && n.isNotBlank()) {
                persist(ctx, current().copy(office = off.copy(gates = off.gates.map { if (it.id == g.id) it.copy(name = n.trim()) else it })))
            }
            renaming = null
        }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Which gate is this?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GATE_NAMES.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { n ->
                                val sel = n == g.name
                                Box(
                                    Modifier.weight(1f).height(46.dp).clip(Soft.pillShape).background(if (sel) c.accent else c.cardMuted)
                                        .pressScale { saveName(n) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(n, color = if (sel) Color.White else c.textPrimary, style = MaterialTheme.typography.labelLarge) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SoftField("Or type a name", text, keyboard = KeyboardType.Text) { text = it }
                }
            },
            confirmButton = { TextButton(onClick = { saveName(text) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Keep \u201c${g.name}\u201d") } },
            shape = RoundedCornerShape(28.dp),
            containerColor = c.card,
        )
    }
}

internal val GATE_NAMES = listOf("Main Gate", "Back Gate", "Side Gate", "Parking Gate", "Staff Gate", "Gate 2")

@Composable
internal fun RoundIcon(icon: Painter, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).pressScale(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Soft.colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

// ───────────────────────────── small controls ─────────────────────────────

/** Row of big tappable choices; the selected one is filled. */
@Composable
internal fun <T> Choices(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val c = Soft.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val sel = value == selected
            val bg = animatedColor(if (sel) c.accent else c.cardMuted, "choiceBg")
            val fg = animatedColor(if (sel) Color.White else c.textPrimary, "choiceFg")
            Box(
                Modifier.weight(1f).height(if (label.contains('\n')) 64.dp else 48.dp).clip(Soft.tileShape).background(bg)
                    .pressScale { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        }
    }
}

internal fun fmt12(min: Int): String {
    val h = min / 60; val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
}

@Composable
internal fun DayDot(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Soft.colors
    val bg = animatedColor(if (selected) c.accent else c.cardMuted, "dayBg")
    val fg = animatedColor(if (selected) Color.White else c.textSecondary, "dayFg")
    Box(modifier.aspectRatio(1f).clip(CircleShape).background(bg).pressScale(onClick), contentAlignment = Alignment.Center) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SoftField(
    label: String, value: String, suffix: String? = null,
    keyboard: KeyboardType = KeyboardType.Number, onChange: (String) -> Unit,
) {
    val c = Soft.colors
    val suffixContent: (@Composable () -> Unit)? = suffix?.let { sfx -> @Composable { Text(sfx, color = c.textSecondary) } }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        suffix = suffixContent,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = c.card, unfocusedContainerColor = c.cardMuted,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.textSecondary,
            cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
