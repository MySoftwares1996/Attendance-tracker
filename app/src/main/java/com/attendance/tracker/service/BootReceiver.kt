package com.attendance.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.attendance.tracker.AttendanceController

/** Restarts tracking and re-arms geofences after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" && action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        WatchdogWorker.schedule(context)
        if (AttendanceController.settings.trackingEnabled && AttendanceController.hasFineLocation(context)) {
            GeofenceManager.register(context)
            runCatching { TrackingService.start(context) }
        }
    }
}
