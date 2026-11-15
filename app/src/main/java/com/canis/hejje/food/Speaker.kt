package com.canis.hejje.food

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks a short heads-up like a navigation prompt: through the car speakers when connected,
 * with music only lowered for a moment (never paused), and nothing during a phone call.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(text: String) {
        if (inCall()) return
        if (ready) speak(text) else {
            pending = text
            if (tts == null) tts = TextToSpeech(app) { status -> if (status == TextToSpeech.SUCCESS) onReady() }
        }
    }

    private fun onReady() {
        val t = tts ?: return
        // An Indian English voice when the phone has one.
        val india = Locale.Builder().setLanguage("en").setRegion("IN").build()
        t.language = if (t.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) india else Locale.UK
        t.setAudioAttributes(attributes)
        t.setSpeechRate(0.95f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { audio?.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { audio?.abandonAudioFocusRequest(focus) }
        })
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    private fun speak(text: String) {
        if (inCall()) return
        audio?.requestAudioFocus(focus)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "food-heads-up")
    }

    private fun inCall(): Boolean = audio?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION || it == AudioManager.MODE_RINGTONE }

    fun release() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }
}
