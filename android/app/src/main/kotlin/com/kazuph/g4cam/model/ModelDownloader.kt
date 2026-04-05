package com.kazuph.g4cam.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-litert-lm.litertlm"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelFile(): File = File(context.filesDir, MODEL_FILENAME)

    fun isModelDownloaded(): Boolean = getModelFile().let { it.exists() && it.length() > 0 }

    fun download(): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))

        val modelFile = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

        try {
            // Resume support: check existing temp file size
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                // If range not supported and we had partial, start fresh
                if (existingBytes > 0 && response.code == 200) {
                    tempFile.delete()
                } else {
                    emit(DownloadState.Error("HTTP ${response.code}: ${response.message}"))
                    return@flow
                }
            }

            val body = response.body ?: run {
                emit(DownloadState.Error("Empty response"))
                return@flow
            }

            val contentLength = body.contentLength()
            val isResumed = response.code == 206
            val totalBytes = if (isResumed) existingBytes + contentLength else contentLength
            var downloadedBytes = if (isResumed) existingBytes else 0L

            // If not resuming after a failed range request, start fresh
            if (!isResumed && existingBytes > 0 && response.code == 200) {
                downloadedBytes = 0L
            }

            body.byteStream().use { input ->
                val fos = if (isResumed) {
                    RandomAccessFile(tempFile, "rw").apply { seek(existingBytes) }
                } else {
                    null
                }
                val outputStream = fos?.let { null } ?: FileOutputStream(tempFile, isResumed)

                try {
                    val buffer = ByteArray(65536)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (fos != null) {
                            fos.write(buffer, 0, bytesRead)
                        } else {
                            outputStream!!.write(buffer, 0, bytesRead)
                        }
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            emit(DownloadState.Downloading(
                                progress = downloadedBytes.toFloat() / totalBytes,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes
                            ))
                        }
                    }
                } finally {
                    fos?.close()
                    outputStream?.close()
                }
            }

            // Rename temp to final
            if (!tempFile.renameTo(modelFile)) {
                // Fallback: copy and delete
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            emit(DownloadState.Completed)
        } catch (e: Exception) {
            // Don't delete temp file on error - allows resume
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
}
