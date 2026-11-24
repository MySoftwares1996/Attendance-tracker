package com.attendance.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.attendance.tracker.core.LocationSample
import com.attendance.tracker.core.LunchState
import com.attendance.tracker.core.Presence
import com.attendance.tracker.core.PresenceEngine
import com.attendance.tracker.core.StatusResolver
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.data.AttendanceDb
import com.attendance.tracker.data.EventType
import com.attendance.tracker.data.SettingsRepository
import com.attendance.tracker.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class DaySummary(
    val firstInMs: Long? = null,
    val lastOutMs: Long? = null,
    val secondsByStatus: Map<String, Long> = emptyMap(),
)

data class LiveState(
    val status: WorkStatus = WorkStatus.UNKNOWN,
    val presence: Presence = Presence.UNKNOWN,
    val trackingOn: Boolean = false,
    val lastFix: LocationSample? = null,
    val lastAcceptedMs: Long? = null,
    val distanceM: Double? = null,
    val lastReject: String? = null,
    val nearGate: String? = null,
    val lastGateUsed: String? = null,
    val lunch: LunchState = LunchState(),
    val today: DaySummary = DaySummary(),
)

/**
 * Central place that combines location → presence → work status, writes the
 * attendance log and accumulates daily durations. Used by both the tracking
 * service and the UI.
 */
@SuppressLint("StaticFieldLeak")
object AttendanceController {
    private lateinit var app: Context
    lateinit var settings: SettingsRepository; private set
    lateinit var db: AttendanceDb; private set

    private val engine = PresenceEngine()
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    private var lastTickMs = 0L
    private var lastStatus: WorkStatus? = null

    fun init(context: Context) {
        app = context.applicationContext
        settings = SettingsRepository(app)
        db = AttendanceDb(app)
        engine.reset(settings.presence)
        _state.value = LiveState(presence = engine.presence, lunch = settings.lunch)
        refresh()
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun dayKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toString()

    @Synchronized
    fun onLocation(sample: LocationSample, nowMs: Long = System.currentTimeMillis()) {
        val s = settings.settings.value
        val res = engine.process(sample, s.office, s.accuracy, nowMs)
        var st = _state.value.copy(lastFix = sample)
        when (res) {
            is PresenceEngine.Result.Rejected -> st = st.copy(lastReject = res.reason)
            is PresenceEngine.Result.Accepted -> {
                st = st.copy(
                    lastReject = null, lastAcceptedMs = sample.timeMs,
                    distanceM = res.distanceM, nearGate = res.nearGate?.name,
                    presence = engine.presence,
                )
                res.transition?.let { t ->
                    settings.presence = t.to
                    val gateTxt = t.gate?.let { " via ${it.name}" } ?: ""
                    when (t.to) {
                        Presence.INSIDE -> {
                            val first = if (t.from == Presence.UNKNOWN) " (first detection)" else ""
                            db.insertEvent(t.atMs, EventType.CHECK_IN, "Entered ${s.office?.name}$gateTxt$first",
                                sample.lat, sample.lng, sample.accuracyM)
                        }
                        Presence.OUTSIDE -> if (t.from == Presence.INSIDE) {
                            db.insertEvent(t.atMs, EventType.CHECK_OUT, "Left ${s.office?.name}$gateTxt",
                                sample.lat, sample.lng, sample.accuracyM)
                        }
                        Presence.UNKNOWN -> Unit
                    }
                    st = st.copy(lastGateUsed = t.gate?.name ?: st.lastGateUsed)
                }
            }
        }
        _state.value = st
        refresh(nowMs)
    }

    /** Called periodically by the tracking service; accumulates time per status. */
    @Synchronized
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        val prev = lastStatus
        if (lastTickMs > 0 && prev != null) {
            // Cap the gap so a killed/frozen service doesn't invent hours of time.
            val delta = ((nowMs - lastTickMs) / 1000).coerceIn(0, 5 * 60)
            db.addDuration(dayKey(lastTickMs), prev.name, delta)
        }
        lastTickMs = nowMs
        refresh(nowMs)
    }

    /** Stop accounting (tracking stopped). */
    @Synchronized
    fun pauseAccounting() {
        tick()
        lastTickMs = 0L
    }

    @Synchronized
    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        val s = settings.settings.value
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        var lunch = settings.lunch
        // Auto-close a lunch that is still open once working hours are over.
        if (lunch.active && !StatusResolver.isWorkingTime(now, s.schedule)) {
            db.insertEvent(nowMs, EventType.LUNCH_END, "Lunch auto-ended (working hours over)")
            lunch = LunchState(); settings.lunch = lunch
        }
        val status = StatusResolver.resolve(now, nowMs, s.schedule, engine.presence, lunch)
        val old = lastStatus
        if (old != null && old != status) {
            db.insertEvent(nowMs, EventType.STATUS, "${old.label} → ${status.label}")
            if (status == WorkStatus.OUTSIDE_OFFICE || status == WorkStatus.LUNCH_EXCEEDED) alert(status)
        }
        lastStatus = status
        _state.value = _state.value.copy(
            status = status, presence = engine.presence, lunch = lunch,
            trackingOn = settings.trackingEnabled, today = summary(now.toLocalDate()),
        )
    }

    fun summary(day: LocalDate): DaySummary {
        val from = day.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val ev = db.events(from, to)
        return DaySummary(
            firstInMs = ev.firstOrNull { it.type == EventType.CHECK_IN }?.timeMs,
            lastOutMs = ev.lastOrNull { it.type == EventType.CHECK_OUT }?.timeMs,
            secondsByStatus = db.durations(day.toString()),
        )
    }

    /** Declare lunch break. Returns an error message or null on success. */
    @Synchronized
    fun startLunch(nowMs: Long = System.currentTimeMillis()): String? {
        val s = settings.settings.value
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        StatusResolver.canStartLunch(now, s.schedule, settings.lunch)?.let { return it }
        tick(nowMs)
        settings.lunch = LunchState(true, nowMs)
        val where = when (engine.presence) {
            Presence.INSIDE -> "inside office"
            Presence.OUTSIDE -> "outside office"
            Presence.UNKNOWN -> "location unknown"
        }
        db.insertEvent(nowMs, EventType.LUNCH_START, "Lunch declared ($where)")
        refresh(nowMs)
        return null
    }

    @Synchronized
    fun endLunch(nowMs: Long = System.currentTimeMillis()) {
        val l = settings.lunch
        if (!l.active) return
        tick(nowMs)
        val mins = (nowMs - l.startedAtMs) / 60_000
        val max = settings.settings.value.schedule.maxLunchMinutes
        val over = if (mins > max) " — exceeded by ${mins - max} min" else ""
        db.insertEvent(nowMs, EventType.LUNCH_END, "Lunch ended after $mins min$over")
        settings.lunch = LunchState()
        refresh(nowMs)
    }

    /** Office/geofence changed: forget the old inside/outside decision. */
    fun onOfficeChanged() {
        engine.reset(Presence.UNKNOWN)
        settings.presence = Presence.UNKNOWN
        refresh()
    }

    fun hasFineLocation(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun alert(status: WorkStatus) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (status) {
            WorkStatus.OUTSIDE_OFFICE -> "You are outside the office during working hours."
            WorkStatus.LUNCH_EXCEEDED -> "Your lunch break has exceeded the allowed time."
            else -> status.label
        }
        runCatching {
            nm.notify(
                2001,
                NotificationCompat.Builder(app, AttendanceApp.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(status.label)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
