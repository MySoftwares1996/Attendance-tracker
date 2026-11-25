package com.attendance.tracker.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Integrity rules for a backup file (format 2): the data travels as one text block with its
 * SHA-256 and row counts, so a damaged or cut-off file is rejected before anything changes.
 */
object BackupFormat {
    const val FORMAT = 2

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Wraps [data] (prefs, events, durations, marks) into the file layout. */
    fun wrap(data: JSONObject, createdAt: Long, appVersion: String): JSONObject {
        val text = data.toString()
        return JSONObject()
            .put("app", "attendance-tracker").put("format", FORMAT).put("createdAt", createdAt).put("appVersion", appVersion)
            .put("counts", JSONObject()
                .put("events", data.optJSONArray("events")?.length() ?: 0)
                .put("durations", data.optJSONArray("durations")?.length() ?: 0)
                .put("marks", data.optJSONArray("marks")?.length() ?: 0))
            .put("sha256", sha256(text))
            .put("dataText", text)
    }

    /** Returns the verified data block, or throws with a message for the user. */
    fun unwrap(file: JSONObject): JSONObject {
        check(file.optString("app") == "attendance-tracker") { "That isn't an Attendance Tracker backup." }
        check(file.optInt("format", 1) <= FORMAT) { "This backup is from a newer app version. Update the app first." }
        val text = file.getString("dataText")
        check(sha256(text) == file.getString("sha256")) { "Checksum mismatch: this backup file is damaged. Nothing was changed." }
        val data = JSONObject(text)
        val c = file.getJSONObject("counts")
        check(c.getInt("events") == (data.optJSONArray("events")?.length() ?: 0) &&
            c.getInt("durations") == (data.optJSONArray("durations")?.length() ?: 0) &&
            c.getInt("marks") == (data.optJSONArray("marks")?.length() ?: 0)) { "Backup is incomplete (row counts don't match). Nothing was changed." }
        return data
    }
}
