package com.canis.hejje.recording

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import com.canis.hejje.notify.Notifier
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Apps may not switch Location on or off themselves. When a drive starts with Location off,
 * Hejje offers it with one tap (Android's own "Turn on location?" dialog). When that drive ends,
 * it offers a shortcut to turn Location off again. People who keep Location on never see either.
 */
class LocationPrompt(private val context: Context, private val notifier: Notifier) {
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)
    private var asking = false

    /** Location was off when the current drive started (kept across app restarts). */
    private var wasOff: Boolean
        get() = prefs.getBoolean(KEY_WAS_OFF, false)
        set(value) = prefs.edit { putBoolean(KEY_WAS_OFF, value) }

    fun onDriveStart(enabled: Boolean) {
        if (!enabled || Health.locationOn(context)) return
        wasOff = true
        asking = true
        notifier.locationTurnOn()
    }

    /** A GPS fix arrived, so Location is on now. */
    fun onFix() {
        if (!asking) return
        asking = false
        notifier.cancel(Notifier.ID_LOCATION)
    }

    fun onDriveEnd(enabled: Boolean) {
        asking = false
        notifier.cancel(Notifier.ID_LOCATION)
        if (wasOff && enabled && Health.locationOn(context)) notifier.locationTurnOff()
        wasOff = false
    }

    companion object {
        private const val KEY_WAS_OFF = "locationWasOff"
    }
}

/** Invisible screen that shows Android's "Turn on location?" dialog, then closes. */
class LocationPromptActivity : ComponentActivity() {
    private val resolve = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the dialog is already showing
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(this).checkLocationSettings(request)
            .addOnSuccessListener { finish() }
            .addOnFailureListener { e ->
                val resolvable = e as? ResolvableApiException
                if (resolvable == null) openSettings()
                else runCatching { resolve.launch(IntentSenderRequest.Builder(resolvable.resolution).build()) }.onFailure { openSettings() }
            }
    }

    /** Fallback when the dialog is unavailable: Android's Location settings page. */
    private fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        finish()
    }
}
