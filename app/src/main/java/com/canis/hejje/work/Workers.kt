package com.canis.hejje.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.canis.hejje.HejjeApp
import com.canis.hejje.data.SettingsData
import java.util.concurrent.TimeUnit

private val Context.container get() = (applicationContext as HejjeApp).container
private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Looks up missing place names once the phone is online. */
class PlaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { applicationContext.container.places.fillMissing() }
        return Result.success()
    }

    companion object {
        /** Never throws: a failed lookup request must not disturb saving or editing a trip. */
        fun enqueue(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "places", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PlaceWorker>().setConstraints(online).setInitialDelay(5, TimeUnit.SECONDS).build(),
                )
            }
        }
    }
}

/** Posts the "trip finished" notification at the time chosen in Settings. */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val delayed = inputData.getBoolean(KEY_DELAYED, false)
        // If another drive is under way the trip is still growing; its summary is scheduled again later.
        if (delayed && c.controller.isRecording) return Result.success()
        val trip = c.trips.trip(inputData.getLong(KEY_TRIP, -1)) ?: return Result.success()
        c.notifier.summary(trip)
        return Result.success()
    }

    companion object {
        private const val KEY_TRIP = "trip"
        private const val KEY_DELAYED = "delayed"

        fun schedule(context: Context, tripId: Long, s: SettingsData) {
            runCatching { scheduleOrThrow(context, tripId, s) }
        }

        private fun scheduleOrThrow(context: Context, tripId: Long, s: SettingsData) {
            val wm = WorkManager.getInstance(context)
            val delayMin = when (s.summaryTiming) {
                0 -> 0L
                1 -> 5L
                2 -> s.mergeGapMin.toLong() + 1
                else -> {
                    wm.cancelUniqueWork("summary")
                    return
                }
            }
            wm.enqueueUniqueWork(
                "summary", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SummaryWorker>()
                    .setInputData(workDataOf(KEY_TRIP to tripId, KEY_DELAYED to (delayMin > 0)))
                    .setInitialDelay(delayMin, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}

/** Daily background check for a new release; shows a notification if the user wants one. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current
        if (!s.updateAutoCheck || !s.updateNotify) return Result.success()
        val before = s.lastUpdateCheck
        val info = runCatching { c.updater.check(manual = false) }.getOrNull()
        // Notify only for a fresh result, not for one the app has already shown.
        if (info != null && c.settings.current.lastUpdateCheck != before) c.notifier.updateAvailable(info.version)
        return Result.success()
    }
}

/** Weekly backup into the folder chosen in Settings. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { applicationContext.container.backup.autoBackup() }
        return Result.success()
    }
}

object Work {
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(online).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "auto-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.HOURS).build(),
        )
        // Also covers a widget placed before this was added (an update does not re-add it).
        com.canis.hejje.widget.WidgetRefresh.schedule(context)
    }
}
