package com.attendance.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Buttons on alerts: undo a wrong punch, snooze a reminder, end lunch from the pill. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UNDO -> {
                val ok = AttendanceController.undoPunch(intent.getLongExtra(EXTRA_TIME, 0L), intent.getBooleanExtra(EXTRA_IN, true))
                Toast.makeText(context, if (ok) "Punch removed" else "Too late to undo \u2014 edit it in History", Toast.LENGTH_LONG).show()
            }
            SNOOZE -> {
                val id = intent.getIntExtra(EXTRA_ID, 0)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(id)
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<SnoozeWorker>().setInitialDelay(10, TimeUnit.MINUTES)
                        .setInputData(workDataOf("id" to id, "title" to intent.getStringExtra(EXTRA_TITLE), "text" to intent.getStringExtra(EXTRA_TEXT)))
                        .build())
            }
            END_LUNCH -> AttendanceController.endLunch()
        }
    }

    companion object {
        const val UNDO = "com.attendance.tracker.UNDO_PUNCH"
        const val SNOOZE = "com.attendance.tracker.SNOOZE"
        const val END_LUNCH = "com.attendance.tracker.END_LUNCH"
        const val EXTRA_TIME = "t"; const val EXTRA_IN = "in"
        const val EXTRA_ID = "id"; const val EXTRA_TITLE = "title"; const val EXTRA_TEXT = "text"
    }
}

/** Shows a snoozed reminder again. */
class SnoozeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val d = inputData
        AttendanceController.notify(d.getInt("id", 4200), d.getString("title") ?: "Reminder", d.getString("text") ?: "", snooze = true)
        return Result.success()
    }
}
