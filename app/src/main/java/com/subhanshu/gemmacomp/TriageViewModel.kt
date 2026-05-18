package com.subhanshu.gemmacomp

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "brain" of the Triage AI app.
 *
 * Orchestrates the full pipeline ported from Python's server.py:
 *   Camera frame → YOLO detect → Crop → Pose analysis → Gemma inference → TTS
 *
 * Key differences from the original:
 * - Cropped bitmap is passed to Gemma for vision (was missing before)
 * - Pose context string is injected into the prompt (was missing before)
 * - Conversation history includes visual context (was text-only before)
 * - Triage level is parsed from LLM response (was hard-coded before)
 */
class TriageViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TriageViewModel"
    }

    private val _uiState = MutableStateFlow(TriageState())
    val uiState: StateFlow<TriageState> = _uiState.asStateFlow()

    // ML components (initialized lazily on background thread)
    private var detector: YoloDetector? = null
    private var poseAnalyzer: PoseAnalyzer? = null
    private var gemma: GemmaInference? = null

    // TTS with speaking state feedback
    private val tts = TriageTTS(application).apply {
        setOnSpeakingChanged { speaking ->
            _uiState.update { it.copy(isSpeaking = speaking) }
        }
    }

    // Session logger for documentation
    private val logger = TriageLogger(application)

    // Conversation history (matches Python's text_history format)
    private val textHistory = mutableListOf<String>()

    // Latest cropped bitmap for passing to Gemma during voice turns
    @Volatile
    private var latestCroppedBitmap: Bitmap? = null

    // Latest pose result for the current frame
    @Volatile
    private var latestPoseResult: PoseResult? = null

    // Voice input with full callbacks
    private val voiceManager = VoiceInputManager(
        context = application,
        onResult = { transcript ->
            _uiState.update { it.copy(voiceTranscript = transcript, isListening = false, voiceError = null) }
            processTurn(transcript)
        },
        onListeningChanged = { listening ->
            _uiState.update { it.copy(isListening = listening) }
            // Interrupt TTS when user starts talking (matches Python's interrupt behavior)
            if (listening) tts.interrupt()
        },
        onPartialResult = { partial ->
            _uiState.update { it.copy(voiceTranscript = partial) }
        },
        onError = { errorMsg ->
            _uiState.update { it.copy(voiceError = errorMsg, isListening = false) }
        }
    )

    fun startListening() {
        // Clear previous error before starting
        _uiState.update { it.copy(voiceError = null, voiceTranscript = "") }
        voiceManager.startListening()
    }

    /**
     * Reset for next patient — matches Python's "next patient" command.
     */
    fun resetForNextPatient() {
        textHistory.clear()
        latestPoseResult = null
        gemma?.forceReset()  // Reset the stateful conversation
        _uiState.update {
            it.copy(
                triageLevel = TriageLevel.UNKNOWN,
                gemmaResponse = "",
                actionText = "",
                nextQuestion = "",
                urgencyLevel = "UNKNOWN",
                voiceTranscript = "",
                voiceError = null,
                poseContextString = "",
                callMade = false,
                sessionStartTime = 0L,
            )
        }
        tts.interrupt()
        logger.newSession()
        Log.d(TAG, "Reset for next patient — conversation cleared")
    }

    fun onCallMade() {
        _uiState.update { it.copy(callMade = true) }
    }

    // Model download manager
    private val downloadManager = ModelDownloadManager(application)

    // ── Model Initialization ──

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 0: Check if model already exists
                _uiState.update { it.copy(isModelLoading = true, loadingStatus = "Checking for AI model…") }
                val modelName = "gemma-4-E2B-it.litertlm"
                if (!ModelUtils.isModelAvailable(application, modelName)) {
                    // Model not on device — switch to download screen
                    Log.d(TAG, "Model not found, starting download...")
                    _uiState.update { it.copy(isModelLoading = false, isDownloading = true) }

                    // Observe download progress on a separate coroutine
                    val progressJob = launch {
                        downloadManager.progress.collect { progress ->
                            _uiState.update {
                                it.copy(
                                    downloadProgress = progress.progressPercent,
                                    downloadedMB = progress.downloadedMB,
                                    totalMB = progress.totalMB,
                                    downloadSpeedMBps = progress.speedMBps,
                                    downloadError = progress.errorMessage
                                )
                            }
                        }
                    }

                    // Block until download completes
                    downloadManager.downloadModel()
                    progressJob.cancel()

                    _uiState.update { it.copy(isDownloading = false, isModelLoading = true) }
                    Log.d(TAG, "Model download complete")
                }

                // Step 1: Load YOLO
                _uiState.update { it.copy(isModelLoading = true, loadingStatus = "Loading YOLO detector…") }
                Log.d(TAG, "Initializing YOLO...")
                detector = YoloDetector(application, "yolo11n.tflite")

                delay(300) // Let the system breathe between heavy native inits

                // Step 2: Load Pose
                _uiState.update { it.copy(loadingStatus = "Loading Pose estimator…") }
                Log.d(TAG, "Initializing PoseAnalyzer...")
                poseAnalyzer = PoseAnalyzer(application)

                delay(300)

                // Step 3: Load Gemma (the big one)
                _uiState.update { it.copy(loadingStatus = "Loading Gemma AI (this may take a moment)…") }
                Log.d(TAG, "Initializing Gemma...")
                gemma = GemmaInference(application)

                _uiState.update { it.copy(isModelLoading = false, loadingStatus = "Ready") }
                Log.d(TAG, "All models initialized successfully")

                // Start first session
                logger.newSession()

            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.message}", e)
                val wasDownloading = _uiState.value.isDownloading
                _uiState.update {
                    it.copy(
                        // If download failed, KEEP showing the download screen with the error
                        isDownloading = wasDownloading,
                        isModelLoading = false,
                        loadingStatus = "Error: ${e.message}",
                        downloadError = e.message
                    )
                }
            }
        }
    }

    // ── Frame Processing Pipeline ──

    private var isProcessing = false
    private val mlDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Process a camera frame through the full pipeline:
     *   Frame → YOLO detectAndCrop → Pose analyzeFull → Update state
     *
     * Mirrors the Python server.py WebSocket handler's image processing logic.
     */
    fun onFrameCaptured(bitmap: Bitmap) {
        if (isProcessing) return
        val currentDetector = detector ?: run {
            Log.w(TAG, "YOLO detector not ready")
            return
        }
        val currentPose = poseAnalyzer ?: return

        // Copy immediately — camera may recycle the original
        val frameBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return

        isProcessing = true

        viewModelScope.launch(mlDispatcher) {
            try {
                // Step 1: YOLO detect + crop
                val cropResult = currentDetector.detectAndCrop(frameBitmap)
                
                if (!cropResult.personDetected) {
                    if (System.currentTimeMillis() % 100 == 0L) {
                        Log.d(TAG, "Detection: No person found in frame")
                    }
                }

                // Step 2: Pose analysis on cropped image
                val poseResult = currentPose.analyzeFull(cropResult.croppedBitmap)

                // Step 3: Generate context string (mirrors Python's pose_to_context_string)
                val contextString = PoseAnalyzer.poseToContextString(poseResult)

                // Step 4: Store latest image for Gemma
                // CRITICAL: Make an independent copy! cropResult.croppedBitmap may be the
                // same object as frameBitmap (when no person is detected), and frameBitmap
                // gets recycled in the finally block. Without a copy, latestCroppedBitmap
                // would point to a recycled bitmap → Gemma gets null → no vision!
                val imageCopy = cropResult.croppedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                
                // Recycle old stored bitmap
                latestCroppedBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }
                latestCroppedBitmap = imageCopy
                latestPoseResult = poseResult

                // Step 5: Update UI state
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            personDetected = cropResult.personDetected,
                            posture = poseResult?.posture ?: "unknown",
                            torsoAngle = poseResult?.torsoAngle ?: 0f,
                            bodyOrientation = poseResult?.bodyOrientation ?: "",
                            landmarksVisible = poseResult?.landmarksVisible ?: emptyMap(),
                            poseContextString = contextString ?: "",
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error: ${e.message}")
            } finally {
                // Recycle the frame copy — latestCroppedBitmap has its own independent copy
                if (!frameBitmap.isRecycled) frameBitmap.recycle()
                // Also recycle YOLO's crop if it was a different bitmap
                isProcessing = false
            }
        }
    }

    // ── Voice Turn Processing ──

    /**
     * Process a voice turn through Gemma inference.
     *
     * Mirrors the Python server.py WebSocket handler's LLM inference logic:
     * 1. Build prompt with pose context + history + image
     * 2. Run Gemma inference
     * 3. Parse triage level from response
     * 4. TTS the response
     * 5. Update history
     */
    private fun processTurn(userText: String) {
        val currentGemma = gemma ?: run {
            Log.e(TAG, "Gemma not ready for turn")
            _uiState.update { it.copy(gemmaResponse = "Triage AI is still loading. Please wait.") }
            return
        }

        _uiState.update { it.copy(isGemmaThinking = true) }

        viewModelScope.launch(Dispatchers.Default) {
            val poseContext = _uiState.value.poseContextString

            Log.d(TAG, "Gemma turn started")

            // Get the latest cropped bitmap
            val imageBitmap = latestCroppedBitmap?.let {
                if (!it.isRecycled) it.copy(Bitmap.Config.ARGB_8888, false) else null
            }

            // Run inference — conversation is stateful, no history injection needed
            try {
                val (aiResponse, triageLevel) = currentGemma.generateTriageResponse(
                    poseContext = poseContext.ifBlank { null },
                    voiceTranscript = userText,
                    history = "",  // Not used in stateful mode — conversation remembers
                    croppedBitmap = imageBitmap
                )
                
                Log.d(TAG, "Gemma turn success: $aiResponse")

                // Recycle the copy we made for inference
                imageBitmap?.recycle()

                withContext(Dispatchers.Main) {
                    tts.speak(aiResponse)
                    _uiState.update {
                        it.copy(
                            gemmaResponse = aiResponse,
                            triageLevel = triageLevel,
                            isGemmaThinking = false,
                        )
                    }
                }

                // Log the turn (on background thread — non-blocking)
                logger.logTurn(
                    image = latestCroppedBitmap,
                    poseContext = poseContext.ifBlank { null },
                    voiceTranscript = userText,
                    aiResponse = aiResponse,
                    triageLevel = triageLevel
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gemma critical error: ${e.message}", e)
                imageBitmap?.recycle()
                withContext(Dispatchers.Main) {
                    _uiState.update { 
                        it.copy(
                            gemmaResponse = "Error processing turn: ${e.message}",
                            isGemmaThinking = false 
                        ) 
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
        tts.shutdown()
        gemma?.close()
        logger.closeSession()
        latestCroppedBitmap?.recycle()
        Log.d(TAG, "ViewModel cleared, resources released")
    }
}
