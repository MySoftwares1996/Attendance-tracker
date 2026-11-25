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

    private val PILL_STATUSES = setOf(WorkStatus.IN_OFFICE, WorkStatus.OVERTIME, WorkStatus.ON_LUNCH, WorkStatus.LUNCH_EXCEEDED)

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
                title = (if (st.status == WorkStatus.ON_DUTY) "On duty" else "In office") + " · net ${short(net)} of ${short(target)}"
                if (endMs != null && endMs > nowMs) {
                    text = "Logout ${clock(endMs)} · ${short((endMs - nowMs) / 1000)} to go"
                    chip = short((endMs - nowMs) / 1000)
                    chronoWhen = endMs; countDown = true
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
                title = "Outside office · net ${short(net)} of ${short(target)}"
                text = lastOut?.let { "Out since ${clock(it)} · not counted as worked" } ?: "Not counted as worked"
                chip = "Out"
                if (lastOut != null) chronoWhen = lastOut
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
        progress?.let { b.setProgress(100, it, false) }
        if (chronoWhen != null) {
            b.setShowWhen(true).setWhen(chronoWhen).setUsesChronometer(true).setChronometerCountDown(countDown)
        } else b.setShowWhen(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // Live Update pill follows the system idea of a "live" moment, not all day:
        // just after a punch, on lunch, the last 30 min before logout, and overtime.
        val lastPunchMs = st.today.punches.lastOrNull()?.timeMs ?: 0L
        val justPunched = nowMs - lastPunchMs in 0..PUNCH_PILL_MS
        val nearLogout = st.status == WorkStatus.IN_OFFICE && endMs != null && endMs - nowMs in 0..NEAR_LOGOUT_MS
        val showPill = st.status in PILL_STATUSES && (
            st.status != WorkStatus.IN_OFFICE || nearLogout || justPunched)
        if (Build.VERSION.SDK_INT >= 36 && AttendanceController.settings.liveUpdates && showPill) {
            // Ask Android 16 to promote this ongoing notification to a Live Update.
            b.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            chip?.let { b.setShortCriticalText(it) }
        }
        return b.build()
    }

    /** Whether Android lets this app show Live Updates (Android 16+); null on older versions. */
    fun liveUpdatesAllowed(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { nm.canPostPromotedNotifications() }.getOrNull()
    }
}
