package com.canis.hejje.domain

/**
 * Decides when a recording starts and stops. Pure logic with no Android dependency so it can be
 * tested with simulated Bluetooth events.
 *
 * - The car's Bluetooth connecting starts a recording (when auto-record is on).
 * - A disconnect does not stop immediately: the recording ends only if the car stays
 *   disconnected for [graceMs]. Short drop-outs ("flapping") are ignored.
 * - The recording then ends at the moment of the disconnect, not at the end of the grace period.
 * - A manual recording runs until stopped by hand, or until the car's Bluetooth, if it
 *   connected during the drive, disconnects.
 */
class RecordingStateMachine(var state: State = State(), private val graceMs: Long = DEFAULT_GRACE_MS) {

    data class State(
        val recording: Boolean = false,
        val startedAt: Long = 0,
        val manual: Boolean = false,
        val carConnected: Boolean = false,
        /** Time of the Bluetooth disconnect we are waiting out, or 0. */
        val disconnectAt: Long = 0,
    )

    sealed interface Effect {
        data class Start(val at: Long, val manual: Boolean) : Effect
        data class Stop(val endAt: Long) : Effect
        /** Ask to be called back with [onTimer] at [at]. */
        data class Schedule(val at: Long) : Effect
        data object CancelSchedule : Effect
    }

    fun onCarConnected(now: Long, autoRecord: Boolean): List<Effect> {
        val s = state
        return when {
            s.recording && s.disconnectAt != 0L -> {
                state = s.copy(carConnected = true, disconnectAt = 0)
                listOf(Effect.CancelSchedule)
            }
            s.recording -> {
                state = s.copy(carConnected = true)
                emptyList()
            }
            autoRecord -> {
                state = State(recording = true, startedAt = now, manual = false, carConnected = true)
                listOf(Effect.Start(now, manual = false))
            }
            else -> {
                state = s.copy(carConnected = true)
                emptyList()
            }
        }
    }

    fun onCarDisconnected(now: Long): List<Effect> {
        val s = state
        if (!s.recording) {
            state = s.copy(carConnected = false)
            return emptyList()
        }
        if (!s.carConnected || s.disconnectAt != 0L) return emptyList()
        state = s.copy(carConnected = false, disconnectAt = now)
        return listOf(Effect.Schedule(now + graceMs))
    }

    fun onTimer(now: Long): List<Effect> {
        val s = state
        if (!s.recording || s.disconnectAt == 0L) return emptyList()
        if (now < s.disconnectAt + graceMs) return listOf(Effect.Schedule(s.disconnectAt + graceMs))
        state = State()
        return listOf(Effect.Stop(s.disconnectAt))
    }

    fun onManualStart(now: Long): List<Effect> {
        val s = state
        if (s.recording) return emptyList()
        state = State(recording = true, startedAt = now, manual = true, carConnected = s.carConnected)
        return listOf(Effect.Start(now, manual = true))
    }

    fun onManualStop(now: Long): List<Effect> {
        val s = state
        if (!s.recording) return emptyList()
        state = State(carConnected = s.carConnected && s.disconnectAt == 0L)
        val endAt = if (s.disconnectAt != 0L) s.disconnectAt else now
        return listOf(Effect.CancelSchedule, Effect.Stop(endAt))
    }

    companion object {
        const val DEFAULT_GRACE_MS = 25_000L
    }
}
