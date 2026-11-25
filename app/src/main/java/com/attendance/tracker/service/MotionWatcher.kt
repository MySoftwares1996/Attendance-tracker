package com.attendance.tracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.attendance.tracker.AttendanceController
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Walking detection: when you start walking (e.g. toward the gate for lunch or to go home),
 * GPS switches to its fast 5-second mode at once instead of waiting for a fix near the gate.
 */
object MotionWatcher {
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 90, Intent(ctx, MotionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!permitted(ctx)) return
        val transitions = listOf(DetectedActivity.WALKING, DetectedActivity.ON_FOOT, DetectedActivity.RUNNING).map {
            ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        } + ActivityTransition.Builder().setActivityType(DetectedActivity.STILL)
            .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
        runCatching {
            ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(ctx))
        }
    }
}

class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val moving = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty().any {
            (it.activityType != DetectedActivity.STILL && it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) ||
                (it.activityType == DetectedActivity.STILL && it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT)
        }
        if (!moving) return
        val s = AttendanceController.settings
        if (!s.trackingEnabled || !AttendanceController.highAccuracyWanted()) return
        // Only near the office: inside it, or within ~500 m outside.
        val st = AttendanceController.state.value
        val near = st.presence == com.attendance.tracker.core.Presence.INSIDE || (st.edgeDistanceM ?: 9_999.0) < 500.0
        if (near) runCatching { TrackingService.start(context, burst = true) }
    }
}
