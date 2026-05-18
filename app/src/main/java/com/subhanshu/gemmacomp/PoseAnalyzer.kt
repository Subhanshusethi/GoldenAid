package com.subhanshu.gemmacomp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Rich pose analysis result — mirrors Python's `analyze_pose()` return dict.
 */
data class PoseResult(
    val posture: String,           // "standing", "lying_down", "sitting_or_slumped"
    val torsoAngle: Float,         // degrees from vertical
    val bodyOrientation: String,   // "upright", "supine (face up)", "prone (face down)", etc.
    val landmarksVisible: Map<String, Boolean>,  // face, left_arm, right_arm, left_leg, right_leg
    val rawLandmarks: List<LandmarkPoint>?       // for drawing overlays
)

/** Simple x,y,visibility landmark for overlay drawing. */
data class LandmarkPoint(
    val x: Float,  // normalized 0..1
    val y: Float,  // normalized 0..1
    val visibility: Float
)

/**
 * MediaPipe BlazePose body analysis — full port of Python `pose_analyzer.py`.
 *
 * Extracts 33 keypoints, calculates torso angle, classifies posture,
 * determines body orientation, and tracks limb visibility.
 */
class PoseAnalyzer(context: Context) {
    private var poseLandmarker: PoseLandmarker? = null

    // Landmark indices (MediaPipe 33-point layout)
    companion object {
        private const val TAG = "PoseAnalyzer"
        private const val NOSE = 0
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_ELBOW = 13
        private const val RIGHT_ELBOW = 14
        private const val LEFT_WRIST = 15
        private const val RIGHT_WRIST = 16
        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24
        private const val LEFT_KNEE = 25
        private const val RIGHT_KNEE = 26
        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28

        private const val VIS_THRESHOLD = 0.5f

        /** Skeleton connections for drawing (pairs of landmark indices). */
        val SKELETON_CONNECTIONS = listOf(
            11 to 12, // shoulders
            11 to 13, 13 to 15, // left arm
            12 to 14, 14 to 16, // right arm
            11 to 23, 12 to 24, // torso sides
            23 to 24, // hips
            23 to 25, 25 to 27, // left leg
            24 to 26, 26 to 28, // right leg
        )

        /**
         * Convert PoseResult into a natural-language context string for Gemma.
         * Ported from Python's `pose_to_context_string()`.
         */
        fun poseToContextString(poseResult: PoseResult?): String? {
            if (poseResult == null) return null

            val postureLabels = mapOf(
                "standing" to "standing upright",
                "lying_down" to "lying on the ground",
                "sitting_or_slumped" to "sitting or slumped over"
            )

            val parts = mutableListOf<String>()
            parts.add("Pose analysis: Patient is ${postureLabels[poseResult.posture] ?: poseResult.posture}.")
            parts.add("Body orientation: ${poseResult.bodyOrientation}.")
            parts.add("Torso angle: ${poseResult.torsoAngle.toInt()}° from vertical.")

            val visible = poseResult.landmarksVisible.filter { it.value }.keys
                .map { it.replace("_", " ") }
            val notVisible = poseResult.landmarksVisible.filter { !it.value }.keys
                .map { it.replace("_", " ") }

            if (visible.isNotEmpty()) {
                parts.add("Visible: ${visible.joinToString(", ")}.")
            }
            if (notVisible.isNotEmpty()) {
                parts.add("Not clearly visible: ${notVisible.joinToString(", ")}.")
            }

            return parts.joinToString(" ")
        }
    }

    init {
        try {
            // Try GPU first
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .setDelegate(Delegate.GPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.d(TAG, "PoseLandmarker initialized with GPU")
        } catch (e: Exception) {
            Log.w(TAG, "GPU init failed, falling back to CPU: ${e.message}")
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .setDelegate(Delegate.CPU)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
                Log.d(TAG, "PoseLandmarker initialized with CPU")
            } catch (e2: Exception) {
                Log.e(TAG, "Total failure in PoseAnalyzer init: ${e2.message}")
            }
        }
    }

    /**
     * Full pose analysis — mirrors Python's `analyze_pose()`.
     *
     * @param bitmap The image to analyze (ideally YOLO-cropped).
     * @return [PoseResult] with posture, angle, orientation, visibility, and raw landmarks.
     *         Returns null if no pose detected.
     */
    fun analyzeFull(bitmap: Bitmap): PoseResult? {
        val landmarker = poseLandmarker ?: return null

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: PoseLandmarkerResult = landmarker.detect(mpImage)

            if (result.landmarks().isEmpty()) return null

            val landmarks = result.landmarks()[0]

            // Extract shoulder and hip keypoints
            val lShoulder = landmarks[LEFT_SHOULDER]
            val rShoulder = landmarks[RIGHT_SHOULDER]
            val lHip = landmarks[LEFT_HIP]
            val rHip = landmarks[RIGHT_HIP]

            // Compute centers (normalized 0..1 coords)
            val midShoulderX = (lShoulder.x() + rShoulder.x()) / 2f
            val midShoulderY = (lShoulder.y() + rShoulder.y()) / 2f
            val midHipX = (lHip.x() + rHip.x()) / 2f
            val midHipY = (lHip.y() + rHip.y()) / 2f

            // Calculate torso angle from vertical
            // Ported from Python: abs(90 - degrees(atan2(dy, dx)))
            val dy = midShoulderY - midHipY
            val dx = midShoulderX - midHipX
            val radians = atan2(dy.toDouble(), dx.toDouble())
            val torsoAngle = abs(90f - Math.toDegrees(radians).toFloat())

            // Classify posture (same thresholds as Python)
            val posture = when {
                torsoAngle < 10f -> "standing"
                torsoAngle > 60f -> "lying_down"
                else -> "sitting_or_slumped"
            }

            // Check limb visibility
            fun vis(idx: Int): Boolean {
                val lm = landmarks[idx]
                return try {
                    lm.visibility().orElse(0f) > VIS_THRESHOLD
                } catch (_: Exception) {
                    try {
                        lm.presence().orElse(0f) > VIS_THRESHOLD
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            val landmarksVisible = mapOf(
                "face" to vis(NOSE),
                "left_arm" to (vis(LEFT_WRIST) && vis(LEFT_ELBOW)),
                "right_arm" to (vis(RIGHT_WRIST) && vis(RIGHT_ELBOW)),
                "left_leg" to (vis(LEFT_KNEE) && vis(LEFT_ANKLE)),
                "right_leg" to (vis(RIGHT_KNEE) && vis(RIGHT_ANKLE)),
            )

            // Body orientation (ported from Python)
            val bodyOrientation = when (posture) {
                "lying_down" -> {
                    val noseY = landmarks[NOSE].y()
                    val avgHipY = (lHip.y() + rHip.y()) / 2f
                    if (landmarksVisible["face"] == true) {
                        if (noseY < avgHipY) "supine (face up)" else "prone (face down)"
                    } else {
                        "face not visible, likely prone"
                    }
                }
                "standing" -> "upright"
                else -> "partially reclined or slumped"
            }

            // Raw landmarks for drawing
            val rawLandmarks = landmarks.map { lm ->
                LandmarkPoint(
                    x = lm.x(),
                    y = lm.y(),
                    visibility = try { lm.visibility().orElse(0f) } catch (_: Exception) { 0f }
                )
            }

            PoseResult(
                posture = posture,
                torsoAngle = torsoAngle,
                bodyOrientation = bodyOrientation,
                landmarksVisible = landmarksVisible,
                rawLandmarks = rawLandmarks
            )
        } catch (e: Exception) {
            Log.e(TAG, "Pose analysis failed: ${e.message}")
            null
        }
    }

    /**
     * Simple analyze for backward compatibility — returns (angle, posture) pair.
     */
    fun analyze(bitmap: Bitmap): Pair<Float, String> {
        val result = analyzeFull(bitmap) ?: return Pair(0f, "Unknown")
        return Pair(result.torsoAngle, result.posture)
    }


    /**
     * Draw pose skeleton on a Canvas (for camera overlay).
     * Landmarks are in normalized [0..1] coordinates.
     */
    fun drawPoseOverlay(
        canvas: Canvas,
        poseResult: PoseResult,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val landmarks = poseResult.rawLandmarks ?: return

        val dotPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.argb(180, 0, 255, 0)
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        // Draw skeleton connections
        for ((i, j) in SKELETON_CONNECTIONS) {
            if (i < landmarks.size && j < landmarks.size) {
                val a = landmarks[i]
                val b = landmarks[j]
                if (a.visibility > VIS_THRESHOLD && b.visibility > VIS_THRESHOLD) {
                    canvas.drawLine(
                        a.x * imageWidth, a.y * imageHeight,
                        b.x * imageWidth, b.y * imageHeight,
                        linePaint
                    )
                }
            }
        }

        // Draw landmark dots
        for (lm in landmarks) {
            if (lm.visibility > VIS_THRESHOLD) {
                canvas.drawCircle(lm.x * imageWidth, lm.y * imageHeight, 4f, dotPaint)
            }
        }
    }
}