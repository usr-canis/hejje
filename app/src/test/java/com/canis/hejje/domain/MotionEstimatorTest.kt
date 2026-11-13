package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Simulated phone lying at an angle in a car, with road vibration, driven through stops and turns. */
class MotionEstimatorTest {
    /** Rotation from car axes (x forward, y left, z up) to phone axes. */
    private fun rotation(rx: Double, ry: Double, rz: Double): Array<DoubleArray> {
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }
        val x = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(rx), -sin(rx)), doubleArrayOf(0.0, sin(rx), cos(rx)))
        val y = arrayOf(doubleArrayOf(cos(ry), 0.0, sin(ry)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(ry), 0.0, cos(ry)))
        val z = arrayOf(doubleArrayOf(cos(rz), -sin(rz), 0.0), doubleArrayOf(sin(rz), cos(rz), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        return mul(mul(x, y), z)
    }

    private fun apply(r: Array<DoubleArray>, v: DoubleArray) = FloatArray(3) { i -> (0..2).sumOf { r[i][it] * v[it] }.toFloat() }

    private class Result(val summaries: List<Pair<SensorSummary?, Double>>, val lateral: List<Double>)

    /**
     * Drives [seconds] of stop-and-go with turns. Returns, per GPS fix, the summary and the true mean
     * forward acceleration over that second.
     */
    private fun drive(
        est: MotionEstimator, seconds: Int, phone: (Int) -> Array<DoubleArray>, screenOn: (Int) -> Boolean = { false },
        seed: Int = 3,
    ): Result {
        val rnd = Random(seed)
        var speed = 0.0
        val out = ArrayList<Pair<SensorSummary?, Double>>()
        val lateral = ArrayList<Double>()
        for (sec in 0 until seconds) {
            val phase = sec % 20
            val lon = when (phase) { in 0..5 -> 2.2; in 6..10 -> 0.0; in 11..14 -> -3.0; else -> 0.0 }
            val turning = phase in 7..9
            val lat = if (turning) 2.6 else 0.0
            var trueSum = 0.0
            for (k in 0 until 50) {
                val t = sec * 1_000_000_000L + k * 20_000_000L
                val a = if (speed <= 0.0 && lon < 0) 0.0 else lon
                speed = (speed + a * 0.02).coerceAtLeast(0.0)
                trueSum += a
                val r = phone(sec)
                val vib = 1.2 * sin(2 * PI * 13 * t / 1e9)
                val car = doubleArrayOf(a + rnd.nextDouble(-0.3, 0.3) + vib * 0.3, lat + rnd.nextDouble(-0.3, 0.3), vib)
                val gyro = apply(r, doubleArrayOf(rnd.nextDouble(-0.02, 0.02), rnd.nextDouble(-0.02, 0.02), if (turning) 0.25 else 0.0))
                est.onMotion(t, apply(r, car), apply(r, doubleArrayOf(0.0, 0.0, 9.81)), gyro, screenOn(sec))
            }
            val gpsSpeed = (speed + rnd.nextDouble(-0.2, 0.2)).toFloat()
            out += est.onFix((sec + 1) * 1_000_000_000L, gpsSpeed) to trueSum / 50
            if (turning) lateral += lat
        }
        return Result(out, lateral)
    }

    private val mounted = rotation(Math.toRadians(62.0), Math.toRadians(8.0), Math.toRadians(33.0))

    @Test fun phoneAtAnAngle_learnsForward_andMeasuresTheCar() {
        val est = MotionEstimator()
        val r = drive(est, 240, { mounted })
        assertTrue(est.isCalibrated)
        val late = r.summaries.drop(60).filter { it.first != null }
        assertTrue("sensor data for most of the drive: ${late.size}", late.size > 150)
        val errors = late.map { abs(it.first!!.meanLon - it.second) }.sorted()
        assertTrue("median error ${errors[errors.size / 2]}", errors[errors.size / 2] < 0.25)
        assertTrue("90th percentile error ${errors[errors.size * 9 / 10]}", errors[errors.size * 9 / 10] < 0.8)
        // Hard braking (-3 m/s²) is clearly visible, with the right sign.
        assertTrue(late.minOf { it.first!!.minLon } < -2.5f)
        assertTrue(late.maxOf { it.first!!.maxLon } > 1.8f)
        // Cornering: about 2.6 m/s² sideways during turns.
        val peak = late.maxOf { it.first!!.peakLat }
        assertEquals(2.6, peak.toDouble(), 0.7)
    }

    @Test fun screenOn_inAMount_stillMeasures() {
        val est = MotionEstimator()
        val r = drive(est, 240, { mounted }, screenOn = { true })
        assertTrue(est.isCalibrated)
        val late = r.summaries.drop(60).filter { it.first != null }
        assertTrue("sensor data with the screen on: ${late.size}", late.size > 150)
        val errors = late.map { abs(it.first!!.meanLon - it.second) }.sorted()
        assertTrue("median error ${errors[errors.size / 2]}", errors[errors.size / 2] < 0.25)
    }

    @Test fun screenOn_inAHand_meansNoSensorData() {
        val est = MotionEstimator()
        // Held in a hand: the angle keeps wandering by a few degrees.
        val r = drive(est, 120, { sec -> rotation(Math.toRadians(62.0 + 4 * sin(sec * 1.3)), Math.toRadians(8.0 + 3 * cos(sec * 0.9)), Math.toRadians(33.0)) }, screenOn = { true })
        assertTrue("${r.summaries.count { it.first != null }} readings used", r.summaries.count { it.first != null } <= 3)
    }

    @Test fun phonePickedUp_stopsUsingSensors_thenLearnsAgain() {
        val est = MotionEstimator()
        val other = rotation(Math.toRadians(-20.0), Math.toRadians(40.0), Math.toRadians(150.0))
        val r = drive(est, 300, { sec -> if (sec < 120) mounted else other })
        // Right after the phone moved, there is no sensor data.
        assertTrue(r.summaries.subList(120, 124).all { it.first == null })
        // Later it has learned the new position and measures correctly again.
        val late = r.summaries.drop(220).filter { it.first != null }
        assertTrue("relearned: ${late.size}", late.size > 40)
        val errors = late.map { abs(it.first!!.meanLon - it.second) }.sorted()
        assertTrue("median error ${errors[errors.size / 2]}", errors[errors.size / 2] < 0.3)
    }

    @Test fun speedBreaker_costsLessThanASecond() {
        val est = MotionEstimator()
        drive(est, 120, { mounted })
        assertTrue(est.isCalibrated)
        // A bump: the phone pitches sharply for 0.3 s and swings back to where it was.
        val rest = doubleArrayOf(0.0, 0.0, 9.81)
        for (k in 0 until 50) {
            val t = 120_000_000_000L + k * 20_000_000L
            val inBump = k in 5..19
            val pitch = if (inBump) Math.toRadians(4.0 * sin(PI * (k - 5) / 15.0)) else 0.0
            val gyro = if (inBump) floatArrayOf((if (k < 12) 1.0f else -1.0f), 0f, 0f) else floatArrayOf(0f, 0f, 0f)
            val r = rotation(Math.toRadians(62.0) + pitch, Math.toRadians(8.0), Math.toRadians(33.0))
            est.onMotion(t, apply(r, doubleArrayOf(0.0, 0.0, if (inBump) 3.0 else 0.0)), apply(r, rest), apply(r, gyro.map { it.toDouble() }.toDoubleArray()), false)
        }
        est.onFix(121_000_000_000L, 10f)
        // The next second is measured again: no 3-second gap.
        for (k in 0 until 50) {
            val t = 121_000_000_000L + k * 20_000_000L
            est.onMotion(t, apply(mounted, doubleArrayOf(0.0, 0.0, 0.0)), apply(mounted, rest), floatArrayOf(0f, 0f, 0f), false)
        }
        assertNotNull(est.onFix(122_000_000_000L, 10f))
    }

    @Test fun handled_noData_untilItSettles() {
        val est = MotionEstimator()
        drive(est, 120, { mounted })
        assertTrue(est.isCalibrated)
        // Someone fiddles with the phone: strong tilting rotation, then it rests again.
        for (k in 0 until 50) {
            val t = 120_000_000_000L + k * 20_000_000L
            est.onMotion(t, floatArrayOf(0.5f, 0f, 0f), apply(mounted, doubleArrayOf(0.0, 0.0, 9.81)), floatArrayOf(1.2f, 0f, 0f), false)
        }
        assertNull(est.onFix(121_000_000_000L, 10f))
    }
}
