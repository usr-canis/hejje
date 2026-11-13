package com.canis.hejje

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.canis.hejje.notify.Notifier
import com.canis.hejje.ui.HejjeRoot

class MainActivity : ComponentActivity() {
    private var openTripId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val container = (application as HejjeApp).container
        setContent { HejjeRoot(container, openTripId, onTripOpened = { openTripId = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getLongExtra(Notifier.EXTRA_TRIP_ID, -1L) ?: -1L
        if (id > 0) openTripId = id
        // From the Quick Settings tile, when Android did not let it start the recording itself.
        if (intent?.getBooleanExtra(EXTRA_START_RECORDING, false) == true) {
            intent.removeExtra(EXTRA_START_RECORDING)
            val controller = (application as HejjeApp).container.controller
            if (controller.live.value == null) controller.manualStart()
        }
    }

    companion object {
        const val EXTRA_START_RECORDING = "start_recording"
    }
}
