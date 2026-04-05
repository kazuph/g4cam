package com.kazuph.g4cam.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "GemmaInference"

sealed class InferenceState {
    data object Idle : InferenceState()
    data object Loading : InferenceState()
    data class Streaming(val text: String) : InferenceState()
    data class Done(val text: String) : InferenceState()
    data class Error(val message: String) : InferenceState()
}

enum class InferenceBackend { AICORE, LITERT_LM }

sealed class ModelStatus {
    data object Checking : ModelStatus()
    data object Downloading : ModelStatus()
    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) : ModelStatus()
    data class Ready(val backend: InferenceBackend) : ModelStatus()
    data class NeedsFallbackDownload(val message: String) : ModelStatus()
    data class Unavailable(val message: String) : ModelStatus()
}

class GemmaInference {

    @Volatile private var aicoreModel: GenerativeModel? = null
    @Volatile private var litertEngine: Engine? = null
    @Volatile private var activeBackend: InferenceBackend? = null
    @Volatile private var isInitialized = false

    suspend fun initialize(): ModelStatus {
        // Try AICore PREVIEW (Gemma 4 E2B) first
        try {
            val config = GenerationConfig.Builder().apply {
                modelConfig = ModelConfig.Builder().apply {
                    preference = ModelPreference.FAST
                    releaseStage = ModelReleaseStage.PREVIEW
                }.build()
            }.build()

            val generativeModel = Generation.getClient(config)
            val status = generativeModel.checkStatus()

            // FeatureStatus: 0=UNAVAILABLE, 1=DOWNLOADABLE, 2=DOWNLOADING, 3=AVAILABLE
            if (status == 3) {
                aicoreModel = generativeModel
                activeBackend = InferenceBackend.AICORE
                isInitialized = true
                Log.i(TAG, "AICore PREVIEW (E2B FAST) available")
                return ModelStatus.Ready(InferenceBackend.AICORE)
            }
            Log.i(TAG, "AICore PREVIEW not available (status=$status), will use LiteRT-LM fallback")
            generativeModel.close()
        } catch (e: Exception) {
            Log.w(TAG, "AICore init failed: ${e.message}, falling back to LiteRT-LM")
        }

        // AICore not available → need LiteRT-LM fallback with model download
        return ModelStatus.NeedsFallbackDownload(
            "AICore Preview未対応のため\nLiteRT-LMモードで動作します\n(モデルのDLが必要・約2.6GB)"
        )
    }

    suspend fun initializeLiteRT(modelFile: File): ModelStatus {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting LiteRT-LM engine initialization (this may take 10-30s)...")
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU,
                    visionBackend = Backend.CPU
                )
                val engine = Engine(config)
                engine.initialize()
                litertEngine = engine
                activeBackend = InferenceBackend.LITERT_LM
                isInitialized = true
                Log.i(TAG, "LiteRT-LM engine initialized successfully")
                ModelStatus.Ready(InferenceBackend.LITERT_LM)
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM init failed", e)
                ModelStatus.Unavailable("LiteRT-LM初期化失敗: ${e.message}")
            }
        }
    }

    fun analyze(bitmap: Bitmap, prompt: String): Flow<InferenceState> = flow {
        if (!isInitialized) {
            emit(InferenceState.Error("Model not initialized"))
            return@flow
        }

        emit(InferenceState.Loading)

        try {
            val result = when (activeBackend) {
                InferenceBackend.AICORE -> analyzeWithAICore(bitmap, prompt)
                InferenceBackend.LITERT_LM -> analyzeWithLiteRT(bitmap, prompt)
                null -> throw IllegalStateException("No backend active")
            }
            emit(result)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("policy", ignoreCase = true) ||
                msg.contains("safety", ignoreCase = true) ||
                msg.contains("blocked", ignoreCase = true)) {
                emit(InferenceState.Done("🔒 セーフティフィルターにより応答がブロックされました。別の画像で試してみてください。"))
            } else {
                emit(InferenceState.Error("推論エラー: $msg"))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun analyzeWithAICore(bitmap: Bitmap, prompt: String): InferenceState {
        val localModel = aicoreModel ?: throw IllegalStateException("AICore model is null")
        val scaledBitmap = scaleBitmap(bitmap, 512)

        val request = generateContentRequest(ImagePart(scaledBitmap), TextPart(prompt)) {}
        val response = localModel.generateContent(request)
        val candidate = response.candidates.firstOrNull()

        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        if (candidate == null) {
            return InferenceState.Done("🔒 セーフティフィルターにより応答がブロックされました。別の画像で試してみてください。")
        }

        val responseText = candidate.text ?: ""
        return if (responseText.isEmpty()) {
            InferenceState.Done("🔒 応答が空でした。再度お試しください。")
        } else if (candidate.finishReason == Candidate.FinishReason.MAX_TOKENS) {
            InferenceState.Done("$responseText\n(応答が途中で切れました)")
        } else {
            InferenceState.Done(responseText)
        }
    }

    private fun analyzeWithLiteRT(bitmap: Bitmap, prompt: String): InferenceState {
        val localEngine = litertEngine ?: throw IllegalStateException("LiteRT engine is null")
        val scaledBitmap = scaleBitmap(bitmap, 512)

        val baos = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val imageBytes = baos.toByteArray()
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        localEngine.createConversation().use { conversation ->
            val message = Message.of(
                Content.ImageBytes(imageBytes),
                Content.Text(prompt)
            )
            val response = conversation.sendMessage(message)
            return InferenceState.Done(response.toString())
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    fun release() {
        isInitialized = false
        activeBackend = null
        aicoreModel?.close()
        aicoreModel = null
        try { litertEngine?.close() } catch (_: Exception) {}
        litertEngine = null
    }
}
