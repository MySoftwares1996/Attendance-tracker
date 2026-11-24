package com.attendance.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles "Yes, lunch" / "No, work" on the auto-lunch notification. */
class LunchActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            YES -> AttendanceController.confirmPendingLunch()
            NO -> AttendanceController.dismissPendingLunch()
        }
    }

    companion object {
        const val YES = "com.attendance.tracker.LUNCH_YES"
        const val NO = "com.attendance.tracker.LUNCH_NO"
    }
}
