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

private enum class Page { SUMMARY, OFFICE, GATES, HOURS, LUNCH, UPDATES, ADVANCED, WIFI, BACKUP, CHECK, RESET, HOLIDAYS, HOME, PAY }

private val WIZARD = listOf(Page.OFFICE, Page.GATES, Page.HOURS, Page.LUNCH)

private data class CaptureUi(val label: String, val secondsLeft: Int, val total: Int, val best: Float?)

/** Saves immediately and keeps tracking / geofences in sync. */
private fun persist(ctx: Context, s: AppSettings, officeMoved: Boolean = false, gatesChanged: Boolean = false) {
    AttendanceController.settings.save(s)
    if (officeMoved) AttendanceController.onOfficeChanged() else AttendanceController.refresh()
    if (gatesChanged && !officeMoved) GeofenceManager.register(ctx)
    val st = AttendanceController.settings
    if (s.office != null && !st.trackingEnabled && !st.userPaused && AttendanceController.hasFineLocation(ctx)) {
        runCatching { TrackingService.start(ctx) }
    }
}

private fun current() = AttendanceController.settings.settings.value

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
        val target = when (UiChrome.setupPage) {
            "check" -> Page.CHECK; "pay" -> Page.PAY; "home" -> Page.HOME; "holidays" -> Page.HOLIDAYS; else -> null
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
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    Page.PAY -> PayStep()
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
private fun WizardHeader(step: Int, total: Int) {
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
private fun BackRow(onBack: () -> Unit) {
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
private fun StepTitle(title: String, subtitle: String) {
    val c = Soft.colors
    Column(Modifier.padding(start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
    }
}

// ───────────────────────────── summary ─────────────────────────────

@Composable
private fun Summary(s: AppSettings, open: (Page) -> Unit) {
    val c = Soft.colors
    Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
        Text("Setup", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Text("Tap anything to change it. Changes save automatically.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
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
        SummaryRow(painterResource(R.drawable.ic_shield), "Salary & overtime",
            if (stg.monthlySalary > 0f) "\u20B9 %,.0f / month \u00b7 OT \u20B9 %,.0f / h".format(stg.monthlySalary, stg.overtimeRate) else "Optional \u00b7 see estimated pay",
            stg.monthlySalary > 0f) { open(Page.PAY) }
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
        SummaryRow(rememberVectorPainter(Icons.Filled.Settings), "Advanced", "Accuracy, overtime", true) { open(Page.ADVANCED) }
    }
    SoftCard(padding = 8.dp) {
        SummaryRow(rememberVectorPainter(Icons.Filled.Delete), "Reset", "Delete test records or start over", true) { open(Page.RESET) }
    }
}

@Composable
private fun SummaryRow(icon: Painter, title: String, value: String, ok: Boolean, onClick: () -> Unit) {
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
private fun Divider() = Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(Soft.colors.hairline))

private fun sizeLabel(r: Double) = when {
    r <= 60 -> "Small (${r.toInt()} m)"
    r <= 120 -> "Medium (${r.toInt()} m)"
    r <= 220 -> "Large (${r.toInt()} m)"
    else -> "${r.toInt()} m"
}

private fun hoursLabel(min: Int) = if (min % 60 == 0) "${min / 60} h per day" else "${min / 60}.${(min % 60) * 10 / 60} h per day"

private val MON_FRI = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val MON_SAT = MON_FRI + DayOfWeek.SATURDAY

private fun daysLabel(d: Set<DayOfWeek>) = when (d) {
    MON_FRI -> "Mon–Fri"
    MON_SAT -> "Mon–Sat"
    DayOfWeek.entries.toSet() -> "Every day"
    else -> DayOfWeek.entries.filter { it in d }.joinToString(" ") { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
}

// ───────────────────────────── step 1: office ─────────────────────────────

@Composable
private fun OfficeStep(
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
private fun MapScaffold(
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
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Soft.colors
    val bg = animatedColor(if (selected) c.accent else c.cardMuted, "modeBg")
    val fg = animatedColor(if (selected) Color.White else c.textPrimary, "modeFg")
    Box(modifier.height(46.dp).clip(Soft.pillShape).background(bg).pressScale(onClick), contentAlignment = Alignment.Center) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    val c = Soft.colors
    Text(
        label,
        Modifier.clip(Soft.pillShape).background(c.cardMuted).pressScale(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium, color = c.textPrimary,
    )
}

private fun areaLabel(m2: Double): String {
    val acres = m2 / 4046.86
    return if (acres >= 0.25) "%.2f acres".format(acres) else "${m2.toInt()} m²"
}

// ───────────────────────────── step 2: gates ─────────────────────────────

@Composable
private fun GatesStep(
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

private val GATE_NAMES = listOf("Main Gate", "Back Gate", "Side Gate", "Parking Gate", "Staff Gate", "Gate 2")

@Composable
private fun RoundIcon(icon: Painter, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).pressScale(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Soft.colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

// ───────────────────────────── step 3: hours ─────────────────────────────

@Composable
private fun HoursStep(s: AppSettings) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val sc = s.schedule
    fun save(f: (com.attendance.tracker.core.WorkSchedule) -> com.attendance.tracker.core.WorkSchedule) =
        persist(ctx, current().copy(schedule = f(current().schedule)))
    fun pick(initial: Int, onPicked: (Int) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, false).show()

    StepTitle("Work hours", "Your day starts whenever you reach the office \u2014 no fixed start time.")
    SoftCard {
        Column {
                Text("Net working hours per day (lunch not included)", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Choices(
                    listOf("7½h" to 450, "8h" to 480, "8½h" to 510, "9h" to 540, "9½h" to 570),
                    sc.netMinutes,
                ) { v -> save { it.copy(netMinutes = v) } }
                val r = sc.requiredMinutes // net + lunch = time at the office
                Text(
                    "${hoursLabel(sc.netMinutes).removeSuffix(" per day")} net + ${sc.maxLunchMinutes}m lunch = ${hoursLabel(r).removeSuffix(" per day")} at the office\nIn at 9 AM → out ${fmt12((540 + r) % 1440)}  ·  10 AM → ${fmt12((600 + r) % 1440)}  ·  11 AM → ${fmt12((660 + r) % 1440)}",
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(Soft.tileShape).background(c.accentSoft).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = c.accent,
                )
        }
    }
    SoftCard {
        Text("Working days", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(12.dp))
        val preset = when (sc.workDays) { MON_FRI -> 0; MON_SAT -> 1; else -> 2 }
        var custom by remember { mutableStateOf(preset == 2) }
        Choices(listOf("Mon–Fri" to 0, "Mon–Sat" to 1, "Choose" to 2), if (custom) 2 else preset) { v ->
            custom = v == 2
            when (v) {
                0 -> save { it.copy(workDays = MON_FRI) }
                1 -> save { it.copy(workDays = MON_SAT) }
            }
        }
        AnimatedVisibility(custom) {
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    DayDot(d.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), d in sc.workDays, Modifier.weight(1f)) {
                        val nd = if (d in sc.workDays) sc.workDays - d else sc.workDays + d
                        if (nd.isNotEmpty()) save { it.copy(workDays = nd) }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── step 4: lunch ─────────────────────────────

@Composable
private fun LunchStep(s: AppSettings) {
    val ctx = LocalContext.current
    val c = Soft.colors
    StepTitle("Lunch break", "How much lunch is deducted from your day? Take it any time.")
    SoftCard {
        Choices(listOf("30 min" to 30, "45 min" to 45, "1 hour" to 60, "1½ hours" to 90), s.schedule.maxLunchMinutes) { v ->
            persist(ctx, current().copy(schedule = current().schedule.copy(maxLunchMinutes = v)))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "This much lunch is always deducted from your working hours \u2014 even if you don't take lunch. " +
                "If you take longer, the longer lunch is deducted and your logout moves later by the extra time. " +
                "Your net hours stay the same (${hoursLabel(s.schedule.netMinutes).removeSuffix(" per day")}).",
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
        )
    }
}

// ───────────────────────────── holidays ─────────────────────────────

@Composable
private fun HolidaysStep() {
    val ctx = LocalContext.current
    val c = Soft.colors
    val st = AttendanceController.settings
    var tick by remember { mutableIntStateOf(0) }
    var national by remember { mutableStateOf(st.holNational) }
    var newYear by remember { mutableStateOf(st.holNewYear) }
    var mayDay by remember { mutableStateOf(st.holMayDay) }
    var xmas by remember { mutableStateOf(st.holChristmas) }
    var paste by remember { mutableStateOf("") }
    var addName by remember { mutableStateOf("") }
    val custom = remember(tick) { st.customHolidays.toSortedMap() }
    fun changed() { tick++; AttendanceController.refresh() }

    StepTitle("Holidays", "These days count as days off \u2014 never absent.")
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.DateRange), "Fixed public holidays")
        Spacer(Modifier.height(6.dp))
        SoftSwitchRow("National holidays", "26 Jan Republic Day \u00b7 15 Aug Independence Day \u00b7 2 Oct Gandhi Jayanti", national) { national = it; st.holNational = it; changed() }
        SoftSwitchRow("New Year (1 Jan)", null, newYear) { newYear = it; st.holNewYear = it; changed() }
        SoftSwitchRow("May Day (1 May)", null, mayDay) { mayDay = it; st.holMayDay = it; changed() }
        SoftSwitchRow("Christmas (25 Dec)", null, xmas) { xmas = it; st.holChristmas = it; changed() }
        Text("Festivals like Pongal, Diwali, Holi or Eid fall on different dates each year \u2014 add them below from your company's list.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Add), "Add a holiday")
        Spacer(Modifier.height(10.dp))
        SoftField("Name (e.g. Pongal)", addName, keyboard = KeyboardType.Text) { addName = it }
        Spacer(Modifier.height(10.dp))
        SoftButton("Pick date & add", {
            val now = java.time.LocalDate.now()
            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                val date = java.time.LocalDate.of(y, m + 1, d)
                st.customHolidays = st.customHolidays + (date.toString() to addName.ifBlank { "Holiday" }.trim())
                addName = ""; changed()
                Toast.makeText(ctx, "Holiday added", Toast.LENGTH_SHORT).show()
            }, now.year, now.monthValue - 1, now.dayOfMonth).show()
        }, icon = rememberVectorPainter(Icons.Filled.DateRange))
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Edit), "Paste your company's list", "One per line, e.g. 14-01-2027 Pongal")
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedTextField(
            value = paste, onValueChange = { paste = it }, minLines = 4, maxLines = 10,
            placeholder = { Text("14-01-2027 Pongal\n29-10-2027 Diwali\n2027-03-22 Holi") },
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        SoftGhostButton("Import list", {
            val list = com.attendance.tracker.core.Insights.parseHolidays(paste)
            if (list.isEmpty()) Toast.makeText(ctx, "No dates found \u2014 use e.g. 14-01-2027 Pongal", Toast.LENGTH_LONG).show()
            else {
                st.customHolidays = st.customHolidays + list.associate { (d, n) -> d.toString() to n }
                paste = ""; changed()
                Toast.makeText(ctx, "${list.size} holidays added", Toast.LENGTH_SHORT).show()
            }
        })
    }
    if (custom.isNotEmpty()) SoftCard(padding = 12.dp) {
        Text("Your holidays", Modifier.padding(4.dp), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        custom.forEach { (date, name) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    Text(runCatching { java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) }.getOrDefault(date),
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                RoundIcon(rememberVectorPainter(Icons.Filled.Close), "Remove") { st.customHolidays = st.customHolidays - date; changed() }
            }
        }
    }
}

// ───────────────────────────── home (commute) ─────────────────────────────

@Composable
private fun HomeStep() {
    val ctx = LocalContext.current
    val c = Soft.colors
    val scope = rememberCoroutineScope()
    val st = AttendanceController.settings
    var home by remember { mutableStateOf(if (st.hasHome) LatLng(st.homeLat, st.homeLng) else null) }
    var seq by remember { mutableIntStateOf(if (st.hasHome) 1 else 0) }
    var capturing by remember { mutableStateOf(false) }
    fun save(p: LatLng) {
        st.homeLat = p.lat; st.homeLng = p.lng; st.atHome = null
        home = p; seq++
        Toast.makeText(ctx, "Home saved", Toast.LENGTH_SHORT).show()
    }
    StepTitle("Home (commute)", "Used only to measure your travel time. It stays on this phone.")
    OfficeMap(
        modifier = Modifier.fillMaxWidth().height(320.dp),
        corners = st.settings.value.office?.polygon ?: emptyList(),
        gates = emptyList(),
        editableCorners = false,
        focus = home, focusSeq = seq,
        onTap = { p -> save(p) },
    )
    Text(if (home == null) "Tap your home on the map, or use your current location." else "Tap the map to move your home pin.",
        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    SoftButton(if (capturing) "Getting location\u2026" else "I'm at home now", {
        if (!AttendanceController.hasFineLocation(ctx)) return@SoftButton
        capturing = true
        scope.launch {
            val r = LocationCapture.capture(ctx, durationMs = 10_000L, maxAccuracyM = 40f)
            capturing = false
            if (r == null) Toast.makeText(ctx, "Couldn't get GPS \u2014 tap the map instead", Toast.LENGTH_LONG).show()
            else save(LatLng(r.point.lat, r.point.lng))
        }
    }, enabled = !capturing, icon = rememberVectorPainter(Icons.Filled.Home))
    if (home != null) SoftGhostButton("Remove home", { st.clearHome(); home = null }, tint = c.danger, background = c.dangerSoft)
}

// ───────────────────────────── pay ─────────────────────────────

@Composable
private fun PayStep() {
    val c = Soft.colors
    val st = AttendanceController.settings
    var salary by remember { mutableStateOf(if (st.monthlySalary > 0f) "%.0f".format(st.monthlySalary) else "") }
    var rate by remember { mutableStateOf(if (st.overtimeRate > 0f) "%.0f".format(st.overtimeRate) else "") }
    var dedAbsent by remember { mutableStateOf(st.deductAbsent) }
    var dedHalf by remember { mutableStateOf(st.deductHalfDay) }
    val suggested = salary.toFloatOrNull()?.let { s -> s / 26f / (st.settings.value.schedule.netMinutes / 60f) }

    StepTitle("Salary & overtime", "Used only for your estimated pay in Insights. Stays on this phone.")
    SoftCard {
        SoftField("Monthly salary (\u20B9)", salary) {
            salary = it.filter { ch -> ch.isDigit() || ch == '.' }; st.monthlySalary = salary.toFloatOrNull() ?: 0f
        }
        Spacer(Modifier.height(12.dp))
        SoftField("Overtime pay per hour (\u20B9)", rate) {
            rate = it.filter { ch -> ch.isDigit() || ch == '.' }; st.overtimeRate = rate.toFloatOrNull() ?: 0f
        }
        if (suggested != null && rate.isBlank()) {
            Spacer(Modifier.height(8.dp))
            MiniAction("Use your hourly rate: \u20B9 %.0f / h".format(suggested)) {
                rate = "%.0f".format(suggested); st.overtimeRate = suggested
            }
        }
        Text("Overtime = net time beyond your daily target.", Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Settings), "Deductions")
        SoftSwitchRow("Deduct absent days", "A working day with no punches and no leave mark", dedAbsent) { dedAbsent = it; st.deductAbsent = it }
        SoftSwitchRow("Deduct half for half days", null, dedHalf) { dedHalf = it; st.deductHalfDay = it }
        Text("Leave, sick leave, WFH and holidays are treated as paid.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

// ───────────────────────────── reset ─────────────────────────────

@Composable
private fun ResetStep(onWholeReset: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val bm = com.attendance.tracker.backup.BackupManager
    var keepToday by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "records" or "all"
    var tick by remember { mutableIntStateOf(0) }
    val todayStart = remember { java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val stats = remember(keepToday, tick) { AttendanceController.db.recordStats(if (keepToday) todayStart else null) }
    val allStats = remember(tick) { AttendanceController.db.recordStats(null) }
    val hasBackup = bm.backupUri(ctx) != null

    StepTitle("Reset", "Remove test records, or start the app over.")

    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Delete), "Clear attendance records", "Keeps your office, gates, hours and Wi-Fi",
            c.warning, c.warningSoft)
        Spacer(Modifier.height(14.dp))
        Choices(listOf("All up to now" to false, "Before today" to true), keepToday) { keepToday = it }
        Spacer(Modifier.height(12.dp))
        Text(
            if (stats.first == 0 && stats.second == 0) "Nothing to delete."
            else "Deletes ${stats.first} punch${if (stats.first == 1) "" else "es"} across ${stats.second} day${if (stats.second == 1) "" else "s"}, " +
                "plus lunches, on-duty, leave marks and totals" + if (keepToday) ". Today's records stay." else ". History will be empty.",
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        SoftButton("Delete records", { confirm = "records" }, enabled = stats.first > 0 || stats.second > 0,
            colors = c.warning.copy(alpha = 0.85f) to c.warning, icon = rememberVectorPainter(Icons.Filled.Delete))
    }

    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Delete), "Reset the whole app", "Records and setup", c.danger, c.dangerSoft)
        Spacer(Modifier.height(10.dp))
        Text("Deletes all ${allStats.first} punches and your office map, gates, hours and Wi-Fi. Tracking stops and setup starts again from step 1. App permissions stay.",
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        SoftGhostButton("Reset everything", { confirm = "all" }, tint = c.danger, background = c.dangerSoft)
    }

    if (hasBackup) Text(
        "You have a Google Drive backup. It is updated every night, so after deleting, tonight's backup will contain the cleared data. Use \u201cBack up first\u201d to keep a copy of the current records.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
    )

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "all") "Reset everything?" else "Delete these records?") },
            text = {
                Text(
                    (if (what == "all") "All records and your whole setup will be deleted."
                    else "${stats.first} punches across ${stats.second} days will be deleted" + if (keepToday) " (today's stay)." else ".") +
                        " This can't be undone.",
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
                        AttendanceController.clearRecords(keepToday)
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
private fun HealthStep(open: (Page) -> Unit) {
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
            com.attendance.tracker.health.Fix.WIFI -> open(Page.WIFI)
            com.attendance.tracker.health.Fix.DATE_SETTINGS -> go(android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
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
                        Text("Fix", Modifier.clip(Soft.pillShape).background(col).pressScale { fix(f) }.padding(horizontal = 14.dp, vertical = 8.dp),
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

// ───────────────────────────── office Wi-Fi ─────────────────────────────

@Composable
private fun WifiStep() {
    val ctx = LocalContext.current
    val c = Soft.colors
    var saved by remember { mutableStateOf(AttendanceController.settings.officeWifi) }
    var current by remember { mutableStateOf(com.attendance.tracker.service.WifiHelper.current(ctx)) }
    StepTitle("Office Wi-Fi", "A second signal that you're inside \u2014 useful when GPS is weak deep in the building.")
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_target), "Connected now")
        Spacer(Modifier.height(12.dp))
        val cur = current
        if (cur == null) {
            Text("Not connected to Wi-Fi. Connect to the office Wi-Fi, then tap Refresh.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        } else {
            Text(cur.ssid, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        }
        Spacer(Modifier.height(12.dp))
        val already = cur != null && saved.any { it.ssid == cur.ssid }
        if (cur != null && !already) {
            SoftButton("Add \u201c${cur.ssid}\u201d as office Wi-Fi", {
                saved = saved + cur
                AttendanceController.settings.officeWifi = saved
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            }, icon = rememberVectorPainter(Icons.Filled.Add))
            Spacer(Modifier.height(8.dp))
        }
        SoftGhostButton("Refresh", { current = com.attendance.tracker.service.WifiHelper.current(ctx) })
    }
    if (saved.isNotEmpty()) SoftCard {
        Text("Saved office networks", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        saved.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.ssid, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                RoundIcon(rememberVectorPainter(Icons.Filled.Close), "Remove") {
                    saved = saved.filter { it.ssid != w.ssid }
                    AttendanceController.settings.officeWifi = saved
                }
            }
        }
    }
    SoftCard {
        Text("How it's used", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Text(
            "While you're on office Wi-Fi, a weak GPS reading can't punch you OUT by mistake. Leaving is still detected by GPS and your gates. " +
                "If tracking starts while you're deep inside and GPS can't place you, Wi-Fi marks you IN.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
        )
    }
}

// ───────────────────────────── backup ─────────────────────────────

@Composable
private fun BackupStep() {
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
        CardHeader(painterResource(R.drawable.ic_shield), "Backup file on Google Drive", "Updated automatically every night", c.success, c.successSoft)
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
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_system_update), "Restore on a new phone")
        Spacer(Modifier.height(10.dp))
        Text("Install the app, finish the permission steps, then pick your backup file from Google Drive.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        SoftGhostButton("Restore from backup file", { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
    }
    SoftCard {
        Text("Also automatic", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Text("Android's own backup also saves this app's data to your Google account (when phone backup is on in Settings \u2192 Google \u2192 Backup) and restores it when you install the app on a new phone.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }

    restoreUri?.let { u ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restore this backup?") },
            text = { Text("This replaces the attendance history and settings on this phone with the backup's.") },
            confirmButton = {
                TextButton(onClick = {
                    val err = bm.restore(ctx, u)
                    restoreUri = null
                    Toast.makeText(ctx, err ?: "Restored", Toast.LENGTH_LONG).show()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
            shape = RoundedCornerShape(28.dp), containerColor = c.card,
        )
    }
}

// ───────────────────────────── advanced ─────────────────────────────

@Composable
private fun AdvancedStep(s: AppSettings) {
    val ctx = LocalContext.current
    val c = Soft.colors
    val sc = s.schedule
    val acc = s.accuracy
    fun pick(initial: Int, onPicked: (Int) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, false).show()

    StepTitle("Advanced", "You normally don't need to change these.")
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Notifications), "Reminders & automation")
        Spacer(Modifier.height(6.dp))
        val st = AttendanceController.settings
        var a1 by remember { mutableStateOf(st.autoLunch) }
        var a2 by remember { mutableStateOf(st.remindBeforeLogout) }
        var a3 by remember { mutableStateOf(st.remindHoursDone) }
        var a4 by remember { mutableStateOf(st.remindLunchOver) }
        SoftSwitchRow("Detect lunch automatically", "After an afternoon break, ask \u201cWas this lunch?\u201d", a1) { a1 = it; st.autoLunch = it }
        SoftSwitchRow("15 min before logout", null, a2) { a2 = it; st.remindBeforeLogout = it }
        SoftSwitchRow("Hours complete", "\u201cYou can leave now\u201d", a3) { a3 = it; st.remindHoursDone = it }
        SoftSwitchRow("Lunch running over", null, a4) { a4 = it; st.remindLunchOver = it }
        var a5 by remember { mutableStateOf(st.askDuty) }
        var a6 by remember { mutableStateOf(st.autoSummary) }
        SoftSwitchRow("Ask \u201cofficial work?\u201d when I leave", "Mark outings as On duty so they count as worked time", a5) { a5 = it; st.askDuty = it }
        SoftSwitchRow("End-of-day summary", "When your day is done, ready to share", a6) { a6 = it; st.autoSummary = it }
        var phone by remember { mutableStateOf(st.managerPhone) }
        Spacer(Modifier.height(6.dp))
        SoftField("Manager's WhatsApp number (optional)", phone, keyboard = KeyboardType.Phone) {
            phone = it.filter { ch -> ch.isDigit() || ch == '+' }; st.managerPhone = phone
        }
        Text("With country code, e.g. 919876543210. Adds a \u201cSend to manager\u201d button to the summary.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    SoftCard {
        CardHeader(rememberVectorPainter(Icons.Filled.Notifications), "Live Updates")
        Spacer(Modifier.height(6.dp))
        var live by remember { mutableStateOf(AttendanceController.settings.liveUpdates) }
        val allowed = remember { com.attendance.tracker.service.LiveNotification.liveUpdatesAllowed(ctx) }
        SoftSwitchRow(
            "Show Live Updates",
            "Only while you're inside the office or on lunch: logout countdown, lunch time left, overtime \u2014 in the status bar and Live Alerts (OPPO, OnePlus, Realme)",
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
            "Rising chime when you arrive, falling chime when you leave",
            sound,
        ) { v -> sound = v; AttendanceController.settings.punchSound = v }
    }
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_clock), "Overtime")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.cardMuted)
                .pressScale { pick(sc.overtimeUntilMin) { v -> persist(ctx, current().copy(schedule = current().schedule.copy(overtimeUntilMin = v))) } }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Count overtime until", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(StatusResolver.fmt(sc.overtimeUntilMin), style = MaterialTheme.typography.titleLarge, color = c.accent)
        }
    }
    SoftCard {
        CardHeader(painterResource(R.drawable.ic_shield), "Detection accuracy")
        Spacer(Modifier.height(12.dp))
        Text("Ignore GPS readings worse than", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Choices(listOf("20 m" to 20f, "35 m" to 35f, "50 m" to 50f, "75 m" to 75f), acc.maxAccuracyM) { v ->
            persist(ctx, current().copy(accuracy = current().accuracy.copy(maxAccuracyM = v)))
        }
        Spacer(Modifier.height(14.dp))
        Text("Readings needed to confirm IN / OUT", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Choices(listOf("2 (faster)" to 2, "3" to 3, "4" to 4, "5 (safer)" to 5), acc.confirmSamples) { v ->
            persist(ctx, current().copy(accuracy = current().accuracy.copy(confirmSamples = v)))
        }
        Spacer(Modifier.height(6.dp))
        SoftSwitchRow("Block fake GPS apps", null, acc.rejectMock) { v ->
            persist(ctx, current().copy(accuracy = current().accuracy.copy(rejectMock = v)))
        }
    }
    s.office?.let { o ->
        SoftCard {
            CardHeader(rememberVectorPainter(Icons.Filled.LocationOn), "Office coordinates")
            Spacer(Modifier.height(12.dp))
            var lat by remember { mutableStateOf("%.7f".format(Locale.US, o.lat)) }
            var lng by remember { mutableStateOf("%.7f".format(Locale.US, o.lng)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SoftField("Latitude", lat, keyboard = KeyboardType.Decimal) { lat = it } }
                Box(Modifier.weight(1f)) { SoftField("Longitude", lng, keyboard = KeyboardType.Decimal) { lng = it } }
            }
            Spacer(Modifier.height(12.dp))
            SoftGhostButton("Use these coordinates", {
                val la = lat.toDoubleOrNull(); val ln = lng.toDoubleOrNull()
                if (la == null || ln == null || la !in -90.0..90.0 || ln !in -180.0..180.0) {
                    Toast.makeText(ctx, "Invalid coordinates", Toast.LENGTH_SHORT).show()
                } else {
                    persist(ctx, current().copy(office = current().office?.copy(lat = la, lng = ln)), officeMoved = true)
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}

// ───────────────────────────── small controls ─────────────────────────────

/** Row of big tappable choices; the selected one is filled. */
@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
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

@Composable
private fun SegmentedToggle(left: String, right: String, rightSelected: Boolean, onChange: (Boolean) -> Unit) {
    val c = Soft.colors
    Row(Modifier.fillMaxWidth().height(52.dp).clip(Soft.pillShape).background(c.cardMuted).padding(4.dp)) {
        listOf(false to left, true to right).forEach { (value, label) ->
            val sel = value == rightSelected
            val bg = animatedColor(if (sel) c.card else c.cardMuted, "segBg")
            val fg = animatedColor(if (sel) c.accent else c.textSecondary, "segFg")
            Box(
                Modifier.weight(1f).fillMaxSize().clip(Soft.pillShape).background(bg).pressScale { onChange(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun TimeTile(
    label: String, minutes: Int, modifier: Modifier,
    tint: Color = Soft.colors.accent, bg: Color = Soft.colors.accentSoft, onClick: () -> Unit,
) {
    Column(modifier.clip(Soft.tileShape).background(bg).pressScale(onClick).padding(vertical = 16.dp, horizontal = 18.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        Spacer(Modifier.height(4.dp))
        Text(fmt12(minutes), style = MaterialTheme.typography.headlineSmall, color = Soft.colors.textPrimary)
    }
}

private fun fmt12(min: Int): String {
    val h = min / 60; val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
}

@Composable
private fun DayDot(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Soft.colors
    val bg = animatedColor(if (selected) c.accent else c.cardMuted, "dayBg")
    val fg = animatedColor(if (selected) Color.White else c.textSecondary, "dayFg")
    Box(modifier.aspectRatio(1f).clip(CircleShape).background(bg).pressScale(onClick), contentAlignment = Alignment.Center) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SoftField(
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
