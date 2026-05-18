package com.subhanshu.gemmacomp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Voice input manager with full error handling and state callbacks.
 *
 * Reports listening state changes and error messages to the ViewModel
 * so the UI can give proper feedback (unlike the Python WebSocket version
 * where audio is processed server-side, here we use Android's SpeechRecognizer).
 */
class VoiceInputManager(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit = {},
    private val onPartialResult: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) : RecognitionListener {

    companion object {
        private const val TAG = "VoiceInputManager"
    }

    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    init {
        speechRecognizer.setRecognitionListener(this)
    }

    fun startListening() {
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            onError("Failed to start voice recognition")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop listening: ${e.message}")
        }
    }

    fun destroy() {
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy recognizer: ${e.message}")
        }
    }

    // ── RecognitionListener callbacks ──

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech")
        onListeningChanged(true)
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "Speech started")
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Could be used for a microphone level indicator
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "Speech ended")
        onListeningChanged(false)
    }

    override fun onResults(results: Bundle?) {
        onListeningChanged(false)
        val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val transcript = data?.firstOrNull()
        if (transcript != null) {
            Log.d(TAG, "Final result: $transcript")
            onResult(transcript)
        } else {
            onError("Could not understand speech. Please try again.")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val data = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partial = data?.firstOrNull()
        if (!partial.isNullOrBlank()) {
            onPartialResult(partial)
        }
    }

    override fun onError(error: Int) {
        onListeningChanged(false)
        val errorMessage = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (offline recognition may not be available)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Tap and speak clearly."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy, try again"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap and try again."
            else -> "Voice recognition error (code $error)"
        }
        Log.w(TAG, "Speech error: $errorMessage (code=$error)")
        onError(errorMessage)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}