package com.attendance.tracker.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle (haversine) distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun centroid(poly: List<LatLng>): LatLng {
        // Area-weighted centroid on a local flat projection (accurate for office-sized areas).
        val o = poly.first()
        val pts = poly.map { project(it.lat, it.lng, o.lat, o.lng) }
        var a = 0.0; var cx = 0.0; var cy = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]; val (x2, y2) = pts[(i + 1) % pts.size]
            val cross = x1 * y2 - x2 * y1
            a += cross; cx += (x1 + x2) * cross; cy += (y1 + y2) * cross
        }
        if (kotlin.math.abs(a) < 1e-6) return LatLng(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lng } / poly.size)
        a *= 0.5
        return unproject(cx / (6 * a), cy / (6 * a), o.lat, o.lng)
    }

    /**
     * Signed distance in metres from a point to a polygon boundary:
     * negative when inside, positive when outside.
     */
    fun signedPolygonDistanceM(lat: Double, lng: Double, poly: List<LatLng>): Double {
        val pts = poly.map { project(it.lat, it.lng, lat, lng) } // point itself is the origin (0,0)
        var inside = false
        var minD = Double.MAX_VALUE
        for (i in pts.indices) {
            val (x1, y1) = pts[i]; val (x2, y2) = pts[(i + 1) % pts.size]
            if ((y1 > 0) != (y2 > 0) && 0.0 < (x2 - x1) * (0 - y1) / (y2 - y1) + x1) inside = !inside
            minD = minOf(minD, segmentDistance(x1, y1, x2, y2))
        }
        return if (inside) -minD else minD
    }

    /** The closest point on the polygon's outline to (lat, lng), and how far away it is in metres. */
    fun nearestPointOnPolygon(lat: Double, lng: Double, poly: List<LatLng>): Pair<LatLng, Double> {
        val pts = poly.map { project(it.lat, it.lng, lat, lng) }
        var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
        for (i in pts.indices) {
            val (x1, y1) = pts[i]; val (x2, y2) = pts[(i + 1) % pts.size]
            val dx = x2 - x1; val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((-x1) * dx + (-y1) * dy) / len2).coerceIn(0.0, 1.0)
            val px = x1 + t * dx; val py = y1 + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) { best = d; bx = px; by = py }
        }
        return unproject(bx, by, lat, lng) to best
    }

    private fun segmentDistance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-x1) * dx + (-y1) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val px = x1 + tc * dx; val py = y1 + tc * dy
        return sqrt(px * px + py * py)
    }

    private fun project(lat: Double, lng: Double, lat0: Double, lng0: Double): Pair<Double, Double> {
        val x = Math.toRadians(lng - lng0) * EARTH_RADIUS_M * cos(Math.toRadians(lat0))
        val y = Math.toRadians(lat - lat0) * EARTH_RADIUS_M
        return x to y
    }

    private fun unproject(x: Double, y: Double, lat0: Double, lng0: Double): LatLng =
        LatLng(lat0 + Math.toDegrees(y / EARTH_RADIUS_M), lng0 + Math.toDegrees(x / (EARTH_RADIUS_M * cos(Math.toRadians(lat0)))))

    /** Area of a polygon in square metres. */
    fun areaM2(poly: List<LatLng>): Double {
        if (poly.size < 3) return 0.0
        val o = poly.first()
        val pts = poly.map { project(it.lat, it.lng, o.lat, o.lng) }
        var a = 0.0
        for (i in pts.indices) { val (x1, y1) = pts[i]; val (x2, y2) = pts[(i + 1) % pts.size]; a += x1 * y2 - x2 * y1 }
        return kotlin.math.abs(a) / 2
    }

    /**
     * Accuracy-weighted mean of several fixes (weight = 1/accuracy²).
     * Used when capturing the office centre / gate positions so one noisy
     * fix does not define the geofence.
     */
    fun weightedMean(samples: List<LocationSample>): LocationSample? {
        if (samples.isEmpty()) return null
        var wSum = 0.0; var lat = 0.0; var lng = 0.0
        for (s in samples) {
            val acc = s.accuracyM.coerceAtLeast(1f).toDouble()
            val w = 1.0 / (acc * acc)
            wSum += w; lat += s.lat * w; lng += s.lng * w
        }
        val mean = LocationSample(lat / wSum, lng / wSum, 0f, samples.maxOf { it.timeMs })
        // Combined uncertainty ≈ 1/sqrt(sum of weights), never better than 1 m.
        val combined = (1.0 / sqrt(wSum)).coerceAtLeast(1.0).toFloat()
        return mean.copy(accuracyM = combined)
    }
}
