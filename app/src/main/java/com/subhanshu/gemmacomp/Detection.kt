package com.subhanshu.gemmacomp

import android.graphics.RectF

data class Detection(
    val boundingBox: RectF,
    val confidence: Float
)