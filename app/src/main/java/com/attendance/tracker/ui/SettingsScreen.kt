package com.attendance.tracker.ui

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.core.AccuracyConfig
import com.attendance.tracker.core.Gate
import com.attendance.tracker.core.OfficeConfig
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkSchedule
import com.attendance.tracker.data.AppSettings
import com.attendance.tracker.service.LocationCapture
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { AttendanceController.settings.settings.value }

    // Office
    var officeName by remember { mutableStateOf(saved.office?.name ?: "My Office") }
    var lat by remember { mutableStateOf(saved.office?.lat?.toString() ?: "") }
    var lng by remember { mutableStateOf(saved.office?.lng?.toString() ?: "") }
    var radius by remember { mutableStateOf((saved.office?.radiusM ?: 100.0).toInt().toString()) }
    val gates = remember { mutableStateListOf<Gate>().apply { addAll(saved.office?.gates ?: emptyList()) } }
    var newGateName by remember { mutableStateOf("Main Gate") }
    var newGateRadius by remember { mutableStateOf("25") }

    // Schedule
    var startMin by remember { mutableStateOf(saved.schedule.startMin) }
    var endMin by remember { mutableStateOf(saved.schedule.endMin) }
    var lunchStart by remember { mutableStateOf(saved.schedule.lunchStartMin) }
    var lunchEnd by remember { mutableStateOf(saved.schedule.lunchEndMin) }
    var maxLunch by remember { mutableStateOf(saved.schedule.maxLunchMinutes.toString()) }
    var restrictLunch by remember { mutableStateOf(saved.schedule.restrictLunchToWindow) }
    val days = remember { mutableStateListOf<DayOfWeek>().apply { addAll(saved.schedule.workDays) } }

    // Accuracy
    var maxAcc by remember { mutableStateOf(saved.accuracy.maxAccuracyM.toInt().toString()) }
    var confirm by remember { mutableStateOf(saved.accuracy.confirmSamples.toString()) }
    var rejectMock by remember { mutableStateOf(saved.accuracy.rejectMock) }

    var capturing by remember { mutableStateOf<String?>(null) }

    fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    fun capture(label: String, onDone: (Double, Double) -> Unit) {
        if (!AttendanceController.hasFineLocation(ctx)) {
            Toast.makeText(ctx, "Grant location permission on the Today tab first", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val r = LocationCapture.capture(ctx) { left, best ->
                capturing = "Capturing $label… ${left}s · best ±${best?.toInt() ?: "–"} m. Stand still, preferably in open sky."
            }
            capturing = null
            if (r == null) {
                Toast.makeText(ctx, "Could not get a GPS fix. Try again outdoors.", Toast.LENGTH_LONG).show()
            } else {
                onDone(r.point.lat, r.point.lng)
                Toast.makeText(ctx, "$label captured from ${r.samplesUsed} fixes (±${r.point.accuracyM.toInt()} m)",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineSmall)
        capturing?.let {
            Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) }
        }

        // ---------------- Working hours ----------------
        Section("Working hours") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickTime(startMin) { startMin = it } }, Modifier.weight(1f)) {
                    Text("From ${StatusResolver.fmt(startMin)}")
                }
                OutlinedButton({ pickTime(endMin) { endMin = it } }, Modifier.weight(1f)) {
                    Text("To ${StatusResolver.fmt(endMin)}")
                }
            }
            if (endMin < startMin) Text("Night shift: ends next day.", style = MaterialTheme.typography.bodySmall)
            Text("Working days")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d in days,
                        onClick = { if (d in days) days.remove(d) else days.add(d) },
                        label = { Text(d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }

        // ---------------- Lunch ----------------
        Section("Lunch break") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickTime(lunchStart) { lunchStart = it } }, Modifier.weight(1f)) {
                    Text("From ${StatusResolver.fmt(lunchStart)}")
                }
                OutlinedButton({ pickTime(lunchEnd) { lunchEnd = it } }, Modifier.weight(1f)) {
                    Text("To ${StatusResolver.fmt(lunchEnd)}")
                }
            }
            NumberField("Maximum lunch duration (minutes)", maxLunch) { maxLunch = it }
            SwitchRow("Allow declaring lunch only inside this window", restrictLunch) { restrictLunch = it }
        }

        // ---------------- Office ----------------
        Section("Office location") {
            OutlinedTextField(officeName, { officeName = it }, label = { Text("Office name") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { capture("office centre") { a, b -> lat = "%.7f".format(java.util.Locale.US, a); lng = "%.7f".format(java.util.Locale.US, b) } },
                enabled = capturing == null, modifier = Modifier.fillMaxWidth()
            ) { Text("Use my current location as office centre") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(lng, { lng = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
            NumberField("Premises radius (metres)", radius) { radius = it }
            Text(
                "Tip: radius should cover the whole compound up to the boundary wall. " +
                    "You are counted inside only when GPS is certain you are within it.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ---------------- Gates ----------------
        Section("Gates") {
            if (gates.isEmpty()) Text("No gates added. Stand at each gate and add it.", style = MaterialTheme.typography.bodySmall)
            gates.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name)
                        Text("%.6f, %.6f · %d m".format(g.lat, g.lng, g.radiusM.toInt()), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { gates.remove(g) }) { Icon(Icons.Filled.Delete, "Remove gate") }
                }
            }
            HorizontalDivider()
            OutlinedTextField(newGateName, { newGateName = it }, label = { Text("Gate name") }, modifier = Modifier.fillMaxWidth())
            NumberField("Gate radius (metres)", newGateRadius) { newGateRadius = it }
            Button(
                onClick = {
                    val name = newGateName.ifBlank { "Gate ${gates.size + 1}" }
                    val r = newGateRadius.toDoubleOrNull() ?: 25.0
                    capture(name) { a, b ->
                        gates.add(Gate(UUID.randomUUID().toString(), name, a, b, r))
                        newGateName = "Gate ${gates.size + 1}"
                    }
                },
                enabled = capturing == null, modifier = Modifier.fillMaxWidth()
            ) { Text("Add gate at my current location") }
        }

        // ---------------- Accuracy ----------------
        Section("Accuracy") {
            NumberField("Ignore GPS fixes worse than (metres)", maxAcc) { maxAcc = it }
            NumberField("Consecutive fixes required to change in/out", confirm) { confirm = it }
            SwitchRow("Reject fake / mock locations", rejectMock) { rejectMock = it }
        }

        Section("App updates") { UpdateControls() }

        Button(
            onClick = {
                val la = lat.toDoubleOrNull(); val ln = lng.toDoubleOrNull(); val r = radius.toDoubleOrNull()
                val err = when {
                    la == null || ln == null || la !in -90.0..90.0 || ln !in -180.0..180.0 -> "Enter a valid office latitude/longitude"
                    r == null || r < 20 -> "Radius must be at least 20 m"
                    startMin == endMin -> "Working start and end time cannot be the same"
                    days.isEmpty() -> "Select at least one working day"
                    else -> null
                }
                if (err != null) {
                    Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); return@Button
                }
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
                } else {
                    AttendanceController.refresh()
                }
                Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save settings") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
