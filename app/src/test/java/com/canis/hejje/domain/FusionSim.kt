package com.canis.hejje.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A deterministic replay for [SpeedFusion]: the car's true motion, the phone's sensor samples at
 * 25 Hz and GPS fixes at 1 Hz with realistic noise, delivered in order of arrival (fixes may come
 * late, twice, or not at all). The same seed and profile always give the same events.
 */
class FusionSim(seed: Int = 1) {
    class Event(
        val deliverT: Long, val t: Long, val isFix: Boolean, val a: Float, val trusted: Boolean,
        val speed: Float, val speedAcc: Float, val lat: Double, val lon: Double, val acc: Float,
    )

    /** The truth when an event arrived, and what the fusion said after it. */
    class Sample(val t: Long, val trueSpeed: Float, val trueDist: Double, val reading: FusedReading)

    private val rnd = Random(seed)
    val events = ArrayList<Event>()
    private val truthSpeed = HashMap<Long, Float>()
    private val truthDist = HashMap<Long, Double>()

    var t = 1_000_000_000L
        private set

    /** Signed velocity along the car's forward axis (negative when reversing). */
    var u = 0f
    var dist = 0.0
        private set
    private var x = 0.0
    private var posNoiseX = 0.0
    private var posNoiseY = 0.0
    private var nextFixT = t

    // What the environment does to the readings.
    var trusted = true
    var slopeLeak = 0f
    var engineOn = true
    var gpsOn = true
    var gpsDelayNs = 0L
    var duplicateFixes = false
    var sensorBias = 0.08f

    private fun gauss(sigma: Float): Float {
        var s: Double
        var a: Double
        do {
            a = rnd.nextDouble(-1.0, 1.0)
            val b = rnd.nextDouble(-1.0, 1.0)
            s = a * a + b * b
        } while (s >= 1.0 || s == 0.0)
        return (a * sqrt(-2.0 * ln(s) / s) * sigma).toFloat()
    }

    private fun sample(aTrue: Float) {
        val still = abs(u) < 1e-4f
        val a = if (trusted) {
            aTrue + sensorBias + slopeLeak + gauss(0.12f) + (if (engineOn && still) gauss(0.1f) else 0f)
        } else {
            gauss(3f) // in a hand: meaningless
        }
        events += Event(t, t, false, a, trusted, Float.NaN, Float.NaN, 0.0, 0.0, Float.NaN)
    }

    private fun fix() {
        if (!gpsOn) return
        val speed = abs(u)
        val still = speed < 1e-4f
        // Standing still, GNSS Doppler speed mostly reads a tenth or so, now and then a few tenths.
        val reported = if (still) abs(gauss(0.12f)).coerceAtMost(0.4f) else (speed + gauss(0.1f)).coerceAtLeast(0f)
        val speedAcc = if (still) 0.6f else 0.4f
        posNoiseX = (posNoiseX + gauss(1.0f)).coerceIn(-4.0, 4.0)
        posNoiseY = (posNoiseY + gauss(1.0f)).coerceIn(-4.0, 4.0)
        val lat = 12.97 + posNoiseY / 110_540.0
        val lon = 77.59 + (x + posNoiseX) / (111_320.0 * cos(Math.toRadians(12.97)))
        val e = Event(t + gpsDelayNs, t, true, Float.NaN, trusted, reported, speedAcc, lat, lon, 5f)
        events += e
        if (duplicateFixes) events += e
    }

    /** Drives for [seconds] with a constant true acceleration [accel] (m/s², signed). */
    fun phase(seconds: Float, accel: Float): FusionSim {
        repeat((seconds * 25 + 0.5f).toInt()) {
            val dt = 0.04f
            u += accel * dt
            x += u * dt
            dist += abs(u) * dt
            t += 40_000_000L
            truthSpeed[t] = abs(u)
            truthDist[t] = dist
            sample(accel)
            if (t >= nextFixT) { fix(); nextFixT += 1_000_000_000L }
        }
        return this
    }

    fun stop(seconds: Float): FusionSim {
        u = 0f
        return phase(seconds, 0f)
    }

    fun cruise(seconds: Float) = phase(seconds, 0f)

    /** Accelerates (or brakes) at [accel] until the velocity is [target] (signed). */
    fun to(target: Float, accel: Float): FusionSim {
        val a = if (target > u) abs(accel) else -abs(accel)
        phase(abs(target - u) / abs(accel), a)
        u = target
        return this
    }

    /** Feeds everything to [fusion] in order of arrival; the truth beside each reading is as of arrival. */
    fun run(fusion: SpeedFusion = SpeedFusion()): List<Sample> {
        val out = ArrayList<Sample>(events.size)
        var speedNow = 0f
        var distNow = 0.0
        for (e in events.sortedWith(compareBy({ it.deliverT }, { if (it.isFix) 1 else 0 }))) {
            truthSpeed[e.deliverT]?.let { speedNow = it }
            truthDist[e.deliverT]?.let { distNow = it }
            val r = if (e.isFix) {
                fusion.onFix(e.t, e.speed, e.speedAcc, e.lat, e.lon, e.acc).reading
            } else {
                fusion.onAccel(e.t, e.a, e.trusted)
                fusion.reading(e.t)
            }
            out += Sample(e.deliverT, speedNow, distNow, r)
        }
        return out
    }
}
