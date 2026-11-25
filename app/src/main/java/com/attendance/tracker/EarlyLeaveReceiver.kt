package com.attendance.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Buttons on the "leaving early" alarm. */
class EarlyLeaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            USE_PERMISSION -> AttendanceController.usePermissionForEarlyLeave(intent.getIntExtra(EXTRA_MIN, 0))
            ON_DUTY -> AttendanceController.earlyLeaveOnDuty()
            DISMISS -> AttendanceController.dismissEarlyLeave()
            RECORD_IN -> AttendanceController.recordInNow()
        }
    }

    companion object {
        const val USE_PERMISSION = "com.attendance.tracker.EARLY_PERMISSION"
        const val ON_DUTY = "com.attendance.tracker.EARLY_DUTY"
        const val DISMISS = "com.attendance.tracker.EARLY_DISMISS"
        const val EXTRA_MIN = "min"
        const val RECORD_IN = "com.attendance.tracker.MORNING_IN"
    }
}
