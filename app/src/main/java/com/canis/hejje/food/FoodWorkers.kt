package com.canis.hejje.food

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.canis.hejje.HejjeApp
import com.canis.hejje.domain.Meal
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val Context.container get() = (applicationContext as HejjeApp).container

/** 8 minutes after parking: asks "Stopped for food or coffee?" if the car is still parked. */
class FoodAskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { applicationContext.container.food.askIfStillParked(inputData.getLong(KEY_PARKED_AT, 0)) }
        return Result.success()
    }

    companion object {
        private const val NAME = "food-ask"
        private const val KEY_PARKED_AT = "parkedAt"

        fun schedule(context: Context, parkedAt: Long, delayMs: Long) = runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FoodAskWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_PARKED_AT to parkedAt)).build(),
            )
        }

        fun cancel(context: Context) = runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}

/** "How was it?" 30 minutes after naming a stop, and the one evening reminder. */
class FoodFeedbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { applicationContext.container.food.askFeedback(inputData.getLong(KEY_ASK, 0)) }
        return Result.success()
    }

    companion object {
        private const val KEY_ASK = "ask"

        fun schedule(context: Context, askId: Long, delayMs: Long) = runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "food-feedback-$askId", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FoodFeedbackWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_ASK to askId)).build(),
            )
        }
    }
}

/** Answers from the food and coffee notifications. */
class FoodReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val askId = intent.getLongExtra(EXTRA_ASK, -1)
        val name = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_NAME)?.toString().orEmpty()
        val done = goAsync()
        c.scope.launch {
            runCatching {
                val ask = c.db.stopAsks().get(askId)
                when (intent.action) {
                    ACTION_FOOD, ACTION_COFFEE -> if (ask != null) {
                        c.food.save(if (intent.action == ACTION_FOOD) Meal.FOOD else Meal.COFFEE, name, ask.lat, ask.lon, ask.parkedAt, askId)
                    }
                    ACTION_DISMISS -> c.food.dismissed(askId)
                    ACTION_GOOD, ACTION_NOT_AGAIN -> ask?.placeId?.let { c.food.rate(it, intent.action == ACTION_GOOD, askId) }
                    ACTION_FEEDBACK_DISMISS -> c.food.feedbackDismissed(askId)
                }
            }
            done.finish()
        }
    }

    companion object {
        const val ACTION_FOOD = "com.canis.hejje.food.FOOD"
        const val ACTION_COFFEE = "com.canis.hejje.food.COFFEE"
        const val ACTION_DISMISS = "com.canis.hejje.food.DISMISS"
        const val ACTION_GOOD = "com.canis.hejje.food.GOOD"
        const val ACTION_NOT_AGAIN = "com.canis.hejje.food.NOT_AGAIN"
        const val ACTION_FEEDBACK_DISMISS = "com.canis.hejje.food.FEEDBACK_DISMISS"
        const val EXTRA_ASK = "ask"
        const val KEY_NAME = "name"
    }
}
