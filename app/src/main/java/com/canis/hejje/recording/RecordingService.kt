package com.canis.hejje.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.canis.hejje.HejjeApp
import com.canis.hejje.R
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.notify.Notifier
import com.canis.hejje.ui.formatter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type "location") that keeps GPS running with the screen off and the app
 * closed. It only collects fixes; all decisions are made by [RecordingController].
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: RecordingController
    private lateinit var notifier: Notifier
    private lateinit var fused: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var updating = false
    private var intervalMs = 0L
    private var pressure = Float.NaN
    private var foreground = false
    private var shownText: String? = ""
    private var shownFinishing: Boolean? = null
    private lateinit var motion: MotionSensors

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) controller.onLocation(toPoint(l))
        }
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val p = event.values[0]
            pressure = if (pressure.isNaN()) p else pressure * 0.85f + p * 0.15f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                // Bluetooth switched off: the car is gone even if no disconnect event arrives.
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                    controller.carDisconnected(source = com.canis.hejje.data.DriveLog.SOURCE_BLUETOOTH_OFF)
                }
            } else if (updating) {
                requestUpdates()
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            controller.checkFix()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as HejjeApp).container
        controller = container.controller
        notifier = container.notifier
        fused = LocationServices.getFusedLocationProviderClient(this)
        motion = MotionSensors(this)
        ContextCompat.registerReceiver(
            this, powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(controller.live, controller.finishing, controller.active) { live, finishing, active -> Triple(live, finishing, active) }
                .collect { (live, finishing, active) ->
                    if (live != null) startUpdates() else stopUpdates()
                    if (!active) {
                        shutdown()
                    } else if (foreground) {
                        showNotification(live, finishing && live == null)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            controller.startFailed(getString(R.string.problem_background_start))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!controller.active.value) {
            shutdown()
            return START_NOT_STICKY
        }
        if (controller.live.value != null) startUpdates()
        return START_STICKY
    }

    /** Must happen within seconds of the service being started. Returns false if Android refuses. */
    private fun enterForeground(): Boolean {
        if (!hasLocationPermission()) return false
        val live = controller.live.value
        val notification = notifier.recording(live?.startedAt ?: controller.startedAt, null, finishing = false)
        return try {
            ServiceCompat.startForeground(
                this, Notifier.ID_RECORDING, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            foreground = true
            shownText = null
            shownFinishing = false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showNotification(live: LiveState?, finishing: Boolean) {
        val text = live?.takeIf { it.hasFix }?.let { formatter().distance(it.distanceM) }
        if (text == shownText && finishing == shownFinishing) return
        shownText = text
        shownFinishing = finishing
        notifier.updateRecording(notifier.recording(live?.startedAt ?: controller.startedAt, text, finishing))
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startUpdates() {
        if (updating) return
        if (!hasLocationPermission()) {
            controller.startFailed(getString(R.string.problem_no_location_permission))
            return
        }
        updating = true
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hejje:recording")
                .apply { setReferenceCounted(false); acquire(MAX_DRIVE_MS) }
        }
        getSystemService(SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(pressureListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        motion.start()
        requestUpdates()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 10_000)
    }

    /** 1 s while charging and 2 s on battery by default; slower in battery-saver mode. */
    @SuppressLint("MissingPermission") // checked in startUpdates()
    private fun requestUpdates() {
        val charging = getSystemService(BatteryManager::class.java)?.isCharging == true
        val saver = (application as HejjeApp).container.settings.current.gpsMode == 1
        val wanted = when {
            charging && !saver -> 1_000L
            charging || !saver -> 2_000L
            else -> 5_000L
        }
        if (wanted == intervalMs) return
        intervalMs = wanted
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, wanted)
            .setMinUpdateIntervalMillis(wanted)
            .setMaxUpdateDelayMillis(0)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            controller.startFailed(getString(R.string.problem_no_location_permission))
        }
    }

    private fun stopUpdates() {
        if (!updating) return
        updating = false
        intervalMs = 0
        fused.removeLocationUpdates(callback)
        getSystemService(SensorManager::class.java)?.unregisterListener(pressureListener)
        motion.stop()
        pressure = Float.NaN
        handler.removeCallbacks(watchdog)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun shutdown() {
        stopUpdates()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun toPoint(l: Location): RawPoint {
        // Use the phone clock (corrected for the fix's age) so times line up with Bluetooth events.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        val altitude = when {
            Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters.toFloat()
            l.hasAltitude() -> l.altitude.toFloat()
            else -> Float.NaN
        }
        val speed = if (l.hasSpeed()) l.speed else Float.NaN
        val sensed = motion.onFix(l.elapsedRealtimeNanos, speed)
        return RawPoint(
            t = System.currentTimeMillis() - ageMs.coerceIn(0, 10_000),
            lat = l.latitude,
            lon = l.longitude,
            alt = altitude,
            speed = speed,
            acc = if (l.hasAccuracy()) l.accuracy else Float.NaN,
            pressure = pressure,
            speedAcc = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else Float.NaN,
            sLon = sensed?.meanLon ?: Float.NaN,
            sMin = sensed?.minLon ?: Float.NaN,
            sMax = sensed?.maxLon ?: Float.NaN,
            sLat = sensed?.peakLat ?: Float.NaN,
        )
    }

    override fun onDestroy() {
        stopUpdates()
        runCatching { unregisterReceiver(powerReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_DRIVE_MS = 24 * 3_600_000L
    }
}
