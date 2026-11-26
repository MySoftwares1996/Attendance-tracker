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

// Setup pages: work hours, lunch, holidays and home (commute).

// ───────────────────────────── step 3: hours ─────────────────────────────

@Composable
internal fun HoursStep(s: AppSettings) {
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
internal fun LunchStep(s: AppSettings) {
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
internal fun HolidaysStep() {
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
    fun changed() { tick++; AttendanceController.refresh(); com.attendance.tracker.service.CalendarSync.syncSoon(ctx) }

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
        val holFile = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { u ->
            if (u != null) runCatching { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } }.onSuccess { txt ->
                // A text / CSV list, or a saved company page: tags removed, one holiday per line.
                val list = com.attendance.tracker.core.Insights.parseHolidays(com.attendance.tracker.core.CompanyReport.plain(txt)
                    .replace(Regex("[\\t,;]+"), " ").lines().joinToString("\n") { it.trim() })
                if (list.isEmpty()) Toast.makeText(ctx, "No dates found in that file", Toast.LENGTH_LONG).show()
                else {
                    st.customHolidays = st.customHolidays + list.associate { (d, n) -> d.toString() to n.ifBlank { "Holiday" } }
                    changed(); Toast.makeText(ctx, "${list.size} holidays added", Toast.LENGTH_SHORT).show()
                }
            }
        }
        SoftGhostButton("Import from a file (text, CSV or saved page)", { holFile.launch(arrayOf("text/*", "application/*", "*/*")) })
        Spacer(Modifier.height(8.dp))
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
internal fun HomeStep() {
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
