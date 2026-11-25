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
        // Punch channels: each has its own chime + vibration so IN and OUT feel different.
        val audio = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun punchChannel(id: String, name: String, sound: Int, vibration: LongArray) =
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Plays when attendance is recorded automatically"
                setSound(android.net.Uri.parse("android.resource://$packageName/$sound"), audio)
                enableVibration(true)
                vibrationPattern = vibration
                enableLights(true)
                lightColor = if (sound == R.raw.punch_in) 0xFF12B886.toInt() else 0xFFF0506E.toInt()
            }
        nm.createNotificationChannel(punchChannel(CHANNEL_PUNCH_IN, "Punch IN", R.raw.punch_in, longArrayOf(0, 120, 90, 120)))
        nm.createNotificationChannel(punchChannel(CHANNEL_PUNCH_OUT, "Punch OUT", R.raw.punch_out, longArrayOf(0, 450)))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Logout time, hours complete, lunch running over"
            }
        )
        // Early-leave alarm: alarm sound + strong vibration so it isn't missed at the gate.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EARLY_LEAVE, "Leaving early alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when you leave before your net hours are complete"
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
                enableLights(true); lightColor = 0xFFF0506E.toInt()
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PUNCH_SILENT, "Punches (silent)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false)
            }
        )
        // OpenStreetMap tile policy requires an identifying user agent; cache tiles in app storage.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
        AttendanceController.init(this)
        // Self-healing: every 15 min make sure tracking and geofences are alive.
        com.attendance.tracker.service.WatchdogWorker.schedule(this)
        com.attendance.tracker.backup.BackupManager.schedule(this)
        com.attendance.tracker.backup.BackupManager.watch(this)
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_PUNCH_IN = "punch_in_v1"
        const val CHANNEL_PUNCH_OUT = "punch_out_v1"
        const val CHANNEL_PUNCH_SILENT = "punch_silent_v1"
        const val CHANNEL_REMINDERS = "reminders_v1"
        const val CHANNEL_EARLY_LEAVE = "early_leave_v1"
    }
}
