package com.attendance.tracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.core.WorkedTime
import com.attendance.tracker.data.EventType
import com.attendance.tracker.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

/** Home-screen widget: status, worked today, logout time, progress and a lunch button. */
class AttendanceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LUNCH) {
            val l = AttendanceController.settings.lunch
            if (l.active) AttendanceController.endLunch()
            else AttendanceController.startLunch()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            update(context)
        }
    }

    companion object {
        private const val ACTION_LUNCH = "com.attendance.tracker.WIDGET_LUNCH"
        private var lastKey = ""

        /** Cheap to call often: only pushes to the launcher when something visible changed. */
        fun updateIfChanged(ctx: Context) {
            val st = AttendanceController.state.value
            val key = "${st.status}|${st.lunch.active}|${st.today.punches.size}|${System.currentTimeMillis() / 60_000}"
            if (key == lastKey) return
            lastKey = key
            update(ctx)
        }

        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, AttendanceWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val st = AttendanceController.state.value
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
                .let { "%d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM") }

            val punches = st.today.punches.map { WorkedTime.Punch(it.timeMs, it.type == EventType.CHECK_IN) }
            val worked = WorkedTime.workedSec(punches, now)
            val plan = st.plan
            val end = plan?.expectedEnd
            val target = AttendanceController.settings.settings.value.schedule.requiredMinutes * 60L
            val (label, color) = when (st.status) {
                WorkStatus.IN_OFFICE -> "● In office" to 0xFF12B886.toInt()
                WorkStatus.OVERTIME -> "● Overtime" to 0xFF6A4FE8.toInt()
                WorkStatus.ON_LUNCH -> "● On lunch" to 0xFFF59F00.toInt()
                WorkStatus.LUNCH_EXCEEDED -> "● Lunch over" to 0xFFD9468F.toInt()
                WorkStatus.OUTSIDE_OFFICE -> "● Outside office" to 0xFFF0506E.toInt()
                WorkStatus.LATE -> "● Running late" to 0xFFF2694A.toInt()
                WorkStatus.DAY_OFF -> "● ${plan?.dayOff ?: "Day off"}" to 0xFF8E63E0.toInt()
                WorkStatus.DAY_COMPLETE -> "● Day done" to 0xFF26A69A.toInt()
                WorkStatus.NOT_STARTED -> "● Not started" to 0xFF3D9BE0.toInt()
                else -> "● ${st.status.label}" to 0xFF7A8099.toInt()
            }
            val since = st.today.punches.lastOrNull()?.let {
                (if (it.type == EventType.CHECK_IN) "in since " else "out since ") + clock(it.timeMs)
            } ?: ""
            val endLocalMs = end?.atZone(zone)?.toInstant()?.toEpochMilli()

            val open = PendingIntent.getActivity(ctx, 20, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val lunch = PendingIntent.getBroadcast(
                ctx, 21, Intent(ctx, AttendanceWidget::class.java).setAction(ACTION_LUNCH), PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget_attendance).apply {
                setTextViewText(R.id.w_status, label)
                setTextColor(R.id.w_status, color)
                setTextViewText(R.id.w_since, since)
                setTextViewText(R.id.w_worked, "${worked / 3600}h ${(worked % 3600) / 60}m")
                setTextViewText(R.id.w_logout, endLocalMs?.let { clock(it) } ?: "—")
                setTextViewText(R.id.w_logout_label, if ((plan?.extraLunchSec ?: 0L) > 0) "logout (+lunch)" else "logout")
                setProgressBar(R.id.w_progress, 100, ((worked * 100) / target.coerceAtLeast(1)).toInt().coerceIn(0, 100), false)
                setTextViewText(R.id.w_lunch, if (st.lunch.active) "I'm back from lunch" else "🍴  Start lunch")
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.w_lunch, lunch)
            }
            runCatching { mgr.updateAppWidget(ids, views) }
        }
    }
}
