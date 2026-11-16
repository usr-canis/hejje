package com.canis.hejje.domain

/**
 * The speed to show while driving. Standing still, GPS keeps reporting a few km/h of noise, so a
 * reading counts only when it is clearly above its own accuracy. Moving off takes two such
 * readings in a row (or one clearly fast one), so the needle rests at 0 at a signal or when parked.
 */
class LiveSpeed {
    private var moving = false
    private var pending = 0

    /** True while the car is taken to be moving (the last [update] returned more than 0). */
    val isMoving: Boolean get() = moving

    fun reset() {
        moving = false
        pending = 0
    }

    /** The speed to show for this fix, in m/s: 0 when it is most likely standing still. */
    fun update(p: RawPoint): Float {
        val raw = p.speed
        if (raw.isNaN()) {
            reset()
            return 0f
        }
        val noise = if (p.speedAcc.isNaN() || p.speedAcc <= 0f) DEFAULT_NOISE else p.speedAcc
        val poorFix = !p.acc.isNaN() && p.acc > POOR_ACCURACY_M
        if (moving) {
            moving = raw >= STOP_MS && raw > noise
        } else {
            val clear = raw >= START_MS && raw > noise * 1.5f && (!poorFix || raw >= FAST_MS)
            pending = if (clear) pending + 1 else 0
            if (clear && (raw >= FAST_MS || pending >= 2)) moving = true
        }
        if (moving) pending = 0
        return if (moving) raw else 0f
    }

    companion object {
        /** About 5 km/h to set off, 3 km/h to stop, 15 km/h counts at once. */
        const val START_MS = 1.4f
        const val STOP_MS = 0.8f
        const val FAST_MS = 4.2f
        private const val DEFAULT_NOISE = 0.7f
        private const val POOR_ACCURACY_M = 30f
    }
}
