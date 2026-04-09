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
    data class Downloading(
        val modelId: ModelId,
        val progress: Float,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
    ) : DownloadState()
    data class Completed(val modelId: ModelId) : DownloadState()
    data class Error(val modelId: ModelId, val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelRoot(spec: LocalModelSpec): File = File(context.filesDir, "models/${spec.storageDir}")

    fun getPrimaryFile(spec: LocalModelSpec): File = File(getModelRoot(spec), spec.primaryFile)

    fun isModelDownloaded(spec: LocalModelSpec): Boolean =
        spec.files.all { fileSpec ->
            val localFile = File(getModelRoot(spec), fileSpec.relativePath)
            localFile.exists() && localFile.length() >= fileSpec.minBytes
        }

    fun download(spec: LocalModelSpec): Flow<DownloadState> = flow {
        val totalBytesExpected = spec.files.sumOf { it.expectedBytes }
        var downloadedAcrossFiles = 0L
        emit(DownloadState.Downloading(spec.id, progress = 0f, downloadedBytes = 0L, totalBytes = totalBytesExpected))

        try {
            val root = getModelRoot(spec)
            if (!root.exists()) {
                root.mkdirs()
            }

            spec.files.forEach { fileSpec ->
                val destination = File(root, fileSpec.relativePath)
                destination.parentFile?.mkdirs()

                if (destination.exists() && destination.length() >= fileSpec.minBytes) {
                    downloadedAcrossFiles += minOf(destination.length(), fileSpec.expectedBytes)
                    emitProgress(spec.id, downloadedAcrossFiles, totalBytesExpected, this)
                    return@forEach
                }

                val tempFile = File(destination.absolutePath + ".tmp")
                var existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val requestBuilder = Request.Builder().url(fileSpec.url)
                if (existingBytes > 0L) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val resumed = response.code == 206
                if (!response.isSuccessful && !resumed) {
                    tempFile.delete()
                    emit(DownloadState.Error(spec.id, "HTTP ${response.code}: ${response.message}"))
                    return@flow
                }
                if (existingBytes > 0L && !resumed) {
                    tempFile.delete()
                    existingBytes = 0L
                }

                val body = response.body ?: run {
                    emit(DownloadState.Error(spec.id, "Empty response"))
                    return@flow
                }
                val responseBytes = body.contentLength()
                val totalForFile = if (resumed && responseBytes > 0) existingBytes + responseBytes else maxOf(fileSpec.expectedBytes, responseBytes)
                var downloadedForFile = existingBytes

                body.byteStream().use { input ->
                    FileOutputStream(tempFile, resumed && existingBytes > 0).use { output ->
                        val buffer = ByteArray(65_536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedForFile += bytesRead
                            emitProgress(
                                spec.id,
                                downloadedAcrossFiles + downloadedForFile,
                                totalBytesExpected,
                                this,
                            )
                        }
                    }
                }

                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                if (destination.length() < fileSpec.minBytes) {
                    emit(DownloadState.Error(spec.id, "${destination.name} のダウンロードが不完全です"))
                    return@flow
                }

                downloadedAcrossFiles += maxOf(destination.length(), totalForFile)
                emitProgress(spec.id, downloadedAcrossFiles, totalBytesExpected, this)
            }

            emit(DownloadState.Completed(spec.id))
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed for ${spec.id}", e)
            emit(DownloadState.Error(spec.id, e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun emitProgress(
        modelId: ModelId,
        downloadedBytes: Long,
        totalBytes: Long,
        collector: kotlinx.coroutines.flow.FlowCollector<DownloadState>,
    ) {
        val safeTotal = totalBytes.coerceAtLeast(1L)
        collector.emit(
            DownloadState.Downloading(
                modelId = modelId,
                progress = downloadedBytes.toFloat() / safeTotal.toFloat(),
                downloadedBytes = downloadedBytes,
                totalBytes = safeTotal,
            ),
        )
    }
}
