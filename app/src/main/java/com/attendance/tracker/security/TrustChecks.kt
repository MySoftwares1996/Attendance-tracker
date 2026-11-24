package com.attendance.tracker.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.data.EventType
import java.time.LocalDate

/** Signals that attendance could have been faked. Used to stamp each punch Verified / Not verified. */
object TrustChecks {
    const val VERIFIED = " · ✓ Verified"
    const val NOT_VERIFIED = " · ⚠ Not verified: "

    fun autoTimeOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrDefault(true)

    fun developerOptionsOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1 }.getOrDefault(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trust", Context.MODE_PRIVATE)

    fun clockChangedToday(ctx: Context) = prefs(ctx).getString("clockChangedDay", null) == LocalDate.now().toString()
    fun lastMockMs(ctx: Context) = prefs(ctx).getLong("lastMock", 0L)
    fun recordMock(ctx: Context, atMs: Long) = prefs(ctx).edit().putLong("lastMock", atMs).apply()

    /** Reasons the current moment can't be trusted (empty = trustworthy). */
    fun problems(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<String> = buildList {
        if (!autoTimeOn(ctx)) add("phone time is set manually")
        if (clockChangedToday(ctx)) add("phone clock was changed today")
        if (nowMs - lastMockMs(ctx) < 30 * 60_000L) add("fake-GPS app used recently")
    }

    fun stamp(ctx: Context): String = problems(ctx).let { if (it.isEmpty()) VERIFIED else NOT_VERIFIED + it.joinToString(", ") }

    /** Remember the wall-clock vs. uptime offset so a manual clock change can be measured. */
    fun saveClockBaseline(ctx: Context) =
        prefs(ctx).edit().putLong("offset", System.currentTimeMillis() - SystemClock.elapsedRealtime()).apply()

    internal fun onTimeSet(ctx: Context) {
        val p = prefs(ctx)
        val newOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val old = p.getLong("offset", newOffset)
        val diffMin = (newOffset - old) / 60_000
        p.edit().putLong("offset", newOffset).apply()
        if (kotlin.math.abs(diffMin) >= 2 && !autoTimeOn(ctx)) {
            p.edit().putString("clockChangedDay", LocalDate.now().toString()).apply()
            AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING,
                "Phone clock changed by hand (${if (diffMin > 0) "+" else ""}$diffMin min)")
            AttendanceController.refresh()
        }
    }
}

/** Android tells us whenever the clock is set (by hand or by the network). */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED) TrustChecks.onTimeSet(context)
    }
}
