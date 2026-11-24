package com.attendance.tracker.ui

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.AccuracyConfig
import com.attendance.tracker.core.Gate
import com.attendance.tracker.core.OfficeConfig
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkSchedule
import com.attendance.tracker.data.AppSettings
import com.attendance.tracker.service.LocationCapture
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.util.Locale
import java.util.UUID

private data class CaptureUi(val label: String, val secondsLeft: Int, val total: Int, val best: Float?)

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val c = Soft.colors
    val scope = rememberCoroutineScope()
    val saved = remember { AttendanceController.settings.settings.value }

    var officeName by remember { mutableStateOf(saved.office?.name ?: "My Office") }
    var lat by remember { mutableStateOf(saved.office?.lat?.toString() ?: "") }
    var lng by remember { mutableStateOf(saved.office?.lng?.toString() ?: "") }
    var radius by remember { mutableStateOf((saved.office?.radiusM ?: 100.0).toInt().toString()) }
    val gates = remember { mutableStateListOf<Gate>().apply { addAll(saved.office?.gates ?: emptyList()) } }
    var newGateName by remember { mutableStateOf("Main Gate") }
    var newGateRadius by remember { mutableStateOf("25") }

    var startMin by remember { mutableIntStateOf(saved.schedule.startMin) }
    var endMin by remember { mutableIntStateOf(saved.schedule.endMin) }
    var lunchStart by remember { mutableIntStateOf(saved.schedule.lunchStartMin) }
    var lunchEnd by remember { mutableIntStateOf(saved.schedule.lunchEndMin) }
    var maxLunch by remember { mutableStateOf(saved.schedule.maxLunchMinutes.toString()) }
    var restrictLunch by remember { mutableStateOf(saved.schedule.restrictLunchToWindow) }
    val days = remember { mutableStateListOf<DayOfWeek>().apply { addAll(saved.schedule.workDays) } }

    var maxAcc by remember { mutableStateOf(saved.accuracy.maxAccuracyM.toInt().toString()) }
    var confirm by remember { mutableStateOf(saved.accuracy.confirmSamples.toString()) }
    var rejectMock by remember { mutableStateOf(saved.accuracy.rejectMock) }
    var showAdvanced by remember { mutableStateOf(false) }

    var capture by remember { mutableStateOf<CaptureUi?>(null) }

    fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    fun doCapture(label: String, onDone: (Double, Double) -> Unit) {
        if (!AttendanceController.hasFineLocation(ctx)) {
            Toast.makeText(ctx, "Allow location on the Today tab first", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val r = LocationCapture.capture(ctx) { left, best -> capture = CaptureUi(label, left, 20, best) }
            capture = null
            if (r == null) {
                Toast.makeText(ctx, "Couldn't get a GPS fix. Try again in open sky.", Toast.LENGTH_LONG).show()
            } else {
                onDone(r.point.lat, r.point.lng)
                Toast.makeText(ctx, "$label saved · ±${r.point.accuracyM.toInt()} m from ${r.samplesUsed} readings", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun save() {
        val la = lat.toDoubleOrNull(); val ln = lng.toDoubleOrNull(); val r = radius.toDoubleOrNull()
        val err = when {
            la == null || ln == null || la !in -90.0..90.0 || ln !in -180.0..180.0 -> "Set the office location first"
            r == null || r < 20 -> "Radius must be at least 20 m"
            startMin == endMin -> "Start and end time can't be the same"
            days.isEmpty() -> "Pick at least one working day"
            else -> null
        }
        if (err != null) { Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); return }
        val old = AttendanceController.settings.settings.value.office
        val office = OfficeConfig(officeName.ifBlank { "Office" }, la!!, ln!!, r!!, gates.toList())
        AttendanceController.settings.save(
            AppSettings(
                office = office,
                schedule = WorkSchedule(
                    startMin, endMin, days.toSet(), lunchStart, lunchEnd,
                    (maxLunch.toIntOrNull() ?: 60).coerceIn(5, 240), restrictLunch,
                ),
                accuracy = AccuracyConfig(
                    maxAccuracyM = (maxAcc.toFloatOrNull() ?: 35f).coerceIn(5f, 200f),
                    confirmSamples = (confirm.toIntOrNull() ?: 3).coerceIn(1, 10),
                    rejectMock = rejectMock,
                ),
            )
        )
        if (old == null || old.lat != office.lat || old.lng != office.lng || old.radiusM != office.radiusM) {
            AttendanceController.onOfficeChanged()
        } else AttendanceController.refresh()
        val st = AttendanceController.settings
        if (!st.trackingEnabled && !st.userPaused && AttendanceController.hasFineLocation(ctx)) {
            runCatching { com.attendance.tracker.service.TrackingService.start(ctx) }
        }
        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(top = 20.dp, start = 4.dp)) {
                Text("Make it yours", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Text("Setup", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            }

            // ---------------- Working hours ----------------
            SoftCard {
                CardHeader(painterResource(R.drawable.ic_clock), "Working hours", "When you're expected at the office")
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeTile("Starts", startMin, Modifier.weight(1f)) { pickTime(startMin) { startMin = it } }
                    TimeTile("Ends", endMin, Modifier.weight(1f)) { pickTime(endMin) { endMin = it } }
                }
                AnimatedVisibility(endMin < startMin) {
                    Text("Night shift — ends the next day", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = c.accent)
                }
                Spacer(Modifier.height(18.dp))
                Text("Working days", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        DayDot(d.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), d in days, Modifier.weight(1f)) {
                            if (d in days) days.remove(d) else days.add(d)
                        }
                    }
                }
            }

            // ---------------- Lunch ----------------
            SoftCard {
                CardHeader(painterResource(R.drawable.ic_lunch), "Lunch break", "Declared from the Today screen", c.warning, c.warningSoft)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeTile("From", lunchStart, Modifier.weight(1f), c.warning, c.warningSoft) { pickTime(lunchStart) { lunchStart = it } }
                    TimeTile("To", lunchEnd, Modifier.weight(1f), c.warning, c.warningSoft) { pickTime(lunchEnd) { lunchEnd = it } }
                }
                Spacer(Modifier.height(14.dp))
                SoftField("Maximum duration", maxLunch, "min") { maxLunch = it.filter(Char::isDigit) }
                Spacer(Modifier.height(6.dp))
                SoftSwitchRow("Only inside this window", "Block lunch declarations at other times", restrictLunch) { restrictLunch = it }
            }

            // ---------------- Office ----------------
            SoftCard {
                CardHeader(painterResource(R.drawable.ic_gate), "Office", "Stand at the centre of the premises", c.success, c.successSoft)
                Spacer(Modifier.height(18.dp))
                SoftField("Office name", officeName, keyboard = KeyboardType.Text) { officeName = it }
                Spacer(Modifier.height(12.dp))
                val hasLoc = lat.toDoubleOrNull() != null && lng.toDoubleOrNull() != null
                Row(
                    Modifier.fillMaxWidth().clip(Soft.tileShape).background(c.cardMuted).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(rememberVectorPainter(Icons.Filled.LocationOn), null, tint = if (hasLoc) c.success else c.textSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (hasLoc) "%.6f, %.6f".format(Locale.US, lat.toDouble(), lng.toDouble()) else "Location not set",
                        style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SoftButton(
                    if (hasLoc) "Re-capture office centre" else "Capture office centre",
                    { doCapture("Office centre") { a, b -> lat = "%.7f".format(Locale.US, a); lng = "%.7f".format(Locale.US, b) } },
                    colors = c.success.copy(alpha = 0.85f) to c.success,
                    icon = painterResource(R.drawable.ic_target),
                    enabled = capture == null,
                )
                Spacer(Modifier.height(14.dp))
                SoftField("Premises radius", radius, "m") { radius = it.filter(Char::isDigit) }
                Text(
                    "Cover the whole compound up to the boundary wall. You're counted inside only when GPS is sure.",
                    Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }

            // ---------------- Gates ----------------
            SoftCard {
                CardHeader(painterResource(R.drawable.ic_gate), "Gates", "Stand at each gate to add it")
                Spacer(Modifier.height(14.dp))
                if (gates.isEmpty()) {
                    Text("No gates yet", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(4.dp))
                }
                gates.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Soft.tileShape).background(c.cardMuted).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBubble(painterResource(R.drawable.ic_gate), c.accent, c.accentSoft, size = 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            Text("${g.radiusM.toInt()} m zone", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).pressScale { gates.remove(g) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(rememberVectorPainter(Icons.Filled.Close), "Remove", tint = c.textSecondary, modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1.6f)) { SoftField("Gate name", newGateName, keyboard = KeyboardType.Text) { newGateName = it } }
                    Box(Modifier.weight(1f)) { SoftField("Zone", newGateRadius, "m") { newGateRadius = it.filter(Char::isDigit) } }
                }
                Spacer(Modifier.height(12.dp))
                SoftGhostButton(
                    "Add gate here",
                    {
                        val name = newGateName.ifBlank { "Gate ${gates.size + 1}" }
                        val r = newGateRadius.toDoubleOrNull() ?: 25.0
                        doCapture(name) { a, b ->
                            gates.add(Gate(UUID.randomUUID().toString(), name, a, b, r))
                            newGateName = "Gate ${gates.size + 1}"
                        }
                    },
                    icon = rememberVectorPainter(Icons.Filled.Add),
                    enabled = capture == null,
                )
            }

            // ---------------- Accuracy ----------------
            SoftCard(onClick = { showAdvanced = !showAdvanced }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        CardHeader(painterResource(R.drawable.ic_shield), "Accuracy", "Fine-tune how strict detection is")
                    }
                    Text(if (showAdvanced) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
                AnimatedVisibility(showAdvanced, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SoftField("Ignore readings worse than", maxAcc, "m") { maxAcc = it.filter(Char::isDigit) }
                        SoftField("Readings needed to switch in/out", confirm, "×") { confirm = it.filter(Char::isDigit) }
                        SoftSwitchRow("Block fake GPS apps", "Reject mock locations", rejectMock) { rejectMock = it }
                    }
                }
            }

            // ---------------- Updates ----------------
            SoftCard {
                CardHeader(painterResource(R.drawable.ic_system_update), "App updates", "Delivered over the air")
                Spacer(Modifier.height(14.dp))
                UpdateControls()
            }

            Spacer(Modifier.height(NavBarClearance + 72.dp))
        }

        // Sticky save / capture bar
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 96.dp)
        ) {
            val cap = capture
            if (cap != null) {
                SoftCard(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(
                            progress = 1f - cap.secondsLeft.toFloat() / cap.total,
                            color = c.accent, track = c.accentSoft, modifier = Modifier.size(44.dp), stroke = 5.dp,
                        ) { Text("${cap.secondsLeft}", style = MaterialTheme.typography.labelMedium, color = c.accent) }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Capturing ${cap.label}…", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            Text(
                                "Hold still · best ±${cap.best?.toInt() ?: "–"} m",
                                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                            )
                        }
                    }
                }
            } else {
                SoftButton("Save changes", { save() }, icon = rememberVectorPainter(Icons.Filled.Check))
            }
        }
    }
}

@Composable
private fun TimeTile(
    label: String, minutes: Int, modifier: Modifier,
    tint: Color = Soft.colors.accent, bg: Color = Soft.colors.accentSoft, onClick: () -> Unit,
) {
    Column(
        modifier.clip(Soft.tileShape).background(bg).pressScale(onClick).padding(vertical = 16.dp, horizontal = 18.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        Spacer(Modifier.height(4.dp))
        Text(StatusResolver.fmt(minutes), style = MaterialTheme.typography.headlineSmall, color = Soft.colors.textPrimary)
    }
}

@Composable
private fun DayDot(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Soft.colors
    val bg = animatedColor(if (selected) c.accent else c.cardMuted, "dayBg")
    val fg = animatedColor(if (selected) Color.White else c.textSecondary, "dayFg")
    Box(
        modifier.aspectRatio(1f).clip(CircleShape).background(bg).pressScale(onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
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
