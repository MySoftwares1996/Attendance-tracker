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
