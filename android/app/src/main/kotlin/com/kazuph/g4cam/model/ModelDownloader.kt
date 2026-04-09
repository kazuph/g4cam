package com.kazuph.g4cam.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "ModelDownloader"

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long = 0, val totalBytes: Long = 0) : DownloadState()
    data object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME = "gemma-4-e2b.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelFile(): File {
        // Check internal storage first
        val internal = File(context.filesDir, MODEL_FILENAME)
        if (internal.exists() && internal.length() > 2_500_000_000L) return internal

        // Check external app storage (accessible via adb, no permission needed)
        val external = context.getExternalFilesDir(null)?.let { File(it, MODEL_FILENAME) }
        if (external != null && external.exists() && external.length() > 2_500_000_000L) {
            Log.i(TAG, "Model found in external storage: ${external.absolutePath}")
            return external
        }

        return internal
    }

    // Model is ~2.58GB. Only consider complete if > 2.5GB
    fun isModelDownloaded(): Boolean = getModelFile().let { it.exists() && it.length() > 2_500_000_000L }

    fun download(): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))

        val modelFile = File(context.filesDir, MODEL_FILENAME)
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

        try {
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                if (existingBytes > 0) tempFile.delete()
                emit(DownloadState.Error("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Error("Empty response"))
                return@flow
            }

            val contentLength = body.contentLength()
            val isResumed = response.code == 206
            val totalBytes = if (isResumed) existingBytes + contentLength else contentLength
            var downloadedBytes = if (isResumed) existingBytes else 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile, isResumed).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            emit(DownloadState.Downloading(
                                progress = downloadedBytes.toFloat() / totalBytes,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes
                            ))
                        }
                    }
                }
            }

            if (!tempFile.renameTo(modelFile)) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            emit(DownloadState.Completed)
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
}
