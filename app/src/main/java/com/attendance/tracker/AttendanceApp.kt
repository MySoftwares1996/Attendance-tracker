package com.attendance.tracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class AttendanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Attendance tracking", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Attendance alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        AttendanceController.init(this)
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
    }
}
