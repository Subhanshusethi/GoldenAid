package com.subhanshu.gemmacomp

/**
 * Complete UI state for the triage screen.
 * Expanded to carry all the data the pipeline produces.
 */
data class TriageState(
    // — Model download —
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,          // 0-100
    val downloadedMB: String = "0",
    val totalMB: String = "0",
    val downloadSpeedMBps: String = "0.0",
    val downloadError: String? = null,

    // — Model loading —
    val isModelLoading: Boolean = true,     // starts true — acts as gate while checking for model
    val loadingStatus: String = "Initializing…",

    // — Detection / Pose —
    val personDetected: Boolean = false,
    val triageLevel: TriageLevel = TriageLevel.UNKNOWN,
    val torsoAngle: Float = 0f,
    val posture: String = "unknown",
    val bodyOrientation: String = "",
    val landmarksVisible: Map<String, Boolean> = emptyMap(),
    val poseContextString: String = "",

    // — Gemma structured response —
    val gemmaResponse: String = "",
    val actionText: String = "",
    val nextQuestion: String = "",
    val urgencyLevel: String = "UNKNOWN",
    val isGemmaThinking: Boolean = false,

    // — Voice —
    val voiceTranscript: String = "",
    val isListening: Boolean = false,
    val voiceError: String? = null,

    // — TTS —
    val isSpeaking: Boolean = false,

    // — Session —
    val callMade: Boolean = false,
    val sessionStartTime: Long = 0L,
)
