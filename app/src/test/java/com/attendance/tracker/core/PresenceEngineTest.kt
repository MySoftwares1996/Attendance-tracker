package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEngineTest {
    private val gate = Gate("g1", "Main Gate", 12.97160, 77.59460, 20.0)
    // Office centre; 1e-4 deg latitude ≈ 11.1 m
    private val office = OfficeConfig("HQ", 12.97160, 77.59500, 100.0, listOf(gate))
    private val cfg = AccuracyConfig(maxAccuracyM = 30f, confirmSamples = 3)

    private fun at(dLatM: Double, acc: Float, t: Long, mock: Boolean = false) =
        LocationSample(office.lat + dLatM / 111_195.0, office.lng, acc, t, mock)

    @Test fun confirmsInsideAfterRequiredSamplesAndUsesFirstSampleTime() {
        val e = PresenceEngine()
        var last: PresenceEngine.Result? = null
        for (i in 0 until 3) last = e.process(at(10.0, 10f, 1000L * i), office, cfg, 1000L * i)
        val tr = (last as PresenceEngine.Result.Accepted).transition
        assertNotNull(tr)
        assertEquals(Presence.INSIDE, tr!!.to)
        assertEquals(0L, tr.atMs)
        assertEquals(Presence.INSIDE, e.presence)
    }

    @Test fun boundaryFixesAreAmbiguousAndDoNotFlip() {
        val e = PresenceEngine(Presence.INSIDE)
        // 110 m from centre with ±20 m accuracy: circle straddles the 100 m edge.
        repeat(10) { i ->
            val r = e.process(at(110.0, 20f, 1000L * i), office, cfg, 1000L * i) as PresenceEngine.Result.Accepted
            assertEquals(PresenceEngine.Zone.AMBIGUOUS, r.zone)
        }
        assertEquals(Presence.INSIDE, e.presence)
    }

    @Test fun singleOutlierDoesNotFlip() {
        val e = PresenceEngine(Presence.INSIDE)
        e.process(at(10.0, 10f, 0), office, cfg, 0)
        e.process(at(300.0, 10f, 30_000), office, cfg, 30_000)
        e.process(at(10.0, 10f, 60_000), office, cfg, 60_000)
        assertEquals(Presence.INSIDE, e.presence)
    }

    @Test fun rejectsLowAccuracyMockAndStale() {
        val e = PresenceEngine()
        assertTrue(e.process(at(0.0, 80f, 0), office, cfg, 0) is PresenceEngine.Result.Rejected)
        assertTrue(e.process(at(0.0, 5f, 0, mock = true), office, cfg, 0) is PresenceEngine.Result.Rejected)
        assertTrue(e.process(at(0.0, 5f, 0), office, cfg, 120_000) is PresenceEngine.Result.Rejected)
    }

    @Test fun rejectsTeleportJump() {
        val e = PresenceEngine()
        e.process(at(0.0, 5f, 0), office, cfg, 0)
        val r = e.process(at(5_000.0, 5f, 1_000), office, cfg, 1_000)
        assertTrue(r is PresenceEngine.Result.Rejected)
    }

    @Test fun exitIsAttributedToGate() {
        val e = PresenceEngine(Presence.INSIDE)
        var t = 0L
        // Walk to the gate (gate sits ~43 m west of centre; use its coordinates).
        e.process(LocationSample(gate.lat, gate.lng, 8f, t), office, cfg, t)
        var tr: PresenceEngine.Transition? = null
        for (i in 1..3) {
            t += 15_000
            val r = e.process(at(200.0, 8f, t), office, cfg, t) as PresenceEngine.Result.Accepted
            tr = r.transition ?: tr
        }
        assertNotNull(tr)
        assertEquals(Presence.OUTSIDE, tr!!.to)
        assertEquals("Main Gate", tr.gate?.name)
        // Leaving is timed at the last moment seen at the gate, not when GPS confirmed it.
        assertEquals(0L, tr.atMs)
        assertTrue(tr.timedByGate)
    }

    @Test fun entryIsTimedAtFirstGateSighting() {
        val e = PresenceEngine(Presence.OUTSIDE)
        var t = 0L
        e.process(at(500.0, 8f, t), office, cfg, t)                       // on the road
        t += 15_000; e.process(LocationSample(gate.lat, gate.lng, 8f, t), office, cfg, t)  // reach gate @15s
        val gateT = t
        t += 15_000; e.process(LocationSample(gate.lat, gate.lng, 8f, t), office, cfg, t)  // still at gate
        var tr: PresenceEngine.Transition? = null
        for (i in 1..3) {
            t += 15_000
            val r = e.process(at(0.0, 8f, t), office, cfg, t) as PresenceEngine.Result.Accepted
            tr = r.transition ?: tr
        }
        assertEquals(Presence.INSIDE, tr!!.to)
        assertEquals(gateT, tr.atMs)
        assertEquals("Main Gate", tr.gate?.name)
    }

    @Test fun gapBeforeTransitionIsReported() {
        val e = PresenceEngine(Presence.INSIDE)
        e.process(at(0.0, 8f, 0), office, cfg, 0)
        // Phone was silent for 40 minutes, next readings are outside.
        var t = 40 * 60_000L
        var tr: PresenceEngine.Transition? = null
        for (i in 1..3) {
            val r = e.process(at(400.0, 8f, t), office, cfg, t) as PresenceEngine.Result.Accepted
            tr = r.transition ?: tr
            t += 10_000
        }
        assertEquals(Presence.OUTSIDE, tr!!.to)
        assertEquals(40 * 60L, tr.gapBeforeSec)
        assertFalse(tr.timedByGate)
    }

    @Test fun everyInAndOutIsDetected() {
        // Simulate a day: in, out for lunch, in, out → exactly 4 alternating punches.
        val e = PresenceEngine(Presence.OUTSIDE)
        val punches = mutableListOf<Presence>()
        var t = 0L
        fun walk(dist: Double, n: Int) = repeat(n) {
            t += 10_000
            (e.process(at(dist, 8f, t), office, cfg, t) as? PresenceEngine.Result.Accepted)?.transition?.let { punches += it.to }
        }
        walk(600.0, 3); walk(300.0, 2); walk(150.0, 2); walk(20.0, 5)     // arrive
        walk(20.0, 20)                                                        // work
        walk(150.0, 2); walk(400.0, 5)                                        // leave for lunch
        walk(150.0, 2); walk(20.0, 5)                                         // come back
        walk(150.0, 2); walk(900.0, 5)                                        // go home
        assertEquals(listOf(Presence.INSIDE, Presence.OUTSIDE, Presence.INSIDE, Presence.OUTSIDE), punches)
    }

    @Test fun noOfficeConfiguredIsRejected() {
        assertTrue(PresenceEngine().process(at(0.0, 5f, 0), null, cfg, 0) is PresenceEngine.Result.Rejected)
    }

    @Test fun weightedMeanFavoursAccurateFix() {
        val m = GeoMath.weightedMean(listOf(
            LocationSample(10.0, 10.0, 2f, 0), LocationSample(10.001, 10.0, 20f, 0)
        ))!!
        assertTrue(m.lat < 10.00002)
        assertNull(GeoMath.weightedMean(emptyList()))
    }
}
