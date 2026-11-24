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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.service.TrackingService
import kotlinx.coroutines.delay

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife")
@Composable
fun DashboardScreen(modifier: Modifier, onOpenSetup: () -> Unit) {
    val ctx = LocalContext.current
    val st by AttendanceController.state.collectAsStateWithLifecycle()
    val settings by AttendanceController.settings.settings.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }

    val fine = remember(permTick) { granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val background = remember(permTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val batteryOk = remember(permTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
    }
    val fgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
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

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- status ----------
        val color = statusColor(st.status)
        Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("CURRENT STATUS", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Text(st.status.label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val sub = when {
                    !st.trackingOn -> "Tracking is OFF"
                    settings.office == null -> "Office location not set up"
                    else -> settings.office!!.name
                }
                Text(sub, color = Color.White.copy(alpha = 0.9f))
                Text(fmtTimeSec(now), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            }
        }

        // ---------- setup warnings ----------
        if (!fine) Warning("Precise location permission is needed.", "Grant") { requestForeground() }
        else if (!background) Warning(
            "Allow location \"All the time\" so attendance keeps working when the app is closed.", "Allow"
        ) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        if (!batteryOk) Warning("Disable battery optimisation so Android doesn't pause tracking.", "Fix") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")))
            }
            permTick++
        }
        if (settings.office == null) Warning("Set your office location, gates and working hours.", "Setup") { onOpenSetup() }

        // ---------- tracking ----------
        if (st.trackingOn) {
            OutlinedButton(onClick = { TrackingService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop tracking")
            }
        } else {
            Button(
                onClick = {
                    if (!fine) requestForeground()
                    else if (settings.office == null) onOpenSetup()
                    else TrackingService.start(ctx)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start tracking") }
        }

        // ---------- lunch ----------
        val sc = settings.schedule
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Lunch break", style = MaterialTheme.typography.titleMedium)
                Text("Window ${StatusResolver.fmt(sc.lunchStartMin)} – ${StatusResolver.fmt(sc.lunchEndMin)} · max ${sc.maxLunchMinutes} min")
                if (st.lunch.active) {
                    val secs = (now - st.lunch.startedAtMs) / 1000
                    val over = secs / 60 >= sc.maxLunchMinutes
                    Text(
                        "On lunch since ${fmtTime(st.lunch.startedAtMs)} · ${fmtDuration(secs)}",
                        color = if (over) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(onClick = { AttendanceController.endLunch() }, modifier = Modifier.fillMaxWidth()) {
                        Text("End lunch break")
                    }
                } else {
                    Button(
                        onClick = {
                            AttendanceController.startLunch()?.let {
                                Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Declare lunch break") }
                }
            }
        }

        // ---------- today ----------
        val t = st.today.secondsByStatus
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                Text("Working hours ${StatusResolver.fmt(sc.startMin)} – ${StatusResolver.fmt(sc.endMin)}")
                Row2("First entry", fmtTime(st.today.firstInMs))
                Row2("Last exit", fmtTime(st.today.lastOutMs))
                Row2("In office", fmtDuration(t[WorkStatus.IN_OFFICE.name] ?: 0))
                Row2("Outside office (work hours)", fmtDuration(t[WorkStatus.OUTSIDE_OFFICE.name] ?: 0))
                Row2("Lunch", fmtDuration((t[WorkStatus.ON_LUNCH.name] ?: 0) + (t[WorkStatus.LUNCH_EXCEEDED.name] ?: 0)))
            }
        }

        // ---------- location quality ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Location", style = MaterialTheme.typography.titleMedium)
                Row2("Presence", when (st.presence) {
                    Presence.INSIDE -> "Inside premises"
                    Presence.OUTSIDE -> "Outside premises"
                    Presence.UNKNOWN -> "Not determined yet"
                })
                val office = settings.office
                Row2("Distance to office", st.distanceM?.let { "${it.toInt()} m (radius ${office?.radiusM?.toInt() ?: 0} m)" } ?: "—")
                Row2("GPS accuracy", st.lastFix?.let { if (it.accuracyM < 1e6) "±${it.accuracyM.toInt()} m" else "—" } ?: "—")
                Row2("Last reliable fix", fmtTimeSec(st.lastAcceptedMs))
                Row2("At gate", st.nearGate ?: "—")
                Row2("Last gate used", st.lastGateUsed ?: "—")
                st.lastReject?.let { Text("Ignored last fix: $it", color = Color(0xFFEF6C00), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Warning(text: String, action: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onClick) { Text(action) }
        }
    }
}
