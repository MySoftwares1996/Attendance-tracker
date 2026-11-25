package com.attendance.tracker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.permissions.PermissionsManager
import java.util.concurrent.TimeUnit

/** Runs every 15 minutes: restarts tracking if the phone stopped it and re-arms geofences. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = AttendanceController.settings
        if (s.trackingEnabled && PermissionsManager.trackingBlockers(ctx).isEmpty()) {
            GeofenceManager.register(ctx)
            if (!TrackingService.running) runCatching { TrackingService.start(ctx) }
            // Battery-killer guard: no heartbeat for 20+ min during work hours → tell the user how to fix it.
            val hb = s.lastHeartbeatMs
            val now = System.currentTimeMillis()
            if (hb > 0 && now - hb > 20 * 60_000 && AttendanceController.highAccuracyWanted(now)) {
                val hour = java.time.LocalDateTime.now().let { "${it.toLocalDate()}_${it.hour}" }
                if (s.firstTime("frozen_$hour")) AttendanceController.notifyTrackingFrozen((now - hb) / 60_000)
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "tracking-watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
