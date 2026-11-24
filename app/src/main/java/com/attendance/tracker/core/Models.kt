package com.attendance.tracker.core

import java.time.DayOfWeek

/** A named gate / entry point of the office premises. */
data class Gate(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** Radius (metres) around the gate that counts as "at the gate". */
    val radiusM: Double = 25.0,
)

/** Office premises: a centre point + radius (geofence) and its gates. */
data class OfficeConfig(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val gates: List<Gate> = emptyList(),
)

/** Working hours and lunch rules. Times are minutes since midnight (0..1439). */
data class WorkSchedule(
    val startMin: Int = 9 * 60,
    val endMin: Int = 18 * 60,
    val workDays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    ),
    val lunchStartMin: Int = 13 * 60,
    val lunchEndMin: Int = 14 * 60,
    val maxLunchMinutes: Int = 60,
    /** If true, lunch can only be declared inside the lunch window. */
    val restrictLunchToWindow: Boolean = false,
)

/** Filters that decide whether a GPS fix is trustworthy enough to use. */
data class AccuracyConfig(
    /** Fixes with a worse (larger) accuracy radius than this are ignored. */
    val maxAccuracyM: Float = 35f,
    /** Consecutive confident fixes needed before switching inside/outside. */
    val confirmSamples: Int = 3,
    /** Reject fixes flagged as coming from a mock-location app. */
    val rejectMock: Boolean = true,
    /** Fixes older than this are ignored. */
    val maxFixAgeSec: Int = 60,
)

data class LocationSample(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val timeMs: Long,
    val isMock: Boolean = false,
)

enum class Presence { UNKNOWN, INSIDE, OUTSIDE }

enum class WorkStatus(val label: String) {
    OUTSIDE_WORKING_HOURS("Outside working hours"),
    IN_OFFICE("In office"),
    OUTSIDE_OFFICE("Outside office (working hours)"),
    ON_LUNCH("On lunch break"),
    LUNCH_EXCEEDED("Lunch break exceeded"),
    UNKNOWN("Location unknown"),
}

data class LunchState(val active: Boolean = false, val startedAtMs: Long = 0L)
