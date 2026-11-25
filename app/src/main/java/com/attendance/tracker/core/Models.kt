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

data class LatLng(val lat: Double, val lng: Double)

/**
 * Office premises. If [polygon] has 3+ corners (drawn on the map) that exact
 * shape is the boundary; otherwise a circle of [radiusM] around [lat]/[lng].
 * For a polygon, [lat]/[lng] is its centre and [radiusM] reaches its farthest corner.
 */
data class OfficeConfig(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val gates: List<Gate> = emptyList(),
    val polygon: List<LatLng> = emptyList(),
) {
    val hasPolygon: Boolean get() = polygon.size >= 3

    /** Metres from the boundary: negative = inside, positive = outside. */
    fun edgeDistanceM(lat: Double, lng: Double): Double =
        if (hasPolygon) GeoMath.signedPolygonDistanceM(lat, lng, polygon)
        else GeoMath.distanceM(lat, lng, this.lat, this.lng) - radiusM

    /** Closest point on the office boundary (drawn outline or circle) and the distance to it in metres. */
    fun nearestOnBoundary(lat: Double, lng: Double): Pair<LatLng, Double> {
        if (hasPolygon) return GeoMath.nearestPointOnPolygon(lat, lng, polygon)
        val d = GeoMath.distanceM(lat, lng, this.lat, this.lng)
        if (d < 0.01) return LatLng(this.lat + radiusM / 111_195.0, this.lng) to radiusM
        val f = radiusM / d
        return LatLng(this.lat + (lat - this.lat) * f, this.lng + (lng - this.lng) * f) to kotlin.math.abs(d - radiusM)
    }

    companion object {
        /** Builds an office from a drawn outline. */
        fun fromPolygon(name: String, poly: List<LatLng>, gates: List<Gate>): OfficeConfig {
            val c = GeoMath.centroid(poly)
            val r = poly.maxOf { GeoMath.distanceM(c.lat, c.lng, it.lat, it.lng) }
            return OfficeConfig(name, c.lat, c.lng, r, gates, poly)
        }
    }
}

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

    // ---- Flexible hours ----
    /** Flexible: the day starts when you arrive; logout = arrival + [requiredMinutes]. */
    val flexible: Boolean = true,
    /** Flexible: earliest time an arrival counts from (arriving earlier starts the day here). */
    val earliestStartMin: Int = 8 * 60,
    /** Flexible: arriving after this is marked late. */
    val latestStartMin: Int = 11 * 60,
    /** Net working time per day, lunch NOT included (e.g. 8h 30m). */
    val netMinutes: Int = 8 * 60 + 30,
    /** Overtime is counted while in office until this time (day shifts). */
    val overtimeUntilMin: Int = 23 * 60,
) {
    /** Length of the day at the office = net hours + the lunch that is always deducted. */
    val requiredMinutes: Int get() = netMinutes + maxLunchMinutes
}

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
    NOT_STARTED("Work day not started"),
    LATE("Not in office — late"),
    OVERTIME("Overtime in office"),
    DAY_OFF("Day off"),
    ON_DUTY("On duty (official work outside)"),
    DAY_COMPLETE("Work day complete"),
    IN_OFFICE("In office"),
    OUTSIDE_OFFICE("Outside office (working hours)"),
    ON_LUNCH("On lunch break"),
    LUNCH_EXCEEDED("Lunch break exceeded"),
    UNKNOWN("Location unknown"),
}

/** A day marked by the user. Full-day marks are not working days. */
enum class DayMark(val label: String, val fullDayOff: Boolean) {
    LEAVE("Leave", true),
    SICK("Sick leave", true),
    WFH("Work from home", true),
    HOLIDAY("Holiday", true),
    HALF_DAY("Half day", false),
    /** Counted as a full day worked (e.g. days before you started using the app). */
    PRESENT("Present", false),
    /** Counted as absent (loss of pay), whatever was recorded. */
    ABSENT("Absent", false),
}

data class LunchState(val active: Boolean = false, val startedAtMs: Long = 0L)
