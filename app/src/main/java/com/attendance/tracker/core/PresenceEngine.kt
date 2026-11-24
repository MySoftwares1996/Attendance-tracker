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
 *     run, and it is attributed to the most recently visited gate.
 */
class PresenceEngine(initial: Presence = Presence.UNKNOWN) {

    sealed class Result {
        data class Rejected(val reason: String) : Result()
        data class Accepted(
            val zone: Zone,
            val distanceM: Double,
            val nearGate: Gate?,
            val transition: Transition?,
        ) : Result()
    }

    enum class Zone { INSIDE, OUTSIDE, AMBIGUOUS }

    data class Transition(val from: Presence, val to: Presence, val atMs: Long, val gate: Gate?)

    var presence: Presence = initial
        private set

    private var candidate: Presence? = null
    private var candidateCount = 0
    private var candidateSinceMs = 0L
    private var lastAccepted: LocationSample? = null
    private var consecutiveJumpRejects = 0
    private var lastGate: Gate? = null
    private var lastGateMs = 0L

    fun reset(p: Presence) {
        presence = p
        candidate = null
        candidateCount = 0
        lastAccepted = null
        consecutiveJumpRejects = 0
        lastGate = null
    }

    fun process(s: LocationSample, office: OfficeConfig?, cfg: AccuracyConfig, nowMs: Long): Result {
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
        lastAccepted = s

        val gate = office.gates
            .map { it to GeoMath.distanceM(s.lat, s.lng, it.lat, it.lng) }
            .filter { (g, d) -> d <= g.radiusM + s.accuracyM }
            .minByOrNull { it.second }?.first
        if (gate != null) { lastGate = gate; lastGateMs = s.timeMs }

        val d = GeoMath.distanceM(s.lat, s.lng, office.lat, office.lng)
        val zone = when {
            d + s.accuracyM <= office.radiusM -> Zone.INSIDE
            d - s.accuracyM > office.radiusM -> Zone.OUTSIDE
            else -> Zone.AMBIGUOUS
        }

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
                }
                if (candidateCount >= cfg.confirmSamples.coerceAtLeast(1)) {
                    val usedGate = lastGate?.takeIf { s.timeMs - lastGateMs <= GATE_WINDOW_MS }
                    transition = Transition(presence, zonePresence, candidateSinceMs, usedGate)
                    presence = zonePresence
                    candidate = null; candidateCount = 0
                    lastGate = null
                }
            }
        }
        return Result.Accepted(zone, d, gate, transition)
    }

    companion object {
        /** ~216 km/h: anything faster between two fixes is a GPS glitch. */
        const val MAX_SPEED_MPS = 60.0
        /** A gate visit within this window before a transition is attributed to it. */
        const val GATE_WINDOW_MS = 10 * 60 * 1000L
    }
}
