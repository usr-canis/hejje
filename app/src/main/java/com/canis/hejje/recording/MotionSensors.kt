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
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.canis.hejje.domain.MotionEstimator
import com.canis.hejje.domain.SensorSummary

/**
 * Feeds the phone's motion sensors into a [MotionEstimator] while a drive is recorded.
 * Readings are taken on a background thread at 25 per second; the screen state is tracked
 * because a phone in use is never treated as settled.
 */
class MotionSensors(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val estimator = MotionEstimator()
    private var thread: HandlerThread? = null
    private val gravity = FloatArray(3)
    private val gyro = FloatArray(3)
    private var haveGravity = false
    private var haveGyro = false

    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    /** Starts the sensors; false when the phone lacks them (recording then uses GPS alone). */
    fun start(): Boolean {
        if (thread != null) return true
        val linear = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return false
        val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return false
        synchronized(estimator) { estimator.reset() }
        haveGravity = false
        haveGyro = false
        screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        ContextCompat.registerReceiver(
            context, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val t = HandlerThread("hejje-motion").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        sm.registerListener(this, gravitySensor, PERIOD_US, handler)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, PERIOD_US, handler) }
        sm.registerListener(this, linear, PERIOD_US, handler)
        return true
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        sm?.unregisterListener(this)
        runCatching { context.unregisterReceiver(screenReceiver) }
        t.quitSafely()
    }

    /** The car's motion since the previous fix; [elapsedNanos] is the fix's time since boot. */
    fun onFix(elapsedNanos: Long, speed: Float): SensorSummary? =
        if (thread == null) null else synchronized(estimator) { estimator.onFix(elapsedNanos, speed) }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> { event.values.copyInto(gravity, endIndex = 3); haveGravity = true }
            Sensor.TYPE_GYROSCOPE -> { event.values.copyInto(gyro, endIndex = 3); haveGyro = true }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (haveGravity) synchronized(estimator) {
                estimator.onMotion(event.timestamp, event.values, gravity, if (haveGyro) gyro else null, screenOn)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val PERIOD_US = 40_000
    }
}
