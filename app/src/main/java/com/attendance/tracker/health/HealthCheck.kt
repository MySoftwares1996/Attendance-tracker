package com.attendance.tracker.health

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.BuildConfig
import com.attendance.tracker.core.GeoMath
import com.attendance.tracker.data.EventType
import com.attendance.tracker.permissions.PermissionsManager
import com.attendance.tracker.security.TrustChecks
import com.attendance.tracker.service.TrackingService
import java.io.File
import java.time.Instant
import java.time.ZoneId

enum class Level { OK, WARN, FAIL, TIP }

/** What tapping "Fix" does. */
enum class Fix { APP_SETTINGS, BACKGROUND, LOCATION_ON, BATTERY, NOTIFICATIONS, UNUSED, AUTOSTART, OFFICE, GATES, DATE_SETTINGS, START_TRACKING }

data class Check(val title: String, val detail: String, val level: Level, val fix: Fix? = null)

data class HealthReport(val score: Int, val checks: List<Check>) {
    val problems: Int get() = checks.count { it.level == Level.FAIL || it.level == Level.WARN }
}

/** Checks everything automatic punching depends on, and scores it out of 100. */
object HealthCheck {

    suspend fun run(ctx: Context): HealthReport {
        val p = PermissionsManager.status(ctx)
        val s = AttendanceController.settings
        val office = s.settings.value.office
        val st = AttendanceController.state.value
        val now = System.currentTimeMillis()
        val c = mutableListOf<Check>()

        c += if (p.preciseLocation) Check("Precise location", "Allowed", Level.OK)
        else Check("Precise location", "Not allowed — punches can't be recorded", Level.FAIL, Fix.APP_SETTINGS)
        c += if (p.backgroundLocation) Check("Location “All the time”", "Allowed", Level.OK)
        else Check("Location “All the time”", "Needed so punches work with the app closed", Level.FAIL, Fix.BACKGROUND)
        c += if (p.locationServicesOn) Check("Phone location (GPS)", "On", Level.OK)
        else Check("Phone location (GPS)", "Turned off", Level.FAIL, Fix.LOCATION_ON)
        c += if (p.batteryUnrestricted) Check("Battery", "No restrictions", Level.OK)
        else Check("Battery", "Android may pause tracking", Level.FAIL, Fix.BATTERY)
        if (p.autostartNeeded) c += if (p.autostartConfirmed) Check("Autostart (${Build.MANUFACTURER})", "Opened and set", Level.OK)
        else Check("Autostart (${Build.MANUFACTURER})", "Your phone stops background apps unless Autostart is on", Level.FAIL, Fix.AUTOSTART)
        c += if (p.notifications) Check("Notifications", "On", Level.OK)
        else Check("Notifications", "You won't hear punch chimes or alerts", Level.WARN, Fix.NOTIFICATIONS)
        com.attendance.tracker.service.LiveNotification.liveUpdatesAllowed(ctx)?.let { ok ->
            c += if (ok || !s.liveUpdates) Check("Live Updates", if (s.liveUpdates) "Shown in the status bar / Live Alerts" else "Turned off in the app", Level.OK)
            else Check("Live Updates", "Allow \u201cLive updates\u201d for this app to see logout countdown in the status bar", Level.TIP, Fix.NOTIFICATIONS)
        }
        c += if (p.keepPermissionsWhenUnused) Check("Keep permissions", "Android won't remove access", Level.OK)
        else Check("Keep permissions", "Android may remove access if the app isn't opened for months", Level.WARN, Fix.UNUSED)

        c += when {
            !s.trackingEnabled -> Check("Auto-punch", "Paused — nothing is being recorded", Level.FAIL, Fix.START_TRACKING)
            !TrackingService.running -> Check("Auto-punch", "Should be on but isn't running right now", Level.WARN, Fix.START_TRACKING)
            else -> Check("Auto-punch", "Running", Level.OK)
        }

        // Office & gates
        c += when {
            office == null -> Check("Office area", "Not set up", Level.FAIL, Fix.OFFICE)
            !office.hasPolygon -> Check("Office area", "Only a circle — draw the exact compound for accuracy", Level.WARN, Fix.OFFICE)
            else -> {
                val area = GeoMath.areaM2(office.polygon)
                when {
                    area < 150 -> Check("Office area", "Very small (${area.toInt()} m²) — include the whole compound", Level.WARN, Fix.OFFICE)
                    area > 2_000_000 -> Check("Office area", "Very large — punches may be late", Level.WARN, Fix.OFFICE)
                    else -> Check("Office area", "Drawn · ${office.polygon.size} corners", Level.OK)
                }
            }
        }
        if (office != null) {
            if (office.gates.isEmpty()) c += Check("Gates", "None marked — IN/OUT times are less precise", Level.WARN, Fix.GATES)
            else {
                val bad = office.gates.mapNotNull { g ->
                    val e = office.edgeDistanceM(g.lat, g.lng)
                    when {
                        e < -40 -> "${g.name} is ${(-e).toInt()} m inside the area"
                        e > 60 -> "${g.name} is ${e.toInt()} m outside the area"
                        else -> null
                    }
                }
                c += if (bad.isEmpty()) Check("Gates", "${office.gates.size} on the boundary", Level.OK)
                else Check("Gates", bad.joinToString("; ") + " — gates should sit on the boundary", Level.WARN, Fix.GATES)
            }
        }

        // GPS quality right now
        val fix = st.lastFix
        val maxAcc = s.settings.value.accuracy.maxAccuracyM
        c += when {
            fix == null || now - fix.timeMs > 10 * 60_000 -> Check("GPS signal", "No recent reading", Level.TIP)
            fix.accuracyM <= 20 -> Check("GPS signal", "±${fix.accuracyM.toInt()} m · excellent", Level.OK)
            fix.accuracyM <= maxAcc -> Check("GPS signal", "±${fix.accuracyM.toInt()} m · usable", Level.OK)
            else -> Check("GPS signal", "±${fix.accuracyM.toInt()} m · too weak here (readings are skipped)", Level.WARN)
        }

        // Tracking gaps in the last 7 days
        val gapEvents = AttendanceController.db.events(now - 7 * 86_400_000L, now)
            .filter { it.type == EventType.TRACKING && it.detail.startsWith("No tracking from") }
        val gaps = gapEvents.size
        // When each gap happened, e.g. "Thu 27 Nov 18:40–19:05", so you can tell what caused it.
        val whenTxt = gapEvents.takeLast(2).joinToString(", ") { e ->
            val d = java.time.Instant.ofEpochMilli(e.timeMs).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
            d + " " + e.detail.removePrefix("No tracking from ").substringBefore(" \u2014").replace(" to ", "\u2013")
        }
        c += when (gaps) {
            0 -> Check("Last 7 days", "No tracking gaps", Level.OK)
            in 1..2 -> Check("Last 7 days", "$gaps tracking gap${if (gaps > 1) "s" else ""} ($whenTxt) — the phone paused the app, often battery saver at low charge", Level.WARN, Fix.BATTERY)
            else -> Check("Last 7 days", "$gaps tracking gaps — battery or autostart settings are stopping the app", Level.FAIL, if (p.autostartNeeded) Fix.AUTOSTART else Fix.BATTERY)
        }

        // Trust
        c += if (TrustChecks.autoTimeOn(ctx)) Check("Phone time", "Set automatically", Level.OK)
        else Check("Phone time", "Set by hand — punches are marked Not verified", Level.WARN, Fix.DATE_SETTINGS)
        if (now - TrustChecks.lastMockMs(ctx) < 24 * 3_600_000L) c += Check("Fake GPS", "A mock-location app was used in the last day", Level.FAIL)

        val scored = c.filter { it.level != Level.TIP }
        val points = scored.sumOf { when (it.level) { Level.OK -> 2; Level.WARN -> 1; else -> 0 }.toInt() }
        val score = if (scored.isEmpty()) 100 else (points * 100) / (scored.size * 2)
        return HealthReport(score, c)
    }

    /** A plain-text diagnostics file the user can send for support. */
    suspend fun shareDiagnostics(ctx: Context) {
        val r = run(ctx)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("Attendance Tracker diagnostics")
        sb.appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Created ${Instant.ofEpochMilli(now).atZone(zone)}")
        sb.appendLine("Health score: ${r.score}/100").appendLine()
        r.checks.forEach { sb.appendLine("[${it.level}] ${it.title}: ${it.detail}") }
        val s = AttendanceController.settings
        val o = s.settings.value.office
        sb.appendLine().appendLine("Office: ${o?.name ?: "-"} · polygon ${o?.polygon?.size ?: 0} pts · gates ${o?.gates?.size ?: 0} · radius ${o?.radiusM?.toInt() ?: 0} m")
        val sc = s.settings.value.schedule
        sb.appendLine("Hours: day starts at arrival, ${sc.netMinutes} min net/day + ${sc.maxLunchMinutes} min lunch (always deducted)")
        sb.appendLine("Accuracy: max ±${s.settings.value.accuracy.maxAccuracyM.toInt()} m, ${s.settings.value.accuracy.confirmSamples} readings to confirm")
        sb.appendLine().appendLine("Events, last 7 days:")
        AttendanceController.db.events(now - 7 * 86_400_000L, now).filter { it.type != EventType.STATUS }.forEach {
            sb.appendLine("${Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDateTime()}  ${it.type}  ${it.detail}" +
                (it.accuracyM?.let { a -> "  ±${a.toInt()}m" } ?: ""))
        }
        val f = File(File(ctx.cacheDir, "reports").apply { mkdirs() }, "attendance-diagnostics.txt")
        f.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Attendance Tracker diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share diagnostics",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
