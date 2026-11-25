package com.attendance.tracker.core

/** Worked time = total time inside the office, from the automatic IN / OUT punches. */
object WorkedTime {
    data class Punch(val timeMs: Long, val isIn: Boolean)

    /**
     * Sums every IN → OUT stay. An IN without a following OUT is closed at [closeOpenAtMs]
     * (now for today, or the day's last recorded moment for past days).
     * An OUT without an earlier IN (e.g. stayed past midnight) is ignored.
     */
    fun workedSec(punches: List<Punch>, closeOpenAtMs: Long): Long {
        var total = 0L
        var inAt: Long? = null
        // Same timestamp: OUT before IN, so back-to-back stays (e.g. on-duty right after an OUT) join cleanly.
        for (p in punches.sortedWith(compareBy<Punch>({ it.timeMs }, { if (it.isIn) 1 else 0 }))) {
            if (p.isIn) {
                if (inAt == null) inAt = p.timeMs
            } else {
                inAt?.let { total += p.timeMs - it }
                inAt = null
            }
        }
        inAt?.let { if (closeOpenAtMs > it) total += closeOpenAtMs - it }
        return total / 1000
    }

    /**
     * Net worked time. Lunch is always deducted: the allowance (e.g. 60 min) even if no lunch
     * was taken, or the actual lunch if it was longer.
     * Lunch time is already outside [officeSec], so only the unused part of the allowance is
     * taken off here.
     */
    fun netSec(officeSec: Long, dutySec: Long, lunchSec: Long, allowanceSec: Long): Long =
        (officeSec + dutySec - (allowanceSec - lunchSec).coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * Net while the day is still running: it counts up from the first minute in the office.
     * The lunch hour you haven't taken yet is only taken off once your time passes the target,
     * so the number never sits at 0 for the first hour, and it lands on exactly the target
     * at logout (target + lunch allowance). After logout time the plain [netSec] applies.
     */
    fun liveNetSec(officeSec: Long, dutySec: Long, lunchSec: Long, allowanceSec: Long, targetSec: Long,
                   deductNow: Boolean = false): Long {
        if (deductNow) return netSec(officeSec, dutySec, lunchSec, allowanceSec)
        val gross = officeSec + dutySec
        return maxOf(minOf(gross, targetSec), netSec(officeSec, dutySec, lunchSec, allowanceSec)).coerceAtLeast(0)
    }

    /**
     * When the unused part of the lunch allowance comes off during the day:
     * as soon as a lunch break has ended (e.g. 30 min taken → the other 30 min now),
     * or at [deductAtMin] (4 PM) if no lunch was taken at all.
     */
    fun deductLunchNow(lunchSec: Long, onLunch: Boolean, nowMinOfDay: Int, deductAtMin: Int): Boolean =
        (lunchSec > 0 && !onLunch) || nowMinOfDay >= deductAtMin

    /** Average over days that have any worked time; null if there are none. */
    fun averageSec(dailySec: List<Long>): Long? {
        val worked = dailySec.filter { it > 0 }
        return if (worked.isEmpty()) null else worked.sum() / worked.size
    }
}
