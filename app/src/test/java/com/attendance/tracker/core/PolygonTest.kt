package com.attendance.tracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonTest {
    // ~100 m x ~100 m square near Bengaluru (1e-3 deg lat ≈ 111 m; lng ≈ 108 m at 12.97°)
    private val lat0 = 12.9700; private val lng0 = 77.5900
    private val sq = listOf(
        LatLng(lat0, lng0), LatLng(lat0, lng0 + 0.000925), LatLng(lat0 + 0.0009, lng0 + 0.000925), LatLng(lat0 + 0.0009, lng0),
    )
    private val office = OfficeConfig.fromPolygon("HQ", sq, emptyList())
    private val cfg = AccuracyConfig(maxAccuracyM = 30f, confirmSamples = 3)

    @Test fun signedDistance() {
        val c = GeoMath.centroid(sq)
        val inside = GeoMath.signedPolygonDistanceM(c.lat, c.lng, sq)
        assertTrue("centre should be ~-50 m, was $inside", inside < -45 && inside > -55)
        val out = GeoMath.signedPolygonDistanceM(lat0 - 0.00045, c.lng, sq) // ~50 m south
        assertTrue("outside should be ~+50 m, was $out", out > 45 && out < 55)
        assertTrue(GeoMath.areaM2(sq) in 9_000.0..11_000.0)
    }

    @Test fun concaveLShapeHandled() {
        // L-shaped compound: the notch must count as outside.
        val l = listOf(
            LatLng(lat0, lng0), LatLng(lat0, lng0 + 0.002), LatLng(lat0 + 0.001, lng0 + 0.002),
            LatLng(lat0 + 0.001, lng0 + 0.001), LatLng(lat0 + 0.002, lng0 + 0.001), LatLng(lat0 + 0.002, lng0),
        )
        assertTrue(GeoMath.signedPolygonDistanceM(lat0 + 0.0005, lng0 + 0.0005, l) < 0)    // in the L
        assertTrue(GeoMath.signedPolygonDistanceM(lat0 + 0.0016, lng0 + 0.0016, l) > 0)    // in the notch
    }

    @Test fun engineUsesDrawnArea() {
        val e = PresenceEngine()
        val c = GeoMath.centroid(sq)
        var tr: PresenceEngine.Transition? = null
        for (i in 0 until 3) {
            val r = e.process(LocationSample(c.lat, c.lng, 10f, 1000L * i), office, cfg, 1000L * i) as PresenceEngine.Result.Accepted
            tr = r.transition ?: tr
        }
        assertEquals(Presence.INSIDE, tr?.to)
        // 10 m inside the edge with ±20 m accuracy is ambiguous: no flip.
        val nearEdge = e.process(LocationSample(lat0 + 0.00009, c.lng, 20f, 5000), office, cfg, 5000) as PresenceEngine.Result.Accepted
        assertEquals(PresenceEngine.Zone.AMBIGUOUS, nearEdge.zone)
    }
}
