package com.canis.hejje.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.canis.hejje.domain.FusedReading
import com.canis.hejje.domain.MotionEstimator
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.domain.SpeedFusion

/**
 * The one worker behind a recording: the phone's motion sensors (25 per second) and every GPS fix
 * meet here, on one background thread, in [MotionEstimator] (which way is forward, is the phone
 * settled) and [SpeedFusion] (speed, motion state, distance). Fixes are stamped on the sensor
 * clock, so a late fix is applied at its own time.
 *
 * [onPoint] gets each fix back with the fused values filled in; [onReading] gets the live picture
 * about ten times a second. Both run on the main thread.
 */
class MotionSensors(
    private val context: Context,
    private val onPoint: (RawPoint) -> Unit,
    private val onReading: (FusedReading) -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val estimator = MotionEstimator()
    private val fusion = SpeedFusion()
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val gravity = FloatArray(3)
    private val gyro = FloatArray(3)
    private var haveGravity = false
    private var haveGyro = false
    private var lastPublished = 0L

    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    /** Starts the worker and, when the phone has them, the sensors. Returns whether it has them. */
    fun start(): Boolean {
        if (thread != null) return haveSensors
        estimator.reset()
        fusion.reset()
        haveGravity = false
        haveGyro = false
        lastPublished = 0
        val t = HandlerThread("hejje-motion").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val linear = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gravitySensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        haveSensors = linear != null && gravitySensor != null
        if (!haveSensors) return false
        screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        ContextCompat.registerReceiver(
            context, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sm.registerListener(this, gravitySensor, PERIOD_US, h)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, PERIOD_US, h) }
        sm.registerListener(this, linear, PERIOD_US, h)
        return true
    }

    private var haveSensors = false

    fun stop() {
        val t = thread ?: return
        thread = null
        handler = null
        if (haveSensors) {
            sm?.unregisterListener(this)
            runCatching { context.unregisterReceiver(screenReceiver) }
        }
        t.quitSafely()
    }

    /**
     * A GPS fix, from the main thread; [elapsedNanos] is when it was measured, on the sensor clock.
     * It comes back through [onPoint] with the sensor summary and the fused speed, distance and state.
     */
    fun fix(p: RawPoint, elapsedNanos: Long) {
        val h = handler
        if (h == null) {
            onPoint(p)
            return
        }
        h.post {
            val summary = estimator.onFix(elapsedNanos, p.speed)
            val result = fusion.onFix(elapsedNanos, p.speed, p.speedAcc, p.lat, p.lon, p.acc)
            // Stored with the fix: the picture at its own measurement time (no speed while GPS is lost).
            val r = result.reading
            val enriched = p.copy(
                sLon = summary?.meanLon ?: Float.NaN, sMin = summary?.minLon ?: Float.NaN,
                sMax = summary?.maxLon ?: Float.NaN, sLat = summary?.peakLat ?: Float.NaN,
                fSpeed = if (r.gap) Float.NaN else r.speed, fDist = result.sinceFixM, fState = r.state.ordinal,
            )
            val live = result.live
            main.post {
                onPoint(enriched)
                onReading(live)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> { event.values.copyInto(gravity, endIndex = 3); haveGravity = true }
            Sensor.TYPE_GYROSCOPE -> { event.values.copyInto(gyro, endIndex = 3); haveGyro = true }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (haveGravity) {
                val forward = estimator.onMotion(event.timestamp, event.values, gravity, if (haveGyro) gyro else null, screenOn)
                fusion.onAccel(event.timestamp, forward, trusted = !forward.isNaN())
                if (event.timestamp - lastPublished >= PUBLISH_NS) {
                    lastPublished = event.timestamp
                    val r = fusion.reading(event.timestamp)
                    main.post { onReading(r) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val PERIOD_US = 40_000
        const val PUBLISH_NS = 100_000_000L
    }
}
