package com.canis.hejje

import android.app.Application
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
    private val finalizer = TripFinalizer(app, db.raw(), trips, settings, log)
    val controller = RecordingController(
        app, settings, buffer, db.raw(), finalizer, scope,
        locationPrompt = LocationPrompt(app, notifier, log), log = log,
    )

    fun scheduleWork() {
        runCatching { Work.schedule(app) }
    }
}
