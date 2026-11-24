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

private enum class Page { SUMMARY, OFFICE, GATES, HOURS, LUNCH, UPDATES, ADVANCED }

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
    SoftCard(padding = 8.dp) {
        SummaryRow(painterResource(R.drawable.ic_gate), "Office",
            o?.let { if (it.hasPolygon) "${it.name} · area drawn on map" else "${it.name} · ${sizeLabel(it.radiusM)} circle — tap to draw exact area" }
                ?: "Not set", o?.hasPolygon == true) { open(Page.OFFICE) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_target), "Gates",
            o?.gates?.takeIf { it.isNotEmpty() }?.joinToString { it.name } ?: "None added", !o?.gates.isNullOrEmpty()) { open(Page.GATES) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_clock), "Work hours",
            "${hoursLabel(sc.requiredMinutes)} from arrival · ${daysLabel(sc.workDays)}", true) { open(Page.HOURS) }
        Divider()
        SummaryRow(painterResource(R.drawable.ic_lunch), "Lunch", "${sc.maxLunchMinutes} min per day", true) { open(Page.LUNCH) }
    }
    SoftCard(padding = 8.dp) {
        SummaryRow(painterResource(R.drawable.ic_system_update), "App updates", "Version ${UpdateManager.currentVersionName}", true) { open(Page.UPDATES) }
        Divider()
        SummaryRow(rememberVectorPainter(Icons.Filled.Settings), "Advanced", "Accuracy, overtime", true) { open(Page.ADVANCED) }
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

    fun saveArea() {
        if (corners.size < 3) return
        val gates = current().office?.gates ?: emptyList()
        persist(ctx, current().copy(office = OfficeConfig.fromPolygon(name.ifBlank { "Office" }, corners.toList(), gates)), officeMoved = true)
    }

    MapScaffold(
        title = "Draw your office",
        stepLabel = stepLabel, showBack = showBack, onBack = onBack,
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
                onTap = { p -> corners.add(p); saveArea() },
                onCornerMoved = { i, p -> if (i in corners.indices) { corners[i] = p; saveArea() } },
            )
        },
    ) {
        val area = GeoMath.areaM2(corners)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    corners.isEmpty() -> "Zoom in and tap each corner of your office"
                    corners.size < 3 -> "${corners.size} corner${if (corners.size == 1) "" else "s"} \u00b7 tap ${3 - corners.size} more"
                    else -> "${corners.size} corners \u00b7 ${areaLabel(area)} \u00b7 saved \u2713"
                },
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = if (corners.size >= 3) c.success else c.textPrimary,
            )
            if (corners.isNotEmpty()) {
                SmallChip("Undo") { corners.removeAt(corners.lastIndex); saveArea() }
                Spacer(Modifier.width(6.dp))
                SmallChip("Clear") { corners.clear() }
            }
        }
        Text("Press and hold a corner to move it.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        SoftField("Office name", name, keyboard = KeyboardType.Text) {
            name = it
            current().office?.let { off -> persist(ctx, current().copy(office = off.copy(name = it.ifBlank { "Office" }))) }
        }
    }
}

/** Full-screen map with a floating top bar and a bottom panel. */
@Composable
private fun MapScaffold(
    title: String, stepLabel: String?, showBack: Boolean, onBack: () -> Unit,
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

    fun addGate(lat: Double, lng: Double) {
        val off = current().office ?: return
        val n = off.gates.size
        val g = Gate(UUID.randomUUID().toString(), if (n == 0) "Main Gate" else "Gate ${n + 1}", lat, lng, 25.0)
        persist(ctx, current().copy(office = off.copy(gates = off.gates + g)), gatesChanged = true)
        renaming = g // name it straight away
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
                onTap = { p -> addGate(p.lat, p.lng) },
            )
        },
    ) {
        if (o.gates.isEmpty()) {
            Text("Tap the map exactly where each gate is", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        } else {
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
            { capture("gate") { lat, lng, _ -> addGate(lat, lng) } },
            icon = rememberVectorPainter(Icons.Filled.LocationOn), enabled = !busy,
        )
    }

    renaming?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this gate") },
            text = { SoftField("Gate name", text, keyboard = KeyboardType.Text) { text = it } },
            confirmButton = {
                TextButton(onClick = {
                    val off = current().office
                    if (off != null && text.isNotBlank()) {
                        persist(ctx, current().copy(office = off.copy(gates = off.gates.map { if (it.id == g.id) it.copy(name = text.trim()) else it })))
                    }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Skip") } },
            shape = RoundedCornerShape(28.dp),
            containerColor = c.card,
        )
    }
}

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

    StepTitle("Work hours", "Your day starts when you reach the office.")
    SoftCard {
        Column {
                Text("I usually arrive between (later = marked late)", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeTile("From", sc.earliestStartMin, Modifier.weight(1f)) { pick(sc.earliestStartMin) { v -> save { it.copy(earliestStartMin = v) } } }
                    TimeTile("To", sc.latestStartMin, Modifier.weight(1f)) { pick(sc.latestStartMin) { v -> save { it.copy(latestStartMin = v) } } }
                }
                Spacer(Modifier.height(18.dp))
                Text("Hours I work each day (with lunch)", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Choices(
                    listOf("8h" to 480, "8½h" to 510, "9h" to 540, "9½h" to 570, "10h" to 600),
                    sc.requiredMinutes,
                ) { v -> save { it.copy(requiredMinutes = v) } }
                val r = sc.requiredMinutes
                Text(
                    "In at 9 AM → out ${fmt12((540 + r) % 1440)}  ·  10 AM → ${fmt12((600 + r) % 1440)}  ·  11 AM → ${fmt12((660 + r) % 1440)}",
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
    StepTitle("Lunch break", "How much lunch time is allowed per day? Take it any time.")
    SoftCard {
        Choices(listOf("30 min" to 30, "45 min" to 45, "1 hour" to 60, "1½ hours" to 90), s.schedule.maxLunchMinutes) { v ->
            persist(ctx, current().copy(schedule = current().schedule.copy(maxLunchMinutes = v)))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Tap “Start lunch break” on the Today screen whenever you go. If you take longer than this, the extra time is added to your logout time automatically.",
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
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
