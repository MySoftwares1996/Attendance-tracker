package com.attendance.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.attendance.tracker.AttendanceController

/** Restarts tracking after reboot / app update if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (AttendanceController.settings.trackingEnabled && AttendanceController.hasFineLocation(context)) {
            runCatching { TrackingService.start(context) }
        }
    }
}
