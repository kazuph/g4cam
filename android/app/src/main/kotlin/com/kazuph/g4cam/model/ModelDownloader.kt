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
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
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
        .build()

    fun getModelFile(): File = File(context.filesDir, MODEL_FILENAME)

    fun isModelDownloaded(): Boolean = getModelFile().let { it.exists() && it.length() > 0 }

    fun download(): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))

        val modelFile = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

        try {
            val request = Request.Builder().url(MODEL_URL).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Error("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Error("Empty response"))
                return@flow
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            emit(DownloadState.Downloading(downloadedBytes.toFloat() / totalBytes))
                        }
                    }
                }
            }

            tempFile.renameTo(modelFile)
            emit(DownloadState.Completed)
        } catch (e: Exception) {
            tempFile.delete()
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
}
