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
    SQLiteOpenHelper(context, "attendance.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertEvent(
        timeMs: Long, type: EventType, detail: String,
        lat: Double? = null, lng: Double? = null, acc: Float? = null,
    ) {
        val cv = ContentValues().apply {
            put("time_ms", timeMs); put("type", type.name); put("detail", detail)
            lat?.let { put("lat", it) }; lng?.let { put("lng", it) }; acc?.let { put("acc", it) }
        }
        writableDatabase.insert("events", null, cv)
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
