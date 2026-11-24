package com.attendance.tracker.core

/**
 * Decides whether the user is INSIDE or OUTSIDE the office premises from a
 * stream of location fixes. Accuracy rules:
 *
 *  1. Reject untrustworthy fixes: mock locations, stale fixes, fixes whose
 *     accuracy radius is larger than the configured limit, and impossible
 *     jumps (teleporting faster than [MAX_SPEED_MPS]).
 *  2. A fix only counts as INSIDE if its whole accuracy circle is inside the
 *     office radius, and as OUTSIDE only if its whole accuracy circle is
 *     outside. Fixes on the boundary are "ambiguous" and never change state.
 *  3. State only flips after [AccuracyConfig.confirmSamples] consecutive
 *     confident fixes agree (hysteresis against GPS jitter).
 *  4. The transition time is the time of the FIRST fix of the confirming
 *     run, refined by the gate: arriving = first moment seen at the gate,
 *     leaving = last moment seen at the gate. The punch records which gate.
 *  5. If there was a gap in readings before the transition (phone asleep,
 *     app killed), the transition reports it so the punch is not presented
 *     as more precise than it is.
 */
class PresenceEngine(initial: Presence = Presence.UNKNOWN) {

    sealed class Result {
        data class Rejected(val reason: String) : Result()
        data class Accepted(
            val zone: Zone,
            val distanceM: Double,
            /** Metres from the office boundary: negative inside, positive outside. */
            val edgeDistanceM: Double,
            val nearGate: Gate?,
            val transition: Transition?,
        ) : Result()
    }

    enum class Zone { INSIDE, OUTSIDE, AMBIGUOUS }

    data class Transition(
        val from: Presence,
        val to: Presence,
        val atMs: Long,
        val gate: Gate?,
        /** Seconds without any accepted reading just before the change (0 = continuous). */
        val gapBeforeSec: Long = 0,
        /** True when [atMs] came from the gate crossing rather than the first confirming fix. */
        val timedByGate: Boolean = false,
    )

    var presence: Presence = initial
        private set

    private var candidate: Presence? = null
    private var candidateCount = 0
    private var candidateSinceMs = 0L
    private var lastAccepted: LocationSample? = null
    private var consecutiveJumpRejects = 0
    private var lastGate: Gate? = null
    private var lastGateMs = 0L
    private var gateVisitStartMs = 0L
    private var candidatePrevMs = 0L

    /** True while a possible in/out change is waiting for confirmation (poll faster). */
    val hasPendingChange: Boolean get() = candidate != null

    /**
     * Office Wi-Fi says you're here while GPS can't tell (e.g. tracking started deep inside).
     * Marks you INSIDE and returns the transition, or null if already inside.
     */
    fun wifiCheckIn(atMs: Long): Transition? {
        if (presence == Presence.INSIDE) return null
        val t = Transition(presence, Presence.INSIDE, atMs, null)
        presence = Presence.INSIDE
        candidate = null; candidateCount = 0
        return t
    }

    fun reset(p: Presence) {
        presence = p
        candidate = null
        candidateCount = 0
        lastAccepted = null
        consecutiveJumpRejects = 0
        lastGate = null
    }

    /**
     * @param onOfficeWifi phone is connected to a saved office Wi-Fi. While true, weak or
     * borderline "outside" readings can't punch you OUT (GPS is poor deep inside buildings);
     * only a precise reading far outside can.
     */
    fun process(s: LocationSample, office: OfficeConfig?, cfg: AccuracyConfig, nowMs: Long, onOfficeWifi: Boolean = false): Result {
        if (office == null) return Result.Rejected("Office location not configured")
        if (cfg.rejectMock && s.isMock) return Result.Rejected("Mock (fake) location detected")
        if (nowMs - s.timeMs > cfg.maxFixAgeSec * 1000L) return Result.Rejected("Stale location fix")
        if (s.accuracyM <= 0f || s.accuracyM > cfg.maxAccuracyM) {
            return Result.Rejected("Low GPS accuracy (±${s.accuracyM.toInt()} m)")
        }

        lastAccepted?.let { prev ->
            val dtSec = (s.timeMs - prev.timeMs) / 1000.0
            if (dtSec > 0) {
                val d = GeoMath.distanceM(prev.lat, prev.lng, s.lat, s.lng)
                val excess = d - s.accuracyM - prev.accuracyM
                if (excess > 0 && excess / dtSec > MAX_SPEED_MPS) {
                    consecutiveJumpRejects++
                    // If many consecutive fixes disagree with the previous one,
                    // the previous fix was the outlier: accept and re-anchor.
                    if (consecutiveJumpRejects < 3) return Result.Rejected("Unrealistic location jump")
                }
            }
        }
        consecutiveJumpRejects = 0
        val prevAcceptedMs = lastAccepted?.timeMs ?: s.timeMs
        lastAccepted = s

        val gate = office.gates
            .map { it to GeoMath.distanceM(s.lat, s.lng, it.lat, it.lng) }
            .filter { (g, d) -> d <= g.radiusM + s.accuracyM }
            .minByOrNull { it.second }?.first
        if (gate != null) {
            if (gate.id != lastGate?.id || s.timeMs - lastGateMs > GATE_VISIT_BREAK_MS) gateVisitStartMs = s.timeMs
            lastGate = gate; lastGateMs = s.timeMs
        }

        val d = GeoMath.distanceM(s.lat, s.lng, office.lat, office.lng)
        val edge = office.edgeDistanceM(s.lat, s.lng)
        // The whole accuracy circle must be on one side of the (circle or drawn) boundary.
        var zone = when {
            edge + s.accuracyM <= 0 -> Zone.INSIDE
            edge - s.accuracyM > 0 -> Zone.OUTSIDE
            else -> Zone.AMBIGUOUS
        }
        if (zone == Zone.OUTSIDE && onOfficeWifi && presence != Presence.OUTSIDE &&
            !(edge > WIFI_OVERRIDE_EDGE_M && s.accuracyM <= WIFI_OVERRIDE_ACC_M)
        ) zone = Zone.AMBIGUOUS

        val zonePresence = when (zone) {
            Zone.INSIDE -> Presence.INSIDE
            Zone.OUTSIDE -> Presence.OUTSIDE
            Zone.AMBIGUOUS -> null
        }
        var transition: Transition? = null
        if (zonePresence != null) {
            if (zonePresence == presence) {
                candidate = null; candidateCount = 0
            } else {
                if (zonePresence == candidate) {
                    candidateCount++
                } else {
                    candidate = zonePresence; candidateCount = 1; candidateSinceMs = s.timeMs
                    candidatePrevMs = prevAcceptedMs
                }
                if (candidateCount >= cfg.confirmSamples.coerceAtLeast(1)) {
                    val usedGate = lastGate?.takeIf { s.timeMs - lastGateMs <= GATE_WINDOW_MS }
                    var at = candidateSinceMs
                    var byGate = false
                    if (usedGate != null) {
                        if (zonePresence == Presence.INSIDE) {
                            // Arrived: the moment you first reached the gate.
                            if (gateVisitStartMs <= candidateSinceMs && candidateSinceMs - gateVisitStartMs <= GATE_TIME_WINDOW_MS) {
                                at = gateVisitStartMs; byGate = true
                            }
                        } else {
                            // Left: the last moment you were at the gate.
                            if (lastGateMs <= candidateSinceMs && candidateSinceMs - lastGateMs <= GATE_TIME_WINDOW_MS) {
                                at = lastGateMs; byGate = true
                            }
                        }
                    }
                    val gap = ((candidateSinceMs - candidatePrevMs) / 1000).coerceAtLeast(0)
                    transition = Transition(presence, zonePresence, at, usedGate, gap, byGate)
                    presence = zonePresence
                    candidate = null; candidateCount = 0
                    lastGate = null
                }
            }
        }
        return Result.Accepted(zone, d, edge, gate, transition)
    }

    companion object {
        /** ~216 km/h: anything faster between two fixes is a GPS glitch. */
        const val MAX_SPEED_MPS = 60.0
        /** A gate visit within this window before a transition is attributed to it. */
        const val GATE_WINDOW_MS = 10 * 60 * 1000L
        /** On office Wi-Fi, only a reading this far outside with this accuracy can punch OUT. */
        const val WIFI_OVERRIDE_EDGE_M = 150.0
        const val WIFI_OVERRIDE_ACC_M = 20f
        /** Gate time refines the punch only if the crossing was this recent. */
        const val GATE_TIME_WINDOW_MS = 3 * 60 * 1000L
        /** Away from a gate this long = a new visit. */
        const val GATE_VISIT_BREAK_MS = 2 * 60 * 1000L
    }
}
