package com.canis.hejje.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The car's motion between two GPS fixes, from the phone's sensors (m/s²). Forward and braking are +/-. */
data class SensorSummary(val meanLon: Float, val minLon: Float, val maxLon: Float, val peakLat: Float)

/**
 * Turns the phone's motion sensors into the car's forward/backward and sideways acceleration.
 *
 * Used only while the phone is settled: resting still (no tilting or turning other than the car's
 * own turns). With the screen on (e.g. in a mount while you look at it) it must also hold its
 * angle steadily, which a phone in a hand does not. "Down" comes from gravity; "forward" is learned by matching the
 * phone's horizontal acceleration with GPS speed changes, so the phone may lie in any position.
 * If the phone is picked up or moved, the learned direction is discarded and learned again.
 *
 * Pure Kotlin so it can be tested with simulated sensor data.
 */
class MotionEstimator {
    private val up = FloatArray(3)
    private var haveUp = false
    private val refUp = FloatArray(3)
    private var haveRef = false
    private val filtered = FloatArray(3)
    private var lastT = 0L
    private var unsettledUntil = 0L
    private var settledSince = 0L

    private val calib = DoubleArray(3)
    private var calibCount = 0
    private val forward = FloatArray(3)
    private var calibrated = false

    private var samples = 0
    private var settledSamples = 0
    private val hSum = DoubleArray(3)
    private var lonN = 0
    private var lonSum = 0.0
    private var lonMin = Float.MAX_VALUE
    private var lonMax = -Float.MAX_VALUE
    private var latPeak = 0f
    private var lastSpeed = Float.NaN
    private var lastFixT = 0L
    private val steadyRef = FloatArray(3)
    private var steadyRefT = 0L
    private var haveSteady = false
    private var steadyNs = 0L
    private var steadyOk = false

    val isCalibrated: Boolean get() = calibrated

    fun reset() {
        haveUp = false; haveRef = false; lastT = 0; unsettledUntil = 0; settledSince = 0; haveSteady = false
        filtered.fill(0f); resetCalibration(); resetInterval(); lastSpeed = Float.NaN; lastFixT = 0
    }

    private fun resetCalibration() {
        calib.fill(0.0); calibCount = 0; calibrated = false
    }

    private fun resetInterval() {
        samples = 0; settledSamples = 0; hSum.fill(0.0); lonN = 0; lonSum = 0.0
        lonMin = Float.MAX_VALUE; lonMax = -Float.MAX_VALUE; latPeak = 0f
    }

    /**
     * One sensor reading. [t] in nanoseconds (the sensor clock), [linear] acceleration without
     * gravity and [gravity] in the phone's axes, [gyro] rotation rate (rad/s) if available.
     */
    fun onMotion(t: Long, linear: FloatArray, gravity: FloatArray, gyro: FloatArray?, screenOn: Boolean) {
        val gn = norm(gravity)
        if (gn < 5f) return
        if (!haveUp) {
            for (i in 0..2) up[i] = gravity[i] / gn
            haveUp = true
        } else {
            for (i in 0..2) up[i] = up[i] * 0.9f + gravity[i] / gn * 0.1f
            val un = norm(up)
            for (i in 0..2) up[i] /= un
        }
        if (!haveRef) {
            up.copyInto(refUp); haveRef = true; settledSince = t
        } else if (angleDeg(up, refUp) > MOVED_DEG) {
            // The phone now lies differently: the learned forward direction no longer applies.
            resetCalibration(); up.copyInto(refUp); settledSince = t
        }
        if (gyro != null) {
            val along = dot(gyro, up)
            val tilt = sqrt(max(0f, dot(gyro, gyro) - along * along))
            if (tilt > if (screenOn) IN_USE_RAD_S else HANDLING_RAD_S) unsettledUntil = t + HANDLING_HOLD_NS
        }
        if (screenOn) {
            // In use: it counts once it has held its angle, as in a mount, through a few seconds of
            // even driving. A hand keeps wobbling. Braking and turns make "down" lean a little,
            // so steadiness is judged only while the car runs evenly; the verdict holds meanwhile.
            val stepNs = if (lastT == 0L) 20_000_000L else (t - lastT).coerceIn(0L, 200_000_000L)
            if (!haveSteady) {
                up.copyInto(steadyRef); steadyRefT = t; haveSteady = true; steadyNs = 0; steadyOk = false
            } else if (norm(filtered) < CALM_ACCEL) {
                if (angleDeg(up, steadyRef) > STEADY_DEG) {
                    steadyOk = false; steadyNs = 0
                    up.copyInto(steadyRef); steadyRefT = t
                } else {
                    steadyNs += stepNs
                    if (steadyNs >= STEADY_CONFIRM_NS) steadyOk = true
                    if (t - steadyRefT > STEADY_WINDOW_NS) { up.copyInto(steadyRef); steadyRefT = t }
                }
            }
            if (!steadyOk) unsettledUntil = max(unsettledUntil, t + 1)
        } else {
            haveSteady = false
        }

        // Horizontal part of the acceleration, smoothed to remove road vibration.
        val vertical = dot(linear, up)
        val dt = if (lastT == 0L) 0.02f else ((t - lastT) / 1e9f).coerceIn(0f, 0.2f)
        lastT = t
        val alpha = dt / (SMOOTH_S + dt)
        for (i in 0..2) filtered[i] += alpha * (linear[i] - vertical * up[i] - filtered[i])

        samples++
        val settled = t >= unsettledUntil && t - settledSince >= SETTLE_NS
        if (!settled) return
        settledSamples++
        for (i in 0..2) hSum[i] += filtered[i]
        if (calibrated) {
            val lon = dot(filtered, forward)
            val left = cross(up, forward)
            val lat = dot(filtered, left)
            lonN++; lonSum += lon
            lonMin = min(lonMin, lon); lonMax = max(lonMax, lon); latPeak = max(latPeak, abs(lat))
        }
    }

    /**
     * Called at each GPS fix ([t] on the same clock as the sensor readings). Returns the car's motion
     * since the previous fix, or null when the phone was not settled or forward is not known yet.
     */
    fun onFix(t: Long, speed: Float): SensorSummary? {
        val dt = if (lastFixT == 0L) 0f else (t - lastFixT) / 1e9f
        val fullySettled = samples > 0 && settledSamples >= samples * 0.9
        if (fullySettled && dt in 0.5f..3.5f && !lastSpeed.isNaN() && !speed.isNaN()) {
            val gpsAccel = (speed - lastSpeed) / dt
            if (abs(gpsAccel) >= LEARN_ACCEL && max(speed, lastSpeed) >= LEARN_SPEED) {
                for (i in 0..2) calib[i] += gpsAccel * hSum[i] / settledSamples
                calibCount++
                if (calibCount >= LEARN_SAMPLES) {
                    val v = FloatArray(3) { calib[it].toFloat() }
                    val vertical = dot(v, up)
                    for (i in 0..2) v[i] -= vertical * up[i]
                    val n = norm(v)
                    if (n > 1e-4f) {
                        for (i in 0..2) forward[i] = v[i] / n
                        calibrated = true
                    }
                }
            }
        }
        val out = if (fullySettled && lonN > 0 && lonN >= settledSamples * 0.9) {
            SensorSummary((lonSum / lonN).toFloat(), lonMin, lonMax, latPeak)
        } else null
        resetInterval()
        lastSpeed = speed
        lastFixT = t
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray) = sqrt(dot(a, a))
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun angleDeg(a: FloatArray, b: FloatArray) = Math.toDegrees(acos(dot(a, b).coerceIn(-1f, 1f).toDouble()))

    companion object {
        private const val MOVED_DEG = 10.0
        private const val HANDLING_RAD_S = 0.35f
        private const val HANDLING_HOLD_NS = 3_000_000_000L
        /** Screen on: tilting faster than this (about 11°/s), or drifting more than [STEADY_DEG] within 2 s, means in a hand. */
        private const val IN_USE_RAD_S = 0.2f
        private const val STEADY_DEG = 3.0
        private const val STEADY_WINDOW_NS = 2_000_000_000L
        private const val STEADY_CONFIRM_NS = 5_000_000_000L
        private const val CALM_ACCEL = 1.0f
        private const val SETTLE_NS = 2_000_000_000L
        private const val SMOOTH_S = 0.2f
        private const val LEARN_ACCEL = 0.6f
        private const val LEARN_SPEED = 3f
        private const val LEARN_SAMPLES = 6
    }
}
