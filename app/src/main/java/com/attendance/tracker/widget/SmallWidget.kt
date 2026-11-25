package com.attendance.tracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId

/** 1×1 widget: net time today and logout time. */
class SmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    companion object {
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, SmallWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val st = AttendanceController.state.value
            val now = System.currentTimeMillis()
            val net = if (st.today.punches.isEmpty()) 0L
                else runCatching { AttendanceController.netWorkedSec(st.today.timeline, now, LocalDate.now()) }.getOrDefault(0L)
            val end = st.plan?.expectedEnd
            val out = end?.let { "out %d:%02d %s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "AM" else "PM") } ?: "not started"
            val views = RemoteViews(ctx.packageName, R.layout.widget_small).apply {
                setTextViewText(R.id.ws_net, "${net / 3600}h ${(net % 3600) / 60}m")
                setTextViewText(R.id.ws_out, out)
                setOnClickPendingIntent(R.id.ws_root, PendingIntent.getActivity(ctx, 22, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            }
            runCatching { mgr.updateAppWidget(ids, views) }
        }
    }
}
