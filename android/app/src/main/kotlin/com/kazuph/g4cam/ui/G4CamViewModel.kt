package com.kazuph.g4cam.ui

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.kazuph.g4cam.ai.GemmaInference
import com.kazuph.g4cam.ai.InferenceBackend
import com.kazuph.g4cam.ai.InferenceService
import com.kazuph.g4cam.ai.InferenceState
import com.kazuph.g4cam.ai.ModelStatus
import com.kazuph.g4cam.ai.OnnxExecutionProvider
import com.kazuph.g4cam.ai.isPixel10
import com.kazuph.g4cam.model.DownloadService
import com.kazuph.g4cam.model.DownloadState
import com.kazuph.g4cam.model.LocalModelSpec
import com.kazuph.g4cam.model.LocalModelSpecs
import com.kazuph.g4cam.model.ModelId
import com.kazuph.g4cam.model.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

private const val TAG = "G4Cam"
private const val PROMPT = "この画像に何が写っていますか？日本語で1文で簡潔に説明してください。文字が見えたら読み上げてください。"
private const val AUTO_INTERVAL_SECONDS = 10

data class HistoryItem(
    val thumbnail: android.graphics.Bitmap,
    val analyzedImage: android.graphics.Bitmap,  // 256px - same as sent to model
    val text: String,
    val durationMs: Long,
)

enum class BackendChoice(val label: String) {
    AICORE_FAST("AICore E2B"),
    AICORE_FULL("AICore E4B"),
    LITERT_GPU("LiteRT-LM (GPU)"),
    LITERT_CPU("LiteRT-LM (CPU)"),
    LITERT_NPU("LiteRT-LM (NPU)"),
    LIQUID_ONNX_NNAPI("Liquid ONNX NNAPI"),
    LIQUID_ONNX_XNNPACK("Liquid ONNX XNNPACK"),
    LIQUID_ONNX_CPU("Liquid ONNX CPU"),
}

data class CameraUiState(
    val showBackendSelector: Boolean = true,
    val isAnalyzing: Boolean = false,
    val isEngineReady: Boolean = false,
    val resultText: String = "",
    val statusText: String = "準備中...",
    val showStatus: Boolean = true,
    val showFlash: Boolean = false,
    val showGlow: Boolean = false,
    val autoMode: Boolean = false,
    val countdown: Int = 0,
    val modelUnavailable: Boolean = false,
    val needsModelDownload: Boolean = false,
    val needsLiteRTInit: Boolean = false,
    val isLiteRTInitializing: Boolean = false,
    val isDownloading: Boolean = false,
    val activeBackend: InferenceBackend? = null,
    val backendDisplayName: String = "",
    val lastDurationMs: Long = 0,
    val detailedPrompt: Boolean = false,
    val showHistory: Boolean = false,
    val modelTitle: String = "",
    val modelDescription: String = "",
)

class G4CamViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val inference = GemmaInference()
    private val downloader = ModelDownloader(application)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var autoJob: Job? = null
    private var statusHideJob: Job? = null
    private var downloadObserverJob: Job? = null
    private var analysisStartTime = 0L
    private var selectedModelSpec: LocalModelSpec = LocalModelSpecs.gemmaLiteRt
    private var selectedOnnxProvider: OnnxExecutionProvider = OnnxExecutionProvider.CPU

    private val promptHistory = mutableListOf<String>()
    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    init {
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.JAPANESE
                tts?.setSpeechRate(1.2f)
                ttsReady = true
            }
        }
    }

    fun selectBackend(choice: BackendChoice) {
        _uiState.value = _uiState.value.copy(showBackendSelector = false)
        when (choice) {
            BackendChoice.AICORE_FAST -> initializeAICore(ModelPreference.FAST)
            BackendChoice.AICORE_FULL -> initializeAICore(ModelPreference.FULL)
            BackendChoice.LITERT_GPU -> initializeLiteRTWithBackend(Backend.GPU())
            BackendChoice.LITERT_CPU -> initializeLiteRTWithBackend(Backend.CPU())
            BackendChoice.LITERT_NPU -> initializeLiteRTWithBackend(
                Backend.NPU(nativeLibraryDir = getApplication<Application>().applicationInfo.nativeLibraryDir)
            )
            BackendChoice.LIQUID_ONNX_NNAPI -> initializeLiquidOnnx(OnnxExecutionProvider.NNAPI)
            BackendChoice.LIQUID_ONNX_XNNPACK -> initializeLiquidOnnx(OnnxExecutionProvider.XNNPACK)
            BackendChoice.LIQUID_ONNX_CPU -> initializeLiquidOnnx(OnnxExecutionProvider.CPU)
        }
    }

    private fun initializeAICore(preference: Int) {
        val modelName = if (preference == ModelPreference.FAST) "E2B" else "E4B"
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusText = "AICore $modelName 初期化中...")
            try {
                val config = GenerationConfig.Builder().apply {
                    modelConfig = ModelConfig.Builder().apply {
                        this.preference = preference
                        releaseStage = ModelReleaseStage.PREVIEW
                    }.build()
                }.build()
                val generativeModel = Generation.getClient(config)
                val status = withTimeout(30_000) { generativeModel.checkStatus() }
                if (status == 3) {
                    inference.setAICoreModel(generativeModel)
                    _uiState.value = _uiState.value.copy(
                        isEngineReady = true,
                        activeBackend = InferenceBackend.AICORE,
                        backendDisplayName = "AICore $modelName",
                        statusText = "準備完了 (AICore $modelName)"
                    )
                } else {
                    generativeModel.close()
                    _uiState.value = _uiState.value.copy(
                        statusText = "AICore利用不可 (status=$status)",
                        modelUnavailable = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AICore init failed", e)
                _uiState.value = _uiState.value.copy(
                    statusText = "AICore初期化失敗: ${e.message}",
                    modelUnavailable = true
                )
            }
        }
    }

    private fun initializeLiteRTWithBackend(backend: Backend) {
        selectedModelSpec = LocalModelSpecs.gemmaLiteRt
        val name = when (backend) {
            is Backend.GPU -> "LiteRT GPU"
            is Backend.NPU -> "LiteRT NPU"
            is Backend.CPU -> "LiteRT CPU"
            else -> "LiteRT"
        }
        _uiState.value = _uiState.value.copy(
            backendDisplayName = name,
            modelTitle = selectedModelSpec.title,
            modelDescription = selectedModelSpec.downloadDescription
        )
        if (downloader.isModelDownloaded(selectedModelSpec)) {
            _uiState.value = _uiState.value.copy(needsLiteRTInit = true)
            selectedLiteRTBackend = backend
        } else {
            _uiState.value = _uiState.value.copy(needsModelDownload = true)
            selectedLiteRTBackend = backend
        }
    }

    private fun initializeLiquidOnnx(provider: OnnxExecutionProvider) {
        selectedModelSpec = LocalModelSpecs.liquidOnnx
        selectedOnnxProvider = provider
        _uiState.value = _uiState.value.copy(
            backendDisplayName = "Liquid ONNX ${provider.displayName}",
            modelTitle = selectedModelSpec.title,
            modelDescription = selectedModelSpec.downloadDescription
        )
        if (downloader.isModelDownloaded(selectedModelSpec)) {
            _uiState.value = _uiState.value.copy(needsLiteRTInit = true)
        } else {
            _uiState.value = _uiState.value.copy(needsModelDownload = true)
        }
    }

    private var selectedLiteRTBackend: Backend = Backend.GPU()

    fun initializeEngine() {
        // Skip if already initialized or in progress
        if (_uiState.value.isEngineReady || _uiState.value.isLiteRTInitializing ||
            _uiState.value.needsLiteRTInit || _uiState.value.needsModelDownload ||
            _uiState.value.isDownloading || _uiState.value.modelUnavailable) {
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusText = "AIモデルを確認中...")
            try {
                val modelStatus = withTimeout(30_000) {
                    inference.initialize()
                }
                handleModelStatus(modelStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed", e)
                _uiState.value = _uiState.value.copy(
                    statusText = "初期化エラー: ${e.message}",
                    modelUnavailable = true
                )
            }
        }
    }

    private fun handleModelStatus(status: ModelStatus) {
        when (status) {
            is ModelStatus.Ready -> {
                val displayName = when {
                    status.backend == InferenceBackend.LIQUID_ONNX && status.hardwareBackend.isNotEmpty() -> "Liquid ONNX ${status.hardwareBackend}"
                    status.backend == InferenceBackend.LIQUID_ONNX -> "Liquid ONNX"
                    status.hardwareBackend.isNotEmpty() -> "LiteRT ${status.hardwareBackend}"
                    status.backend == InferenceBackend.AICORE -> _uiState.value.backendDisplayName.ifEmpty { "AICore" }
                    status.backend == InferenceBackend.AICORE_IMAGE_DESC -> "AICore"
                    else -> "LiteRT"
                }
                _uiState.value = _uiState.value.copy(
                    isEngineReady = true,
                    activeBackend = status.backend,
                    backendDisplayName = displayName,
                    statusText = "準備完了 ($displayName) - タップで解析"
                )
                scheduleHideStatus()
            }
            is ModelStatus.NeedsFallbackDownload -> {
                if (downloader.isModelDownloaded(selectedModelSpec)) {
                    // Model downloaded but not initialized - show button to start
                    _uiState.value = _uiState.value.copy(
                        needsModelDownload = false,
                        needsLiteRTInit = true,
                        statusText = "モデルDL済み - 初期化ボタンを押してください"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        needsModelDownload = true,
                        statusText = status.message
                    )
                }
            }
            is ModelStatus.Unavailable -> {
                _uiState.value = _uiState.value.copy(
                    statusText = status.message,
                    modelUnavailable = true
                )
            }
            else -> {}
        }
    }

    fun startModelDownload() {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            needsModelDownload = false,
            statusText = "モデルをダウンロード中..."
        )

        // Start ForegroundService for background download
        DownloadService.start(app, selectedModelSpec.id)

        // Observe download state from service
        downloadObserverJob?.cancel()
        downloadObserverJob = viewModelScope.launch {
            DownloadService.downloadState.collect { state ->
                when (state) {
                    is DownloadState.Downloading -> {
                        if (state.modelId != selectedModelSpec.id) return@collect
                        val mb = state.downloadedBytes / 1_000_000
                        val totalMb = if (state.totalBytes > 0) state.totalBytes / 1_000_000 else 0L
                        _uiState.value = _uiState.value.copy(
                            statusText = if (totalMb > 0) "DL中... ${mb}MB / ${totalMb}MB" else "DL中... ${mb}MB"
                        )
                    }
                    is DownloadState.Completed -> {
                        if (state.modelId != selectedModelSpec.id) return@collect
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            needsLiteRTInit = true,
                            statusText = "DL完了！初期化ボタンを押してください"
                        )
                    }
                    is DownloadState.Error -> {
                        if (state.modelId != selectedModelSpec.id) return@collect
                        _uiState.value = _uiState.value.copy(
                            statusText = "DLエラー: ${state.message}",
                            isDownloading = false,
                            needsModelDownload = true
                        )
                    }
                    is DownloadState.Idle -> {}
                }
            }
        }
    }

    fun switchToLiteRTFallback() {
        _uiState.value = _uiState.value.copy(
            modelUnavailable = false
        )
        if (downloader.isModelDownloaded(selectedModelSpec)) {
            _uiState.value = _uiState.value.copy(needsLiteRTInit = true)
        } else {
            _uiState.value = _uiState.value.copy(needsModelDownload = true)
        }
    }

    fun startLiteRTInit() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                needsLiteRTInit = false,
                needsModelDownload = false,
                isLiteRTInitializing = true,
                statusText = selectedModelSpec.initDescription,
                modelDescription = selectedModelSpec.initDescription,
                showStatus = true
            )
            try {
                val result = withTimeout(180_000) {
                    when (selectedModelSpec.id) {
                        ModelId.GEMMA_LITERT_E2B -> {
                            val modelFile = downloader.getPrimaryFile(selectedModelSpec)
                            Log.i(TAG, "LiteRT model: ${modelFile.absolutePath}, exists=${modelFile.exists()}, size=${modelFile.length()}")
                            inference.initializeLiteRTWithBackend(modelFile, selectedLiteRTBackend)
                        }
                        ModelId.LIQUID_LFM2_5_VL_450M_ONNX_Q4 -> {
                            val modelRoot = downloader.getModelRoot(selectedModelSpec)
                            Log.i(TAG, "Liquid ONNX root: ${modelRoot.absolutePath}")
                            inference.initializeLiquidOnnx(modelRoot, selectedOnnxProvider)
                        }
                    }
                }
                Log.i(TAG, "initialize result: $result, actualHW=${inference.activeHardwareBackend}")
                _uiState.value = _uiState.value.copy(isLiteRTInitializing = false)
                when (result) {
                    is ModelStatus.NeedsFallbackDownload -> {
                        _uiState.value = _uiState.value.copy(
                            needsModelDownload = true,
                            statusText = result.message
                        )
                        return@launch
                    }
                    is ModelStatus.Unavailable -> {
                        _uiState.value = _uiState.value.copy(
                            statusText = result.message,
                            modelUnavailable = true
                        )
                        return@launch
                    }
                    else -> {
                        handleModelStatus(result)
                        // Pixel 10 + GPU: warn about degraded performance
                        if (selectedModelSpec.id == ModelId.GEMMA_LITERT_E2B &&
                            isPixel10() &&
                            selectedLiteRTBackend is Backend.GPU
                        ) {
                            Log.w(TAG, "Pixel 10 + GPU: GPU sampler unavailable, performance degraded")
                            _uiState.value = _uiState.value.copy(
                                statusText = "準備完了 (LiteRT GPU) - Pixel 10: GPU低速・NPU推奨"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT init timeout/error", e)
                _uiState.value = _uiState.value.copy(
                    isLiteRTInitializing = false,
                    statusText = "初期化エラー: ${e.message}",
                    modelUnavailable = true
                )
            }
        }
    }

    fun onCapturedFrame(bitmap: Bitmap) {
        val state = _uiState.value
        if (state.isAnalyzing || !state.isEngineReady) return

        analysisStartTime = System.currentTimeMillis()
        cancelHideStatus()

        // Start foreground service to protect inference from process kill
        InferenceService.start(getApplication())

        // Create images for history
        val thumbnail = scaleBitmap(bitmap, 64)
        val analyzedImage = scaleBitmap(bitmap, 512)
        if (analyzedImage !== bitmap) {
            bitmap.recycle()
        }

        _uiState.value = state.copy(
            isAnalyzing = true,
            showGlow = true,
            showStatus = true,
            statusText = "解析中..."
        )

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(showFlash = true)
            delay(150)
            _uiState.value = _uiState.value.copy(showFlash = false)
        }

        val promptWithHistory = buildPrompt()

        viewModelScope.launch {
            inference.analyze(analyzedImage, promptWithHistory).collect { inferenceState ->
                when (inferenceState) {
                    is InferenceState.Loading -> {}
                    is InferenceState.Streaming -> {
                        _uiState.value = _uiState.value.copy(resultText = inferenceState.text)
                    }
                    is InferenceState.Done -> {
                        val durationMs = System.currentTimeMillis() - analysisStartTime
                        val backendLabel = _uiState.value.backendDisplayName.ifEmpty {
                            when (_uiState.value.activeBackend) {
                                InferenceBackend.AICORE -> "AICore"
                                InferenceBackend.AICORE_IMAGE_DESC -> "AICore"
                                InferenceBackend.LITERT_LM -> "LiteRT"
                                InferenceBackend.LIQUID_ONNX -> "Liquid ONNX"
                                null -> ""
                            }
                        }
                        Log.i(TAG, "INFERENCE_DONE: ${durationMs}ms backend=$backendLabel text=${inferenceState.text.take(50)}")
                        _uiState.value = _uiState.value.copy(
                            resultText = inferenceState.text,
                            showGlow = false,
                            showStatus = true,
                            isAnalyzing = false,
                            lastDurationMs = durationMs,
                            statusText = "${durationMs / 1000}.${(durationMs % 1000) / 100}秒 ($backendLabel)"
                        )
                        // Save to history
                        _historyItems.value = _historyItems.value + HistoryItem(
                            thumbnail = thumbnail,
                            analyzedImage = analyzedImage,
                            text = inferenceState.text,
                            durationMs = durationMs
                        )
                        onInferenceFinished(inferenceState.text)
                        speak(inferenceState.text)
                        // Keep status visible (show duration) until next analysis
                        if (_uiState.value.autoMode) startCountdown()
                    }
                    is InferenceState.SafetyBlocked -> {
                        Log.w(TAG, "AICore safety blocked, trying LiteRT-LM fallback...")
                        if (inference.hasLiteRTFallback()) {
                            _uiState.value = _uiState.value.copy(
                                statusText = "セーフティブロック → LiteRT-LMで再試行中..."
                            )
                            // Re-analyze with LiteRT-LM
                            inference.analyzeWithFallback(analyzedImage, promptWithHistory).collect { fallbackState ->
                                when (fallbackState) {
                                    is InferenceState.Done -> {
                                        val fbDuration = System.currentTimeMillis() - analysisStartTime
                                        _uiState.value = _uiState.value.copy(
                                            resultText = fallbackState.text,
                                            showGlow = false,
                                            showStatus = true,
                                            isAnalyzing = false,
                                            lastDurationMs = fbDuration,
                                            statusText = "${fbDuration / 1000}.${(fbDuration % 1000) / 100}秒 (LiteRT fallback)"
                                        )
                                        _historyItems.value = _historyItems.value + HistoryItem(
                                            thumbnail = thumbnail,
                                            analyzedImage = analyzedImage,
                                            text = fallbackState.text,
                                            durationMs = fbDuration
                                        )
                                        onInferenceFinished(fallbackState.text)
                                        speak(fallbackState.text)
                                        if (_uiState.value.autoMode) startCountdown()
                                    }
                                    is InferenceState.Error -> {
                                        _uiState.value = _uiState.value.copy(
                                            resultText = "フォールバックも失敗: ${fallbackState.message}",
                                            showGlow = false,
                                            showStatus = true,
                                            isAnalyzing = false,
                                            statusText = "エラー"
                                        )
                                        onInferenceFinished("エラー: ${fallbackState.message}")
                                        recycleCapturedImages(thumbnail, analyzedImage)
                                    }
                                    else -> {}
                                }
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(
                                resultText = "🔒 セーフティフィルターによりブロックされました",
                                showGlow = false,
                                showStatus = true,
                                isAnalyzing = false,
                                statusText = "ブロック (フォールバックなし)"
                            )
                            onInferenceFinished("セーフティブロック")
                            recycleCapturedImages(thumbnail, analyzedImage)
                        }
                    }
                    is InferenceState.Error -> {
                        Log.e(TAG, "Inference error: ${inferenceState.message}")
                        _uiState.value = _uiState.value.copy(
                            resultText = "エラー: ${inferenceState.message}",
                            showGlow = false,
                            showStatus = true,
                            isAnalyzing = false,
                            statusText = "エラー"
                        )
                        onInferenceFinished("エラー: ${inferenceState.message}")
                        recycleCapturedImages(thumbnail, analyzedImage)
                    }
                    is InferenceState.Idle -> {}
                }
            }
        }
    }

    fun onCaptureError(error: String) {
        cancelHideStatus()
        _uiState.value = _uiState.value.copy(
            resultText = "キャプチャエラー: $error",
            showGlow = false,
            showStatus = true,
            isAnalyzing = false
        )
    }

    fun toggleAutoMode() {
        val newAutoMode = !_uiState.value.autoMode
        _uiState.value = _uiState.value.copy(
            autoMode = newAutoMode,
            showStatus = true,
            statusText = if (newAutoMode) "オート解析中" else "準備完了",
            countdown = 0
        )
        if (newAutoMode) {
            requestAnalysis = true
        } else {
            autoJob?.cancel()
            autoJob = null
            tts?.stop()
            scheduleHideStatus()
        }
    }

    var requestAnalysis = false
        private set

    fun consumeAnalysisRequest() {
        requestAnalysis = false
    }

    private fun startCountdown() {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            for (i in AUTO_INTERVAL_SECONDS downTo 1) {
                _uiState.value = _uiState.value.copy(countdown = i)
                delay(1000)
            }
            _uiState.value = _uiState.value.copy(countdown = 0)
            requestAnalysis = true
        }
    }

    fun toggleHistory() {
        _uiState.value = _uiState.value.copy(showHistory = !_uiState.value.showHistory)
    }

    fun deleteHistoryItem(item: HistoryItem) {
        recycleCapturedImages(item.thumbnail, item.analyzedImage)
        _historyItems.value = _historyItems.value - item
    }

    fun switchBackend() {
        // Reset and show selector
        inference.release()
        downloadObserverJob?.cancel()
        _uiState.value = CameraUiState(showBackendSelector = true)
    }

    fun togglePromptMode() {
        _uiState.value = _uiState.value.copy(detailedPrompt = !_uiState.value.detailedPrompt)
    }

    private fun buildPrompt(): String {
        return if (_uiState.value.detailedPrompt) {
            "この画像を詳しく説明してください。何が写っているか、色、形、配置、文字があれば読み上げ、全体の雰囲気も含めて日本語で3〜5文で説明してください。"
        } else {
            PROMPT
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun recycleCapturedImages(thumbnail: Bitmap, analyzedImage: Bitmap) {
        if (!thumbnail.isRecycled && thumbnail !== analyzedImage) {
            thumbnail.recycle()
        }
        if (!analyzedImage.isRecycled) {
            analyzedImage.recycle()
        }
    }

    private fun onInferenceFinished(resultText: String) {
        val app = getApplication<Application>()
        InferenceService.stop(app)
        InferenceService.notifyComplete(app, resultText)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.stop()
        // Remove emoji for natural TTS
        val cleanText = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (cleanText.isNotEmpty()) {
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "g4cam_utterance")
        }
    }

    private fun scheduleHideStatus() {
        statusHideJob?.cancel()
        statusHideJob = viewModelScope.launch {
            delay(3000)
            _uiState.value = _uiState.value.copy(showStatus = false)
        }
    }

    private fun cancelHideStatus() {
        statusHideJob?.cancel()
        statusHideJob = null
    }

    override fun onCleared() {
        super.onCleared()
        _historyItems.value.forEach { recycleCapturedImages(it.thumbnail, it.analyzedImage) }
        _historyItems.value = emptyList()
        autoJob?.cancel()
        statusHideJob?.cancel()
        downloadObserverJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        inference.release()
    }
}
