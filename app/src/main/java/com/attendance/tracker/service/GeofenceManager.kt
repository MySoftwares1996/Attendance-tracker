package com.attendance.tracker.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.permissions.PermissionsManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Second, independent detection path. Google Play services watches the office
 * and gate geofences at system level, so even if the phone kills the app the
 * system wakes it on entry/exit and the punch is still recorded.
 */
object GeofenceManager {
    private const val OFFICE_ID = "office"

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 77, Intent(ctx, GeofenceReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        val office = AttendanceController.settings.settings.value.office ?: return
        if (!PermissionsManager.hasPrecise(app) || !PermissionsManager.hasBackground(app)) return
        val fences = mutableListOf<Geofence>()
        fences += Geofence.Builder()
            .setRequestId(OFFICE_ID)
            .setCircularRegion(office.lat, office.lng, office.radiusM.coerceAtLeast(100.0).toFloat())
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setNotificationResponsiveness(0)
            .build()
        office.gates.take(90).forEach { g ->
            fences += Geofence.Builder()
                .setRequestId("gate:${g.id}")
                .setCircularRegion(g.lat, g.lng, g.radiusM.coerceAtLeast(80.0).toFloat())
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setNotificationResponsiveness(0)
                .build()
        }
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
        val client = LocationServices.getGeofencingClient(app)
        try {
            client.removeGeofences(pendingIntent(app)).addOnCompleteListener {
                try { client.addGeofences(req, pendingIntent(app)) } catch (_: SecurityException) { }
            }
        } catch (_: SecurityException) { }
    }

    fun unregister(ctx: Context) {
        runCatching { LocationServices.getGeofencingClient(ctx).removeGeofences(pendingIntent(ctx.applicationContext)) }
    }
}
