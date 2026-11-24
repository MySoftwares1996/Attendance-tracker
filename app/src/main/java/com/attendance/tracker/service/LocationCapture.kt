package com.attendance.tracker.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.attendance.tracker.core.GeoMath
import com.attendance.tracker.core.LocationSample
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay

data class CaptureResult(val point: LocationSample, val samplesUsed: Int, val bestAccuracyM: Float)

/**
 * Captures a precise position for setting up the office centre or a gate:
 * collects GPS fixes for [durationMs] while the user stands still, keeps only
 * accurate, non-mock fixes, and returns their accuracy-weighted average.
 */
object LocationCapture {
    @SuppressLint("MissingPermission")
    suspend fun capture(
        context: Context,
        durationMs: Long = 20_000L,
        maxAccuracyM: Float = 20f,
        onProgress: (secondsLeft: Int, bestAccuracy: Float?) -> Unit = { _, _ -> },
    ): CaptureResult? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val samples = mutableListOf<LocationSample>()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { samples += it.toSample() }
            }
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        try {
            var left = (durationMs / 1000).toInt()
            while (left > 0) {
                onProgress(left, samples.filter { !it.isMock }.minOfOrNull { it.accuracyM })
                delay(1000)
                left--
            }
        } finally {
            client.removeLocationUpdates(cb)
        }
        val real = samples.filter { !it.isMock && it.accuracyM < Float.MAX_VALUE }
        if (real.isEmpty()) return null
        var good = real.filter { it.accuracyM <= maxAccuracyM }
        // Fall back to the best third if nothing met the threshold.
        if (good.isEmpty()) good = real.sortedBy { it.accuracyM }.take((real.size / 3).coerceAtLeast(1))
        val mean = GeoMath.weightedMean(good) ?: return null
        return CaptureResult(mean, good.size, good.minOf { it.accuracyM })
    }
}
