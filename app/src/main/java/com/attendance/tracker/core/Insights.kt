package com.attendance.tracker.core

import kotlin.math.roundToLong

/** Pure calculations behind the Insights tab (unit-tested). */
object Insights {

    /**
     * Current and best streak of working days that reached the target.
     * [days] newest first; value = true (met), false (missed), null (not a working day → skipped).
     */
    fun streaks(days: List<Boolean?>): Pair<Int, Int> {
        var current = 0; var counting = true
        var best = 0; var run = 0
        for (d in days) {
            if (d == null) continue
            if (d) { run++; if (counting) current++ } else { counting = false; run = 0 }
            best = maxOf(best, run)
        }
        return current to best
    }

    /** Average of minutes-after-midnight values, or null. */
    fun avgMinutes(values: List<Int>): Int? = if (values.isEmpty()) null else values.sum() / values.size

    /**
     * Morning commute: from the last time you left home before your first IN that day.
     * Returns minutes, or null when it doesn't look like a commute (none, or longer than 3 h).
     */
    fun commuteMinutes(leftHomeMs: List<Long>, firstInMs: Long): Long? {
        val left = leftHomeMs.filter { it < firstInMs }.maxOrNull() ?: return null
        val min = (firstInMs - left) / 60_000
        return if (min in 1..180) min else null
    }

    /**
     * Best time to leave home: groups trips by departure time in [bucketMin]-minute buckets
     * and returns the bucket start with the shortest average trip (needs ≥ 2 trips in a bucket).
     * Each sample is (departure minutes-after-midnight, trip minutes).
     */
    fun bestDeparture(samples: List<Pair<Int, Long>>, bucketMin: Int = 15): Pair<Int, Long>? =
        samples.groupBy { it.first / bucketMin * bucketMin }
            .filterValues { it.size >= 2 }
            .mapValues { (_, v) -> v.map { it.second }.average() }
            .minByOrNull { it.value }
            ?.let { it.key to it.value.roundToLong() }

    /**
     * Parses a pasted holiday list, one per line: "2027-01-14 Pongal", "14-01-2027 Pongal",
     * "14/01/2027, Pongal" or "14 Jan 2027 Pongal". Unreadable lines are skipped.
     */
    fun parseHolidays(text: String): List<Pair<java.time.LocalDate, String>> {
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val iso = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})[\s,;:-]*(.*)$""")
        val dmy = Regex("""^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})[\s,;:-]*(.*)$""")
        val dmonY = Regex("""^(\d{1,2})\s+([A-Za-z]{3,})\s+(\d{4})[\s,;:-]*(.*)$""")
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            runCatching {
                iso.find(line)?.let { m -> val (y, mo, d, n) = m.destructured; return@runCatching java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) to n.trim() }
                dmy.find(line)?.let { m -> val (d, mo, y, n) = m.destructured; return@runCatching java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) to n.trim() }
                dmonY.find(line)?.let { m ->
                    val (d, mon, y, n) = m.destructured
                    val mi = months.indexOf(mon.take(3).lowercase()); if (mi < 0) return@runCatching null
                    return@runCatching java.time.LocalDate.of(y.toInt(), mi + 1, d.toInt()) to n.trim()
                }
                null
            }.getOrNull()?.let { (d, n) -> d to n.ifBlank { "Holiday" } }
        }
    }
}
