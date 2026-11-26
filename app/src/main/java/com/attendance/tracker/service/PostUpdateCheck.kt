package com.attendance.tracker.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.attendance.tracker.AttendanceApp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.BuildConfig
import com.attendance.tracker.R
import com.attendance.tracker.permissions.PermissionsManager
import com.attendance.tracker.ui.MainActivity

/**
 * After every app update: re-arm everything auto-punch needs (tracking, geofences, watchdog,
 * backups) and confirm it's healthy. Updates are when tracking most often stops silently.
 * Runs once per version, from the update broadcast or the next app start.
 */
object PostUpdateCheck {
    private const val PREFS = "post_update"
    private const val ID = 4150

    fun run(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getInt("checked", 0)
        if (last == BuildConfig.VERSION_CODE) return
        p.edit().putInt("checked", BuildConfig.VERSION_CODE).apply()
        if (last == 0) return // first install: the setup wizard takes care of it

        val s = AttendanceController.settings
        WatchdogWorker.schedule(ctx)
        runCatching { com.attendance.tracker.backup.BackupManager.schedule(ctx) }
        val blockers = PermissionsManager.trackingBlockers(ctx)
        if (s.trackingEnabled && blockers.isEmpty()) {
            runCatching { GeofenceManager.register(ctx) }
            runCatching { TrackingService.start(ctx) }
        }
        val ok = !s.trackingEnabled || blockers.isEmpty()
        val notes = com.attendance.tracker.update.UpdateManager.notesFor(ctx, BuildConfig.VERSION_CODE)
        if (notes.isNotBlank()) com.attendance.tracker.update.UpdateManager.setWhatsNewPending(ctx, BuildConfig.VERSION_CODE)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(ctx, 86, Intent(ctx, MainActivity::class.java).putExtra("openSetupPage", "check"), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(ID, NotificationCompat.Builder(ctx, if (ok) AttendanceApp.CHANNEL_PUNCH_SILENT else AttendanceApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (ok) "Updated to v${BuildConfig.VERSION_NAME} \u00b7 auto-punch active \u2713"
                    else "After the update: ${blockers.first()}")
                .setContentText(if (ok) "Tracking, gates and backups checked." else "Tap to fix it so punches aren't missed.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    (if (ok) "Tracking, gates and backups checked." else "Tap to fix it so punches aren't missed.") +
                        if (notes.isNotBlank()) "\n\nWhat's new:\n$notes" else ""))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
