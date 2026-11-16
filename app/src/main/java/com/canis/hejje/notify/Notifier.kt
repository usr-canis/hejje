package com.canis.hejje.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.canis.hejje.MainActivity
import com.canis.hejje.R
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.domain.Formatter
import com.canis.hejje.food.FoodReceiver
import com.canis.hejje.recording.ControlReceiver
import com.canis.hejje.ui.formatter

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUMMARY, context.getString(R.string.channel_summary), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Updates come from the Play Store now; remove the channel earlier versions created.
        nm.deleteNotificationChannel(CH_UPDATES_OLD)
        nm.createNotificationChannel(
            NotificationChannel(CH_LOCATION, context.getString(R.string.channel_location), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FOOD, context.getString(R.string.channel_food), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FOOD_FEEDBACK, context.getString(R.string.channel_food_feedback), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun canPost(): Boolean = manager.areNotificationsEnabled() && (
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )

    private fun open(extra: (Intent.() -> Unit)? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        extra?.invoke(intent)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Ongoing notification: the system counts the elapsed time itself, so we only update when the
     * distance changes. On Android 16 and newer it is a Live Update: it stays at the top of the
     * shade and on the lock screen, and shows [chip] (the distance) in the status bar.
     */
    fun recording(startedAt: Long, distance: String?, finishing: Boolean, chip: String? = null): Notification {
        val stop = PendingIntent.getBroadcast(
            context, 1, Intent(context, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_hejje)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(if (finishing) R.string.notif_saving else R.string.notif_recording))
            .setContentIntent(open())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
        if (finishing) b.setShortCriticalText(context.getString(R.string.notif_chip_saving))
        else if (chip != null) b.setShortCriticalText(chip)
        if (!finishing) {
            b.setContentText(distance ?: context.getString(R.string.notif_waiting_gps))
                .setWhen(startedAt).setUsesChronometer(true).setShowWhen(true)
                .addAction(0, context.getString(R.string.action_stop), stop)
        }
        return b.build()
    }

    private fun foodIntent(action: String, askId: Long, mutable: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        context, (askId * 8 + action.hashCode().mod(8)).toInt(),
        Intent(context, FoodReceiver::class.java).setAction(action).putExtra(FoodReceiver.EXTRA_ASK, askId),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
    )

    /** "What did you stop for?": Food or Coffee with the place's name, or Other with your own purpose. */
    fun foodQuestion(askId: Long) {
        if (!canPost()) return
        fun answer(action: String, label: Int, hint: Int = R.string.food_name_hint) =
            NotificationCompat.Action.Builder(0, context.getString(label), foodIntent(action, askId, mutable = true))
            .addRemoteInput(RemoteInput.Builder(FoodReceiver.KEY_NAME).setLabel(context.getString(hint)).build())
            .setAllowGeneratedReplies(false)
            .build()
        val n = NotificationCompat.Builder(context, CH_FOOD)
            .setSmallIcon(R.drawable.ic_stat_hejje)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(R.string.food_question))
            .setContentText(context.getString(R.string.food_question_text))
            .addAction(answer(FoodReceiver.ACTION_FOOD, R.string.food_action_food))
            .addAction(answer(FoodReceiver.ACTION_COFFEE, R.string.food_action_coffee))
            .addAction(answer(FoodReceiver.ACTION_OTHER, R.string.food_action_other, R.string.food_other_hint))
            .setDeleteIntent(foodIntent(FoodReceiver.ACTION_DISMISS, askId))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(ID_FOOD + askId.toInt(), n)
    }

    /** Replaces the question once answered, then goes away by itself. */
    fun foodSaved(askId: Long, name: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_FOOD)
            .setSmallIcon(R.drawable.ic_stat_hejje)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(R.string.food_saved, name))
            .setSilent(true)
            .setTimeoutAfter(4_000)
            .build()
        notify(ID_FOOD + askId.toInt(), n)
    }

    fun cancelFoodQuestion(askId: Long) = manager.cancel(ID_FOOD + askId.toInt())

    /** "How was it?": suggest again, or not again. */
    fun foodFeedback(askId: Long, name: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_FOOD_FEEDBACK)
            .setSmallIcon(R.drawable.ic_stat_hejje)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(R.string.food_feedback, name))
            .addAction(0, context.getString(R.string.food_good), foodIntent(FoodReceiver.ACTION_GOOD, askId))
            .addAction(0, context.getString(R.string.food_not_again), foodIntent(FoodReceiver.ACTION_NOT_AGAIN, askId))
            .setDeleteIntent(foodIntent(FoodReceiver.ACTION_FEEDBACK_DISMISS, askId))
            .setOnlyAlertOnce(true)
            .build()
        notify(ID_FOOD_FEEDBACK + askId.toInt(), n)
    }

    fun cancelFoodFeedback(askId: Long) = manager.cancel(ID_FOOD_FEEDBACK + askId.toInt())

    fun summary(trip: TripEntity, f: Formatter = context.formatter()) {
        if (!canPost()) return
        val title = context.getString(R.string.notif_summary_title, f.distance(trip.distanceM), f.duration(trip.totalMs))
        val text = context.getString(
            R.string.notif_summary_text, f.speed(trip.avgMovingSpeed),
            context.resources.getQuantityString(R.plurals.stop_count, trip.stopCount, trip.stopCount), trip.smoothness,
        )
        val n = NotificationCompat.Builder(context, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_hejje)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open({ putExtra(EXTRA_TRIP_ID, trip.id) }, requestCode = 2))
            .setAutoCancel(true)
            .build()
        notify(ID_SUMMARY, n)
    }

    /** Location is off as a drive starts: one tap shows Android's "Turn on location?" dialog. */
    fun locationTurnOn() {
        val intent = Intent(context, com.canis.hejje.recording.LocationPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(
            ID_LOCATION,
            NotificationCompat.Builder(context, CH_LOCATION)
                .setSmallIcon(R.drawable.ic_stat_hejje)
                .setColor(ContextCompat.getColor(context, R.color.accent))
                .setContentTitle(context.getString(R.string.notif_location_on_title))
                .setContentText(context.getString(R.string.notif_location_on_text))
                .setContentIntent(tap)
                .addAction(0, context.getString(R.string.notif_location_on_action), tap)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The drive is saved and Location was off before it: offer a shortcut to switch it off again. */
    fun locationTurnOff() {
        val intent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, 6, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(
            ID_LOCATION,
            NotificationCompat.Builder(context, CH_LOCATION)
                .setSmallIcon(R.drawable.ic_stat_hejje)
                .setColor(ContextCompat.getColor(context, R.color.accent))
                .setContentTitle(context.getString(R.string.notif_location_off_title))
                .setContentText(context.getString(R.string.notif_location_off_text))
                .setContentIntent(tap)
                .addAction(0, context.getString(R.string.notif_location_off_action), tap)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setTimeoutAfter(30 * 60_000L)
                .build(),
        )
    }

    fun cancel(id: Int) = manager.cancel(id)

    fun updateRecording(n: Notification) = notify(ID_RECORDING, n)

    private fun notify(id: Int, n: Notification) {
        try {
            if (canPost()) manager.notify(id, n)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call.
        }
    }

    companion object {
        const val CH_RECORDING = "recording"
        const val CH_SUMMARY = "summary"
        private const val CH_UPDATES_OLD = "updates"
        const val CH_LOCATION = "location"
        const val CH_FOOD = "food"
        const val CH_FOOD_FEEDBACK = "food-feedback"
        const val ID_RECORDING = 1
        const val ID_SUMMARY = 2
        const val ID_LOCATION = 4
        private const val ID_FOOD = 1_000
        private const val ID_FOOD_FEEDBACK = 500_000
        const val EXTRA_TRIP_ID = "trip_id"
    }
}
