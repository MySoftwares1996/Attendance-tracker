package com.attendance.tracker.core

import kotlin.math.hypot

/** Turns a hand-drawn outline into a clean polygon with a few straight edges. */
object Simplify {
    data class P(val x: Double, val y: Double)

    /** Ramer–Douglas–Peucker: keeps the points that matter for the shape. */
    fun douglasPeucker(pts: List<P>, epsilon: Double): List<P> {
        if (pts.size < 3) return pts
        var maxD = 0.0; var idx = 0
        val a = pts.first(); val b = pts.last()
        for (i in 1 until pts.lastIndex) {
            val d = distToSegment(pts[i], a, b)
            if (d > maxD) { maxD = d; idx = i }
        }
        return if (maxD > epsilon) {
            douglasPeucker(pts.subList(0, idx + 1), epsilon).dropLast(1) + douglasPeucker(pts.subList(idx, pts.size), epsilon)
        } else listOf(a, b)
    }

    /**
     * Simplifies a closed hand-drawn loop to between 3 and [maxCorners] corners.
     * [startEpsilon] is in the same units as the points (e.g. screen pixels).
     */
    fun outline(raw: List<P>, startEpsilon: Double, maxCorners: Int = 12): List<P> {
        // drop jitter: points closer than 1/3 epsilon to the previous kept one
        val pts = mutableListOf<P>()
        raw.forEach { p -> if (pts.isEmpty() || hypot(p.x - pts.last().x, p.y - pts.last().y) >= startEpsilon / 3) pts += p }
        if (pts.size < 3) return emptyList()
        // close the loop, simplify, then drop the duplicated closing point
        val closed = pts + pts.first()
        var eps = startEpsilon
        var out: List<P>
        do {
            out = douglasPeucker(closed, eps).dropLast(1)
            eps *= 1.4
        } while (out.size > maxCorners)
        return if (out.size >= 3) out else emptyList()
    }

    private fun distToSegment(p: P, a: P, b: P): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
