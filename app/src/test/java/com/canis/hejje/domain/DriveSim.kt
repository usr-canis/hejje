package com.canis.hejje.domain

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Generates realistic GPS fixes for tests: accelerate, cruise, turn, idle, lose signal. */
class DriveSim(
    var t: Long = T0,
    var lat: Double = 12.9716,
    var lon: Double = 77.5946,
    private val stepMs: Long = 1000,
    private val seed: Int = 7,
    private val speedNoise: Float = 0f,
    private val positionNoiseM: Double = 0.0,
    var pressureAtStart: Float = Float.NaN,
    /** Adds what a settled phone's motion sensors would report with each fix. */
    private val sensors: Boolean = false,
) {
    /** Added to the reported GPS speed only (a GPS glitch the car does not feel). */
    var gpsSpeedOffset = 0f
    /** Reported GPS speed accuracy, m/s. */
    var speedAccuracy = Float.NaN
    private var lonAccel = 0f
    private var latAccel = 0f
    val points = ArrayList<RawPoint>()
    var heading = 45.0
    var speed = 0f
    var alt = 900f
    private val rnd = Random(seed)

    private fun emit(visible: Boolean, accuracy: Float = 5f) {
        if (!visible) return
        val nLat = lat + rnd.nextDouble(-1.0, 1.0) * positionNoiseM / 110_540.0
        val nLon = lon + rnd.nextDouble(-1.0, 1.0) * positionNoiseM / (111_320.0 * cos(Math.toRadians(lat)))
        val v = (speed + (rnd.nextFloat() * 2 - 1) * speedNoise).coerceAtLeast(0f)
        val pressure = if (pressureAtStart.isNaN()) Float.NaN else pressureAtStart - (alt - 900f) * 0.111f
        val reported = (v + gpsSpeedOffset).coerceAtLeast(0f)
        var p = RawPoint(t, nLat, nLon, alt = alt + (rnd.nextFloat() * 2 - 1) * 3f, speed = reported, acc = accuracy, pressure = pressure, speedAcc = speedAccuracy)
        if (sensors) {
            val noise = (rnd.nextFloat() * 2 - 1) * 0.15f
            p = p.copy(sLon = lonAccel + noise, sMin = lonAccel + noise - 0.4f, sMax = lonAccel + noise + 0.4f, sLat = kotlin.math.abs(latAccel) + 0.2f)
        }
        points += p
    }

    /** Moves for [seconds], changing speed towards [target] by at most [accel] m/s per second. */
    fun drive(seconds: Int, target: Float, accel: Float = 1.5f, turnPerSec: Double = 0.0, climbPerSec: Float = 0f, visible: Boolean = true): DriveSim {
        val dt = stepMs / 1000f
        repeat((seconds * 1000 / stepMs).toInt()) {
            val dv = (target - speed).coerceIn(-accel * dt, accel * dt)
            speed += dv
            lonAccel = dv / dt
            latAccel = (speed * Math.toRadians(turnPerSec)).toFloat()
            heading += turnPerSec * dt
            val d = speed * dt
            lat += d * cos(Math.toRadians(heading)) / 110_540.0
            lon += d * sin(Math.toRadians(heading)) / (111_320.0 * cos(Math.toRadians(lat)))
            alt += climbPerSec * dt
            t += stepMs
            emit(visible)
        }
        return this
    }

    fun idle(seconds: Int): DriveSim = drive(seconds, 0f, accel = 100f)

    /** Tunnel: the car keeps going but no fixes arrive. */
    fun tunnel(seconds: Int): DriveSim = drive(seconds, speed, visible = false)

    fun segment(cfg: ProcessorConfig = ProcessorConfig()): Segment = TripProcessor.process(points, points.first().t, points.last().t, cfg)!!

    companion object {
        /** 2026-01-05 (a Monday) 06:30 UTC = noon in India. */
        const val T0 = 1_767_594_600_000L
    }
}
