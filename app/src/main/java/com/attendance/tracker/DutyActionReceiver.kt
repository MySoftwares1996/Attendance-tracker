package com.attendance.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "On duty" / "Lunch or personal" on the notification shown when you leave during work hours. */
class DutyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ON_DUTY -> AttendanceController.confirmPendingDuty("Official work")
            PERSONAL -> AttendanceController.dismissPendingDuty()
        }
    }

    companion object {
        const val ON_DUTY = "com.attendance.tracker.DUTY_YES"
        const val PERSONAL = "com.attendance.tracker.DUTY_NO"
    }
}
