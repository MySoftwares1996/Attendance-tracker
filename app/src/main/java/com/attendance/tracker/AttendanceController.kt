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
import com.attendance.tracker.core.DayPlan
import com.attendance.tracker.core.DayPlanner
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
    /** Every IN / OUT punch of the day, in order. */
    val punches: List<com.attendance.tracker.data.AttendanceEvent> = emptyList(),
)

data class LiveState(
    val status: WorkStatus = WorkStatus.UNKNOWN,
    val presence: Presence = Presence.UNKNOWN,
    val trackingOn: Boolean = false,
    val lastFix: LocationSample? = null,
    val lastAcceptedMs: Long? = null,
    val distanceM: Double? = null,
    /** Metres from the office boundary: negative inside, positive outside. */
    val edgeDistanceM: Double? = null,
    val lastReject: String? = null,
    val nearGate: String? = null,
    val lastGateUsed: String? = null,
    val lunch: LunchState = LunchState(),
    val today: DaySummary = DaySummary(),
    val plan: DayPlan? = null,
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
                    distanceM = res.distanceM, edgeDistanceM = res.edgeDistanceM, nearGate = res.nearGate?.name,
                    presence = engine.presence,
                )
                res.transition?.let { t ->
                    settings.presence = t.to
                    val gateTxt = t.gate?.let { if (t.timedByGate) " via ${it.name} (gate time)" else " via ${it.name}" } ?: ""
                    val gapTxt = if (t.gapBeforeSec >= GAP_NOTE_SEC)
                        " · detected after ${t.gapBeforeSec / 60} min without location, time approximate" else ""
                    when (t.to) {
                        Presence.INSIDE -> {
                            val first = if (t.from == Presence.UNKNOWN) " (already inside when tracking started)" else ""
                            db.insertEvent(t.atMs, EventType.CHECK_IN, "Entered ${s.office?.name}$gateTxt$first$gapTxt",
                                sample.lat, sample.lng, sample.accuracyM)
                            punchAlert(true, t.atMs, t.gate?.name, t.gapBeforeSec >= GAP_NOTE_SEC)
                        }
                        Presence.OUTSIDE -> if (t.from == Presence.INSIDE) {
                            db.insertEvent(t.atMs, EventType.CHECK_OUT, "Left ${s.office?.name}$gateTxt$gapTxt",
                                sample.lat, sample.lng, sample.accuracyM)
                            punchAlert(false, t.atMs, t.gate?.name, t.gapBeforeSec >= GAP_NOTE_SEC)
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
        settings.lastHeartbeatMs = 0L
    }

    /** True while the engine is confirming a possible punch (service polls faster). */
    val pendingChange: Boolean get() = engine.hasPendingChange

    /**
     * Called by the tracking service every tick. If the phone stopped the app during
     * the working day, the gap is written to the log so it is never silently lost.
     */
    @Synchronized
    fun recordHeartbeat(nowMs: Long, resumed: Boolean) {
        val prev = settings.lastHeartbeatMs
        if (prev > 0 && nowMs - prev > HEARTBEAT_GAP_MS && (highAccuracyWanted(prev) || highAccuracyWanted(nowMs))) {
            val f = { ms: Long -> LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()).let { "%02d:%02d".format(it.hour, it.minute) } }
            db.insertEvent(nowMs, EventType.TRACKING,
                "No tracking from ${f(prev)} to ${f(nowMs)}" + if (resumed) " — phone stopped the app; restarted automatically" else " — phone was asleep")
        }
        settings.lastHeartbeatMs = nowMs
    }

    private const val GAP_NOTE_SEC = 10 * 60L
    private const val HEARTBEAT_GAP_MS = 10 * 60_000L

    /** First office entry on [date]. */
    private fun firstCheckIn(date: LocalDate): LocalDateTime? {
        val from = date.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        return db.events(from, to).firstOrNull { it.type == EventType.CHECK_IN }
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.timeMs), zone()) }
    }

    /** Total lunch taken on [date] from the lunch log, plus a lunch still in progress. */
    private fun lunchTakenSec(date: LocalDate, nowMs: Long): Long {
        val from = date.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        var total = 0L
        var open: Long? = null
        for (e in db.events(from, to)) {
            when (e.type) {
                EventType.LUNCH_START -> open = e.timeMs
                EventType.LUNCH_END -> { open?.let { total += (e.timeMs - it) / 1000 }; open = null }
                else -> Unit
            }
        }
        val active = settings.lunch
        if (active.active && active.startedAtMs in from until to) total += ((nowMs - active.startedAtMs) / 1000).coerceAtLeast(0)
        return total
    }

    fun plan(nowMs: Long = System.currentTimeMillis()): DayPlan {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        return DayPlanner.plan(now, settings.settings.value.schedule, ::firstCheckIn) { lunchTakenSec(it, nowMs) }
    }

    /** High-accuracy GPS from 30 min before the day can start until overtime cut-off. */
    fun highAccuracyWanted(nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = settings.settings.value.schedule
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        val p = plan(nowMs)
        if (!p.workDay) return false
        val dayStartMin = if (s.flexible) s.earliestStartMin else s.startMin
        val opens = p.date.atStartOfDay().plusMinutes(dayStartMin.toLong() - 30)
        return now >= opens && now < p.cutoff
    }

    @Synchronized
    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        val s = settings.settings.value
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        var dayPlan = plan(nowMs)
        var lunch = settings.lunch
        // Auto-close a lunch that is still open once the working day is over.
        if (lunch.active && (!dayPlan.workDay || now >= dayPlan.cutoff)) {
            db.insertEvent(nowMs, EventType.LUNCH_END, "Lunch auto-ended (working day over)")
            lunch = LunchState(); settings.lunch = lunch
            dayPlan = plan(nowMs)
        }
        val status = StatusResolver.resolve(now, nowMs, dayPlan, s.schedule, engine.presence, lunch)
        val old = lastStatus
        if (old != null && old != status) {
            db.insertEvent(nowMs, EventType.STATUS, "${old.label} → ${status.label}")
            if (status == WorkStatus.OUTSIDE_OFFICE || status == WorkStatus.LUNCH_EXCEEDED ||
                status == WorkStatus.LATE || status == WorkStatus.DAY_COMPLETE) alert(status, dayPlan)
        }
        lastStatus = status
        _state.value = _state.value.copy(
            status = status, presence = engine.presence, lunch = lunch,
            trackingOn = settings.trackingEnabled, today = summary(now.toLocalDate()), plan = dayPlan,
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
            punches = ev.filter { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT },
        )
    }

    data class WorkedStats(
        val todaySec: Long,
        /** Average per worked day over the last 30 days (today excluded unless it's the only day). */
        val avgSec: Long?,
        val daysCounted: Int,
        val weekAvgSec: Long?,
        /** Last 7 days, oldest first, today last. */
        val last7: List<Pair<LocalDate, Long>>,
        val targetSec: Long,
    )

    /** Worked time per day from the IN / OUT punches, and the averages shown on Today. */
    fun workedStats(nowMs: Long = System.currentTimeMillis()): WorkedStats {
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone()).toLocalDate()
        val from = today.minusDays(30).atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val byDay = db.events(from, to).groupBy {
            Instant.ofEpochMilli(it.timeMs).atZone(zone()).toLocalDate()
        }
        fun dayWorked(d: LocalDate): Long {
            val ev = byDay[d] ?: return 0
            val punches = ev.filter { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT }
                .map { com.attendance.tracker.core.WorkedTime.Punch(it.timeMs, it.type == EventType.CHECK_IN) }
            // Open stay: today → now; past day → last thing recorded that day.
            val close = if (d == today) nowMs else ev.maxOf { it.timeMs }
            return com.attendance.tracker.core.WorkedTime.workedSec(punches, close)
        }
        val past = (1L..30L).map { dayWorked(today.minusDays(it)) }
        val todaySec = dayWorked(today)
        val avg = com.attendance.tracker.core.WorkedTime.averageSec(past)
            ?: com.attendance.tracker.core.WorkedTime.averageSec(listOf(todaySec))
        val counted = past.count { it > 0 }.let { if (it == 0 && todaySec > 0) 1 else it }
        // This week = Monday..today
        val weekDays = (0L..((today.dayOfWeek.value - 1).toLong())).map { today.minusDays(it) }
        val weekAvg = com.attendance.tracker.core.WorkedTime.averageSec(weekDays.map { if (it == today) todaySec else dayWorked(it) })
        val last7 = (6L downTo 0L).map { today.minusDays(it) }.map { it to if (it == today) todaySec else dayWorked(it) }
        return WorkedStats(todaySec, avg, counted, weekAvg, last7, settings.settings.value.schedule.requiredMinutes * 60L)
    }

    /** Declare lunch break. Returns an error message or null on success. */
    @Synchronized
    fun startLunch(nowMs: Long = System.currentTimeMillis()): String? {
        val s = settings.settings.value
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        StatusResolver.canStartLunch(now, plan(nowMs), s.schedule, settings.lunch)?.let { return it }
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
        db.insertEvent(nowMs, EventType.LUNCH_END, "Lunch ended after $mins min")
        settings.lunch = LunchState()
        val p = plan(nowMs)
        if (p.extraLunchSec > 0 && p.expectedEnd != null) {
            val t = "%02d:%02d".format(p.expectedEnd.hour, p.expectedEnd.minute)
            db.insertEvent(nowMs, EventType.STATUS, "Extra lunch ${p.extraLunchSec / 60} min today — logout moved to $t")
        }
        refresh(nowMs)
    }

    /** Office/geofence changed: forget the old inside/outside decision and re-arm geofences. */
    fun onOfficeChanged() {
        engine.reset(Presence.UNKNOWN)
        settings.presence = Presence.UNKNOWN
        com.attendance.tracker.service.GeofenceManager.register(app)
        refresh()
    }

    fun hasFineLocation(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Chime + vibration + notification for every automatic IN / OUT. */
    private fun punchAlert(isIn: Boolean, atMs: Long, gate: String?, approximate: Boolean) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), zone())
        val time = "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")
        val where = gate ?: settings.settings.value.office?.name ?: "Office"
        val title = (if (isIn) "IN  " else "OUT  ") + (if (approximate) "~" else "") + time
        val text = if (isIn) {
            val p = plan(System.currentTimeMillis())
            val out = p.expectedEnd?.let { " · expected out ${"%d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM")}" } ?: ""
            "$where$out · Have a great day!"
        } else {
            val worked = summary(t.toLocalDate()).secondsByStatus.let {
                (it[WorkStatus.IN_OFFICE.name] ?: 0) + (it[WorkStatus.OVERTIME.name] ?: 0)
            }
            "$where · In office ${worked / 3600}h ${(worked % 3600) / 60}m today"
        }
        val channel = when {
            !settings.punchSound -> AttendanceApp.CHANNEL_PUNCH_SILENT
            isIn -> AttendanceApp.CHANNEL_PUNCH_IN
            else -> AttendanceApp.CHANNEL_PUNCH_OUT
        }
        val pi = PendingIntent.getActivity(app, 5, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(
                if (isIn) 3101 else 3102,
                NotificationCompat.Builder(app, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(if (isIn) 0xFF12B886.toInt() else 0xFFF0506E.toInt())
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(atMs).setShowWhen(true)
                    .build()
            )
        }
    }

    private fun alert(status: WorkStatus, plan: DayPlan) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (status) {
            WorkStatus.OUTSIDE_OFFICE -> "You are outside the office during working hours."
            WorkStatus.LUNCH_EXCEEDED -> "Lunch is over the allowed time — every extra minute is added to your logout."
            WorkStatus.LATE -> "You haven't reached the office by the latest arrival time."
            WorkStatus.DAY_COMPLETE -> "Work day complete. Have a good evening!"
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
