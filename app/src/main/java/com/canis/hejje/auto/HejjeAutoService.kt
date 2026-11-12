@file:OptIn(UnstableApi::class)

package com.canis.hejje.auto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.canis.hejje.AppContainer
import com.canis.hejje.HejjeApp
import com.canis.hejje.MainActivity
import com.canis.hejje.R
import com.canis.hejje.ui.formatter
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Hejje on Android Auto, as a media-style card: live speed and distance as the title, the drive
 * time below, position, accuracy and elevation as the third line, and a speed bar as the progress.
 *
 * It never reports itself as playing and offers no play/pause, so steering-wheel and Bluetooth
 * media buttons keep controlling your music app and can never stop a recording by accident.
 */
class HejjeAutoService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as HejjeApp).container
        val player = DrivePlayer(this, container)
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(this)).setSessionActivity(open).build()
        // Refresh on every GPS fix and once a second for the drive timer.
        scope.launch {
            combine(container.controller.live, container.settings.data) { _, _ -> Unit }.collect { player.refresh() }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                if (container.controller.live.value != null) player.refresh()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** No media notification: the recording notification already shows the drive on the phone. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) = Unit

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private class LibraryCallback(private val context: Context) : MediaLibrarySession.Callback {
        private fun root() = MediaItem.Builder().setMediaId(ROOT_ID)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(context.getString(R.string.app_name)).setIsBrowsable(true).setIsPlayable(false).build())
            .build()

        private fun drive() = MediaItem.Builder().setMediaId(DRIVE_ID)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(context.getString(R.string.auto_current_drive))
                    .setIsBrowsable(false).setIsPlayable(false).build(),
            )
            .build()

        /** Only Android Auto and Hejje itself may connect: the card carries your live position. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.packageName in trusted(context)) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(if (parentId == ROOT_ID) ImmutableList.of(drive()) else ImmutableList.of(), params))

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            when (mediaId) {
                ROOT_ID -> LibraryResult.ofItem(root(), null)
                DRIVE_ID -> LibraryResult.ofItem(drive(), null)
                else -> LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            },
        )
    }

    companion object {
        const val ROOT_ID = "root"
        const val DRIVE_ID = "drive"
        private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

        fun trusted(context: Context): Set<String> = setOf(ANDROID_AUTO, context.packageName)
    }
}

/** A "player" that plays nothing: it only carries the live drive card to Android Auto. */
class DrivePlayer(private val context: Context, private val container: AppContainer) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val artwork: ByteArray by lazy { artwork(context) }

    fun refresh() = invalidateState()

    fun card(): CardContent {
        val s = container.settings.current
        return DriveCard.build(
            container.controller.live.value, System.currentTimeMillis(), context.formatter(s.miles), cardText(context),
            autoReady = s.autoRecord && s.carAddress != null,
        )
    }

    override fun getState(): State {
        val card = card()
        val metadata = MediaMetadata.Builder()
            .setTitle(card.title).setDisplayTitle(card.title)
            .setArtist(card.subtitle).setSubtitle(card.subtitle)
            .setAlbumTitle(card.details).setDescription(card.details)
            .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setIsBrowsable(false).setIsPlayable(false)
            .build()
        val item = MediaItemData.Builder(HejjeAutoService.DRIVE_ID)
            .setMediaItem(MediaItem.Builder().setMediaId(HejjeAutoService.DRIVE_ID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(CardContent.DURATION_MS * 1000)
            .setIsSeekable(false)
            .build()
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
                ).build(),
            )
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            // Never "playing": see the class comment on HejjeAutoService.
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(card.positionMs)
            .build()
    }

    companion object {
        fun cardText(context: Context) = CardText(
            live = context.getString(R.string.auto_card_live),
            recording = context.getString(R.string.auto_card_recording),
            waiting = context.getString(R.string.notif_waiting_gps),
            idle = context.getString(R.string.auto_card_idle),
            idleAuto = context.getString(R.string.auto_card_idle_auto),
            idleManual = context.getString(R.string.auto_card_idle_manual),
            details = context.getString(R.string.auto_card_details),
        )

        /** The footprint mark on the app's dark background, as the card's picture. */
        private fun artwork(context: Context): ByteArray {
            val size = 320
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF131517.toInt() }
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 48f, 48f, paint)
            ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let {
                it.setBounds(-20, -14, size + 20, size + 26)
                it.draw(canvas)
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
