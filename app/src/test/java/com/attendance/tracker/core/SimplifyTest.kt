package com.attendance.tracker.core

import com.attendance.tracker.core.Simplify.P
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SimplifyTest {
    /** A shaky hand-drawn square (200 px sides) becomes ~4 corners. */
    @Test fun shakySquareBecomesFourCorners() {
        val raw = mutableListOf<P>()
        val r = java.util.Random(7)
        fun side(x0: Double, y0: Double, x1: Double, y1: Double) {
            for (i in 0 until 40) {
                val t = i / 40.0
                raw += P(x0 + (x1 - x0) * t + r.nextGaussian() * 2, y0 + (y1 - y0) * t + r.nextGaussian() * 2)
            }
        }
        side(0.0, 0.0, 200.0, 0.0); side(200.0, 0.0, 200.0, 200.0); side(200.0, 200.0, 0.0, 200.0); side(0.0, 200.0, 0.0, 0.0)
        val out = Simplify.outline(raw, 12.0)
        assertTrue("got ${out.size} corners", out.size in 4..5)
    }

    @Test fun roundBlobIsCapped() {
        val raw = (0 until 360).map { a -> P(300 * cos(Math.toRadians(a.toDouble())), 300 * sin(Math.toRadians(a.toDouble()))) }
        val out = Simplify.outline(raw, 4.0, maxCorners = 12)
        assertTrue(out.size in 3..12)
    }

    @Test fun tapIsIgnored() {
        assertTrue(Simplify.outline(listOf(P(1.0, 1.0), P(2.0, 2.0)), 12.0).isEmpty())
    }
}
