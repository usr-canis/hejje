package com.canis.hejje.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the car is doing, as far as the readings can tell. */
enum class MotionState { STOPPED, CREEPING, MOVING, UNKNOWN }

/**
 * The fused picture at one moment. [accel] is NaN unless the phone's sensors are trusted just now;
 * [speedSigma] is one standard deviation of [speed]; [fixAgeMs] is how old the last accepted GPS
 * fix is (Long.MAX_VALUE before the first); [gap] means distance is not being counted because GPS
 * has been lost for too long to keep estimating.
 */
data class FusedReading(
    val t: Long,
    val speed: Float,
    val accel: Float,
    val distanceM: Double,
    val state: MotionState,
    val speedSigma: Float,
    val fixAgeMs: Long,
    val gap: Boolean,
)

/** The result of a GPS fix: the reading, and the distance covered since the previous fix (NaN across a gap). */
class FixResult(val reading: FusedReading, val sinceFixM: Float)

/**
 * Fuses GPS speed with the phone's forward acceleration into one speed, a motion state and a
 * distance, for stop-and-go traffic where GPS alone cannot tell a crawl from a stop.
 *
 * A two-state Kalman filter tracks speed and the accelerometer's slow bias. Between fixes the
 * trusted acceleration carries the speed forward; each fix corrects it, weighted by the fix's own
 * reported accuracy. A stop is confirmed only after a short dwell of near-zero readings with the
 * sensors quiet, and only then are zero-velocity updates applied; a credible launch suspends them
 * at once and the next fix confirms it. Fixes that arrive late are applied at their measurement
 * time by rewinding a short history and replaying the samples after them, so every interval is
 * counted once.
 *
 * All times are nanoseconds on one clock (the sensor clock; GPS fixes use their elapsed-realtime
 * stamp). Pure Kotlin, constant work per event, so it can be replayed deterministically in tests.
 */
class SpeedFusion {
    /** Everything that changes with each event, so a late fix can rewind to an earlier moment. */
    private class Core {
        var t = 0L
        var v = 0f
        var b = 0f
        var p00 = INITIAL_SIGMA * INITIAL_SIGMA
        var p01 = 0f
        var p11 = INITIAL_BIAS_SIGMA * INITIAL_BIAS_SIGMA
        var dist = 0.0
        var distAtFix = 0.0
        var state = MotionState.UNKNOWN
        var zupt = false
        var gap = false
        var gapInInterval = false
        var lastFixT = 0L
        var lastZ = Float.NaN
        var lastZSigma = Float.NaN
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var lastAcc = Float.NaN
        var lastAccel = Float.NaN
        var aAvg = 0f
        var aAbsAvg = 0f
        var stopEvidenceSince = 0L
        var launchSince = 0L
        var launchedAt = 0L
        var distrustUntil = 0L
        var rejected = 0
        var rejectedZ = Float.NaN
        var innov0 = Float.NaN
        var innov1 = Float.NaN
        var innov2 = Float.NaN
        var untrustedSinceFix = false
        var z0 = Float.NaN
        var z1 = Float.NaN
        var z2 = Float.NaN

        /** Mean of the last three precise GPS speeds; NaN until there are three. */
        val zMean: Float get() = if (z2.isNaN()) Float.NaN else (z0 + z1 + z2) / 3

        fun copyFrom(o: Core) {
            t = o.t; v = o.v; b = o.b; p00 = o.p00; p01 = o.p01; p11 = o.p11; dist = o.dist; distAtFix = o.distAtFix
            state = o.state; zupt = o.zupt; gap = o.gap; gapInInterval = o.gapInInterval
            lastFixT = o.lastFixT; lastZ = o.lastZ; lastZSigma = o.lastZSigma; lastLat = o.lastLat; lastLon = o.lastLon; lastAcc = o.lastAcc
            lastAccel = o.lastAccel; aAvg = o.aAvg; aAbsAvg = o.aAbsAvg; stopEvidenceSince = o.stopEvidenceSince
            launchSince = o.launchSince; launchedAt = o.launchedAt; distrustUntil = o.distrustUntil
            rejected = o.rejected; rejectedZ = o.rejectedZ; innov0 = o.innov0; innov1 = o.innov1; innov2 = o.innov2; untrustedSinceFix = o.untrustedSinceFix; z0 = o.z0; z1 = o.z1; z2 = o.z2
        }
    }

    private val core = Core()

    // The last few seconds of sensor samples, with the state before each, for late fixes.
    private val ring = Array(HISTORY) { Core() }
    private val ringT = LongArray(HISTORY)
    private val ringA = FloatArray(HISTORY)
    private val ringTrusted = BooleanArray(HISTORY)
    private var ringNext = 0
    private var ringCount = 0

    val state: MotionState get() = core.state
    val speed: Float get() = max(0f, core.v)
    val distanceM: Double get() = core.dist

    fun reset() {
        core.copyFrom(Core())
        ringNext = 0
        ringCount = 0
    }

    /**
     * One sensor sample: [aLong] is the car's forward acceleration (m/s², braking negative) and
     * [trusted] whether the phone is settled and its forward axis known. Untrusted samples still
     * mark time passing.
     */
    fun onAccel(t: Long, aLong: Float, trusted: Boolean) {
        if (t <= core.t) return
        remember(t, aLong, trusted)
        step(t, aLong, trusted)
    }

    /**
     * A GPS fix taken at [t] (its measurement time, which may be a little in the past). [speed] is
     * NaN when the fix has none; [acc] is the position accuracy in metres.
     */
    fun onFix(t: Long, speed: Float, speedAcc: Float, lat: Double, lon: Double, acc: Float): FixResult {
        if (t <= core.lastFixT) return FixResult(reading(core.t), Float.NaN) // a duplicate, or older than one already applied
        if (t < core.t) {
            // Late: go back to just before the first sample after the fix, apply it, replay the rest.
            val from = firstSampleAfter(t)
            if (from >= 0) {
                core.copyFrom(ring[from])
                applyFix(t, speed, speedAcc, lat, lon, acc)
                val since = sinceFix()
                val oldest = (ringNext - ringCount + HISTORY) % HISTORY
                var i = from
                repeat(ringCount - ((from - oldest + HISTORY) % HISTORY)) {
                    val k = i % HISTORY
                    ring[k].copyFrom(core)
                    step(ringT[k], ringA[k], ringTrusted[k])
                    i++
                }
                return FixResult(reading(core.t), since)
            }
            // Older than the history we keep: apply it as of now.
        }
        applyFix(max(t, core.t), speed, speedAcc, lat, lon, acc)
        return FixResult(reading(core.t), sinceFix())
    }

    /** The current picture, with the fix age as of [now]. */
    fun reading(now: Long): FusedReading {
        val age = if (core.lastFixT == 0L) Long.MAX_VALUE else ((now - core.lastFixT) / 1_000_000L).coerceAtLeast(0)
        return FusedReading(core.t, max(0f, core.v), core.lastAccel, core.dist, core.state, sqrt(core.p00), age, core.gap)
    }

    // ---- Time passing ------------------------------------------------------------------------

    private fun remember(t: Long, a: Float, trusted: Boolean) {
        ring[ringNext].copyFrom(core)
        ringT[ringNext] = t
        ringA[ringNext] = a
        ringTrusted[ringNext] = trusted
        ringNext = (ringNext + 1) % HISTORY
        if (ringCount < HISTORY) ringCount++
    }

    /** Index into the ring of the first remembered sample after [t], or -1 if [t] is older than the history. */
    private fun firstSampleAfter(t: Long): Int {
        if (ringCount == 0) return -1
        val oldest = (ringNext - ringCount + HISTORY) % HISTORY
        // Older than everything remembered (a full ring): cannot rewind that far.
        if (ringT[oldest] > t && ringCount == HISTORY) return -1
        var i = oldest
        repeat(ringCount) {
            if (ringT[i] > t) return i
            i = (i + 1) % HISTORY
        }
        return -1
    }

    /** Advances the filter to [t] with the sample [a] (ignored unless [trusted]). */
    private fun step(t: Long, a: Float, trusted: Boolean) {
        val c = core
        val dt = if (c.t == 0L) 0f else ((t - c.t) / 1e9f)
        c.t = t
        val usable = trusted && !a.isNaN() && t >= c.distrustUntil
        if (dt <= 0f) {
            c.lastAccel = if (usable) a - c.b else Float.NaN
            return
        }
        if (dt > MAX_STEP_S) {
            // A long gap in the sensor stream: nothing to integrate; speed is simply less certain.
            c.p00 += UNOBSERVED_SIGMA * UNOBSERVED_SIGMA * dt
            unobserved(c)
        } else if (usable) {
            val eff = a - c.b
            c.v += eff * dt
            if (!c.zupt) c.v = max(0f, c.v)
            // P = F P Fᵀ + Q with F = [[1, -dt], [0, 1]].
            val p00 = c.p00 - 2 * dt * c.p01 + dt * dt * c.p11 + ACCEL_SIGMA * ACCEL_SIGMA * dt * dt
            val p01 = c.p01 - dt * c.p11
            c.p00 = p00
            c.p01 = p01
            val biasSigma = if (c.zupt) BIAS_SIGMA_STOPPED else BIAS_SIGMA
            c.p11 += biasSigma * biasSigma * dt
            c.lastAccel = eff
            // Smoothed over a few tenths of a second: a push to set off, and how quiet the car is.
            c.aAvg += (eff - c.aAvg) * dt / (PUSH_AVG_S + dt)
            c.aAbsAvg += (abs(eff) - c.aAbsAvg) * dt / (QUIET_AVG_S + dt)
            if (c.aAvg > LAUNCH_ACCEL + 2 * sqrt(c.p11)) { if (c.launchSince == 0L) c.launchSince = t } else c.launchSince = 0
        } else {
            c.p00 += UNOBSERVED_SIGMA * UNOBSERVED_SIGMA * dt
            c.p11 += BIAS_SIGMA * BIAS_SIGMA * dt
            unobserved(c)
        }
        if (c.zupt) update(0f, ZUPT_SIGMA * ZUPT_SIGMA, learnBias = c.aAvg <= LAUNCH_ACCEL)
        // A confirmed stop adds no distance; a lost GPS adds none once the estimate has run out.
        if (!c.zupt && !c.gap && dt <= MAX_STEP_S) c.dist += max(0f, c.v) * dt
        if (!c.gap && c.lastFixT != 0L) {
            val age = t - c.lastFixT
            if (age > COAST_MIN_NS && (age > COAST_MAX_NS || sqrt(c.p00) > GAP_SIGMA)) {
                c.gap = true
                c.gapInInterval = true
            }
        }
        judge(t)
    }

    private fun unobserved(c: Core) {
        c.untrustedSinceFix = true
        c.lastAccel = Float.NaN
        c.aAvg = 0f
        c.aAbsAvg = 0f
        c.launchSince = 0
    }

    // ---- GPS fixes ---------------------------------------------------------------------------

    private fun applyFix(t: Long, speed: Float, speedAcc: Float, lat: Double, lon: Double, acc: Float) {
        val c = core
        if (t > c.t) step(t, Float.NaN, trusted = false)
        var z = speed
        var r: Float
        if (!z.isNaN()) {
            z = max(0f, z)
            val sigma = if (speedAcc.isNaN() || speedAcc <= 0f) DEFAULT_SPEED_SIGMA else max(speedAcc, SPEED_NOISE_FLOOR)
            r = sigma * sigma
        } else {
            // No speed: the displacement since the last fix, only when it clearly exceeds the uncertainty.
            val dtFix = (t - c.lastFixT) / 1e9f
            val unc = max(if (acc.isNaN()) 20f else acc, if (c.lastAcc.isNaN()) 20f else c.lastAcc)
            r = Float.NaN
            if (c.lastFixT != 0L && dtFix in 0.5f..6f && !c.lastLat.isNaN()) {
                val d = Geo.distance(c.lastLat, c.lastLon, lat, lon).toFloat()
                if (d > unc + 2f) {
                    z = d / dtFix
                    val s = unc / dtFix + 0.5f
                    r = s * s
                }
            }
        }
        val prevFixT = c.lastFixT
        c.lastFixT = t
        c.lastLat = lat
        c.lastLon = lon
        c.lastAcc = acc
        if (c.gap) {
            // GPS is back: start from what it says.
            c.gap = false
            if (!r.isNaN()) { c.v = z; c.p00 = r }
            c.rejected = 0
        }
        if (r.isNaN()) {
            c.lastZ = Float.NaN
            c.lastZSigma = Float.NaN
            judge(t)
            return
        }
        c.lastZ = z
        c.lastZSigma = sqrt(r)
        if (c.lastZSigma <= PRECISE_SIGMA) { c.z2 = c.z1; c.z1 = c.z0; c.z0 = z }
        val y = z - c.v
        val s = c.p00 + r
        val trusted = !c.lastAccel.isNaN()
        // The sensors keep pulling the speed one way while GPS says otherwise (a slope's pull, a
        // shifted mount, reversing, hard braking the filter does not follow): three fixes in a row
        // off the same way, each beyond the noise, and GPS wins, sensors aside for a while.
        c.innov2 = c.innov1; c.innov1 = c.innov0; c.innov0 = y
        val limit = INCONSISTENT_BASE + c.lastZSigma / 2
        val oneWay = !c.innov2.isNaN() &&
            (c.innov0 > limit && c.innov1 > limit && c.innov2 > limit || c.innov0 < -limit && c.innov1 < -limit && c.innov2 < -limit)
        if (trusted && oneWay) {
            reacquire(t, z, r)
        } else if (y * y > GATE * GATE * s) {
            // Implausible jump. Two such fixes that agree with each other mean the filter is wrong:
            // start again from GPS.
            if (c.rejected >= 1 && abs(z - c.rejectedZ) < REACQUIRE_AGREE) {
                reacquire(t, z, r)
            } else {
                c.rejected++
                c.rejectedZ = z
            }
        } else {
            c.rejected = 0
            val before = max(0f, c.v)
            val dtFix = (t - prevFixT) / 1e9f
            update(z, r)
            if (c.untrustedSinceFix && !c.zupt && !c.gap && prevFixT != 0L && dtFix in 0.2f..MAX_HELD_S) {
                c.dist += (max(0f, c.v) - before) * dtFix / 2
            }
        }
        c.untrustedSinceFix = false
        judge(t)
    }

    private fun reacquire(t: Long, z: Float, r: Float) {
        val c = core
        c.v = z
        c.p00 = r
        c.p01 = 0f
        // The bias may well be what was wrong: let it be learned again quickly.
        c.p11 = max(c.p11, RELEARN_BIAS_SIGMA * RELEARN_BIAS_SIGMA)
        c.rejected = 0
        c.innov0 = Float.NaN; c.innov1 = Float.NaN; c.innov2 = Float.NaN
        c.distrustUntil = t + DISTRUST_NS
        unobserved(c)
    }

    /** Kalman correction with a speed measurement [z] of variance [r]. */
    private fun update(z: Float, r: Float, learnBias: Boolean = true) {
        val c = core
        val s = c.p00 + r
        val k0 = c.p00 / s
        val k1 = if (learnBias) c.p01 / s else 0f
        val y = z - c.v
        c.v += k0 * y
        if (!c.zupt) c.v = max(0f, c.v)
        c.b = (c.b + k1 * y).coerceIn(-MAX_BIAS, MAX_BIAS)
        val p00 = (1 - k0) * c.p00
        val p01 = (1 - k0) * c.p01
        val p11 = c.p11 - k1 * c.p01
        c.p00 = max(p00, MIN_SPEED_VAR)
        c.p01 = p01
        c.p11 = max(p11, MIN_VAR)
    }

    private fun sinceFix(): Float {
        val c = core
        val d = (c.dist - c.distAtFix).toFloat()
        c.distAtFix = c.dist
        val out = if (c.gapInInterval) Float.NaN else d
        c.gapInInterval = false
        return out
    }

    // ---- Motion state ------------------------------------------------------------------------

    /** Decides STOPPED / CREEPING / MOVING / UNKNOWN from what is known at [t]. */
    private fun judge(t: Long) {
        val c = core
        if (c.gap) {
            c.state = MotionState.UNKNOWN
            c.stopEvidenceSince = 0
            return
        }
        val fixAge = if (c.lastFixT == 0L) Long.MAX_VALUE else t - c.lastFixT
        val precise = fixAge <= FRESH_FIX_NS && !c.lastZ.isNaN() && c.lastZSigma <= PRECISE_SIGMA
        val trusted = !c.lastAccel.isNaN()
        if (c.zupt) {
            // A credible launch: a sustained push from the sensors, or GPS clearly seeing movement.
            val sensorLaunch = trusted && c.launchSince != 0L && t - c.launchSince >= LAUNCH_NS && t >= c.distrustUntil + LAUNCH_HOLD_NS
            val gpsLaunch = precise && (c.lastZ > max(CREEP_START, 1.5f * c.lastZSigma) || c.zMean > ZERO_MEAN_LOOSE)
            if (sensorLaunch || gpsLaunch) {
                val seen = if (c.lastZ > max(CREEP_START, 1.5f * c.lastZSigma)) c.lastZ else c.zMean
                c.zupt = false
                c.launchedAt = t
                c.stopEvidenceSince = 0
                c.z0 = Float.NaN; c.z1 = Float.NaN; c.z2 = Float.NaN
                // The push began before it was recognised: credit what was integrated away meanwhile.
                c.v = max(0f, max(c.v, if (sensorLaunch) c.aAvg * LAUNCH_LAG_S else 0f))
                if (gpsLaunch) {
                    c.v = max(c.v, seen)
                    c.p00 = max(c.p00, c.lastZSigma * c.lastZSigma)
                }
                c.state = MotionState.UNKNOWN
            } else {
                c.state = MotionState.STOPPED
            }
            return
        }
        // A launch waits for the next fix to confirm it; if GPS still sees nothing, GPS prevails.
        if (c.launchedAt != 0L) {
            if (c.lastFixT > c.launchedAt && !c.lastZ.isNaN()) {
                if (c.lastZ <= CREEP_MIN) {
                    c.v = min(c.v, c.lastZ)
                    c.p00 = max(c.p00, c.lastZSigma * c.lastZSigma)
                }
                c.launchedAt = 0
            } else if (t - c.launchedAt < LAUNCH_CONFIRM_NS) {
                c.state = MotionState.UNKNOWN
                return
            } else {
                c.launchedAt = 0
            }
        }
        // Standing still: fresh precise readings near zero (within their own error), the sensors
        // quiet, and either the filter itself at zero or a run of readings strictly at zero (which
        // separates a stop from a steady crawl at a few tenths of a metre per second).
        val zeroFix = precise && c.lastZ <= ZERO_SPEED + c.lastZSigma / 2
        val zMean = c.zMean
        val quiet = !trusted || c.aAbsAvg < QUIET_ACCEL
        val atZero = c.v < STOP_SPEED
        val gpsStill = zMean <= ZERO_MEAN && c.v < CREEP_GPS_MIN
        val still = zeroFix && (atZero && quiet && c.v < STOP_SPEED_LOOSE || gpsStill || quiet && c.v < STOP_SPEED_LOOSE && zMean <= ZERO_MEAN_LOOSE)
        if (still) {
            if (c.stopEvidenceSince == 0L) c.stopEvidenceSince = t
            if (t - c.stopEvidenceSince >= STOP_DWELL_NS && (atZero || zMean <= ZERO_MEAN)) {
                c.zupt = true
                c.v = 0f
                c.state = MotionState.STOPPED
                return
            }
        } else {
            c.stopEvidenceSince = 0
        }
        val stale = fixAge > STALE_FIX_NS
        c.state = when {
            stale && !trusted -> MotionState.UNKNOWN
            c.v >= MOVING_SPEED -> MotionState.MOVING
            c.state == MotionState.MOVING && c.v >= MOVING_SPEED - MOVING_HYSTERESIS -> MotionState.MOVING
            c.v >= CREEP_MIN && !(zMean <= ZERO_STRICT && c.v < CREEP_GPS_MIN) -> MotionState.CREEPING
            // A crawl, once seen, holds until the car is all but stopped.
            c.state == MotionState.CREEPING && c.v >= STOP_SPEED -> MotionState.CREEPING
            // Too slow to call a crawl, yet not confirmed stopped.
            else -> MotionState.UNKNOWN
        }
    }

    companion object {
        /** Samples kept for late fixes: 5 s at 25 Hz. */
        private const val HISTORY = 128
        private const val MAX_STEP_S = 0.5f
        private const val MAX_HELD_S = 3f

        // Noise: the filtered sensor acceleration, its bias's drift, and speed when unobserved.
        const val ACCEL_SIGMA = 0.25f
        private const val BIAS_SIGMA = 0.03f
        private const val BIAS_SIGMA_STOPPED = 0.08f
        private const val UNOBSERVED_SIGMA = 1.2f
        private const val MAX_BIAS = 1.5f
        private const val INITIAL_SIGMA = 10f
        private const val INITIAL_BIAS_SIGMA = 0.2f
        private const val RELEARN_BIAS_SIGMA = 0.3f
        private const val MIN_VAR = 1e-6f
        /** Speed is never claimed better than this (σ 0.1 m/s), so GPS always gets some say. */
        private const val MIN_SPEED_VAR = 0.01f

        // GPS speed: its reported accuracy, with a floor; a guess when none is reported.
        const val SPEED_NOISE_FLOOR = 0.3f
        const val DEFAULT_SPEED_SIGMA = 1.0f
        private const val GATE = 4f
        private const val REACQUIRE_AGREE = 1.0f
        private const val INCONSISTENT_BASE = 0.15f
        private const val DISTRUST_NS = 5_000_000_000L

        // Stops: near-zero, precise fixes and quiet sensors for the dwell; then zero-velocity updates.
        const val STOP_DWELL_NS = 2_500_000_000L
        private const val ZERO_SPEED = 0.3f
        private const val ZERO_STRICT = 0.2f
        private const val ZERO_MEAN = 0.15f
        private const val ZERO_MEAN_LOOSE = 0.25f
        private const val STOP_SPEED = 0.15f
        private const val STOP_SPEED_LOOSE = 0.5f
        private const val PRECISE_SIGMA = 1.0f
        private const val QUIET_ACCEL = 0.25f
        private const val QUIET_AVG_S = 0.5f
        private const val ZUPT_SIGMA = 0.05f
        private const val FRESH_FIX_NS = 1_600_000_000L

        // Launch: a sustained push, or GPS clearly moving; confirmed by the next fix.
        const val LAUNCH_ACCEL = 0.25f
        private const val PUSH_AVG_S = 0.3f
        private const val LAUNCH_NS = 200_000_000L
        private const val LAUNCH_LAG_S = 0.5f
        private const val LAUNCH_HOLD_NS = 3_000_000_000L
        const val CREEP_START = 0.5f
        private const val LAUNCH_CONFIRM_NS = 2_500_000_000L

        // Creeping below this, moving above it (with a little hysteresis).
        const val MOVING_SPEED = 2.0f
        private const val MOVING_HYSTERESIS = 0.2f
        const val CREEP_MIN = 0.25f
        /** With GPS reading zero, only a speed this high counts as a crawl on the sensors' word alone. */
        private const val CREEP_GPS_MIN = 0.8f

        // GPS lost: a short estimate from the sensors, then a gap once too uncertain or too long.
        private const val STALE_FIX_NS = 3_000_000_000L
        private const val COAST_MIN_NS = 4_000_000_000L
        private const val COAST_MAX_NS = 6_000_000_000L
        private const val GAP_SIGMA = 1.5f
    }
}
