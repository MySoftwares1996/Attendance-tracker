package com.attendance.tracker.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.attendance.tracker.AttendanceApp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.core.WorkedTime
import com.attendance.tracker.data.EventType
import com.attendance.tracker.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The always-on tracking notification, built as an Android 16 "Live Update":
 * a short chip in the status bar (also shown in OPPO / OnePlus / Realme Live Alerts),
 * a live countdown/count-up, and a day progress bar. Older Android shows the same
 * notification without the status-bar chip.
 */
object LiveNotification {
    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16). */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private const val PUNCH_PILL_MS = 2 * 60_000L
    private const val NEAR_LOGOUT_MS = 30 * 60_000L
    /** In the office the pill pops up for [PERIODIC_SHOW_MIN] minutes every [PERIODIC_EVERY_MIN] minutes. */
    private const val PERIODIC_EVERY_MIN = 20L
    private const val PERIODIC_SHOW_MIN = 2L

    private val PILL_STATUSES = setOf(WorkStatus.IN_OFFICE, WorkStatus.OVERTIME, WorkStatus.ON_LUNCH, WorkStatus.LUNCH_EXCEEDED,
        WorkStatus.OUTSIDE_OFFICE)

    private fun clock(t: LocalDateTime) = "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")
    private fun clock(ms: Long) = clock(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()))
    private fun short(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun build(ctx: Context): Notification {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val today = LocalDate.now()
        val st = AttendanceController.state.value
        val plan = st.plan
        val sched = AttendanceController.settings.settings.value.schedule
        val hasPunches = st.today.punches.isNotEmpty()
        val (office, duty, lunch) = if (hasPunches) AttendanceController.presenceSec(st.today.timeline, nowMs) else Triple(0L, 0L, 0L)
        val net = AttendanceController.netFrom(office, duty, lunch, nowMs, today)
        val target = AttendanceController.netTargetSec(today)
        val endMs = plan?.expectedEnd?.atZone(zone)?.toInstant()?.toEpochMilli()
        val startMs = plan?.start?.atZone(zone)?.toInstant()?.toEpochMilli()

        var title: String
        var text: String
        var chip: String? = null
        var chronoWhen: Long? = null
        var countDown = false
        var progress: Int? = null
        if (startMs != null && endMs != null && endMs > startMs) {
            progress = (((nowMs - startMs) * 100) / (endMs - startMs)).toInt().coerceIn(0, 100)
        }

        when (st.status) {
            WorkStatus.IN_OFFICE, WorkStatus.ON_DUTY -> {
                // Short enough to fit the collapsed notification; no ticking timer (it read like a clock).
                title = (if (st.status == WorkStatus.ON_DUTY) "On duty" else "In office") + " · ${short(net)} / ${short(target)}"
                if (endMs != null && endMs > nowMs) {
                    text = "Logout ${clock(endMs)} · ${short((endMs - nowMs) / 1000)} to go"
                    chip = short((endMs - nowMs) / 1000)
                } else { text = "Logout time reached"; chip = "Done" }
            }
            WorkStatus.OVERTIME -> {
                val ot = endMs?.let { (nowMs - it) / 1000 } ?: 0
                title = "Overtime · +${short(ot)}"
                text = "Net ${short(net)} · logout was ${endMs?.let { clock(it) } ?: "—"}"
                chip = "OT ${short(ot)}"
                if (endMs != null) chronoWhen = endMs
            }
            WorkStatus.ON_LUNCH, WorkStatus.LUNCH_EXCEEDED -> {
                val left = sched.maxLunchMinutes * 60L - (plan?.lunchTakenSec ?: 0)
                title = if (left >= 0) "On lunch · ${short(left)} left" else "Lunch over by ${short(-left)}"
                text = "Logout ${endMs?.let { clock(it) } ?: "—"}" + if (left < 0) " (moved later)" else ""
                chip = if (left >= 0) "\uD83C\uDF74 ${short(left)}" else "\uD83C\uDF74 +${short(-left)}"
                chronoWhen = nowMs + left * 1000; countDown = left >= 0
            }
            WorkStatus.OUTSIDE_OFFICE -> {
                val lastOut = st.today.punches.lastOrNull()?.takeIf { it.type == EventType.CHECK_OUT }?.timeMs
                title = "Outside · ${short(net)} / ${short(target)}"
                text = lastOut?.let { "Out since ${clock(it)} · not counted as worked" } ?: "Not counted as worked"
                chip = lastOut?.let { "Out ${short((nowMs - it) / 1000)}" } ?: "Out"
                // no running timer here: it read like a clock
            }
            WorkStatus.NOT_STARTED -> {
                title = "Ready · your day starts when you arrive"
                chip = "Ready"
                text = "Logout = arrival + ${short(target)} net + ${sched.maxLunchMinutes}m lunch"
            }
            WorkStatus.LATE -> {
                title = "Running late"
                text = "On time was until ${plan?.latestStart?.let { clock(it) } ?: "—"}"
                chip = "Late"
            }
            WorkStatus.DAY_COMPLETE -> {
                title = "Day complete · net ${short(net)}"
                chip = "Done"
                text = "Have a good evening"
                progress = 100
            }
            WorkStatus.DAY_OFF -> { title = plan?.dayOff ?: "Day off"; text = "Enjoy your day"; progress = null; chip = "Off" }
            else -> {
                chip = if (st.status == WorkStatus.OUTSIDE_WORKING_HOURS) "Off" else "\u2022"
                title = st.status.label
                text = st.today.punches.lastOrNull()?.let { (if (it.type == EventType.CHECK_IN) "Last IN " else "Last OUT ") + clock(it.timeMs) } ?: "Auto attendance on"
                progress = null
            }
        }

        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, AttendanceApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // Android 16: the day as a coloured bar (office / lunch / duty / outside) with a point at each punch.
        var styled = false
        if (Build.VERSION.SDK_INT >= 36 && startMs != null && endMs != null && endMs > startMs && hasPunches) {
            styled = runCatching {
                val span = endMs.coerceAtLeast(nowMs) - startMs
                val totalMin = (span / 60_000).toInt().coerceAtLeast(1)
                val segs = com.attendance.tracker.core.DayTimeline.segments(AttendanceController.timelineEvents(st.today.timeline), nowMs)
                fun col(k: com.attendance.tracker.core.DayTimeline.Kind) = when (k) {
                    com.attendance.tracker.core.DayTimeline.Kind.OFFICE -> 0xFF12B886.toInt()
                    com.attendance.tracker.core.DayTimeline.Kind.LUNCH -> 0xFFF59F00.toInt()
                    com.attendance.tracker.core.DayTimeline.Kind.DUTY -> 0xFF6A4FE8.toInt()
                    com.attendance.tracker.core.DayTimeline.Kind.OUTSIDE -> 0xFFF0506E.toInt()
                }
                val list = segs.mapNotNull { sg -> ((sg.endMs - sg.startMs) / 60_000).toInt().takeIf { it > 0 }?.let { Notification.ProgressStyle.Segment(it).setColor(col(sg.kind)) } }
                    .toMutableList()
                val used = list.sumOf { it.length }
                if (totalMin - used > 0) list += Notification.ProgressStyle.Segment(totalMin - used).setColor(0xFFB8BCCB.toInt())
                val points = st.today.punches.map { p ->
                    Notification.ProgressStyle.Point((((p.timeMs - startMs) / 60_000).toInt()).coerceIn(0, totalMin))
                        .setColor(if (p.type == EventType.CHECK_IN) 0xFF12B886.toInt() else 0xFFF0506E.toInt())
                }
                b.setStyle(Notification.ProgressStyle().setStyledByProgress(false).setProgressSegments(list)
                    .setProgressPoints(points).setProgress(((nowMs - startMs) / 60_000).toInt().coerceIn(0, totalMin)))
                true
            }.getOrDefault(false)
        }
        if (!styled) progress?.let { b.setProgress(100, it, false) }

        // Buttons on the notification / pill.
        if (st.lunch.active) {
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notification), "Back from lunch", PendingIntent.getBroadcast(ctx, 93,
                Intent(ctx, com.attendance.tracker.AlertActionReceiver::class.java).setAction(com.attendance.tracker.AlertActionReceiver.END_LUNCH),
                PendingIntent.FLAG_IMMUTABLE)).build())
        } else if (st.status == WorkStatus.IN_OFFICE && endMs != null && endMs - nowMs in 0..NEAR_LOGOUT_MS) {
            val short = (target - net).coerceAtLeast(0)
            val need = (((short + 59) / 60 + 29) / 30 * 30).toInt()
            val left = AttendanceController.permissionLeftMin(today) - AttendanceController.permissionMin(today)
            if (short > 0 && left >= need) b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notification), "Use ${short(need * 60L)} permission & leave",
                PendingIntent.getBroadcast(ctx, 94, Intent(ctx, com.attendance.tracker.EarlyLeaveReceiver::class.java)
                    .setAction(com.attendance.tracker.EarlyLeaveReceiver.USE_PERMISSION).putExtra(com.attendance.tracker.EarlyLeaveReceiver.EXTRA_MIN, need),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        if (chronoWhen != null) {
            b.setShowWhen(true).setWhen(chronoWhen).setUsesChronometer(true).setChronometerCountDown(countDown)
        } else b.setShowWhen(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // Live Update pill follows the system idea of a "live" moment, not all day:
        // just after a punch, on lunch, the last 30 min before logout, and overtime.
        val lastPunchMs = st.today.punches.lastOrNull()?.timeMs ?: 0L
        val justPunched = nowMs - lastPunchMs in 0..PUNCH_PILL_MS
        val nearLogout = st.status == WorkStatus.IN_OFFICE && endMs != null && endMs - nowMs in 0..NEAR_LOGOUT_MS
        // In the office: every 20 min (for 2 min), right after a punch, and through the last 30 min before logout.
        // Outside during working hours, on lunch and in overtime: all the time.
        val periodic = startMs != null && ((nowMs - startMs) / 60_000) % PERIODIC_EVERY_MIN < PERIODIC_SHOW_MIN
        val showPill = st.status in PILL_STATUSES && (
            st.status != WorkStatus.IN_OFFICE || nearLogout || justPunched || periodic)
        if (Build.VERSION.SDK_INT >= 36 && AttendanceController.settings.liveUpdates && showPill) {
            // Ask Android 16 to promote this ongoing notification to a Live Update.
            b.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            chip?.let { b.setShortCriticalText(it) }
        }
        // What the person actually sees; re-posting an identical notification makes the shade blink.
        lastBuiltSig = listOf(title, text, chip, showPill, progress, st.lunch.active, st.status,
            chronoWhen?.let { it / 10_000 }, countDown, st.today.punches.size, styled,
            if (startMs != null) (nowMs - startMs) / 60_000 else null).joinToString("|")
        return b.build()
    }

    /** Signature of the last notification built (see [build]). */
    @Volatile var lastBuiltSig: String = ""

    /** Whether Android lets this app show Live Updates (Android 16+); null on older versions. */
    fun liveUpdatesAllowed(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { nm.canPostPromotedNotifications() }.getOrNull()
    }
}
