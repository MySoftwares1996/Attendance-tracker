package com.attendance.tracker.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.data.Holidays
import com.attendance.tracker.leavesIn
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Puts applied leave and holidays into the phone's main calendar as all-day events.
 * Events it made carry a marker in the description, so a re-sync replaces only its own events.
 */
object CalendarSync {
    private const val MARK = "[Attendance Tracker]"

    fun permitted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Runs in the background; safe to call after any leave / holiday change. */
    fun syncSoon(ctx: Context) {
        val app = ctx.applicationContext
        if (!AttendanceController.settings.calendarSync || !permitted(app)) return
        Thread { runCatching { sync(app) } }.start()
    }

    private fun calendarId(ctx: Context): Long? = ctx.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
        "${CalendarContract.Calendars.VISIBLE}=1", null, null,
    )?.use { c ->
        var best: Long? = null
        while (c.moveToNext()) {
            if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
            if (best == null) best = c.getLong(0)
            if (c.getInt(1) == 1) return@use c.getLong(0)
        }
        best
    }

    @Synchronized
    fun sync(ctx: Context): Int {
        val cal = calendarId(ctx) ?: return 0
        val cr = ctx.contentResolver
        cr.delete(CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
            arrayOf(cal.toString(), "%$MARK%"))
        val today = LocalDate.now()
        val items = AttendanceController.leavesIn(today.year).filter { !it.date.isBefore(today.minusMonths(1)) }
            .map { it.date to "${it.type.label}${if (it.half) " (half day)" else ""}" } +
            AttendanceController.leavesIn(today.year + 1).map { it.date to it.type.label } +
            Holidays.upcoming(today, 400).map { it.first to "Holiday · ${it.second}" }
        items.distinctBy { it.first to it.second }.forEach { (d, title) ->
            val start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            cr.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal); put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, "Added by Attendance Tracker $MARK")
                put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, start + 86_400_000L)
                put(CalendarContract.Events.ALL_DAY, 1); put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            })
        }
        return items.size
    }
}
