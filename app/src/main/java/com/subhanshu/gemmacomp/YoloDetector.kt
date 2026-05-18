package com.subhanshu.gemmacomp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLO11n person detector with auto-crop support.
 * Mirrors the Python `person_detector.py` pipeline:
 *   Camera frame → YOLO detect → Crop to largest person (+ 15% padding)
 */
class YoloDetector(context: Context, modelPath: String) {
    private var interpreter: Interpreter? = null

    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 640
        private const val PERSON_CLASS_INDEX = 0 // COCO class 0 = person
        private const val CONFIDENCE_THRESHOLD = 0.35f // LOWERED from 0.45f to improve detection
        private const val CROP_PADDING = 0.15f // 15% padding around detection (matches Python)
    }

    init {
        try {
            val model = FileUtil.loadMappedFile(context, modelPath)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            interpreter = Interpreter(model, options)
            Log.d(TAG, "YOLO initialized with NNAPI")
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI init failed, falling back to CPU: ${e.message}")
            try {
                val model = FileUtil.loadMappedFile(context, modelPath)
                interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
                Log.d(TAG, "YOLO fallback to CPU successful")
            } catch (e2: Exception) {
                Log.e(TAG, "Critical failure in YOLO init: ${e2.message}")
            }
        }
    }

    /**
     * Detect the largest person and return a [Detection] (bounding box + confidence).
     * Returns null if no person found.
     */
    fun detect(bitmap: Bitmap): Detection? {
        val engine = interpreter ?: return null

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // Output shape: [1][84][8400] — YOLO11 output format
        val output = Array(1) { Array(84) { FloatArray(8400) } }

        return try {
            engine.run(inputBuffer, output)

            var bestConfidence = 0.0f
            var bestDetection: Detection? = null

            for (i in 0 until 8400) {
                // Class scores start at index 4; class 0 = person
                val personScore = output[0][4 + PERSON_CLASS_INDEX][i]

                if (personScore > CONFIDENCE_THRESHOLD && personScore > bestConfidence) {
                    val cx = output[0][0][i]
                    val cy = output[0][1][i]
                    val w = output[0][2][i]
                    val h = output[0][3][i]

                    // Skip invalid boxes: must have positive dimensions and be within frame
                    if (w < 10f || h < 10f || cx < 0f || cy < 0f || cx > INPUT_SIZE || cy > INPUT_SIZE) {
                        continue
                    }

                    bestConfidence = personScore

                    // Clamp to valid range within YOLO's 640x640 space
                    val left = maxOf(0f, cx - w / 2)
                    val top = maxOf(0f, cy - h / 2)
                    val right = minOf(INPUT_SIZE.toFloat(), cx + w / 2)
                    val bottom = minOf(INPUT_SIZE.toFloat(), cy + h / 2)

                    bestDetection = Detection(
                        boundingBox = RectF(left, top, right, bottom),
                        confidence = personScore
                    )
                }
            }
            bestDetection
        } catch (e: Exception) {
            Log.e(TAG, "Error during YOLO inference: ${e.message}")
            null
        }
    }

    /**
     * Detect the largest person, crop the image to focus on them (with 15% padding),
     * and return a [CropResult].
     *
     * This mirrors Python's `person_detector.detect_and_crop(return_viz_data=True)`.
     *
     * @param bitmap Full camera frame.
     * @return [CropResult] with cropped bitmap and bounding box, or a fallback using the full frame.
     */
    fun detectAndCrop(bitmap: Bitmap): CropResult {
        val detection = detect(bitmap)

        if (detection == null) {
            Log.d(TAG, "No person detected — using full frame")
            return CropResult(
                croppedBitmap = bitmap,
                boundingBox = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                confidence = 0f,
                personDetected = false
            )
        }

        // Scale bounding box from YOLO's 640x640 space to actual image dimensions
        val scaleX = bitmap.width.toFloat() / INPUT_SIZE
        val scaleY = bitmap.height.toFloat() / INPUT_SIZE

        val box = detection.boundingBox
        var x1 = (box.left * scaleX).toInt()
        var y1 = (box.top * scaleY).toInt()
        var x2 = (box.right * scaleX).toInt()
        var y2 = (box.bottom * scaleY).toInt()

        // Add 15% padding (matches Python behavior)
        val padW = ((x2 - x1) * CROP_PADDING).toInt()
        val padH = ((y2 - y1) * CROP_PADDING).toInt()
        x1 = maxOf(0, x1 - padW)
        y1 = maxOf(0, y1 - padH)
        x2 = minOf(bitmap.width, x2 + padW)
        y2 = minOf(bitmap.height, y2 + padH)

        val cropWidth = x2 - x1
        val cropHeight = y2 - y1

        if (cropWidth <= 0 || cropHeight <= 0) {
            Log.w(TAG, "Invalid crop dimensions — using full frame")
            return CropResult(
                croppedBitmap = bitmap,
                boundingBox = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                confidence = detection.confidence,
                personDetected = false
            )
        }

        val cropped = Bitmap.createBitmap(bitmap, x1, y1, cropWidth, cropHeight)
        Log.d(TAG, "Cropped person: ${bitmap.width}x${bitmap.height} → ${cropWidth}x${cropHeight}")

        return CropResult(
            croppedBitmap = cropped,
            boundingBox = RectF(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()),
            confidence = detection.confidence,
            personDetected = true
        )
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        byteBuffer.rewind()

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16 and 0xFF) / 255f))
            byteBuffer.putFloat(((pixelValue shr 8 and 0xFF) / 255f))
            byteBuffer.putFloat(((pixelValue and 0xFF) / 255f))
        }
        byteBuffer.rewind()
        return byteBuffer
    }
}