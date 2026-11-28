package com.attendance.tracker.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.permissions.PermissionsManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Deep-sleep guard. Indoors GPS gives few readings, so nothing wakes the app and some phones
 * then freeze it for hours. An allow-while-idle alarm every few minutes during working hours
 * wakes the app, takes one fresh reading, keeps the heartbeat and restarts tracking if needed.
 */
object KeepAlive {
    private const val EVERY_MS = 5 * 60_000L

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 95, Intent(ctx, KeepAliveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        if (!AttendanceController.settings.trackingEnabled) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact but allowed in Doze (Android limits it to roughly every 9 minutes in deep sleep).
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY_MS, pending(ctx))
        }
    }

    fun cancel(ctx: Context) {
        runCatching { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx)) }
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val s = AttendanceController.settings
        if (!s.trackingEnabled) return
        KeepAlive.schedule(ctx) // next one
        val now = System.currentTimeMillis()
        if (!AttendanceController.highAccuracyWanted(now) || PermissionsManager.trackingBlockers(ctx).isNotEmpty()) return
        if (!TrackingService.running) runCatching { TrackingService.start(ctx) }
        AttendanceController.recordHeartbeat(now, resumed = false)
        // One fresh reading (up to 25 s) so detection never goes stale while the phone sleeps.
        val done = goAsync()
        val cts = CancellationTokenSource()
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(25_000).setMaxUpdateAgeMillis(30_000).build(), cts.token)
                .addOnSuccessListener { loc -> if (loc != null) runCatching { AttendanceController.onLocation(loc.toSample()) } }
                .addOnCompleteListener { runCatching { AttendanceController.tick() }; done.finish() }
        }.onFailure { done.finish() }
    }
}
