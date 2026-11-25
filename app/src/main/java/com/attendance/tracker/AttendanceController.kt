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
import com.attendance.tracker.core.DayMark
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
    /** Punches + lunch + duty events (for the live timeline). */
    val timeline: List<com.attendance.tracker.data.AttendanceEvent> = emptyList(),
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
    /** An afternoon OUT → IN waiting for "Was this lunch?" (outMs to inMs). */
    val pendingLunch: Pair<Long, Long>? = null,
    /** On duty since (ms) with reason, or null. */
    val duty: Pair<Long, String>? = null,
    /** Left during work hours, waiting for "Official work?" (OUT time). */
    val pendingDutyOut: Long? = null,
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

    /**
     * Deletes attendance records: everything, or only what is before today.
     * Setup (office, gates, hours, Wi-Fi) is kept and tracking carries on.
     */
    @Synchronized
    fun clearRecords(keepToday: Boolean) {
        // Always keep an on-phone copy first, so a reset can be undone from Backup → On-phone copies.
        com.attendance.tracker.backup.BackupManager.saveLocal(app, "before-reset")
        val today = LocalDate.now()
        if (keepToday) {
            db.clearRecords(today.atStartOfDay(zone()).toInstant().toEpochMilli(), today.toString())
            settings.permissions = settings.permissions.filterKeys { it >= today.toString() }
        } else {
            db.clearRecords(null, null)
            settings.permissions = emptyMap()
            settings.lunch = LunchState()
            settings.dutyStartMs = 0L
            settings.pendingDutyOutMs = 0L
            settings.pendingLunchOut = 0L; settings.pendingLunchIn = 0L
            settings.lastHeartbeatMs = 0L
            settings.clearOnceFlags()
            app.getSharedPreferences("trust", Context.MODE_PRIVATE).edit().clear().apply()
            // Start fresh: the next GPS reading decides inside/outside again.
            engine.reset(Presence.UNKNOWN)
            settings.presence = Presence.UNKNOWN
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        }
        lastStatus = null
        lastTickMs = 0L
        _state.value = LiveState(presence = engine.presence, lunch = settings.lunch, trackingOn = settings.trackingEnabled)
        refresh()
    }

    /** Deletes all records AND the whole setup; the app starts from the setup wizard again. */
    @Synchronized
    fun resetEverything() {
        runCatching { com.attendance.tracker.service.TrackingService.stop(app) }
        runCatching { com.attendance.tracker.service.GeofenceManager.unregister(app) }
        clearRecords(keepToday = false)
        settings.wipe()
        engine.reset(Presence.UNKNOWN)
        lastStatus = null
        _state.value = LiveState(presence = Presence.UNKNOWN)
        refresh()
    }

    /** After a restore: re-read settings and state from storage. */
    @Synchronized
    fun reload() {
        settings.reload()
        engine.reset(settings.presence)
        lastStatus = null
        _state.value = LiveState(presence = engine.presence, lunch = settings.lunch)
        runCatching { com.attendance.tracker.service.GeofenceManager.register(app) }
        refresh()
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun dayKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toString()

    @Synchronized
    fun onLocation(sample: LocationSample, nowMs: Long = System.currentTimeMillis()) {
        val s = settings.settings.value
        trackHome(sample)
        val res = engine.process(sample, s.office, s.accuracy, nowMs, onOfficeWifi = officeWifiSince > 0)
        var st = _state.value.copy(lastFix = sample)
        when (res) {
            is PresenceEngine.Result.Rejected -> {
                st = st.copy(lastReject = res.reason)
                if (sample.isMock) {
                    com.attendance.tracker.security.TrustChecks.recordMock(app, nowMs)
                    if (settings.firstTime("mock_${nowMs / 3_600_000}")) {
                        notify(4301, "Fake-GPS app detected", "Location readings are ignored until it is turned off. Punches today are marked Not verified.")
                    }
                }
            }
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
                            db.insertEvent(t.atMs, EventType.CHECK_IN, "Entered ${s.office?.name}$gateTxt$first$gapTxt${trustStamp()}",
                                sample.lat, sample.lng, sample.accuracyM)
                            punchAlert(true, t.atMs, t.gate?.name, t.gapBeforeSec >= GAP_NOTE_SEC)
                            cancelEarlyLeave() // came back in: no need to warn any more
                            if (t.from == Presence.OUTSIDE) onReturnInside(t.atMs)
                        }
                        Presence.OUTSIDE -> if (t.from == Presence.INSIDE) {
                            db.insertEvent(t.atMs, EventType.CHECK_OUT, "Left ${s.office?.name}$gateTxt$gapTxt${trustStamp()}",
                                sample.lat, sample.lng, sample.accuracyM)
                            punchAlert(false, t.atMs, t.gate?.name, t.gapBeforeSec >= GAP_NOTE_SEC)
                            if (!maybeEarlyLeaveAlarm(t.atMs)) maybeAskDuty(t.atMs)
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
        com.attendance.tracker.security.TrustChecks.saveClockBaseline(app)
        refresh(nowMs)
    }

    /** Stop accounting (tracking stopped). */
    @Synchronized
    fun pauseAccounting() {
        tick()
        lastTickMs = 0L
        settings.lastHeartbeatMs = 0L
    }

    // ───────────── office Wi-Fi ─────────────
    private var officeWifiSince = 0L
    val onOfficeWifi: Boolean get() = officeWifiSince > 0

    /**
     * Called by the service with the current Wi-Fi state. Returns true when the phone just
     * dropped off the office Wi-Fi while inside (a good moment to poll GPS fast).
     */
    @Synchronized
    fun updateWifi(connected: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val wasOn = officeWifiSince > 0
        if (connected && !wasOn) officeWifiSince = nowMs
        if (!connected) officeWifiSince = 0L
        // Wi-Fi for 60 s but GPS can't place you (or you're right at the edge): count as inside.
        if (connected && engine.presence != Presence.INSIDE && nowMs - officeWifiSince >= 60_000) {
            val st = _state.value
            val gpsFresh = st.lastAcceptedMs?.let { nowMs - it < 3 * 60_000 } == true
            val nearEdge = (st.edgeDistanceM ?: 0.0) < 60.0
            if (engine.presence == Presence.UNKNOWN || !gpsFresh || nearEdge) {
                engine.wifiCheckIn(nowMs)?.let { t ->
                    settings.presence = Presence.INSIDE
                    db.insertEvent(nowMs, EventType.CHECK_IN, "Entered ${settings.settings.value.office?.name} (via office Wi-Fi)${trustStamp()}")
                    punchAlert(true, nowMs, null, false)
                    if (t.from == Presence.OUTSIDE) onReturnInside(nowMs)
                    refresh(nowMs)
                }
            }
        }
        return wasOn && !connected && engine.presence == Presence.INSIDE
    }

    // ───────────── commute (home → office) ─────────────

    /** Notes when you leave home and get back, to measure travel time. */
    private fun trackHome(sample: LocationSample) {
        if (!settings.hasHome || sample.isMock || sample.accuracyM > 60f) return
        val d = com.attendance.tracker.core.GeoMath.distanceM(sample.lat, sample.lng, settings.homeLat, settings.homeLng)
        val was = settings.atHome
        if (d <= 120) {
            if (was == false) db.insertEvent(sample.timeMs, EventType.REACHED_HOME, "Reached home")
            if (was != true) settings.atHome = true
            settings.lastAtHomeMs = sample.timeMs
        } else if (d > 250) {
            // Left: timed at the last moment you were seen at home.
            if (was == true) db.insertEvent(settings.lastAtHomeMs.takeIf { it > 0 } ?: sample.timeMs, EventType.LEFT_HOME, "Left home")
            if (was != false) settings.atHome = false
        }
    }

    /** Today's (or [day]'s) morning commute in minutes and the departure time, if known. */
    fun commute(day: LocalDate): Pair<Long, Long>? {
        val from = day.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val ev = db.events(from - 6 * 3_600_000L, to)
        val firstIn = ev.firstOrNull { it.type == EventType.CHECK_IN && it.timeMs >= from }?.timeMs ?: return null
        val lefts = ev.filter { it.type == EventType.LEFT_HOME }.map { it.timeMs }
        val min = com.attendance.tracker.core.Insights.commuteMinutes(lefts, firstIn) ?: return null
        return min to lefts.filter { it < firstIn }.max()
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
        val mark = db.dayMark(now.toLocalDate().toString())
        var sched = settings.settings.value.schedule
        if (mark == DayMark.HALF_DAY) sched = sched.copy(netMinutes = sched.netMinutes / 2, maxLunchMinutes = 0)
        // Permission: that much less to be in the office, so logout moves earlier.
        val perm = settings.permissionMin(now.toLocalDate().toString())
        if (perm > 0) sched = sched.copy(netMinutes = (sched.netMinutes - perm).coerceAtLeast(0))
        val p = DayPlanner.plan(now, sched, ::firstCheckIn) { lunchTakenSec(it, nowMs) }
        if (mark?.fullDayOff == true) return p.copy(workDay = false, dayOff = mark.label)
        // Public / company holiday (a day mark set by you always wins)
        if (mark == null) com.attendance.tracker.data.Holidays.name(now.toLocalDate())?.let { return p.copy(workDay = false, dayOff = "Holiday \u00b7 $it") }
        return p
    }

    fun dayMark(day: LocalDate): DayMark? = db.dayMark(day.toString())

    fun setDayMark(day: LocalDate, mark: DayMark?) {
        db.setDayMark(day.toString(), mark)
        refresh()
    }

    /**
     * Marks every working day from [from] to [to] that has no punches and no mark yet
     * (e.g. the days before you installed the app). Returns how many days were marked.
     */
    fun markEmptyDays(from: LocalDate, to: LocalDate, mark: DayMark): Int {
        if (to.isBefore(from)) return 0
        val sched = settings.settings.value.schedule
        val marks = db.dayMarks(from.toString(), to.toString())
        val punchDays = db.events(from.atStartOfDay(zone()).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli())
            .filter { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT }
            .map { Instant.ofEpochMilli(it.timeMs).atZone(zone()).toLocalDate() }.toSet()
        var n = 0
        var d = from
        while (!d.isAfter(to)) {
            if (d.dayOfWeek in sched.workDays && d !in punchDays && marks[d.toString()] == null &&
                com.attendance.tracker.data.Holidays.name(d) == null) {
                db.setDayMark(d.toString(), mark); n++
            }
            d = d.plusDays(1)
        }
        refresh()
        return n
    }

    // ───────────── permission ─────────────

    fun permissionMin(day: LocalDate): Int = settings.permissionMin(day.toString())

    /** Minutes still available this month for [day] (its own permission not counted as used). */
    fun permissionLeftMin(day: LocalDate): Int =
        (settings.permissionMonthMin - settings.permissionUsedInMonth(day.toString(), except = day.toString())).coerceAtLeast(0)

    fun setPermission(day: LocalDate, minutes: Int) {
        settings.permissions = settings.permissions.toMutableMap().apply {
            if (minutes > 0) put(day.toString(), minutes) else remove(day.toString())
        }
        refresh()
    }

    // ───────────── missed punches ─────────────

    /** Adds a punch the phone missed (battery dead, phone off). Clearly marked as manual. */
    fun addManualPunch(isIn: Boolean, timeMs: Long, reason: String) {
        db.insertEvent(timeMs, if (isIn) EventType.CHECK_IN else EventType.CHECK_OUT, "$MANUAL_PREFIX${reason.trim()}")
        refresh()
    }

    fun deleteManualPunch(e: com.attendance.tracker.data.AttendanceEvent) {
        if (e.detail.startsWith(MANUAL_PREFIX)) { db.deleteEvent(e.id); refresh() }
    }

    const val MANUAL_PREFIX = "Manual \u00b7 "

    // ───────────── automatic lunch ─────────────

    /** Called on every OUT → IN. Ends an open lunch, or asks whether the break was lunch. */
    private fun onReturnInside(inMs: Long) {
        val day = LocalDateTime.ofInstant(Instant.ofEpochMilli(inMs), zone()).toLocalDate()
        val from = day.atStartOfDay(zone()).toInstant().toEpochMilli()
        val events = db.events(from, inMs + 1)
        if (settings.dutyStartMs > 0) { endDuty(inMs, auto = true); return }
        dismissPendingDutySilently()
        val lastOut = events.lastOrNull { it.type == EventType.CHECK_OUT && it.timeMs < inMs } ?: return
        val l = settings.lunch
        if (l.active) {
            // Went out for lunch and came back: end it automatically at the moment you walked in.
            if (lastOut.timeMs >= l.startedAtMs) {
                db.insertEvent(inMs, EventType.LUNCH_END, "Lunch ended automatically when you came back")
                settings.lunch = LunchState()
                notify(4101, "Welcome back \u2014 lunch ended", "Lunch ended at ${clock(inMs)} when you came in.")
            }
            return
        }
        if (!settings.autoLunch) return
        val gapMin = (inMs - lastOut.timeMs) / 60_000
        val outAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastOut.timeMs), zone())
        val outMin = outAt.hour * 60 + outAt.minute
        val allowance = settings.settings.value.schedule.maxLunchMinutes * 60L
        if (gapMin !in 10..150 || outMin !in (11 * 60)..(16 * 60 + 30)) return
        if (lunchTakenSec(day, inMs) >= allowance) return
        settings.pendingLunchOut = lastOut.timeMs
        settings.pendingLunchIn = inMs
        askLunch(lastOut.timeMs, inMs)
    }

    private fun askLunch(outMs: Long, inMs: Long) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(
            app, req, Intent(app, LunchActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(app, 6, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(
                LUNCH_ASK_ID,
                NotificationCompat.Builder(app, AttendanceApp.CHANNEL_REMINDERS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Back at ${clock(inMs)} \u00b7 were you at lunch?")
                    .setContentText("Out ${clock(outMs)} \u2192 In ${clock(inMs)} (${(inMs - outMs) / 60_000} min)")
                    .addAction(0, "Yes, lunch", action(LunchActionReceiver.YES, 61))
                    .addAction(0, "No, work", action(LunchActionReceiver.NO, 62))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** "Yes, lunch": record the break as lunch (extra minutes extend logout). */
    @Synchronized
    fun confirmPendingLunch() {
        val out = settings.pendingLunchOut; val inn = settings.pendingLunchIn
        if (out > 0 && inn > out) {
            db.insertEvent(out, EventType.LUNCH_START, "Lunch (auto-detected, confirmed)")
            db.insertEvent(inn, EventType.LUNCH_END, "Lunch ended after ${(inn - out) / 60_000} min (auto-detected)")
        }
        dismissPendingLunch()
    }

    @Synchronized
    fun dismissPendingLunch() {
        settings.pendingLunchOut = 0L; settings.pendingLunchIn = 0L
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(LUNCH_ASK_ID)
        refresh()
    }

    // ───────────── reminders ─────────────

    private fun reminders(now: LocalDateTime, plan: DayPlan, status: WorkStatus, lunch: LunchState, nowMs: Long) {
        val day = plan.date.toString()
        val end = plan.expectedEnd
        if (end != null && status == WorkStatus.IN_OFFICE && settings.remindBeforeLogout) {
            val minsLeft = java.time.Duration.between(now, end).toMinutes()
            if (minsLeft in 0..15 && settings.firstTime("pre_$day")) {
                notify(4201, "$minsLeft min to your logout \u00b7 ${clock(end)}", "Get ready to wrap up.")
            }
        }
        if (status == WorkStatus.OVERTIME && settings.remindHoursDone && settings.firstTime("done_$day")) {
            val req = settings.settings.value.schedule.netMinutes
            notify(4202, "Your ${req / 60}h${if (req % 60 > 0) " ${req % 60}m" else ""} net hours are complete \u2014 you can leave \uD83C\uDF89",
                "Time in office from now counts as overtime.")
        }
        if (lunch.active && settings.remindLunchOver) {
            val over = plan.lunchTakenSec - settings.settings.value.schedule.maxLunchMinutes * 60L
            if (over >= 5 * 60 && settings.firstTime("lunchover_$day")) {
                notify(4203, "Lunch is ${over / 60} min over", "Logout moves to ${end?.let { clock(it) } ?: "later"}.")
            }
        }
    }

    private fun clock(ms: Long) = clock(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()))
    private fun clock(t: LocalDateTime) = "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")

    private fun notify(id: Int, title: String, text: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(app, 7, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(id, NotificationCompat.Builder(app, AttendanceApp.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    private const val LUNCH_ASK_ID = 4100

    /** High-accuracy GPS from 30 min before the day can start until overtime cut-off. */
    fun highAccuracyWanted(nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = settings.settings.value.schedule
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        val p = plan(nowMs)
        if (!p.workDay) return false
        // No arrival window: on working days use precise GPS from 5 AM (it adapts by distance anyway).
        val dayStartMin = if (s.flexible) 5 * 60 + 30 else s.startMin
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
        val status = StatusResolver.resolve(now, nowMs, dayPlan, s.schedule, engine.presence, lunch, onDuty = settings.dutyStartMs > 0)
        val old = lastStatus
        if (old != null && old != status) {
            db.insertEvent(nowMs, EventType.STATUS, "${old.label} → ${status.label}")
            if (status == WorkStatus.LATE || status == WorkStatus.DAY_COMPLETE) alert(status, dayPlan)
            if (status == WorkStatus.DAY_COMPLETE && settings.autoSummary && settings.firstTime("eod_${dayPlan.date}")) {
                notifySummary(dayPlan.date)
            }
        }
        lastStatus = status
        reminders(now, dayPlan, status, lunch, nowMs)
        val pl = settings.pendingLunchOut.takeIf { it > 0 }?.let { it to settings.pendingLunchIn }
        _state.value = _state.value.copy(
            status = status, presence = engine.presence, lunch = lunch,
            trackingOn = settings.trackingEnabled, today = summary(now.toLocalDate()), plan = dayPlan,
            pendingLunch = pl,
            duty = settings.dutyStartMs.takeIf { it > 0 }?.let { it to settings.dutyReason },
            pendingDutyOut = settings.pendingDutyOutMs.takeIf { it > 0 },
        )
        runCatching { com.attendance.tracker.widget.AttendanceWidget.updateIfChanged(app) }
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
            timeline = ev.filter { it.type in TIMELINE_TYPES },
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
            val punches = workPunches(ev)
            // Open stay: today → now; past day → last thing recorded that day.
            val close = if (d == today) nowMs else ev.maxOf { it.timeMs }
            if (punches.isEmpty()) return 0
            return netWorkedSec(ev, close, d)
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
        return WorkedStats(todaySec, avg, counted, weekAvg, last7, settings.settings.value.schedule.netMinutes * 60L)
    }

    /** IN/OUT punches plus on-duty time (counted as worked) for worked-time maths. */
    fun workPunches(ev: List<com.attendance.tracker.data.AttendanceEvent>): List<com.attendance.tracker.core.WorkedTime.Punch> =
        ev.mapNotNull {
            when (it.type) {
                EventType.CHECK_IN, EventType.DUTY_START -> com.attendance.tracker.core.WorkedTime.Punch(it.timeMs, true)
                EventType.CHECK_OUT, EventType.DUTY_END -> com.attendance.tracker.core.WorkedTime.Punch(it.timeMs, false)
                else -> null
            }
        }

    /** Time in office + on duty (lunch excluded), before the lunch deduction. */
    fun presenceSec(ev: List<com.attendance.tracker.data.AttendanceEvent>, closeMs: Long): Triple<Long, Long, Long> {
        val t = com.attendance.tracker.core.DayTimeline.totals(com.attendance.tracker.core.DayTimeline.segments(timelineEvents(ev), closeMs))
        return Triple(
            t[com.attendance.tracker.core.DayTimeline.Kind.OFFICE] ?: 0L,
            t[com.attendance.tracker.core.DayTimeline.Kind.DUTY] ?: 0L,
            t[com.attendance.tracker.core.DayTimeline.Kind.LUNCH] ?: 0L,
        )
    }

    /** Lunch allowance that is always deducted on [day] (none on a half day). */
    fun lunchAllowanceSec(day: LocalDate): Long =
        if (db.dayMark(day.toString()) == DayMark.HALF_DAY) 0L else settings.settings.value.schedule.maxLunchMinutes * 60L

    /** Net target for [day]: e.g. 8h 30m, halved on a half day. */
    fun netTargetSec(day: LocalDate): Long {
        val net = settings.settings.value.schedule.netMinutes * 60L
        return if (db.dayMark(day.toString()) == DayMark.HALF_DAY) net / 2 else net
    }

    /** Net worked on [day]: office + on duty, minus lunch (at least the allowance, even if skipped). */
    fun netWorkedSec(ev: List<com.attendance.tracker.data.AttendanceEvent>, closeMs: Long, day: LocalDate): Long {
        val (office, duty, lunch) = presenceSec(ev, closeMs)
        return netFrom(office, duty, lunch, closeMs, day)
    }

    /**
     * Net from office / duty / lunch seconds. Today, before logout time, the live rule is used
     * so the number counts up from arrival; otherwise the untaken lunch allowance is fully deducted.
     */
    fun netFrom(office: Long, dutyOnly: Long, lunch: Long, closeMs: Long, day: LocalDate): Long {
        // Permission counts as worked time.
        val duty = dutyOnly + permissionMin(day) * 60L
        val allowance = lunchAllowanceSec(day)
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), zone()).toLocalDate()
        val end = if (day == today) runCatching { plan(closeMs).expectedEnd }.getOrNull() else null
        val closing = LocalDateTime.ofInstant(Instant.ofEpochMilli(closeMs), zone())
        val deductNow = com.attendance.tracker.core.WorkedTime.deductLunchNow(lunch, settings.lunch.active,
            closing.hour * 60 + closing.minute, settings.lunchDeductAtMin)
        return if (end != null && closing.isBefore(end))
            com.attendance.tracker.core.WorkedTime.liveNetSec(office, duty, lunch, allowance, netTargetSec(day), deductNow)
        else com.attendance.tracker.core.WorkedTime.netSec(office, duty, lunch, allowance)
    }

    private fun trustStamp(): String = runCatching { com.attendance.tracker.security.TrustChecks.stamp(app) }.getOrDefault("")

    // ───────────── on duty ─────────────

    /** Left during work hours (not at lunch): ask "Official work?". */
    // ───────────── leaving early ─────────────

    private fun fmtHm(sec: Long): String { val m = (sec + 59) / 60; return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m" }

    /**
     * You left before your net hours are done (after lunch, or after the lunch-deduct time).
     * Rings an alarm: go back, or regularise with permission in one tap. True when it rang.
     */
    private fun maybeEarlyLeaveAlarm(outMs: Long): Boolean {
        if (!settings.earlyLeaveAlarm || settings.lunch.active || settings.dutyStartMs > 0) return false
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(outMs), zone())
        val day = now.toLocalDate()
        val p = plan(outMs)
        if (!p.workDay || p.dayOff != null || p.start == null) return false
        val ev = db.events(day.atStartOfDay(zone()).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli())
        val lunchTaken = presenceSec(ev, outMs).third
        // A midday OUT is probably lunch: only warn once lunch is done or it's past the lunch-deduct time.
        val afterLunch = lunchTaken > 0 || now.hour * 60 + now.minute >= settings.lunchDeductAtMin
        if (!afterLunch) return false
        val target = netTargetSec(day)
        val net = netWorkedSec(ev, outMs, day)
        val short = target - net
        if (short < 60) return false
        val left = permissionLeftMin(day) - permissionMin(day)
        val needMin = (((short + 59) / 60 + 29) / 30 * 30).toInt()   // round up to 30 min
        val canCover = left >= needMin
        settings.pendingDutyOutMs = outMs
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun action(a: String, req: Int, min: Int = 0) = PendingIntent.getBroadcast(
            app, req, Intent(app, EarlyLeaveReceiver::class.java).setAction(a).putExtra(EarlyLeaveReceiver.EXTRA_MIN, min),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = "Net worked ${fmtHm(net)} of ${fmtHm(target)} · ${fmtHm(short)} short.\n" +
            "Go back in to finish your hours" + when {
                canCover -> ", or regularise with ${fmtHm(needMin * 60L)} permission (${fmtHm(left * 60L)} left this month)."
                left > 0 -> ". Only ${fmtHm(left * 60L)} permission left this month — not enough to cover it."
                else -> ". No permission left this month."
            }
        val b = NotificationCompat.Builder(app, AttendanceApp.CHANNEL_EARLY_LEAVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠ Leaving early? ${fmtHm(short)} still to work")
            .setContentText("Net ${fmtHm(net)} of ${fmtHm(target)}. Go back, or use permission.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(PendingIntent.getActivity(app, 9, Intent(app, com.attendance.tracker.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
        if (canCover) b.addAction(0, "Use ${fmtHm(needMin * 60L)} permission", action(EarlyLeaveReceiver.USE_PERMISSION, 81, needMin))
        else if (left > 0) b.addAction(0, "Use ${fmtHm(left * 60L)} permission", action(EarlyLeaveReceiver.USE_PERMISSION, 81, left))
        b.addAction(0, "On duty", action(EarlyLeaveReceiver.ON_DUTY, 82))
        b.addAction(0, "OK", action(EarlyLeaveReceiver.DISMISS, 83))
        runCatching { nm.notify(EARLY_LEAVE_ID, b.build()) }
        refresh()
        return true
    }

    fun usePermissionForEarlyLeave(minutes: Int) {
        val today = LocalDate.now()
        val add = minutes.coerceAtMost(permissionLeftMin(today) - permissionMin(today)).coerceAtLeast(0)
        if (add > 0) setPermission(today, permissionMin(today) + add)
        settings.pendingDutyOutMs = 0L
        cancelEarlyLeave()
    }

    fun earlyLeaveOnDuty() {
        cancelEarlyLeave()
        confirmPendingDuty("Official work")
    }

    fun dismissEarlyLeave() {
        settings.pendingDutyOutMs = 0L
        cancelEarlyLeave()
    }

    private fun cancelEarlyLeave() {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(EARLY_LEAVE_ID)
    }

    private fun maybeAskDuty(outMs: Long) {
        if (!settings.askDuty || settings.lunch.active || settings.dutyStartMs > 0) return
        val p = plan(outMs)
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(outMs), zone())
        val end = p.expectedEnd ?: return
        if (!p.workDay || p.dayOff != null || p.start == null || now >= end) return
        settings.pendingDutyOutMs = outMs
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(
            app, req, Intent(app, DutyActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            nm.notify(DUTY_ASK_ID, NotificationCompat.Builder(app, AttendanceApp.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("You left at ${clock(outMs)} \u00b7 official work?")
                .setContentText("On duty time counts as worked hours.")
                .addAction(0, "On duty", action(DutyActionReceiver.ON_DUTY, 71))
                .addAction(0, "Lunch / personal", action(DutyActionReceiver.PERSONAL, 72))
                .setContentIntent(PendingIntent.getActivity(app, 8, Intent(app, com.attendance.tracker.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
        }
        refresh()
    }

    /** Start on-duty now, or from the pending OUT time if there is one. */
    @Synchronized
    fun confirmPendingDuty(reason: String) {
        val from = settings.pendingDutyOutMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        startDuty(reason, from)
    }

    @Synchronized
    fun startDuty(reason: String, fromMs: Long = System.currentTimeMillis()) {
        if (settings.dutyStartMs > 0) { setDutyReason(reason); return }
        settings.dutyStartMs = fromMs
        settings.dutyReason = reason
        db.insertEvent(fromMs, EventType.DUTY_START, "On duty \u00b7 $reason")
        dismissPendingDutySilently()
        refresh()
    }

    fun setDutyReason(reason: String) {
        settings.dutyReason = reason
        refresh()
    }

    @Synchronized
    fun endDuty(atMs: Long = System.currentTimeMillis(), auto: Boolean = false) {
        val start = settings.dutyStartMs
        if (start <= 0) return
        val mins = (atMs - start) / 60_000
        db.insertEvent(atMs, EventType.DUTY_END, "Back from duty \u00b7 ${settings.dutyReason} \u00b7 $mins min counted as work")
        settings.dutyStartMs = 0L
        if (auto) notify(4102, "Welcome back \u2014 duty ended", "$mins min of ${settings.dutyReason} counted as work.")
        refresh()
    }

    @Synchronized
    fun dismissPendingDuty() { dismissPendingDutySilently(); refresh() }

    private fun dismissPendingDutySilently() {
        settings.pendingDutyOutMs = 0L
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DUTY_ASK_ID)
    }

    // ───────────── end-of-day summary ─────────────

    fun summaryText(day: LocalDate): String {
        val from = day.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val ev = db.events(from, to)
        val now = System.currentTimeMillis()
        val close = if (day == LocalDate.now()) now else ev.maxOfOrNull { it.timeMs } ?: from
        val worked = netWorkedSec(ev, close, day)
        val segs = com.attendance.tracker.core.DayTimeline.segments(timelineEvents(ev), close)
        val totals = com.attendance.tracker.core.DayTimeline.totals(segs)
        val lunchTaken = totals[com.attendance.tracker.core.DayTimeline.Kind.LUNCH] ?: 0L
        val lunchDeducted = maxOf(lunchTaken, lunchAllowanceSec(day))
        fun hm(sec: Long) = "${sec / 3600}h ${"%02d".format((sec % 3600) / 60)}m"
        val gate = Regex("via (.+?)( \\(|$| \u00b7)")
        val lines = mutableListOf("\uD83D\uDCCB Attendance \u2013 ${day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))}")
        ev.filter { it.type == EventType.CHECK_IN || it.type == EventType.CHECK_OUT }.forEach { e ->
            val g = gate.find(e.detail)?.groupValues?.get(1)?.let { " ($it)" } ?: ""
            val m = if (e.detail.startsWith(MANUAL_PREFIX)) " (manual)" else ""
            lines += "${if (e.type == EventType.CHECK_IN) "IN " else "OUT"} ${clock(e.timeMs)}$g$m"
        }
        lines += "Net worked ${hm(worked)} of ${hm(netTargetSec(day))}"
        lines += "Lunch deducted ${hm(lunchDeducted)}" + if (lunchTaken == 0L) " (not taken)" else ""
        totals[com.attendance.tracker.core.DayTimeline.Kind.DUTY]?.let { lines += "On duty ${hm(it)}" }
        val p = plan(close)
        db.dayMark(day.toString())?.let { lines += it.label }
        return lines.joinToString("\n")
    }

    fun timelineEvents(ev: List<com.attendance.tracker.data.AttendanceEvent>): List<com.attendance.tracker.core.DayTimeline.Event> =
        ev.mapNotNull { e ->
            val k = when (e.type) {
                EventType.CHECK_IN -> com.attendance.tracker.core.DayTimeline.Ev.IN
                EventType.CHECK_OUT -> com.attendance.tracker.core.DayTimeline.Ev.OUT
                EventType.LUNCH_START -> com.attendance.tracker.core.DayTimeline.Ev.LUNCH_START
                EventType.LUNCH_END -> com.attendance.tracker.core.DayTimeline.Ev.LUNCH_END
                EventType.DUTY_START -> com.attendance.tracker.core.DayTimeline.Ev.DUTY_START
                EventType.DUTY_END -> com.attendance.tracker.core.DayTimeline.Ev.DUTY_END
                else -> null
            }
            k?.let { kind -> com.attendance.tracker.core.DayTimeline.Event(e.timeMs, kind) }
        }

    fun shareIntent(text: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share today's attendance"
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens WhatsApp to the manager's number with the text filled in (you still tap Send). */
    fun managerIntent(text: String): Intent? {
        val digits = settings.managerPhone.filter { it.isDigit() }
        if (digits.length < 8) return null
        return Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/$digits?text=" + java.net.URLEncoder.encode(text, "UTF-8")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun notifySummary(day: LocalDate) {
        val text = summaryText(day)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val share = PendingIntent.getActivity(app, 81, shareIntent(text), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(app, AttendanceApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Day complete \u00b7 " + text.lines().firstOrNull { it.startsWith("Worked") }.orEmpty())
            .setContentText("Tap Share to send today's summary")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "Share", share)
            .setAutoCancel(true)
        managerIntent(text)?.let {
            b.addAction(0, "Send to manager", PendingIntent.getActivity(app, 82, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        runCatching { nm.notify(4401, b.build()) }
    }

    /** Average first-IN time (minutes after midnight) over the last 30 days, or null. */
    fun usualArrivalMin(): Int? {
        val today = LocalDate.now()
        val from = today.minusDays(30).atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = today.atStartOfDay(zone()).toInstant().toEpochMilli()
        val firsts = db.events(from, to).filter { it.type == EventType.CHECK_IN }
            .groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone()).toLocalDate() }
            .map { (_, v) -> Instant.ofEpochMilli(v.minOf { it.timeMs }).atZone(zone()).toLocalTime().let { it.hour * 60 + it.minute } }
        return if (firsts.isEmpty()) null else firsts.sum() / firsts.size
    }

    private const val DUTY_ASK_ID = 4110
    private const val EARLY_LEAVE_ID = 4120
    private val TIMELINE_TYPES = setOf(EventType.CHECK_IN, EventType.CHECK_OUT, EventType.LUNCH_START,
        EventType.LUNCH_END, EventType.DUTY_START, EventType.DUTY_END)

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
