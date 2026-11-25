package com.attendance.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class EventType(val label: String) {
    CHECK_IN("Entered office"),
    CHECK_OUT("Left office"),
    LUNCH_START("Lunch started"),
    LUNCH_END("Lunch ended"),
    STATUS("Status changed"),
    TRACKING("Tracking"),
    DUTY_START("On duty"),
    DUTY_END("Back from duty"),
    LEFT_HOME("Left home"),
    REACHED_HOME("Reached home"),
}

data class AttendanceEvent(
    val id: Long,
    val timeMs: Long,
    val type: EventType,
    val detail: String,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Float?,
)

/** Plain SQLite storage for the attendance log and per-day status durations. */
class AttendanceDb(context: Context) :
    SQLiteOpenHelper(context, "attendance.db", null, 2) {

    private val appContext: Context = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time_ms INTEGER NOT NULL,
                type TEXT NOT NULL,
                detail TEXT NOT NULL,
                lat REAL, lng REAL, acc REAL)"""
        )
        db.execSQL("CREATE INDEX idx_events_time ON events(time_ms)")
        db.execSQL(
            """CREATE TABLE durations(
                day TEXT NOT NULL,
                status TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                PRIMARY KEY(day, status))"""
        )
        createDayMarks(db)
    }

    private fun createDayMarks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS day_marks(day TEXT PRIMARY KEY, mark TEXT NOT NULL, note TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDayMarks(db)
    }

    // ---- day marks (leave / holiday / WFH / half day) ----
    fun dayMark(day: String): com.attendance.tracker.core.DayMark? =
        readableDatabase.rawQuery("SELECT mark FROM day_marks WHERE day=?", arrayOf(day)).use { c ->
            if (c.moveToFirst()) runCatching { com.attendance.tracker.core.DayMark.valueOf(c.getString(0)) }.getOrNull() else null
        }

    fun dayMarks(fromDay: String, toDay: String): Map<String, com.attendance.tracker.core.DayMark> {
        val out = mutableMapOf<String, com.attendance.tracker.core.DayMark>()
        readableDatabase.rawQuery("SELECT day, mark FROM day_marks WHERE day>=? AND day<=?", arrayOf(fromDay, toDay)).use { c ->
            while (c.moveToNext()) runCatching { out[c.getString(0)] = com.attendance.tracker.core.DayMark.valueOf(c.getString(1)) }
        }
        return out
    }

    fun setDayMark(day: String, mark: com.attendance.tracker.core.DayMark?) {
        com.attendance.tracker.backup.BackupManager.backupSoon(appContext)
        if (mark == null) writableDatabase.delete("day_marks", "day=?", arrayOf(day))
        else writableDatabase.insertWithOnConflict("day_marks", null,
            ContentValues().apply { put("day", day); put("mark", mark.name) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- reset ----
    /** Punch count and number of days with records before [beforeMs] (or all when null). */
    fun recordStats(beforeMs: Long?): Pair<Int, Int> {
        val where = if (beforeMs != null) "WHERE time_ms < $beforeMs" else ""
        val punches = readableDatabase.rawQuery("SELECT COUNT(*) FROM events $where ${if (where.isEmpty()) "WHERE" else "AND"} type IN ('CHECK_IN','CHECK_OUT')", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val days = readableDatabase.rawQuery("SELECT COUNT(DISTINCT date(time_ms/1000,'unixepoch','localtime')) FROM events $where", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return punches to days
    }

    /** Deletes attendance records before [beforeMs] / [beforeDay] (yyyy-MM-dd), or everything when null. */
    fun clearRecords(beforeMs: Long?, beforeDay: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (beforeMs == null || beforeDay == null) {
                db.delete("events", null, null); db.delete("durations", null, null); db.delete("day_marks", null, null)
            } else {
                db.delete("events", "time_ms < ?", arrayOf(beforeMs.toString()))
                db.delete("durations", "day < ?", arrayOf(beforeDay))
                db.delete("day_marks", "day < ?", arrayOf(beforeDay))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- backup / restore ----
    fun dumpEvents(): List<List<Any?>> = readableDatabase.rawQuery("SELECT time_ms,type,detail,lat,lng,acc FROM events ORDER BY time_ms", null).use { c ->
        buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getString(1), c.getString(2),
            if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4), if (c.isNull(5)) null else c.getDouble(5))) }
    }
    fun dumpDurations(): List<List<Any?>> = readableDatabase.rawQuery("SELECT day,status,seconds FROM durations", null).use { c ->
        buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getLong(2))) }
    }
    fun dumpMarks(): List<List<Any?>> = readableDatabase.rawQuery("SELECT day,mark,note FROM day_marks", null).use { c ->
        buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))) }
    }

    /** Adds imported events and marks in one all-or-nothing transaction. */
    fun importBatch(events: List<Triple<Long, EventType, String>>, marks: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            events.forEach { (t, type, detail) ->
                check(db.insert("events", null, ContentValues().apply {
                    put("time_ms", t); put("type", type.name); put("detail", detail)
                }) >= 0) { "insert failed" }
            }
            marks.forEach { (day, mark) ->
                db.insertWithOnConflict("day_marks", null, ContentValues().apply { put("day", day); put("mark", mark) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Row counts of every table: used to verify a backup / restore is complete. */
    fun counts(): Map<String, Int> = listOf("events", "durations", "day_marks").associateWith { t ->
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun restoreAll(events: List<org.json.JSONArray>, durations: List<org.json.JSONArray>, marks: List<org.json.JSONArray>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", null, null); db.delete("durations", null, null); db.delete("day_marks", null, null)
            events.forEach { r ->
                db.insert("events", null, ContentValues().apply {
                    put("time_ms", r.getLong(0)); put("type", r.getString(1)); put("detail", r.getString(2))
                    if (!r.isNull(3)) put("lat", r.getDouble(3)); if (!r.isNull(4)) put("lng", r.getDouble(4)); if (!r.isNull(5)) put("acc", r.getDouble(5))
                })
            }
            durations.forEach { r -> db.insert("durations", null, ContentValues().apply { put("day", r.getString(0)); put("status", r.getString(1)); put("seconds", r.getLong(2)) }) }
            marks.forEach { r -> db.insert("day_marks", null, ContentValues().apply {
                put("day", r.getString(0)); put("mark", r.getString(1))
                if (r.length() > 2 && !r.isNull(2)) put("note", r.getString(2))
            }) }
            // All or nothing: any row that failed to insert rolls the whole restore back.
            val got = listOf("events", "durations", "day_marks").map { t ->
                db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            }
            check(got == listOf(events.size, durations.size, marks.size)) { "row count mismatch $got" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteEvent(id: Long) {
        writableDatabase.delete("events", "id=?", arrayOf(id.toString()))
    }

    fun insertEvent(
        timeMs: Long, type: EventType, detail: String,
        lat: Double? = null, lng: Double? = null, acc: Float? = null,
    ) {
        val cv = ContentValues().apply {
            put("time_ms", timeMs); put("type", type.name); put("detail", detail)
            lat?.let { put("lat", it) }; lng?.let { put("lng", it) }; acc?.let { put("acc", it) }
        }
        writableDatabase.insert("events", null, cv)
        if (type != EventType.STATUS && type != EventType.TRACKING) com.attendance.tracker.backup.BackupManager.backupSoon(appContext)
    }

    fun events(fromMs: Long, toMs: Long): List<AttendanceEvent> {
        val out = mutableListOf<AttendanceEvent>()
        readableDatabase.rawQuery(
            "SELECT id,time_ms,type,detail,lat,lng,acc FROM events WHERE time_ms>=? AND time_ms<? ORDER BY time_ms",
            arrayOf(fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { EventType.valueOf(c.getString(2)) }.getOrNull() ?: continue
                out += AttendanceEvent(
                    c.getLong(0), c.getLong(1), type, c.getString(3),
                    if (c.isNull(4)) null else c.getDouble(4),
                    if (c.isNull(5)) null else c.getDouble(5),
                    if (c.isNull(6)) null else c.getFloat(6),
                )
            }
        }
        return out
    }

    /** Adds [seconds] to the running total of [status] for [day] (yyyy-MM-dd). */
    fun addDuration(day: String, status: String, seconds: Long) {
        if (seconds <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE durations SET seconds = seconds + ? WHERE day=? AND status=?",
                arrayOf<Any>(seconds, day, status)
            )
            db.rawQuery("SELECT changes()", null).use { c ->
                if (c.moveToFirst() && c.getInt(0) == 0) {
                    db.insert("durations", null, ContentValues().apply {
                        put("day", day); put("status", status); put("seconds", seconds)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun durations(day: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT status, seconds FROM durations WHERE day=?", arrayOf(day))
            .use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) }
        return out
    }
}
