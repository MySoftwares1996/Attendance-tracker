package com.attendance.tracker.core

/** Splits a day into coloured segments for the live timeline bar. */
object DayTimeline {
    enum class Kind { OFFICE, LUNCH, DUTY, OUTSIDE }
    enum class Ev { IN, OUT, LUNCH_START, LUNCH_END, DUTY_START, DUTY_END }

    data class Event(val timeMs: Long, val ev: Ev)
    data class Segment(val startMs: Long, val endMs: Long, val kind: Kind) {
        val seconds: Long get() = (endMs - startMs) / 1000
    }

    /**
     * Segments from the first IN (or first duty) until [untilMs]. Lunch and duty take priority over
     * inside/outside. Adjacent segments of the same kind are merged.
     */
    fun segments(events: List<Event>, untilMs: Long): List<Segment> {
        val sorted = events.sortedWith(compareBy<Event>({ it.timeMs }, { it.ev.ordinal }))
        val startIdx = sorted.indexOfFirst { it.ev == Ev.IN || it.ev == Ev.DUTY_START }
        if (startIdx < 0) return emptyList()
        var inside = false; var lunch = false; var duty = false
        // apply any lunch/duty state that began before the first IN
        sorted.take(startIdx).forEach { apply(it.ev, { inside = it }, { lunch = it }, { duty = it }) }
        val out = mutableListOf<Segment>()
        var cursor = sorted[startIdx].timeMs
        fun kind() = when { lunch -> Kind.LUNCH; duty -> Kind.DUTY; inside -> Kind.OFFICE; else -> Kind.OUTSIDE }
        for (e in sorted.drop(startIdx)) {
            if (e.timeMs > untilMs) break
            if (e.timeMs > cursor) push(out, Segment(cursor, e.timeMs, kind()))
            cursor = maxOf(cursor, e.timeMs)
            apply(e.ev, { inside = it }, { lunch = it }, { duty = it })
        }
        if (untilMs > cursor) push(out, Segment(cursor, untilMs, kind()))
        return out
    }

    fun totals(segs: List<Segment>): Map<Kind, Long> =
        segs.groupBy { it.kind }.mapValues { (_, v) -> v.sumOf { it.seconds } }

    private inline fun apply(ev: Ev, setIn: (Boolean) -> Unit, setLunch: (Boolean) -> Unit, setDuty: (Boolean) -> Unit) {
        when (ev) {
            Ev.IN -> setIn(true); Ev.OUT -> setIn(false)
            Ev.LUNCH_START -> setLunch(true); Ev.LUNCH_END -> setLunch(false)
            Ev.DUTY_START -> setDuty(true); Ev.DUTY_END -> setDuty(false)
        }
    }

    private fun push(out: MutableList<Segment>, s: Segment) {
        val last = out.lastOrNull()
        if (last != null && last.kind == s.kind && last.endMs == s.startMs) out[out.lastIndex] = last.copy(endMs = s.endMs)
        else out += s
    }
}
