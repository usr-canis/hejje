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
import androidx.core.content.ContextCompat
import com.canis.hejje.MainActivity
import com.canis.hejje.R
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.domain.Formatter
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
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LOCATION, context.getString(R.string.channel_location), NotificationManager.IMPORTANCE_HIGH),
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

    fun updateAvailable(version: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_hejje)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(R.string.notif_update_title, version))
            .setContentText(context.getString(R.string.notif_update_text))
            .setContentIntent(open({ putExtra(EXTRA_SHOW_UPDATE, true) }, requestCode = 3))
            .setAutoCancel(true)
            .build()
        notify(ID_UPDATE, n)
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
        const val CH_UPDATES = "updates"
        const val CH_LOCATION = "location"
        const val ID_RECORDING = 1
        const val ID_SUMMARY = 2
        const val ID_UPDATE = 3
        const val ID_LOCATION = 4
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_SHOW_UPDATE = "show_update"
    }
}
