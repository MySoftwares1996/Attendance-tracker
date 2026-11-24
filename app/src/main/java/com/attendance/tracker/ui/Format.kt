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

/** Two-stop gradient for the status hero card. Soft, pastel-leaning tones. */
fun statusGradient(s: WorkStatus): Pair<Color, Color> = when (s) {
    WorkStatus.IN_OFFICE -> Color(0xFF34D3A0) to Color(0xFF12A97A)
    WorkStatus.OUTSIDE_OFFICE -> Color(0xFFFF8BA0) to Color(0xFFEF4F6E)
    WorkStatus.ON_LUNCH -> Color(0xFFFFC86B) to Color(0xFFF59F00)
    WorkStatus.LUNCH_EXCEEDED -> Color(0xFFF79AC8) to Color(0xFFD9468F)
    WorkStatus.OUTSIDE_WORKING_HOURS -> Color(0xFF8E9BFF) to Color(0xFF5B6CFF)
    WorkStatus.UNKNOWN -> Color(0xFFB4A5FF) to Color(0xFF7C66F0)
}

fun statusColor(s: WorkStatus): Color = statusGradient(s).second

fun statusShort(s: WorkStatus): String = when (s) {
    WorkStatus.IN_OFFICE -> "At work"
    WorkStatus.OUTSIDE_OFFICE -> "Away"
    WorkStatus.ON_LUNCH -> "Lunch"
    WorkStatus.LUNCH_EXCEEDED -> "Lunch over"
    WorkStatus.OUTSIDE_WORKING_HOURS -> "Off duty"
    WorkStatus.UNKNOWN -> "Locating"
}
