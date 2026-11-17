package com.canis.hejje.replay

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.canis.hejje.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One replay video at a time. It is drawn by a page hosted at the app's root (ReplayHost), so it
 * keeps going while the person uses the rest of the app; it pauses while the app is in the
 * background and carries on when they come back. When done it is saved to the gallery.
 */
class ReplayJobs(private val context: Context, private val container: AppContainer) {
    enum class Phase { PREPARING, MAP, VIDEO, SAVING, DONE, EMPTY, UNSUPPORTED, FAILED }

    data class Job(
        val id: Int,
        val tripId: Long,
        val phase: Phase,
        val progress: Float = 0f,
        val outline: FloatArray? = null,
        val title: String = "",
        val json: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        val videoStartedAt: Long = 0L,
        val saved: Uri? = null,
        val paused: Boolean = false,
    ) {
        val running: Boolean get() = phase == Phase.PREPARING || phase == Phase.MAP || phase == Phase.VIDEO || phase == Phase.SAVING
    }

    private val state = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = state
    val file: File get() = File(File(context.cacheDir, "shared").apply { mkdirs() }, "hejje-replay.mp4")
    private var sink: VideoSink? = null
    private var nextId = 1
    private var lastNotified = 0L
    private val main = Handler(Looper.getMainLooper())

    init {
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_START) {
                    state.update { it?.copy(paused = event == Lifecycle.Event.ON_STOP) }
                    state.value?.takeIf { it.running }?.let { notifyProgress(it, force = true) }
                }
            })
        }
    }

    val busy: Boolean get() = state.value?.running == true

    /** Starts a replay of [tripId] unless one is already being made. */
    fun start(tripId: Long) {
        if (busy) return
        val id = nextId++
        state.value = Job(id, tripId, Phase.PREPARING)
        container.scope.launch {
            val prepared = runCatching { ReplayData.build(context, container, tripId) }.getOrNull()
            if (state.value?.id != id) return@launch
            if (prepared == null) { state.update { it?.copy(phase = Phase.EMPTY) }; return@launch }
            sink = VideoSink(file)
            state.update { it?.copy(phase = Phase.MAP, json = prepared.json, outline = prepared.outline, title = prepared.title) }
            state.value?.let { notifyProgress(it, force = true) }
        }
    }

    fun cancel() {
        state.value = null
        sink?.close(); sink = null
        file.delete()
        container.notifier.cancelReplay()
    }

    /** Forgets a finished (or failed) job, so the next visit can start a new one. */
    fun clear() { if (!busy) state.value = null }

    // ---- from the page (any thread) ----

    fun onProgress(id: Int, value: Float, stage: String) {
        val now = System.currentTimeMillis()
        state.update { j ->
            if (j?.id != id || !j.running) j else {
                val phase = if (stage == "video") Phase.VIDEO else Phase.MAP
                j.copy(phase = phase, progress = value, videoStartedAt = if (phase == Phase.VIDEO && j.videoStartedAt == 0L) now else j.videoStartedAt)
            }
        }
        state.value?.takeIf { it.id == id }?.let { notifyProgress(it) }
    }

    fun onChunk(id: Int, bytes: ByteArray, position: Long) { if (state.value?.id == id) sink?.write(bytes, position) }

    fun onDone(id: Int) {
        if (state.value?.id != id) return
        sink?.close(); sink = null
        state.update { it?.copy(phase = Phase.SAVING, progress = 1f) }
        container.scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { saveToGallery(file) }.getOrNull() }
            if (state.value?.id != id) return@launch
            state.update { it?.copy(phase = Phase.DONE, saved = uri) }
            container.notifier.replayDone(uri, state.value?.tripId ?: 0L)
        }
    }

    fun onFail(id: Int, reason: String) {
        if (state.value?.id != id) return
        sink?.close(); sink = null
        file.delete()
        state.update { it?.copy(phase = if (reason == "unsupported") Phase.UNSUPPORTED else Phase.FAILED) }
        container.notifier.cancelReplay()
    }

    /** The page was closed before it finished (the app was closed, for example). */
    fun onHostGone(id: Int) { val p = state.value?.takeIf { it.id == id }?.phase; if (p == Phase.MAP || p == Phase.VIDEO) onFail(id, "stopped") }

    private fun notifyProgress(j: Job, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotified < 1500) return
        lastNotified = now
        container.notifier.replayProgress((j.progress * 100).toInt(), j.paused, j.tripId)
    }

    /** Copies the video into Movies/Hejje (Android 10 and later; earlier versions keep it to share). */
    private fun saveToGallery(file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = "hejje-replay-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Hejje")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return null
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** For screenshots in tests. */
    internal fun show(job: Job?) { state.value = job }

}

/** Writes the encoder's pieces at their places in the file (the header is patched at the end). */
class VideoSink(file: File) {
    private val out = RandomAccessFile(file.apply { delete() }, "rw")
    private var open = true

    @Synchronized fun write(bytes: ByteArray, position: Long) {
        if (!open) return
        out.seek(position); out.write(bytes)
    }

    @Synchronized fun close() {
        if (open) { open = false; out.close() }
    }
}
