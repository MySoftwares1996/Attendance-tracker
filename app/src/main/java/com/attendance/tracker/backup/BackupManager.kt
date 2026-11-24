package com.attendance.tracker.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.attendance.tracker.AttendanceController
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Backup = settings (office map, gates, hours…) + the whole attendance log, as one JSON file.
 * Saved nightly to a file the user picked (e.g. on Google Drive) and restorable on any phone.
 */
object BackupManager {
    private const val PREFS = "attendance_settings"
    private const val META = "backup_meta"

    fun backupUri(ctx: Context): Uri? = ctx.getSharedPreferences(META, Context.MODE_PRIVATE).getString("uri", null)?.let(Uri::parse)
    fun lastBackupMs(ctx: Context): Long = ctx.getSharedPreferences(META, Context.MODE_PRIVATE).getLong("last", 0L)

    /** Remember the chosen file (persisting access) and back up right away. */
    fun setBackupUri(ctx: Context, uri: Uri): Boolean {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        ctx.getSharedPreferences(META, Context.MODE_PRIVATE).edit().putString("uri", uri.toString()).apply()
        schedule(ctx)
        return backupNow(ctx)
    }

    fun backupNow(ctx: Context): Boolean {
        val uri = backupUri(ctx) ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export(ctx).toString().toByteArray()) }
            ctx.getSharedPreferences(META, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
        }.isSuccess
    }

    fun export(ctx: Context): JSONObject {
        val prefs = JSONObject()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                else -> null
            }
            if (e != null) prefs.put(k, e)
        }
        val db = AttendanceController.db
        return JSONObject()
            .put("app", "attendance-tracker").put("format", 1).put("createdAt", System.currentTimeMillis())
            .put("settings", prefs)
            .put("events", JSONArray(db.dumpEvents().map { JSONArray(it) }))
            .put("durations", JSONArray(db.dumpDurations().map { JSONArray(it) }))
            .put("marks", JSONArray(db.dumpMarks().map { JSONArray(it) }))
    }

    /** Replaces everything with the backup's content. Returns an error message or null. */
    fun restore(ctx: Context, uri: Uri): String? {
        val json = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { JSONObject(it.readBytes().decodeToString()) }
        }.getOrElse { return "Couldn't read that file." }
        if (json.optString("app") != "attendance-tracker") return "That isn't an Attendance Tracker backup."
        return runCatching {
            fun rows(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) } } ?: emptyList()
            AttendanceController.db.restoreAll(rows("events"), rows("durations"), rows("marks"))
            val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            val s = json.optJSONObject("settings") ?: JSONObject()
            s.keys().forEach { k ->
                val e = s.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                }
            }
            ed.commit()
            AttendanceController.reload()
            null
        }.getOrElse { "Restore failed: ${it.message}" }
    }

    fun schedule(ctx: Context) {
        if (backupUri(ctx) == null) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "nightly-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build(),
        )
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (BackupManager.backupNow(applicationContext)) Result.success() else Result.retry()
}
