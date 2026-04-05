package com.kazuph.g4cam.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed class InferenceState {
    data object Idle : InferenceState()
    data object Loading : InferenceState()
    data class Streaming(val text: String) : InferenceState()
    data class Done(val text: String) : InferenceState()
    data class Error(val message: String) : InferenceState()
}

sealed class ModelStatus {
    data object Checking : ModelStatus()
    data object Available : ModelStatus()
    data object Downloading : ModelStatus()
    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) : ModelStatus()
    data object Ready : ModelStatus()
    data class Unavailable(val message: String) : ModelStatus()
}

class GemmaInference {

    @Volatile
    private var model: GenerativeModel? = null

    @Volatile
    private var isInitialized = false

    suspend fun initialize(): ModelStatus {
        val generativeModel = Generation.getClient()
        model = generativeModel

        // Check if model is available
        val status = generativeModel.checkStatus()

        // FeatureStatus: 0=UNAVAILABLE, 1=DOWNLOADABLE, 2=DOWNLOADING, 3=AVAILABLE
        return when (status) {
            3 -> {  // AVAILABLE
                isInitialized = true
                ModelStatus.Ready
            }
            1 -> {  // DOWNLOADABLE - need to download
                ModelStatus.Downloading
            }
            2 -> {  // DOWNLOADING - already in progress
                ModelStatus.Downloading
            }
            else -> {
                ModelStatus.Unavailable("Gemini Nano is not available on this device (status: $status)")
            }
        }
    }

    suspend fun downloadModel(): Flow<ModelStatus> = flow {
        val localModel = model ?: run {
            emit(ModelStatus.Unavailable("Model not initialized"))
            return@flow
        }

        localModel.download().collect { downloadStatus ->
            when (downloadStatus) {
                is DownloadStatus.DownloadStarted -> {
                    emit(ModelStatus.DownloadProgress(0, downloadStatus.bytesToDownload))
                }
                is DownloadStatus.DownloadProgress -> {
                    emit(ModelStatus.DownloadProgress(downloadStatus.totalBytesDownloaded, 0))
                }
                is DownloadStatus.DownloadCompleted -> {
                    isInitialized = true
                    emit(ModelStatus.Ready)
                }
                is DownloadStatus.DownloadFailed -> {
                    emit(ModelStatus.Unavailable("Download failed: ${downloadStatus.e.message}"))
                }
            }
        }
    }

    fun analyze(bitmap: Bitmap, prompt: String): Flow<InferenceState> = flow {
        val localModel = model
        if (!isInitialized || localModel == null) {
            emit(InferenceState.Error("Model not initialized"))
            return@flow
        }

        emit(InferenceState.Loading)

        try {
            val scaledBitmap = scaleBitmap(bitmap, 512)

            val request = generateContentRequest(ImagePart(scaledBitmap), TextPart(prompt)) {}

            val response = localModel.generateContent(request)
            val responseText = response.candidates.firstOrNull()?.text ?: ""

            if (scaledBitmap !== bitmap) scaledBitmap.recycle()

            emit(InferenceState.Done(responseText))
        } catch (e: Exception) {
            emit(InferenceState.Error("Inference failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    fun release() {
        isInitialized = false
        model?.close()
        model = null
    }
}
