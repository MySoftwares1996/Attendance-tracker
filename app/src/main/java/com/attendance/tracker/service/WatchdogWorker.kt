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
