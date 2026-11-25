package com.attendance.tracker.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.attendance.tracker.R
import com.attendance.tracker.permissions.PermissionStatus
import com.attendance.tracker.permissions.PermissionsManager
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

private fun prefs(ctx: Context) = ctx.getSharedPreferences("perm", Context.MODE_PRIVATE)

/**
 * Guided, step-by-step permission setup. Android never lets an app grant itself
 * "Allow all the time", so this walks the user through each system prompt and
 * re-checks everything whenever they return to the app.
 */
@Composable
fun PermissionsScreen(status: PermissionStatus, onChanged: () -> Unit, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val c = Soft.colors

    fun openSafely(intent: Intent, fallback: Intent? = null) {
        runCatching { ctx.startActivity(intent) }.onFailure { fallback?.let { f -> runCatching { ctx.startActivity(f) } } }
    }
    fun deniedForever(p: String, key: String): Boolean =
        prefs(ctx).getBoolean(key, false) && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p)

    val settingsReturn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) settingsReturn.launch(PermissionsManager.appSettingsIntent(ctx))
        onChanged()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val resolveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onChanged() }

    fun askPrecise() {
        if (deniedForever(Manifest.permission.ACCESS_FINE_LOCATION, "askedFine") || status.approximateOnly && deniedForever(Manifest.permission.ACCESS_FINE_LOCATION, "askedFine")) {
            settingsReturn.launch(PermissionsManager.appSettingsIntent(ctx))
        } else {
            prefs(ctx).edit().putBoolean("askedFine", true).apply()
            fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    fun askBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (deniedForever(Manifest.permission.ACCESS_BACKGROUND_LOCATION, "askedBg")) {
            settingsReturn.launch(PermissionsManager.appSettingsIntent(ctx))
        } else {
            prefs(ctx).edit().putBoolean("askedBg", true).apply()
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    fun turnOnLocation() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build()
        LocationServices.getSettingsClient(ctx)
            .checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(req).setAlwaysShow(true).build())
            .addOnSuccessListener { onChanged() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    resolveLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                } else {
                    settingsReturn.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !deniedForever(Manifest.permission.POST_NOTIFICATIONS, "askedNotif")) {
            prefs(ctx).edit().putBoolean("askedNotif", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSafely(PermissionsManager.notificationSettingsIntent(ctx), PermissionsManager.appSettingsIntent(ctx))
        }
    }
    fun askBattery() = openSafely(PermissionsManager.batteryIntent(ctx), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    fun askUnused() = openSafely(PermissionsManager.unusedAppIntent(ctx), PermissionsManager.appSettingsIntent(ctx))
    fun askAutostart() {
        PermissionsManager.openAutostartSettings(ctx)
        PermissionsManager.setAutostartConfirmed(ctx)
        onChanged()
    }

    data class Step(val icon: Painter, val title: String, val why: String, val hint: String?, val done: Boolean, val required: Boolean, val action: () -> Unit)

    val bgHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        "On the next screen choose “Allow all the time”" else null
    val steps = listOf(
        Step(rememberVectorPainter(Icons.Filled.LocationOn), "Precise location",
            "Pinpoints you within metres of the office and gates.",
            if (status.approximateOnly) "Turn ON “Use precise location”" else null,
            status.preciseLocation, true, ::askPrecise),
        Step(painterResource(R.drawable.ic_target), "Allow all the time",
            "Keeps attendance accurate even when the app is closed.", bgHint,
            status.backgroundLocation, true, ::askBackground),
        Step(painterResource(R.drawable.ic_gate), "Location & high accuracy on",
            "Phone GPS plus Wi-Fi scanning for the most accurate position.", null,
            status.locationServicesOn, true, ::turnOnLocation),
        Step(painterResource(R.drawable.ic_shield), "No battery restrictions",
            "Stops Android from pausing tracking in the background.", "Tap “Allow” on the popup",
            status.batteryUnrestricted, true, ::askBattery),
        Step(rememberVectorPainter(Icons.Filled.Notifications), "Notifications",
            "Alerts when you leave during work hours or lunch runs over.", null,
            status.notifications, false, ::askNotifications),
        Step(painterResource(R.drawable.ic_clock), "Keep permissions",
            "Prevents Android from removing access if the app isn't opened for a while.",
            "Turn OFF “Pause app activity if unused”",
            status.keepPermissionsWhenUnused, false, ::askUnused),
    ) + if (status.autostartNeeded) listOf(
        Step(painterResource(R.drawable.ic_system_update), "Allow Autostart",
            "Your phone (${android.os.Build.MANUFACTURER}) blocks background apps unless Autostart is on.",
            "Find Attendance Tracker and switch Autostart ON",
            status.autostartConfirmed, false, ::askAutostart),
    ) else emptyList()
    val nextIndex = steps.indexOfFirst { !it.done && (it.required || steps.filter { s -> s.required }.all { s -> s.done }) }
    // Background location can only be requested after precise location is granted.
    val bgBlocked = !status.preciseLocation

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    status.doneCount.toFloat() / status.totalSteps, c.accent, c.accentSoft,
                    Modifier.size(64.dp), stroke = 7.dp,
                ) { Text("${status.doneCount}/${status.totalSteps}", style = MaterialTheme.typography.labelLarge, color = c.accent) }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Let's get you set up", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                    Text(
                        "Allow these once so your attendance is always accurate.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            steps.forEachIndexed { i, s ->
                val enabled = !(i == 1 && bgBlocked)
                StepCard(
                    number = i + 1, icon = s.icon, title = s.title, why = s.why,
                    hint = if (!s.done && i == nextIndex) s.hint else null,
                    done = s.done, required = s.required, highlight = i == nextIndex,
                    enabled = enabled, onClick = s.action,
                )
            }
            Spacer(Modifier.height(120.dp))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(20.dp)
        ) {
            AnimatedContent(status.requiredOk, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "cont") { ok ->
                if (ok) {
                    SoftButton(
                        if (status.allOk) "All set — continue" else "Continue",
                        onContinue, icon = rememberVectorPainter(Icons.Filled.Check),
                        colors = c.success.copy(alpha = 0.85f) to c.success,
                    )
                } else {
                    val next = steps.getOrNull(nextIndex)
                    SoftButton(next?.let { "Allow: ${it.title}" } ?: "Continue", { next?.action?.invoke() })
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int, icon: Painter, title: String, why: String, hint: String?,
    done: Boolean, required: Boolean, highlight: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val c = Soft.colors
    val border = animatedColor(if (highlight) c.accent else Color.Transparent, "stepBorder")
    val bubbleBg = animatedColor(if (done) c.successSoft else c.accentSoft, "stepBubble")
    val bubbleFg = animatedColor(if (done) c.success else c.accent, "stepBubbleFg")
    SoftCard(
        modifier = Modifier.border(2.dp, border, Soft.cardShape),
        padding = 16.dp,
        onClick = if (!done && enabled) onClick else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(bubbleBg), contentAlignment = Alignment.Center) {
                AnimatedContent(done, label = "stepIcon") { d ->
                    Icon(if (d) rememberVectorPainter(Icons.Filled.Check) else icon, null, tint = bubbleFg, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$number. $title", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    if (!required) {
                        Spacer(Modifier.width(6.dp))
                        Text("recommended", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    }
                }
                Text(why, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Spacer(Modifier.width(10.dp))
            if (done) Pill("Done", c.success, c.successSoft)
            else Pill(if (enabled) "Allow" else "After 1", if (enabled) Color.White else c.textSecondary,
                if (enabled) c.accent else c.cardMuted)
        }
        if (hint != null) {
            Text(
                hint,
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(Soft.tileShape).background(c.accentSoft).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = c.accent,
            )
        }
    }
}
