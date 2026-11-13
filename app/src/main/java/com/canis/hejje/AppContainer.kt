package com.canis.hejje

import android.app.Application
import androidx.core.content.edit
import com.canis.hejje.data.BackupManager
import com.canis.hejje.data.DriveLog
import com.canis.hejje.data.PlaceRepository
import com.canis.hejje.data.RawBuffer
import com.canis.hejje.data.SettingsStore
import com.canis.hejje.data.TripRepository
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.notify.Notifier
import com.canis.hejje.recording.LocationPrompt
import com.canis.hejje.recording.RecordingController
import com.canis.hejje.recording.TripFinalizer
import com.canis.hejje.update.Updater
import com.canis.hejje.work.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/** Hand-wired dependencies; one instance for the whole app. */
class AppContainer(val app: Application) {
    companion object {
        private const val KEY_STATS_VERSION = "statsVersion"
        const val STATS_VERSION = 2
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, scope)
    val db = HejjeDb.create(app)
    val trips = TripRepository(db)
    val notifier = Notifier(app)

    /** Identifies the app to map-tile, place-name and update servers, as their usage policies ask. */
    val userAgent = "Hejje/${BuildConfig.VERSION_NAME} (Android; +https://github.com/${BuildConfig.UPDATE_REPO})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .cache(Cache(File(app.cacheDir, "http"), 5L * 1024 * 1024))
        .build()

    val places = PlaceRepository(db.places(), http, trips, settings)
    val backup = BackupManager(app, db, trips, settings)
    val updater = Updater(app, http, settings, scope)
    private val buffer = RawBuffer(File(app.filesDir, "raw"), db.raw())
    val log = DriveLog(db.log(), scope)
    private val finalizer = TripFinalizer(app, db.raw(), trips, settings, log, db.zones())
    val controller = RecordingController(
        app, settings, buffer, db.raw(), finalizer, scope,
        locationPrompt = LocationPrompt(app, notifier, log), log = log,
    )

    /** Recalculates every trip once when the way stats are computed has changed (Fluidity in 1.2.0). */
    suspend fun upgradeStats() {
        val prefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_STATS_VERSION, 1) >= STATS_VERSION) return
        runCatching { trips.recomputeAll() }.onSuccess {
            prefs.edit { putInt(KEY_STATS_VERSION, STATS_VERSION) }
            com.canis.hejje.widget.HejjeWidget.refresh(app)
        }
    }

    fun scheduleWork() {
        runCatching { Work.schedule(app) }
    }
}
