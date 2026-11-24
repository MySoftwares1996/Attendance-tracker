package com.attendance.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.data.EventType
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/** Woken by the system when you cross the office or a gate boundary. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // Location was switched off; geofences are cleared. The watchdog re-registers later.
                AttendanceController.db.insertEvent(System.currentTimeMillis(), EventType.TRACKING, "Geofence backup unavailable (location off)")
            }
            return
        }
        // Feed the triggering fix to the engine (it applies the same accuracy rules)…
        event.triggeringLocation?.let { AttendanceController.onLocation(it.toSample()) }
        if (!AttendanceController.settings.trackingEnabled) return
        // …and make sure the tracker is running in fast mode to confirm the crossing precisely.
        val fast = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        runCatching { TrackingService.start(context, burst = fast) }
    }
}
