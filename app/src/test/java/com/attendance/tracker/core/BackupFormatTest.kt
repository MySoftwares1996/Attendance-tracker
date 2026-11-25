package com.attendance.tracker.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private fun data() = JSONObject()
        .put("prefs", JSONObject().put("attendance_settings", JSONObject().put("netMin", JSONObject().put("t", "i").put("v", 510))))
        .put("events", JSONArray().put(JSONArray().put(1L).put("CHECK_IN").put("Entered").put(17.0).put(78.0).put(8.0)))
        .put("durations", JSONArray())
        .put("marks", JSONArray().put(JSONArray().put("2026-11-05").put("ABSENT").put(JSONObject.NULL)))

    @Test fun roundTripKeepsEverything() {
        val file = BackupFormat.wrap(data(), 123L, "1.0.0")
        val back = BackupFormat.unwrap(JSONObject(file.toString()))   // through text, like a real file
        assertEquals(data().toString(), back.toString())
    }

    @Test fun damagedFileIsRejected() {
        val file = BackupFormat.wrap(data(), 123L, "1.0.0")
        file.put("dataText", file.getString("dataText").replace("CHECK_IN", "CHECK_OUT"))
        val e = runCatching { BackupFormat.unwrap(file) }.exceptionOrNull()
        assertTrue(e?.message!!.contains("Checksum"))
    }

    @Test fun wrongCountsAreRejected() {
        val file = BackupFormat.wrap(data(), 123L, "1.0.0")
        file.getJSONObject("counts").put("events", 5)
        assertTrue(runCatching { BackupFormat.unwrap(file) }.exceptionOrNull()!!.message!!.contains("incomplete"))
    }

    @Test fun otherFilesAreRejected() {
        assertTrue(runCatching { BackupFormat.unwrap(JSONObject().put("app", "x")) }.isFailure)
    }
}
