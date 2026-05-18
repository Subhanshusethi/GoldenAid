package com.subhanshu.gemmacomp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs triage sessions to external storage for documentation and review.
 *
 * Each session creates a folder with:
 *   - session.json   → structured log of all turns + GPS + timestamps
 *   - turn_001.jpg   → patient image for that turn
 *   - turn_002.jpg   → ...
 *
 * Storage: /sdcard/Android/data/com.subhanshu.gemmacomp/files/triage_sessions/
 * No extra permissions needed (app-scoped external storage).
 */
class TriageLogger(private val context: Context) {

    companion object {
        private const val TAG = "TriageLogger"
        private const val SESSIONS_DIR = "triage_sessions"
        private const val JPEG_QUALITY = 85
    }

    private var sessionDir: File? = null
    private var turnCount = 0
    private var sessionData = JSONObject()
    private var turnsArray = JSONArray()
    private var sessionStartTime: Long = 0

    /**
     * Start a new triage session. Call this when the app starts or on "Next Patient".
     */
    fun newSession() {
        // Save previous session first
        saveSession()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val baseDir = File(context.getExternalFilesDir(null), SESSIONS_DIR)
        sessionDir = File(baseDir, timestamp).also { it.mkdirs() }

        turnCount = 0
        turnsArray = JSONArray()
        sessionStartTime = System.currentTimeMillis()

        sessionData = JSONObject().apply {
            put("session_id", timestamp)
            put("start_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            put("device_model", android.os.Build.MODEL)
            put("device_manufacturer", android.os.Build.MANUFACTURER)

            // GPS coordinates
            val location = getLastKnownLocation()
            if (location != null) {
                put("gps_latitude", location.latitude)
                put("gps_longitude", location.longitude)
                put("gps_accuracy_meters", location.accuracy)
                put("gps_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(location.time)))
            } else {
                put("gps_latitude", JSONObject.NULL)
                put("gps_longitude", JSONObject.NULL)
            }
        }

        Log.d(TAG, "New session started: $timestamp at ${sessionDir?.absolutePath}")
    }

    /**
     * Log a single triage turn (image + text + AI response).
     */
    fun logTurn(
        image: Bitmap?,
        poseContext: String?,
        voiceTranscript: String,
        aiResponse: String,
        triageLevel: TriageLevel
    ) {
        val dir = sessionDir ?: run {
            // Auto-start session if none exists
            newSession()
            sessionDir ?: return
        }

        turnCount++
        val turnId = String.format("turn_%03d", turnCount)

        // Save image as JPEG
        var imagePath: String? = null
        if (image != null && !image.isRecycled) {
            try {
                val imageFile = File(dir, "$turnId.jpg")
                FileOutputStream(imageFile).use { out ->
                    image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                imagePath = imageFile.name
                Log.d(TAG, "Saved image: ${imageFile.name} (${imageFile.length() / 1024}KB)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image: ${e.message}")
            }
        }

        // Build turn entry
        val turnEntry = JSONObject().apply {
            put("turn_number", turnCount)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            put("voice_transcript", voiceTranscript)
            put("pose_context", poseContext ?: "")
            put("ai_response", aiResponse)
            put("triage_level", triageLevel.name)
            put("image_file", imagePath ?: JSONObject.NULL)
            put("has_image", image != null)
        }

        turnsArray.put(turnEntry)

        // Save after every turn (in case app crashes)
        saveSession()

        Log.d(TAG, "Logged $turnId: triage=$triageLevel, image=${imagePath != null}")
    }

    /**
     * Write session.json to disk.
     */
    private fun saveSession() {
        val dir = sessionDir ?: return
        if (turnsArray.length() == 0 && turnCount == 0) return

        try {
            sessionData.put("turns", turnsArray)
            sessionData.put("total_turns", turnCount)
            sessionData.put("end_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            sessionData.put("duration_seconds", (System.currentTimeMillis() - sessionStartTime) / 1000)

            // Final triage level = last turn's level
            if (turnsArray.length() > 0) {
                val lastTurn = turnsArray.getJSONObject(turnsArray.length() - 1)
                sessionData.put("final_triage_level", lastTurn.getString("triage_level"))
            }

            val file = File(dir, "session.json")
            file.writeText(sessionData.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session: ${e.message}")
        }
    }

    /**
     * Get last known GPS location without requesting updates.
     * Uses coarse location from network or GPS providers.
     */
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try GPS first, then network
            val gpsLocation = try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (_: SecurityException) { null }

            val networkLocation = try {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) { null }

            // Return whichever is more recent
            when {
                gpsLocation != null && networkLocation != null ->
                    if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
                gpsLocation != null -> gpsLocation
                networkLocation != null -> networkLocation
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get location: ${e.message}")
            null
        }
    }

    /**
     * Finalize and close the current session.
     */
    fun closeSession() {
        saveSession()
        sessionDir = null
        Log.d(TAG, "Session closed")
    }
}
