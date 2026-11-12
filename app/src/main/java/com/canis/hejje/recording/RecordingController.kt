package com.canis.hejje.recording

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.canis.hejje.R
import com.canis.hejje.data.RawBuffer
import com.canis.hejje.data.SettingsStore
import com.canis.hejje.data.db.RawDao
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.domain.RecordingStateMachine
import com.canis.hejje.domain.RecordingStateMachine.Effect
import com.canis.hejje.domain.TripProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** What the UI and the notification show while a drive is being recorded. */
data class LiveState(
    val startedAt: Long,
    val manual: Boolean,
    val distanceM: Double = 0.0,
    val hasFix: Boolean = false,
    val lastFixAt: Long = 0,
)

/**
 * The single place that reacts to "car connected", "car disconnected", manual start/stop and
 * incoming GPS fixes. Its state is saved on every change, so a recording survives the app being
 * killed or the phone rebooting.
 */
class RecordingController(
    private val app: Context,
    private val settings: SettingsStore,
    private val buffer: RawBuffer,
    private val rawDao: RawDao,
    private val finalizer: TripFinalizer,
    private val scope: CoroutineScope,
) {
    private val prefs = app.getSharedPreferences("recording", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val machine = RecordingStateMachine(load())
    private val flushLock = Mutex()
    private val finishingCount = AtomicInteger(0)
    private var lastPoint: RawPoint? = null
    private var lastFlush = 0L
    private var autoStartPending = false

    private val _live = MutableStateFlow<LiveState?>(null)
    val live: StateFlow<LiveState?> = _live.asStateFlow()

    /** True while the service has work: recording, or saving a drive that just ended. */
    private val _active = MutableStateFlow(machine.state.recording)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _finishing = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    val isRecording: Boolean get() = machine.state.recording
    val startedAt: Long get() = machine.state.startedAt
    private var session: String?
        get() = prefs.getString(KEY_SESSION, null)
        set(value) = prefs.edit(commit = true) { putString(KEY_SESSION, value) }

    private fun now() = System.currentTimeMillis()

    private fun load() = RecordingStateMachine.State(
        recording = prefs.getBoolean("recording", false),
        startedAt = prefs.getLong("startedAt", 0),
        manual = prefs.getBoolean("manual", false),
        carConnected = prefs.getBoolean("carConnected", false),
        disconnectAt = prefs.getLong("disconnectAt", 0),
    )

    private fun persist() {
        val s = machine.state
        prefs.edit(commit = true) {
            putBoolean("recording", s.recording).putLong("startedAt", s.startedAt).putBoolean("manual", s.manual)
                .putBoolean("carConnected", s.carConnected).putLong("disconnectAt", s.disconnectAt)
        }
        _active.value = s.recording || finishingCount.get() > 0
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun carConnected() = onMain { apply(machine.onCarConnected(now(), settings.current.autoRecord)) }

    fun carDisconnected(at: Long = now()) = onMain { apply(machine.onCarDisconnected(at)) }

    fun manualStart() = onMain { apply(machine.onManualStart(now())) }

    fun manualStop() = onMain { apply(machine.onManualStop(now())) }

    private val timer = Runnable { apply(machine.onTimer(now())) }

    private fun apply(effects: List<Effect>) {
        val startedAt = _live.value?.startedAt ?: prefs.getLong("startedAt", 0)
        persist()
        for (e in effects) when (e) {
            is Effect.Start -> start(e)
            is Effect.Stop -> stop(startedAt, e.endAt)
            is Effect.Schedule -> {
                main.removeCallbacks(timer)
                main.postDelayed(timer, max(0L, e.at - now()))
            }
            Effect.CancelSchedule -> main.removeCallbacks(timer)
        }
    }

    private fun start(e: Effect.Start) {
        session = UUID.randomUUID().toString()
        lastPoint = null
        autoStartPending = !e.manual
        _live.value = LiveState(e.at, e.manual)
        launchService()
    }

    private fun launchService(): Boolean = try {
        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        true
    } catch (ex: Exception) {
        // Android refused a background start (missing "Allow all the time" or battery exemption).
        startFailed(app.getString(R.string.problem_background_start))
        false
    }

    /** Called when recording could not begin; nothing has been recorded. */
    fun startFailed(reason: String) = onMain {
        val id = session
        machine.state = RecordingStateMachine.State(carConnected = machine.state.carConnected)
        main.removeCallbacks(timer)
        session = null
        _live.value = null
        persist()
        reportProblem(reason)
        if (id != null) scope.launch { finalizer.finalizeOrphan(id) }
    }

    fun reportProblem(reason: String) {
        scope.launch { settings.update { it.copy(lastProblem = reason, lastProblemAt = now()) } }
    }

    private fun stop(startedAt: Long, endAt: Long) {
        val id = session ?: return
        session = null
        _live.value = null
        finishingCount.incrementAndGet()
        _finishing.value = true
        _active.value = true
        scope.launch {
            try {
                flushLock.withLock { buffer.flush() }
                finalizer.finalize(id, startedAt, max(endAt, startedAt + 1))
            } catch (ex: Exception) {
                reportProblem(app.getString(R.string.problem_save_failed, ex.message ?: ex.javaClass.simpleName))
            } finally {
                onMain {
                    _finishing.value = finishingCount.decrementAndGet() > 0
                    _active.value = machine.state.recording || finishingCount.get() > 0
                }
            }
        }
    }

    /** A new GPS fix from the service (main thread). */
    fun onLocation(p: RawPoint) {
        val live = _live.value ?: return
        val id = session ?: return
        buffer.append(id, p)
        var distance = live.distanceM
        if (p.acc.isNaN() || p.acc <= TripProcessor.MAX_ACCURACY_M) {
            val last = lastPoint
            if (last != null && p.t > last.t) {
                val d = Geo.distance(last.lat, last.lon, p.lat, p.lon)
                val speed = d / ((p.t - last.t) / 1000.0)
                val moving = if (p.speed.isNaN()) speed > 1.0 else p.speed > 1.0f
                if (moving && speed < 70) distance += d
            }
            lastPoint = p
        }
        _live.value = live.copy(distanceM = distance, hasFix = true, lastFixAt = p.t)
        if (autoStartPending) {
            autoStartPending = false
            scope.launch { settings.update { it.copy(lastAutoStart = now()) } }
        }
        if (buffer.pendingCount >= BATCH_SIZE || p.t - lastFlush >= BATCH_MS) {
            lastFlush = p.t
            scope.launch { flushLock.withLock { buffer.flush() } }
        }
    }

    /** Called regularly by the service: flags GPS loss (tunnel) for the notification. */
    fun checkFix() {
        val live = _live.value ?: return
        if (live.hasFix && now() - live.lastFixAt > FIX_LOST_MS) _live.value = live.copy(hasFix = false)
    }

    /**
     * Runs once when the app process starts. Saves fixes left over from a crash and, if a drive
     * was in progress, carries on recording it (or closes it when the car is no longer connected).
     */
    fun recover() {
        scope.launch {
            buffer.recover()
            val activeId = session
            for (orphan in rawDao.sessions()) if (orphan != activeId) finalizer.finalizeOrphan(orphan)
            val lastTime = activeId?.let { rawDao.lastTime(it) }
            val distance = activeId?.let { id -> liveDistance(rawDao.points(id).map(RawBuffer::toRaw)) } ?: 0.0
            onMain { resume(activeId, lastTime, distance) }
        }
    }

    private fun resume(activeId: String?, lastTime: Long?, distance: Double) {
        val s = machine.state
        if (!s.recording) return
        if (activeId == null) {
            machine.state = RecordingStateMachine.State(carConnected = s.carConnected)
            persist()
            return
        }
        if (_live.value == null) _live.value = LiveState(s.startedAt, s.manual, distance, lastFixAt = lastTime ?: 0)
        if (!launchService()) return
        if (s.disconnectAt != 0L) {
            apply(machine.onTimer(now()))
            return
        }
        // We may have missed the disconnect while the app was not running: check the car directly.
        val address = settings.current.carAddress
        if (address != null && s.carConnected) {
            CarBluetooth.isConnected(app, address) { connected ->
                if (!connected && machine.state.recording && machine.state.disconnectAt == 0L) {
                    carDisconnected(lastTime ?: now())
                }
            }
        }
    }

    private fun liveDistance(points: List<RawPoint>): Double {
        var d = 0.0
        val clean = TripProcessor.clean(points)
        for (i in 1 until clean.size) {
            if (clean[i].speed.isNaN() || clean[i].speed > 1f) d += Geo.distance(clean[i - 1].lat, clean[i - 1].lon, clean[i].lat, clean[i].lon)
        }
        return d
    }

    companion object {
        private const val KEY_SESSION = "session"
        private const val BATCH_SIZE = 30
        private const val BATCH_MS = 30_000L
        private const val FIX_LOST_MS = 15_000L
    }
}
