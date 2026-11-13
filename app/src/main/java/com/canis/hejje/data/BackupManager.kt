package com.canis.hejje.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.canis.hejje.BuildConfig
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.domain.BackupFile
import com.canis.hejje.domain.BackupSegment
import com.canis.hejje.domain.BackupTrip
import com.canis.hejje.domain.BackupZone
import com.canis.hejje.domain.Gpx
import com.canis.hejje.domain.HejjeJson
import com.canis.hejje.domain.ProcessorConfig
import com.canis.hejje.domain.Segment
import com.canis.hejje.domain.TripProcessor
import com.canis.hejje.domain.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

data class ImportResult(val added: Int = 0, val duplicates: Int = 0, val failed: Int = 0)

class BackupManager(
    private val context: Context,
    private val db: HejjeDb,
    private val trips: TripRepository,
    private val settings: SettingsStore,
) {
    private class Known(val uuid: String, val start: Long, val end: Long)

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun writeBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            settings = settings.export(settings.current),
            tags = trips.allTags().map { it.name },
            zones = db.zones().all().map { BackupZone(it.name, it.lat, it.lon, it.radiusM, it.icon, it.hide, it.tag) },
            trips = trips.all().map { t ->
                BackupTrip(
                    t.uuid, t.note, t.startPlace, t.endPlace, trips.tagNames(t.id),
                    trips.segments(t.id).map { BackupSegment(it.start, it.end, it.data) },
                )
            },
        )
        GZIPOutputStream(out).use { HejjeJson.encodeToStream(BackupFile.serializer(), file, it) }
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { writeBackup(it) } ?: error("Cannot open file")
    }

    /** Imports a Hejje backup or a GPX file; the type is detected from the content. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { read(it) } ?: ImportResult(failed = 1)
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun read(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val stream = BufferedInputStream(input)
        stream.mark(4)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        val known = trips.all().map { Known(it.uuid, it.startTime, it.endTime) }.toMutableList()
        when {
            b0 == 0x1f && b1 == 0x8b -> importBackup(HejjeJson.decodeFromStream(BackupFile.serializer(), GZIPInputStream(stream)), known)
            b0 == '{'.code -> importBackup(HejjeJson.decodeFromStream(BackupFile.serializer(), stream), known)
            else -> importGpx(stream.readBytes().toString(Charsets.UTF_8), known)
        }
    }

    private fun isDuplicate(known: List<Known>, uuid: String?, start: Long, end: Long): Boolean = known.any { k ->
        val overlap = min(k.end, end) - max(k.start, start)
        k.uuid == uuid || overlap > 0.5 * min(k.end - k.start, end - start).coerceAtLeast(1)
    }

    private suspend fun importBackup(file: BackupFile, known: MutableList<Known>): ImportResult {
        require(file.format == BackupFile.FORMAT) { "Not a Hejje backup" }
        require(file.version <= BackupFile.VERSION) { "Backup was made by a newer version of the app" }
        var added = 0
        var duplicates = 0
        var failed = 0
        file.tags.forEach { trips.createTag(it) }
        val zones = db.zones().all()
        file.zones.filter { z -> zones.none { it.lat == z.lat && it.lon == z.lon && it.radiusM == z.radiusM } }
            .forEach { db.zones().upsert(ZoneEntity(0, it.name, it.lat, it.lon, it.radiusM, it.icon, it.hide, it.tag)) }
        for (t in file.trips) {
            val segs = t.segments.map { Segment(it.start, it.end, it.data) }.filter { it.end > it.start }
            if (segs.isEmpty()) { failed++; continue }
            val start = segs.minOf { it.start }
            val end = segs.maxOf { it.end }
            if (isDuplicate(known, t.uuid, start, end)) { duplicates++; continue }
            runCatching { trips.insertTrip(t.uuid, segs, t.note, t.startPlace, t.endPlace, t.tags) }
                .onSuccess { added++; known += Known(t.uuid, start, end) }
                .onFailure { failed++ }
        }
        if (file.settings.isNotEmpty()) settings.import(file.settings)
        return ImportResult(added, duplicates, failed)
    }

    private suspend fun importGpx(text: String, known: MutableList<Known>): ImportResult {
        val tracks = Gpx.parse(text)
        if (tracks.isEmpty()) return ImportResult(failed = 1)
        var added = 0
        var duplicates = 0
        var failed = 0
        val s = settings.current
        val cfg = ProcessorConfig(s.brakeThreshold, s.accelThreshold)
        for (track in tracks) {
            val segs = track.segments.mapNotNull { pts -> TripProcessor.process(pts, pts.first().t, pts.last().t, cfg) }
            if (segs.isEmpty()) { failed++; continue }
            val start = segs.minOf { it.start }
            val end = segs.maxOf { it.end }
            if (isDuplicate(known, null, start, end)) { duplicates++; continue }
            val uuid = UUID.randomUUID().toString()
            trips.insertTrip(uuid, segs, track.name.orEmpty().takeIf { it != DEFAULT_GPX_NAME }.orEmpty(), null, null, emptyList())
            known += Known(uuid, start, end)
            added++
        }
        return ImportResult(added, duplicates, failed)
    }

    suspend fun gpx(tripId: Long): String {
        val zones = db.zones().all().filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }
        val trip = trips.trip(tripId)
        val name = trip?.note?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_GPX_NAME
        return Gpx.write(name, "Hejje ${BuildConfig.VERSION_NAME}", Gpx.pointsOf(trips.segments(tripId), zones))
    }

    suspend fun exportGpx(tripId: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val text = gpx(tripId)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Cannot open file")
    }

    /** Writes a dated backup into the chosen folder and keeps only the newest few. */
    suspend fun autoBackup(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current
        val tree = s.backupUri?.let { DocumentFile.fromTreeUri(context, it.toUri()) } ?: return@withContext false
        if (!tree.canWrite()) return@withContext false
        val name = "$PREFIX${LocalDate.now()}$EXTENSION"
        tree.findFile(name)?.delete()
        val file = tree.createFile("application/octet-stream", name) ?: return@withContext false
        context.contentResolver.openOutputStream(file.uri)?.use { writeBackup(it) } ?: return@withContext false
        tree.listFiles().filter { it.name?.startsWith(PREFIX) == true }
            .sortedByDescending { it.name }.drop(s.backupKeep.coerceAtLeast(1)).forEach { it.delete() }
        settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
        true
    }

    companion object {
        const val PREFIX = "hejje-backup-"
        const val EXTENSION = ".hejje"
        private const val DEFAULT_GPX_NAME = "Drive"

        fun backupName(): String = "$PREFIX${LocalDate.now()}$EXTENSION"
    }
}
