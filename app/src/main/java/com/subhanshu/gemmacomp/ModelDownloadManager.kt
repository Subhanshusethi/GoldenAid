package com.subhanshu.gemmacomp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading the Gemma model file at runtime from HuggingFace.
 *
 * Features (inspired by Edge Gallery's DownloadWorker):
 *   - Progress reporting via StateFlow
 *   - Resume support via HTTP Range headers
 *   - Download speed calculation
 *   - Manual redirect following (HuggingFace uses CDN redirects)
 *   - Atomic rename (tmp file → final file) to prevent partial-file usage
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        // TODO: (Optional) Replace with your HuggingFace token for faster downloads (https://huggingface.co/settings/tokens)
        private const val HF_TOKEN = ""
        private const val MODEL_SIZE_BYTES = 2_780_000_000L  // ~2.59 GB
        private const val TMP_EXTENSION = ".downloading"
        private const val MAX_REDIRECTS = 5
    }

    /** Download progress state exposed to the UI. */
    data class DownloadProgress(
        val status: Status = Status.NOT_STARTED,
        val totalBytes: Long = MODEL_SIZE_BYTES,
        val downloadedBytes: Long = 0L,
        val bytesPerSecond: Long = 0L,
        val errorMessage: String? = null,
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0
        val downloadedMB: String
            get() = "%.0f".format(downloadedBytes / 1_000_000.0)
        val totalMB: String
            get() = "%.0f".format(totalBytes / 1_000_000.0)
        val speedMBps: String
            get() = "%.1f".format(bytesPerSecond / 1_000_000.0)
    }

    enum class Status {
        NOT_STARTED,
        DOWNLOADING,
        COMPLETED,
        FAILED
    }

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /** Returns the model file path if the model already exists on disk, null otherwise. */
    fun getExistingModelPath(): String? {
        val externalFile = File(context.getExternalFilesDir(null), MODEL_NAME)
        if (externalFile.exists()) {
            Log.d(TAG, "Model found: ${externalFile.absolutePath}")
            return externalFile.absolutePath
        }

        val internalFile = File(context.filesDir, MODEL_NAME)
        if (internalFile.exists()) {
            Log.d(TAG, "Model found: ${internalFile.absolutePath}")
            return internalFile.absolutePath
        }

        Log.d(TAG, "Model not found on device")
        return null
    }

    /**
     * Opens a connection to the given URL, manually following redirects.
     * HttpURLConnection doesn't follow cross-protocol (http→https) redirects,
     * and HuggingFace redirects to a CDN URL.
     */
    private fun openConnectionWithRedirects(
        urlString: String,
        existingBytes: Long = 0L
    ): HttpURLConnection {
        var currentUrl = urlString
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false  // Handle redirects manually
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "GoldenAid-Android/1.0")
            if (HF_TOKEN.isNotBlank() && HF_TOKEN != "PASTE_YOUR_TOKEN_HERE") {
                connection.setRequestProperty("Authorization", "Bearer $HF_TOKEN")
            }

            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
                connection.setRequestProperty("Accept-Encoding", "identity")
            }

            connection.connect()
            val responseCode = connection.responseCode
            Log.d(TAG, "URL: $currentUrl → HTTP $responseCode")

            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl == null) throw RuntimeException("Redirect with no Location header")
                Log.d(TAG, "Following redirect → $newUrl")
                currentUrl = newUrl
                redirectCount++
            } else {
                return connection
            }
        }
        throw RuntimeException("Too many redirects ($MAX_REDIRECTS)")
    }

    /**
     * Download the model. Returns the file path on success.
     * Progress is reported via [progress] StateFlow.
     */
    suspend fun downloadModel(): String = withContext(Dispatchers.IO) {
        val outputDir = context.getExternalFilesDir(null)
            ?: throw RuntimeException("External files dir not available")
        val finalFile = File(outputDir, MODEL_NAME)
        val tmpFile = File(outputDir, "$MODEL_NAME$TMP_EXTENSION")

        // If already fully downloaded, skip
        if (finalFile.exists() && finalFile.length() > MODEL_SIZE_BYTES * 0.95) {
            _progress.value = DownloadProgress(status = Status.COMPLETED, downloadedBytes = finalFile.length())
            return@withContext finalFile.absolutePath
        }

        try {
            _progress.value = DownloadProgress(status = Status.DOWNLOADING)
            Log.d(TAG, "Starting download from: $MODEL_URL")

            // Resume support: if tmp file exists, request remaining bytes
            var existingBytes = 0L
            if (tmpFile.exists()) {
                existingBytes = tmpFile.length()
                Log.d(TAG, "Resuming download from byte $existingBytes")
            }

            val connection = openConnectionWithRedirects(MODEL_URL, existingBytes)
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw RuntimeException("HTTP error: $responseCode")
            }

            // Determine total size from Content-Range or Content-Length
            val totalBytes = if (connection.getHeaderField("Content-Range") != null) {
                val rangeParts = connection.getHeaderField("Content-Range")
                    .substringAfter("bytes ").split("/")
                if (rangeParts.size == 2 && rangeParts[1] != "*") {
                    rangeParts[1].toLong()
                } else {
                    MODEL_SIZE_BYTES
                }
            } else {
                val contentLength = connection.contentLengthLong
                if (contentLength > 0) contentLength + existingBytes else MODEL_SIZE_BYTES
            }

            Log.d(TAG, "Total model size: $totalBytes bytes, existing: $existingBytes bytes")

            var downloadedBytes = existingBytes
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tmpFile, true /* append for resume */)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            // Speed calculation buffers (rolling window of 5 samples, like Edge Gallery)
            val sizeBuffer = mutableListOf<Long>()
            val latencyBuffer = mutableListOf<Long>()
            var lastReportTime = System.currentTimeMillis()
            var deltaBytes = 0L

            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                deltaBytes += bytesRead

                // Report progress every ~300ms
                val now = System.currentTimeMillis()
                if (now - lastReportTime > 300) {
                    // Rolling window speed calculation
                    if (sizeBuffer.size >= 5) sizeBuffer.removeAt(0)
                    sizeBuffer.add(deltaBytes)
                    if (latencyBuffer.size >= 5) latencyBuffer.removeAt(0)
                    latencyBuffer.add(now - lastReportTime)

                    val bytesPerMs = if (latencyBuffer.sum() > 0)
                        sizeBuffer.sum().toFloat() / latencyBuffer.sum() else 0f

                    _progress.value = DownloadProgress(
                        status = Status.DOWNLOADING,
                        totalBytes = totalBytes,
                        downloadedBytes = downloadedBytes,
                        bytesPerSecond = (bytesPerMs * 1000).toLong()
                    )

                    deltaBytes = 0L
                    lastReportTime = now
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // Atomic rename: tmp → final (prevents using a partial file)
            if (finalFile.exists()) finalFile.delete()
            tmpFile.renameTo(finalFile)

            _progress.value = DownloadProgress(
                status = Status.COMPLETED,
                totalBytes = totalBytes,
                downloadedBytes = totalBytes
            )
            Log.d(TAG, "✅ Download complete: ${finalFile.absolutePath}")
            return@withContext finalFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: ${e.message}", e)
            _progress.value = DownloadProgress(
                status = Status.FAILED,
                errorMessage = e.message ?: "Unknown error"
            )
            throw e
        }
    }
}
