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

    /** Average over days that have any worked time; null if there are none. */
    fun averageSec(dailySec: List<Long>): Long? {
        val worked = dailySec.filter { it > 0 }
        return if (worked.isEmpty()) null else worked.sum() / worked.size
    }
}
