package com.subhanshu.gemmacomp

import android.content.Context
import android.util.Log
import java.io.File

object ModelUtils {
    private const val TAG = "ModelUtils"

    /**
     * Get path to the model file.
     *
     * Lookup order:
     *   1. External files dir (downloaded at runtime or pushed via adb)
     *   2. Internal files dir (copied from assets — legacy fallback)
     *
     * Returns the path if found, or throws if not found.
     * When not found, the app should trigger ModelDownloadManager to download it.
     */
    fun getModelPath(context: Context, modelName: String): String {
        // 1. Check external files dir (download destination / adb push destination)
        val externalFile = File(context.getExternalFilesDir(null), modelName)
        if (externalFile.exists()) {
            Log.d(TAG, "Model found in external storage: ${externalFile.absolutePath}")
            return externalFile.absolutePath
        }

        // 2. Check internal files dir (legacy: copied from assets previously)
        val internalFile = File(context.filesDir, modelName)
        if (internalFile.exists()) {
            Log.d(TAG, "Model found in internal storage: ${internalFile.absolutePath}")
            return internalFile.absolutePath
        }

        // 3. Model not found — caller should download it
        Log.w(TAG, "Model '$modelName' not found on device. Download required.")
        throw ModelNotFoundExcption(modelName)
    }

    /** Check if the model exists without throwing. */
    fun isModelAvailable(context: Context, modelName: String): Boolean {
        val externalFile = File(context.getExternalFilesDir(null), modelName)
        if (externalFile.exists()) return true

        val internalFile = File(context.filesDir, modelName)
        return internalFile.exists()
    }
}

/** Thrown when the model file is not found on disk. */
class ModelNotFoundExcption(modelName: String) :
    RuntimeException("Model '$modelName' not found. Download required.")