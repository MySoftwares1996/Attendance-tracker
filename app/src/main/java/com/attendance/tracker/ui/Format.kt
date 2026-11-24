package com.attendance.tracker.ui

import androidx.compose.ui.graphics.Color
import com.attendance.tracker.core.WorkStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val timeSecFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

fun fmtTime(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt) } ?: "—"

fun fmtTimeSec(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeSecFmt) } ?: "—"

fun fmtDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun statusColor(s: WorkStatus): Color = when (s) {
    WorkStatus.IN_OFFICE -> Color(0xFF2E7D32)
    WorkStatus.OUTSIDE_OFFICE -> Color(0xFFC62828)
    WorkStatus.ON_LUNCH -> Color(0xFFEF6C00)
    WorkStatus.LUNCH_EXCEEDED -> Color(0xFFAD1457)
    WorkStatus.OUTSIDE_WORKING_HOURS -> Color(0xFF546E7A)
    WorkStatus.UNKNOWN -> Color(0xFF6D4C41)
}
