package com.kazuph.g4cam.ai

import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

sealed class InferenceState {
    data object Idle : InferenceState()
    data object Loading : InferenceState()
    data class Streaming(val text: String) : InferenceState()
    data class Done(val text: String) : InferenceState()
    data class Error(val message: String) : InferenceState()
}

class GemmaInference {

    private var engine: Engine? = null
    private var isInitialized = false

    suspend fun initialize(modelFile: File) {
        withContext(Dispatchers.IO) {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU,
                visionBackend = Backend.CPU
            )
            engine = Engine(config)
            engine!!.initialize()
            isInitialized = true
        }
    }

    fun analyze(bitmap: Bitmap, prompt: String): Flow<InferenceState> = flow {
        if (!isInitialized || engine == null) {
            emit(InferenceState.Error("Engine not initialized"))
            return@flow
        }

        emit(InferenceState.Loading)

        try {
            val baos = ByteArrayOutputStream()
            val scaledBitmap = scaleBitmap(bitmap, 512)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val imageBytes = baos.toByteArray()

            val conversation = engine!!.createConversation()

            val message = Message.of(
                Content.ImageBytes(imageBytes),
                Content.Text(prompt)
            )

            val response = conversation.sendMessage(message)

            emit(InferenceState.Done(response.toString()))

            conversation.close()
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
        try { engine?.close() } catch (_: Exception) {}
        engine = null
        isInitialized = false
    }
}
