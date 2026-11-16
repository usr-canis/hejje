package com.canis.hejje.data

import com.canis.hejje.data.db.RawDao
import com.canis.hejje.data.db.RawPointEntity
import com.canis.hejje.domain.RawPoint
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe store for incoming GPS fixes.
 *
 * Each fix is appended to a small journal file straight away, so it survives the app being killed.
 * Every so often the pending fixes are written to the database in one batch and the journal is
 * rotated. After a crash, [recover] moves whatever is left in journal files into the database.
 */
class RawBuffer(private val dir: File, private val dao: RawDao) {
    /** Fixes not yet in the database, per recording session. */
    private val pending = LinkedHashMap<String, ArrayList<RawPoint>>()
    private var session: String? = null
    private var out: DataOutputStream? = null
    private var file: FileOutputStream? = null
    private var sinceSync = 0

    init {
        dir.mkdirs()
    }

    private fun journal(session: String) = File(dir, "$session$JOURNAL")

    @Synchronized
    fun append(session: String, p: RawPoint) {
        try {
            if (this.session != session || out == null) open(session)
            out?.apply {
                writeLong(p.t); writeDouble(p.lat); writeDouble(p.lon)
                writeFloat(p.alt); writeFloat(p.speed); writeFloat(p.acc); writeFloat(p.pressure); writeFloat(p.speedAcc)
                writeFloat(p.sLon); writeFloat(p.sMin); writeFloat(p.sMax); writeFloat(p.sLat)
                writeFloat(p.fSpeed); writeFloat(p.fDist); writeInt(p.fState)
                flush()
            }
            if (++sinceSync >= SYNC_EVERY) {
                file?.fd?.sync()
                sinceSync = 0
            }
        } catch (_: Exception) {
            // The point is still kept in memory and reaches the database on the next flush.
        }
        pending.getOrPut(session) { ArrayList() } += p
    }

    private fun open(session: String) {
        close()
        this.session = session
        file = FileOutputStream(journal(session), true).also { out = DataOutputStream(it) }
    }

    private fun close() {
        runCatching { out?.close() }
        out = null
        file = null
    }

    val pendingCount: Int @Synchronized get() = pending.values.sumOf { it.size }

    /** Writes the pending fixes to the database, then drops the journals that covered them. */
    suspend fun flush() {
        val batches: Map<String, List<RawPoint>>
        val journals = ArrayList<File>()
        synchronized(this) {
            if (pending.isEmpty()) return
            batches = pending.mapValues { ArrayList(it.value) }
            pending.clear()
            close()
            for (name in batches.keys) {
                val target = File(dir, "$name-${System.nanoTime()}$FLUSHING")
                if (journal(name).renameTo(target)) journals += target
            }
        }
        for ((name, points) in batches) dao.insert(points.map { toEntity(name, it) })
        journals.forEach { it.delete() }
    }

    /** After a restart: move fixes left in journal files into the database. */
    suspend fun recover() {
        synchronized(this) { close(); pending.clear(); session = null }
        val suffixes = listOf(JOURNAL, FLUSHING, V2_JOURNAL, V2_FLUSHING, OLD_JOURNAL, OLD_FLUSHING)
        val files = dir.listFiles { f -> suffixes.any { f.name.endsWith(it) } } ?: return
        for (f in files.sortedBy { it.lastModified() }) {
            val flushing = f.name.endsWith(FLUSHING) || f.name.endsWith(V2_FLUSHING) || f.name.endsWith(OLD_FLUSHING)
            val name = if (flushing) f.name.substringBeforeLast('-') else f.name.removeSuffix(JOURNAL).removeSuffix(V2_JOURNAL).removeSuffix(OLD_JOURNAL)
            val last = dao.lastTime(name) ?: Long.MIN_VALUE
            // Files from before 1.2.0 have no sensor values in each record; before 1.8.0 no fused values.
            val version = when {
                f.name.endsWith(JOURNAL) || f.name.endsWith(FLUSHING) -> 3
                f.name.endsWith(V2_JOURNAL) || f.name.endsWith(V2_FLUSHING) -> 2
                else -> 1
            }
            val points = read(f, version).filter { it.t > last }
            if (points.isNotEmpty()) dao.insert(points.map { toEntity(name, it) })
            f.delete()
        }
    }

    private fun read(f: File, version: Int): List<RawPoint> {
        val list = ArrayList<RawPoint>()
        runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                while (true) {
                    val p = try {
                        val base = RawPoint(
                            input.readLong(), input.readDouble(), input.readDouble(),
                            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                        )
                        val sensed = if (version >= 2) base.copy(sLon = input.readFloat(), sMin = input.readFloat(), sMax = input.readFloat(), sLat = input.readFloat()) else base
                        if (version >= 3) sensed.copy(fSpeed = input.readFloat(), fDist = input.readFloat(), fState = input.readInt()) else sensed
                    } catch (_: EOFException) {
                        break // a half-written last record is simply dropped
                    }
                    list += p
                }
            }
        }
        return list
    }

    companion object {
        private const val JOURNAL = ".journal3"
        private const val FLUSHING = ".flushing3"
        private const val V2_JOURNAL = ".journal2"
        private const val V2_FLUSHING = ".flushing2"
        private const val OLD_JOURNAL = ".journal"
        private const val OLD_FLUSHING = ".flushing"
        private const val SYNC_EVERY = 10

        fun toEntity(session: String, p: RawPoint) = RawPointEntity(
            0, session, p.t, p.lat, p.lon,
            p.alt.takeUnless { it.isNaN() }, p.speed.takeUnless { it.isNaN() }, p.acc.takeUnless { it.isNaN() },
            p.pressure.takeUnless { it.isNaN() }, p.speedAcc.takeUnless { it.isNaN() },
            p.sLon.takeUnless { it.isNaN() }, p.sMin.takeUnless { it.isNaN() }, p.sMax.takeUnless { it.isNaN() }, p.sLat.takeUnless { it.isNaN() },
            p.fSpeed.takeUnless { it.isNaN() }, p.fDist.takeUnless { it.isNaN() }, p.fState.takeIf { it >= 0 },
        )

        fun toRaw(e: RawPointEntity) = RawPoint(
            e.t, e.lat, e.lon, e.alt ?: Float.NaN, e.speed ?: Float.NaN, e.acc ?: Float.NaN,
            e.pressure ?: Float.NaN, e.speedAcc ?: Float.NaN,
            e.sLon ?: Float.NaN, e.sMin ?: Float.NaN, e.sMax ?: Float.NaN, e.sLat ?: Float.NaN,
            fSpeed = e.fSpeed ?: Float.NaN, fDist = e.fDist ?: Float.NaN, fState = e.fState ?: -1,
        )
    }
}
