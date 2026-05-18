package com.subhanshu.gemmacomp

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Text-to-Speech with interruption support and speaking state tracking.
 *
 * Uses Android's built-in TTS (no Kokoro on-device yet).
 * Matches Python's streaming TTS behavior: interrupts current speech for new responses.
 */
class TriageTTS(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TriageTTS"
    }

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false
    private var onSpeakingChanged: ((Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setPitch(1.0f)
            tts.setSpeechRate(1.1f)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingChanged?.invoke(true)
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingChanged?.invoke(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS error for utterance: $utteranceId")
                    onSpeakingChanged?.invoke(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS error code $errorCode for utterance: $utteranceId")
                    onSpeakingChanged?.invoke(false)
                }
            })

            isReady = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /** Set a callback for speaking state changes. */
    fun setOnSpeakingChanged(callback: (Boolean) -> Unit) {
        onSpeakingChanged = callback
    }

    /**
     * Speak text, interrupting any current speech.
     * Uses QUEUE_FLUSH to match Python's interrupt-on-new-response behavior.
     */
    fun speak(text: String) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, skipping: ${text.take(50)}...")
            return
        }
        if (text.isBlank()) return

        val params = android.os.Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "triage_${System.currentTimeMillis()}")
    }

    /** Immediately stop current speech (e.g., when user starts talking). */
    fun interrupt() {
        if (tts.isSpeaking) {
            tts.stop()
            onSpeakingChanged?.invoke(false)
        }
    }

    /** Whether TTS is currently speaking. */
    val isSpeaking: Boolean
        get() = isReady && tts.isSpeaking

    /** Clean up TTS resources. */
    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS: ${e.message}")
        }
    }
}