package com.attendance.tracker.service

import com.attendance.tracker.isRestDay
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.attendance.tracker.AttendanceApp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.LocationSample
import com.attendance.tracker.data.EventType
import com.attendance.tracker.permissions.PermissionsManager
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
import kotlin.math.abs

/**
 * Foreground service: the primary punch detector. GPS speed adapts to how close
 * you are to the office boundary so crossings are caught within seconds:
 *  - NEAR  (within ~200 m of the boundary, or a change is being confirmed): every 5 s
 *  - NORMAL (around the office):                                            every 15 s
 *  - FAR   (> 3 km away):                                                   every 60 s
 *  - LOW_POWER (outside the working day incl. overtime):                    every 5 min
 */
class TrackingService : LifecycleService() {

    private enum class Mode(val intervalMs: Long, val minMs: Long, val priority: Int) {
        NEAR(5_000, 2_000, Priority.PRIORITY_HIGH_ACCURACY),
        NORMAL(15_000, 5_000, Priority.PRIORITY_HIGH_ACCURACY),
        FAR(60_000, 20_000, Priority.PRIORITY_HIGH_ACCURACY),
        LOW_POWER(5 * 60_000, 60_000, Priority.PRIORITY_BALANCED_POWER_ACCURACY),
        /** Leave / holiday / weekend: almost asleep; the office geofence still wakes tracking. */
        SLEEP(20 * 60_000, 5 * 60_000, Priority.PRIORITY_LOW_POWER),
    }

    private lateinit var fused: FusedLocationProviderClient
    private var mode: Mode? = null
    private var ticker: Job? = null
    private var burstUntilMs = 0L
    private var lastBlockers: List<String> = emptyList()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { AttendanceController.onLocation(it.toSample()) }
            applyMode(force = false)
            updateNotification()
        }
    }

    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = watchdog()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        fused = LocationServices.getFusedLocationProviderClient(this)
        ContextCompat.registerReceiver(
            this, providerReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val s = AttendanceController.settings
        if (!s.trackingEnabled) {
            s.trackingEnabled = true
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Tracking started")
        }
        AttendanceController.recordHeartbeat(System.currentTimeMillis(), resumed = true)
        if (intent?.getBooleanExtra(EXTRA_BURST, false) == true) burstUntilMs = System.currentTimeMillis() + BURST_MS
        GeofenceManager.register(this)
        MotionWatcher.register(this)
        WatchdogWorker.schedule(this)
        applyMode(force = true)
        if (ticker?.isActive != true) {
            ticker = lifecycleScope.launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    AttendanceController.recordHeartbeat(now, resumed = false)
                    AttendanceController.tick(now)
                    watchdog()
                    applyMode(force = false)
                    updateNotification()
                    runCatching { UpdateManager.checkIfDue(applicationContext) }
                    delay(TICK_MS)
                }
            }
        }
        return START_STICKY
    }


    private fun desiredMode(): Mode {
        val now = System.currentTimeMillis()
        if (now < burstUntilMs) return Mode.NEAR
        // Day complete and you've left: rest until tomorrow (the office geofence wakes it if you go back).
        if (AttendanceController.state.value.status == com.attendance.tracker.core.WorkStatus.DAY_COMPLETE &&
            (AttendanceController.state.value.edgeDistanceM ?: 9_999.0) > 300.0) return Mode.SLEEP
        // Rest day, and not at / near the office: sleep to save battery.
        if (AttendanceController.isRestDay(now) && AttendanceController.state.value.presence != com.attendance.tracker.core.Presence.INSIDE &&
            (AttendanceController.state.value.edgeDistanceM ?: 9_999.0) > 300.0) return Mode.SLEEP
        if (!AttendanceController.highAccuracyWanted(now)) return Mode.LOW_POWER
        if (AttendanceController.pendingChange) return Mode.NEAR
        // At or near a gate: poll every 5 s so the crossing is caught at once.
        if (AttendanceController.state.value.nearGate != null) return Mode.NEAR
        val edge = AttendanceController.state.value.edgeDistanceM ?: return Mode.NORMAL
        val d = AttendanceController.state.value.distanceM ?: return Mode.NORMAL
        val fixAge = now - (AttendanceController.state.value.lastAcceptedMs ?: 0L)
        if (fixAge > 3 * 60_000L) return Mode.NORMAL
        return when {
            abs(edge) < NEAR_BAND_M -> Mode.NEAR
            d > FAR_M -> Mode.FAR
            else -> Mode.NORMAL
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyMode(force: Boolean) {
        val m = desiredMode()
        if (!force && m == mode) return
        mode = m
        fused.removeLocationUpdates(callback)
        val b = LocationRequest.Builder(m.priority, m.intervalMs).setMinUpdateIntervalMillis(m.minMs)
        if (m != Mode.LOW_POWER && m != Mode.SLEEP) b.setGranularity(Granularity.GRANULARITY_FINE).setWaitForAccurateLocation(true)
        try {
            fused.requestLocationUpdates(b.build(), callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            watchdog()
        }
    }

    /** Alerts immediately if location access is switched off while tracking. */
    private fun watchdog() {
        val blockers = PermissionsManager.trackingBlockers(this)
        if (blockers == lastBlockers) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (blockers.isNotEmpty()) {
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Tracking blocked: ${blockers.first()}")
            val fix = PendingIntent.getActivity(
                this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                nm.notify(
                    ALERT_ID,
                    NotificationCompat.Builder(this, AttendanceApp.CHANNEL_ALERTS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Attendance can't be tracked")
                        .setContentText("${blockers.first()}. Tap to fix.")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(blockers.joinToString("\n") + "\nTap to fix."))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(fix)
                        .setOngoing(true)
                        .build()
                )
            }
        } else if (lastBlockers.isNotEmpty()) {
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Location access restored")
            nm.cancel(ALERT_ID)
            GeofenceManager.register(this)
            applyMode(force = true)
        }
        lastBlockers = blockers
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        ticker?.cancel()
        AttendanceController.pauseAccounting()
        if (AttendanceController.settings.trackingEnabled) {
            AttendanceController.settings.trackingEnabled = false
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Tracking paused by you")
        }
        GeofenceManager.unregister(this)
        AttendanceController.refresh()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** App swiped away from recents: schedule an immediate restart. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!AttendanceController.settings.trackingEnabled) return
        val pi = PendingIntent.getForegroundService(
            this, 9, Intent(this, TrackingService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3_000, pi) }
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(providerReceiver) }
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun buildNotification(): Notification = LiveNotification.build(this)

    private fun fmt(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val ALERT_ID = 2002
        private const val TICK_MS = 20_000L
        private const val BURST_MS = 3 * 60_000L
        private const val NEAR_BAND_M = 200.0
        private const val FAR_M = 3_000.0
        const val ACTION_STOP = "com.attendance.tracker.STOP"
        const val EXTRA_BURST = "burst"

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context, burst: Boolean = false) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, TrackingService::class.java).putExtra(EXTRA_BURST, burst)
            )
        }

        /** Re-draw the live notification (e.g. after the Live Updates setting changed). */
        fun refreshNotification(ctx: Context) {
            if (!running) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIF_ID, LiveNotification.build(ctx)) }
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
