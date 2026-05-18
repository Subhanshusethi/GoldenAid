package com.subhanshu.gemmacomp

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Result of YOLO person detection and cropping.
 * Mirrors the Python `detect_and_crop(return_viz_data=True)` return tuple.
 */
data class CropResult(
    /** The cropped bitmap focused on the detected person (with padding). */
    val croppedBitmap: Bitmap,
    /** Bounding box in original image coordinates (for drawing overlays). */
    val boundingBox: RectF,
    /** Detection confidence score (0–1). */
    val confidence: Float,
    /** Whether a person was actually detected (false = fallback to full frame). */
    val personDetected: Boolean
)
