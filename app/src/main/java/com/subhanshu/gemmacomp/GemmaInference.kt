package com.subhanshu.gemmacomp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.*
import java.io.ByteArrayOutputStream

/**
 * Gemma 4 on-device inference via LiteRT-LM.
 *
 * HYBRID approach: Stateful conversation with auto-reset.
 * - Keeps a persistent conversation (fast: system prompt KV cache is reused)
 * - Auto-resets after MAX_TURNS_BEFORE_RESET to prevent hallucination buildup
 * - On reset, closes old conversation (frees native memory) and creates a fresh one
 */
class GemmaInference(context: Context) {

    companion object {
        private const val TAG = "GemmaInference"

        /** Reset the conversation after this many turns to prevent context overflow. */
        private const val MAX_TURNS_BEFORE_RESET = 8

        /**
         * Trimmed system prompt — ~300 tokens instead of ~800.
         * Removed the first-aid knowledge base (model knows it from training).
         * Kept: decision tree, output format, core behavior rules.
         */
        val SYSTEM_PROMPT = """
You are GoldenAid, an emergency first aid AI assistant for untrained bystanders at road accidents in India. The person talking to you is a civilian who needs simple, immediate guidance.

DECISION TREE (follow in order, STOP when a level is assigned):
1. WALKING? → YES: GREEN. STOP.
2. BREATHING? → NO: RED. STOP.
3. Uncontrolled bleeding? → YES: RED. STOP.
4. Cannot follow simple commands? → YES: RED. STOP.
5. Responds appropriately? → YELLOW. STOP.
6. User states person is dead? → BLACK. STOP.

RULES:
1. In your VERY FIRST response only, tell them to call 108. After that, NEVER mention 108 again.
2. Give ONE action at a time. Max 2 sentences. Action verbs first ("Press", "Hold", "Check").
3. NEVER ask "are you ready" — just TELL them what to do.
4. You can SEE the patient through the camera. Trust the image.
5. If bleeding: "Press hard with any cloth and do not lift it."
6. If not breathing: "Place hands on center of chest. Push hard and fast 30 times."
7. NEVER use medical jargon. Plain language only.
8. End each response with ONE short follow-up question.
9. STATE STABILITY: Once you assign RED, keep the triage level as RED in future turns unless the user explicitly says the person is fully recovered. Do not bounce between RED and YELLOW.

FORMAT — reply exactly like this:
TRIAGE_LEVEL: [RED/YELLOW/GREEN/BLACK]
RESPONSE: [Your 1-2 sentence instruction + follow-up question]
        """.trimIndent()
    }

    private val modelPath = ModelUtils.getModelPath(context, "gemma-4-E2B-it.litertlm")
    private val cacheDir = context.getExternalFilesDir(null)?.absolutePath
    private var engine: Engine? = null

    // Stateful conversation — persists across turns for speed
    private var conversation: Conversation? = null
    private var turnCount = 0

    init {
        try {
            // Match Edge Gallery 1.0.14 config for Gemma 4 E2B exactly:
            //   accelerators: "gpu,cpu", visionAccelerator: "gpu"
            //   No maxNumImages set, cacheDir for GPU shader compilation
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                maxNumTokens = 4000,
                cacheDir = cacheDir     // GPU needs writable dir for compiled shaders
            )
            engine = Engine(config)
            engine?.initialize()
            Log.d(TAG, "✅ Engine initialized (GPU LLM + GPU vision)")
            resetConversation()
        } catch (e: Exception) {
            Log.w(TAG, "GPU init failed, trying CPU+GPU vision: ${e.message}")
            try {
                val fallbackConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.GPU(),
                    maxNumTokens = 4000,
                    cacheDir = cacheDir
                )
                engine = Engine(fallbackConfig)
                engine?.initialize()
                Log.d(TAG, "✅ Engine initialized (CPU LLM + GPU vision)")
                resetConversation()
            } catch (e2: Exception) {
                Log.e(TAG, "❌ All init failed: ${e2.message}", e2)
            }
        }
    }

    /**
     * Close the old conversation (freeing native memory) and create a fresh one
     * with system prompt and low-temperature sampling pre-configured.
     */
    @Synchronized
    private fun resetConversation() {
        // Close old conversation to free native memory
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Old conversation close failed: ${e.message}")
        }

        // Create fresh conversation with low temperature + system prompt via config
        try {
            val config = ConversationConfig(
                systemInstruction = Contents.of(Content.Text(SYSTEM_PROMPT)),
                samplerConfig = SamplerConfig(
                    temperature = 0.45,   // Increased to 0.45 to prevent conversational looping
                    topK = 20,           // Narrow token selection
                    topP = 0.85          // Nucleus sampling cutoff
                )
            )
            conversation = engine?.createConversation(config)
            turnCount = 0
            Log.d(TAG, "Conversation reset — system prompt + sampler configured (temp=0.45)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation: ${e.message}", e)
            conversation = null
        }
    }

    /**
     * Generate a triage response using a STATEFUL conversation.
     *
     * Fast because the system prompt KV cache is reused across turns.
     * Auto-resets after MAX_TURNS_BEFORE_RESET to prevent hallucination buildup.
     */
    @Synchronized
    fun generateTriageResponse(
        poseContext: String?,
        voiceTranscript: String,
        history: String,
        croppedBitmap: Bitmap? = null
    ): Pair<String, TriageLevel> {
        val currentConversation = conversation
            ?: return Pair("Triage AI is not ready yet.", TriageLevel.UNKNOWN)

        // Auto-reset if we've hit the turn limit
        if (turnCount >= MAX_TURNS_BEFORE_RESET) {
            Log.d(TAG, "Auto-resetting conversation after $turnCount turns")
            resetConversation()
            // Use the freshly created conversation
            val freshConv = conversation
                ?: return Pair("Triage AI reset failed.", TriageLevel.UNKNOWN)
            return doInference(freshConv, poseContext, voiceTranscript, croppedBitmap)
        }

        return doInference(currentConversation, poseContext, voiceTranscript, croppedBitmap)
    }

    private fun doInference(
        conv: Conversation,
        poseContext: String?,
        voiceTranscript: String,
        croppedBitmap: Bitmap?
    ): Pair<String, TriageLevel> {

        val prompt = buildString {
            if (!poseContext.isNullOrBlank()) {
                append("[POSE DATA] $poseContext\n")
            }
            append("[RESPONDER] \"$voiceTranscript\"")
        }

        Log.d(TAG, "Prompt (${prompt.length} chars, image=${croppedBitmap != null}, pose=${!poseContext.isNullOrBlank()})")

        return try {
            val rawResponse: String = if (croppedBitmap != null) {
                try {
                    val imgBytes = bitmapToPngBytes(croppedBitmap)
                    Log.d(TAG, "📸 Sending image: ${croppedBitmap.width}x${croppedBitmap.height}, ${imgBytes.size} bytes (PNG)")
                    val message = Message.user(
                        Contents.of(
                            Content.ImageBytes(imgBytes),
                            Content.Text(prompt)
                        )
                    )
                    val response = conv.sendMessage(message).toString()
                    Log.d(TAG, "✅ Vision inference succeeded")
                    response
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Vision failed, text-only fallback: ${e.message}", e)
                    conv.sendMessage(prompt).toString()
                }
            } else {
                conv.sendMessage(prompt).toString()
            }

            turnCount++

            val cleaned = rawResponse.replace("<|\"|>", "").trim()
            val triageLevel = TriageLevel.fromResponseText(cleaned)
            val responseText = extractResponseText(cleaned)

            Log.d(TAG, "Turn $turnCount/$MAX_TURNS_BEFORE_RESET (triage=$triageLevel): $responseText")
            Pair(responseText, triageLevel)

        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            resetConversation()
            Pair(
                "I had trouble processing that. Could you repeat what you said?",
                TriageLevel.UNKNOWN
            )
        }
    }

    /**
     * Extract the response text from the structured LLM output.
     * Then truncate to max 3 sentences as a safety net.
     */
    private fun extractResponseText(rawText: String): String {
        val responseMatch = Regex("RESPONSE:\\s*(.+)", RegexOption.DOT_MATCHES_ALL).find(rawText)
        var text = if (responseMatch != null) {
            responseMatch.groupValues[1].trim()
        } else {
            val lines = rawText.lines().filter { !it.trimStart().startsWith("TRIAGE_LEVEL:", ignoreCase = true) }
            lines.joinToString(" ").trim().ifBlank { rawText }
        }

        text = stripPreamble(text)
        return truncateToSentences(text, maxSentences = 3)
    }

    /** Remove common preamble patterns to make TTS output crisp and direct. */
    private fun stripPreamble(text: String): String {
        val preamblePatterns = listOf(
            Regex("^.*?(?:I am assigning|assigning)\\s+(?:a\\s+)?(?:RED|YELLOW|GREEN|BLACK)\\s*(?:triage)?\\s*(?:level)?[.,;:!]?\\s*", RegexOption.IGNORE_CASE),
            Regex("^.*?(?:This is|That is)\\s+(?:a\\s+)?(?:RED|YELLOW|GREEN|BLACK)\\s*(?:triage)?\\s*(?:level|situation)?[.,;:!]?\\s*", RegexOption.IGNORE_CASE),
        )
        var result = text
        for (pattern in preamblePatterns) {
            val stripped = pattern.replaceFirst(result, "")
            if (stripped.length > 15) {
                result = stripped.replaceFirstChar { it.uppercaseChar() }
            }
        }
        return result
    }

    private fun truncateToSentences(text: String, maxSentences: Int): String {
        val sentences = text.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
        if (sentences.size <= maxSentences) return text
        return sentences.take(maxSentences).joinToString(" ")
    }

    /** Convert Bitmap to PNG bytes — matches Edge Gallery's toPngByteArray(). */
    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /** Force a manual reset (e.g., "next patient" command). */
    fun forceReset() {
        resetConversation()
    }

    fun close() {
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null
        engine = null
    }
}
