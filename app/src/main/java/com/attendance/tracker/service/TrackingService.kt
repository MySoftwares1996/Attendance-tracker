package com.attendance.tracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.attendance.tracker.AttendanceApp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.LocationSample
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.data.EventType
import com.attendance.tracker.ui.MainActivity
import com.attendance.tracker.update.UpdateManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Foreground service that keeps high-accuracy GPS running around working
 * hours (and a low-power mode outside them) and feeds every fix into
 * [AttendanceController].
 */
class TrackingService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private var highAccuracyMode: Boolean? = null
    private var ticker: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { AttendanceController.onLocation(it.toSample()) }
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (!AttendanceController.hasFineLocation(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AttendanceController.settings.trackingEnabled) {
            AttendanceController.settings.trackingEnabled = true
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Tracking started")
        }
        applyLocationMode(force = true)
        if (ticker?.isActive != true) {
            ticker = lifecycleScope.launch {
                while (isActive) {
                    AttendanceController.tick()
                    applyLocationMode(force = false)
                    updateNotification()
                    runCatching { UpdateManager.checkIfDue(applicationContext) }
                    delay(TICK_MS)
                }
            }
        }
        return START_STICKY
    }

    /** High accuracy from 30 min before work until 30 min after; low power otherwise. */
    private fun wantHighAccuracy(): Boolean {
        val s = AttendanceController.settings.settings.value.schedule
        val now = LocalDateTime.now()
        return StatusResolver.isWorkingTime(now.plusMinutes(30), s) ||
            StatusResolver.isWorkingTime(now, s) ||
            StatusResolver.isWorkingTime(now.minusMinutes(30), s)
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationMode(force: Boolean) {
        val high = wantHighAccuracy()
        if (!force && high == highAccuracyMode) return
        highAccuracyMode = high
        fused.removeLocationUpdates(callback)
        val req = if (high) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(true)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60_000L)
                .setMinUpdateIntervalMillis(60_000L)
                .build()
        }
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopTracking()
        }
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        ticker?.cancel()
        AttendanceController.pauseAccounting()
        if (AttendanceController.settings.trackingEnabled) {
            AttendanceController.settings.trackingEnabled = false
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Tracking stopped")
        }
        AttendanceController.refresh()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val st = AttendanceController.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val accuracy = st.lastFix?.let { " · GPS ±${it.accuracyM.toInt()} m" } ?: ""
        return NotificationCompat.Builder(this, AttendanceApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(st.status.label)
            .setContentText("Attendance tracking active$accuracy")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 20_000L
        const val ACTION_STOP = "com.attendance.tracker.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}

fun Location.toSample(): LocationSample {
    val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock
    else @Suppress("DEPRECATION") isFromMockProvider
    return LocationSample(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        timeMs = time,
        isMock = mock,
    )
}
