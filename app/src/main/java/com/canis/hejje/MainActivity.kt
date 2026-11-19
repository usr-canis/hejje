package com.canis.hejje

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.canis.hejje.notify.Notifier
import com.canis.hejje.ui.HejjeRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openTripId by mutableStateOf<Long?>(null)
    private var openReplay by mutableStateOf(false)
    private var pip by mutableStateOf(false)
    private val container get() = (application as HejjeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { HejjeRoot(container, openTripId, openReplay, pip, onTripOpened = { openTripId = null; openReplay = false }) }
        // While a replay video is being made, leaving the app shrinks it to a small floating window,
        // so the video keeps being drawn (Android 12 and later do this by themselves).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lifecycleScope.launch {
            container.replays.job.map { it?.running == true }.distinctUntilChanged().collect { making ->
                if (canPip()) runCatching { setPictureInPictureParams(pipParams(making)) }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && container.replays.busy && canPip()) runCatching { enterPictureInPictureMode(pipParams(true)) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    private fun canPip() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(auto: Boolean): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(9, 16))
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(auto) }
        .build()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getLongExtra(Notifier.EXTRA_TRIP_ID, -1L) ?: -1L
        if (id > 0) { openReplay = intent?.getBooleanExtra(Notifier.EXTRA_OPEN_REPLAY, false) == true; openTripId = id }
        // From the Quick Settings tile, when Android did not let it start the recording itself.
        if (intent?.getBooleanExtra(EXTRA_START_RECORDING, false) == true) {
            intent.removeExtra(EXTRA_START_RECORDING)
            val controller = container.controller
            if (controller.live.value == null) controller.manualStart()
        }
    }

    companion object {
        const val EXTRA_START_RECORDING = "start_recording"
    }
}
