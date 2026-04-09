package com.kazuph.g4cam.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.imagedescription.ImageDescriptionResult
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
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
    data object SafetyBlocked : InferenceState()
    data class Error(val message: String) : InferenceState()
}

enum class InferenceBackend { AICORE, AICORE_IMAGE_DESC, LITERT_LM }

sealed class ModelStatus {
    data object Checking : ModelStatus()
    data object Downloading : ModelStatus()
    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) : ModelStatus()
    data class Ready(val backend: InferenceBackend, val hardwareBackend: String = "") : ModelStatus()
    data class NeedsFallbackDownload(val message: String) : ModelStatus()
    data class Unavailable(val message: String) : ModelStatus()
}

fun isPixel10(): Boolean {
    return Build.MODEL?.lowercase()?.contains("pixel 10") == true
}

class GemmaInference {

    @Volatile private var aicoreModel: GenerativeModel? = null
    @Volatile private var imageDescriber: ImageDescriber? = null
    @Volatile private var litertEngine: Engine? = null
    @Volatile private var activeBackend: InferenceBackend? = null
    @Volatile var activeHardwareBackend: String = ""
        private set
    @Volatile private var isInitialized = false

    suspend fun initializeImageDescription(context: Context) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val options = ImageDescriberOptions.builder(context).build()
            val describer = ImageDescription.getClient(options)
            val statusFuture = describer.checkFeatureStatus()
            val status = kotlinx.coroutines.suspendCancellableCoroutine<Int> { cont ->
                statusFuture.addListener({ cont.resume(statusFuture.get()) {} }, java.util.concurrent.Executors.newSingleThreadExecutor())
            }
            Log.i(TAG, "Image Description status: $status")
            if (status == 3) {
                imageDescriber = describer
                activeBackend = InferenceBackend.AICORE_IMAGE_DESC
                isInitialized = true
                Log.i(TAG, "Image Description API available")
            } else if (status == 1 || status == 2) {
                Log.i(TAG, "Image Description model downloading...")
                val callback = object : com.google.mlkit.genai.common.DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) { Log.i(TAG, "ImgDesc DL started: $bytesToDownload bytes") }
                    override fun onDownloadProgress(totalBytesDownloaded: Long) { Log.i(TAG, "ImgDesc DL progress: $totalBytesDownloaded") }
                    override fun onDownloadCompleted() { Log.i(TAG, "ImgDesc DL completed") }
                    override fun onDownloadFailed(e: com.google.mlkit.genai.common.GenAiException) { Log.e(TAG, "ImgDesc DL failed", e) }
                }
                val dlFuture = describer.downloadFeature(callback)
                kotlinx.coroutines.suspendCancellableCoroutine<Void?> { cont ->
                    dlFuture.addListener({ cont.resume(null) {} }, java.util.concurrent.Executors.newSingleThreadExecutor())
                }
                imageDescriber = describer
                activeBackend = InferenceBackend.AICORE_IMAGE_DESC
                isInitialized = true
                Log.i(TAG, "Image Description API ready after download")
            } else {
                throw RuntimeException("Image Description not available (status=$status)")
            }
        }
    }

    fun setAICoreModel(model: GenerativeModel) {
        aicoreModel = model
        activeBackend = InferenceBackend.AICORE
        isInitialized = true
    }

    suspend fun initializeLiteRTWithBackend(modelFile: File, backend: Backend): ModelStatus {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val fileSize = modelFile.length()
            Log.i(TAG, "Model file size: $fileSize bytes (${fileSize / 1_000_000}MB)")
            if (fileSize < 2_500_000_000L) {
                Log.e(TAG, "Model file incomplete ($fileSize bytes)")
                modelFile.delete()
                return@withContext ModelStatus.NeedsFallbackDownload(
                    "モデルファイルが不完全です\n再ダウンロードが必要です"
                )
            }

            val backendName = when (backend) {
                is Backend.GPU -> "GPU"
                is Backend.NPU -> "NPU"
                is Backend.CPU -> "CPU"
                else -> backend.javaClass.simpleName
            }
            try {
                Log.i(TAG, "Trying $backendName backend...")
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    visionBackend = backend,
                )
                val engine = Engine(config)
                engine.initialize()
                litertEngine = engine
                activeBackend = InferenceBackend.LITERT_LM
                activeHardwareBackend = backendName
                isInitialized = true
                Log.i(TAG, "LiteRT-LM $backendName engine initialized successfully (device=${Build.MODEL})")
                ModelStatus.Ready(InferenceBackend.LITERT_LM, hardwareBackend = backendName)
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM ${backend.javaClass.simpleName} failed: ${e.message}")
                ModelStatus.Unavailable("${backend.javaClass.simpleName}初期化失敗: ${e.message}")
            }
        }
    }

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
            // Verify file integrity
            val fileSize = modelFile.length()
            Log.i(TAG, "Model file size: $fileSize bytes (${fileSize / 1_000_000}MB)")
            if (fileSize < 2_500_000_000L) {
                Log.e(TAG, "Model file appears incomplete ($fileSize bytes). Expected ~2.58GB")
                modelFile.delete()
                return@withContext ModelStatus.NeedsFallbackDownload(
                    "モデルファイルが不完全です（${fileSize / 1_000_000}MB）\n再ダウンロードが必要です"
                )
            }

            // Try GPU first (676MB RAM) - much lighter than CPU (1733MB)
            try {
                Log.i(TAG, "Trying GPU backend (uses ~676MB RAM)...")
                val gpuConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                )
                val engine = Engine(gpuConfig)
                engine.initialize()
                litertEngine = engine
                activeBackend = InferenceBackend.LITERT_LM
                isInitialized = true
                Log.i(TAG, "LiteRT-LM GPU engine initialized successfully")
                return@withContext ModelStatus.Ready(InferenceBackend.LITERT_LM)
            } catch (e: Exception) {
                Log.w(TAG, "GPU backend failed: ${e.message}, trying CPU fallback...")
            }

            // CPU fallback (1733MB RAM - heavier but more compatible)
            try {
                Log.i(TAG, "Trying CPU backend (uses ~1733MB RAM)...")
                val cpuConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                )
                val engine = Engine(cpuConfig)
                engine.initialize()
                litertEngine = engine
                activeBackend = InferenceBackend.LITERT_LM
                isInitialized = true
                Log.i(TAG, "LiteRT-LM CPU engine initialized successfully")
                return@withContext ModelStatus.Ready(InferenceBackend.LITERT_LM)
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM CPU fallback also failed", e)
                return@withContext ModelStatus.Unavailable("LiteRT-LM初期化失敗: ${e.message}")
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
                InferenceBackend.AICORE_IMAGE_DESC -> analyzeWithImageDescription(bitmap)
                InferenceBackend.LITERT_LM -> analyzeWithLiteRT(bitmap, prompt)
                null -> throw IllegalStateException("No backend active")
            }
            emit(result)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("policy", ignoreCase = true) ||
                msg.contains("safety", ignoreCase = true) ||
                msg.contains("blocked", ignoreCase = true)) {
                emit(InferenceState.SafetyBlocked)
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
            return InferenceState.SafetyBlocked
        }

        val responseText = candidate.text ?: ""
        return if (responseText.isEmpty()) {
            InferenceState.SafetyBlocked
        } else if (candidate.finishReason == Candidate.FinishReason.MAX_TOKENS) {
            InferenceState.Done("$responseText\n(応答が途中で切れました)")
        } else {
            InferenceState.Done(responseText)
        }
    }

    private suspend fun analyzeWithImageDescription(bitmap: Bitmap): InferenceState {
        val describer = imageDescriber ?: throw IllegalStateException("ImageDescriber is null")
        val scaledBitmap = scaleBitmap(bitmap, 512)
        val request = ImageDescriptionRequest.builder(scaledBitmap).build()

        val resultFuture = describer.runInference(request)
        val result = kotlinx.coroutines.suspendCancellableCoroutine<ImageDescriptionResult> { cont ->
            resultFuture.addListener({
                try { cont.resume(resultFuture.get()) {} }
                catch (e: Exception) { cont.resumeWith(Result.failure(e)) }
            }, java.util.concurrent.Executors.newSingleThreadExecutor())
        }

        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        val text = result.description ?: ""
        return if (text.isEmpty()) {
            InferenceState.SafetyBlocked
        } else {
            InferenceState.Done(text)
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
            val contents = Contents.of(
                Content.ImageBytes(imageBytes),
                Content.Text(prompt)
            )
            val response = conversation.sendMessage(contents)
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

    fun hasLiteRTFallback(): Boolean = litertEngine != null

    fun analyzeWithFallback(bitmap: Bitmap, prompt: String): Flow<InferenceState> = flow {
        if (litertEngine == null) {
            emit(InferenceState.Error("LiteRT-LMフォールバックが利用できません"))
            return@flow
        }
        emit(InferenceState.Loading)
        try {
            val result = analyzeWithLiteRT(bitmap, prompt)
            emit(result)
        } catch (e: Exception) {
            emit(InferenceState.Error("フォールバック推論エラー: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    fun release() {
        isInitialized = false
        activeBackend = null
        aicoreModel?.close()
        aicoreModel = null
        imageDescriber?.close()
        imageDescriber = null
        try { litertEngine?.close() } catch (_: Exception) {}
        litertEngine = null
    }
}
