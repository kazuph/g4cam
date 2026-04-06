package com.kazuph.g4cam.ui

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazuph.g4cam.ai.GemmaInference
import com.kazuph.g4cam.ai.InferenceBackend
import com.kazuph.g4cam.ai.InferenceState
import com.kazuph.g4cam.ai.ModelStatus
import com.kazuph.g4cam.model.DownloadState
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
private const val PROMPT = "絵文字1つ＋この画像の説明を1文で。文字が見えたら必ず読む。日本語で短く。"
private const val AUTO_INTERVAL_SECONDS = 10

data class HistoryItem(
    val thumbnail: android.graphics.Bitmap,
    val text: String,
    val durationMs: Long,
)

data class CameraUiState(
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
    val lastDurationMs: Long = 0,
    val showHistory: Boolean = false,
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
    private var analysisStartTime = 0L

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
                _uiState.value = _uiState.value.copy(
                    isEngineReady = true,
                    activeBackend = status.backend,
                    statusText = when (status.backend) {
                        InferenceBackend.AICORE -> "準備完了 (AICore E2B) - タップで解析"
                        InferenceBackend.LITERT_LM -> "準備完了 (LiteRT-LM) - タップで解析"
                    }
                )
                scheduleHideStatus()
            }
            is ModelStatus.NeedsFallbackDownload -> {
                if (downloader.isModelDownloaded()) {
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                needsModelDownload = false,
                statusText = "モデルをダウンロード中..."
            )
            try {
                downloader.download().collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> {
                            val mb = state.downloadedBytes / 1_000_000
                            val totalMb = if (state.totalBytes > 0) state.totalBytes / 1_000_000 else 0L
                            _uiState.value = _uiState.value.copy(
                                statusText = if (totalMb > 0) "DL中... ${mb}MB / ${totalMb}MB" else "DL中... ${mb}MB"
                            )
                        }
                        is DownloadState.Completed -> {
                            _uiState.value = _uiState.value.copy(
                                isDownloading = false,
                                needsLiteRTInit = true,
                                statusText = "DL完了！初期化ボタンを押してください"
                            )
                        }
                        is DownloadState.Error -> {
                            _uiState.value = _uiState.value.copy(
                                statusText = "DLエラー: ${state.message}",
                                isDownloading = false,
                                needsModelDownload = true
                            )
                        }
                        is DownloadState.Idle -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _uiState.value = _uiState.value.copy(
                    statusText = "DLエラー: ${e.message}",
                    isDownloading = false,
                    needsModelDownload = true
                )
            }
        }
    }

    fun switchToLiteRTFallback() {
        _uiState.value = _uiState.value.copy(
            modelUnavailable = false
        )
        if (downloader.isModelDownloaded()) {
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
                statusText = "LiteRT-LMエンジンを初期化中...\n(初回は30〜60秒かかります)",
                showStatus = true
            )
            try {
                val modelFile = downloader.getModelFile()
                Log.i(TAG, "Model file: ${modelFile.absolutePath}, exists=${modelFile.exists()}, size=${modelFile.length()}")

                val result = withTimeout(180_000) {
                    inference.initializeLiteRT(modelFile)
                }
                Log.i(TAG, "initializeLiteRT result: $result")
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
                    else -> handleModelStatus(result)
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

        // Create thumbnail for history (64px, low quality)
        val thumbScale = 64f / maxOf(bitmap.width, bitmap.height)
        val thumbnail = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * thumbScale).toInt(),
            (bitmap.height * thumbScale).toInt(),
            true
        )

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
            inference.analyze(bitmap, promptWithHistory).collect { inferenceState ->
                when (inferenceState) {
                    is InferenceState.Loading -> {}
                    is InferenceState.Streaming -> {
                        _uiState.value = _uiState.value.copy(resultText = inferenceState.text)
                    }
                    is InferenceState.Done -> {
                        val durationMs = System.currentTimeMillis() - analysisStartTime
                        val backendLabel = when (_uiState.value.activeBackend) {
                            InferenceBackend.AICORE -> "AICore"
                            InferenceBackend.LITERT_LM -> "LiteRT"
                            null -> ""
                        }
                        _uiState.value = _uiState.value.copy(
                            resultText = inferenceState.text,
                            showGlow = false,
                            isAnalyzing = false,
                            lastDurationMs = durationMs,
                            statusText = "${durationMs / 1000}.${(durationMs % 1000) / 100}秒 ($backendLabel)"
                        )
                        // Save to history
                        _historyItems.value = _historyItems.value + HistoryItem(
                            thumbnail = thumbnail,
                            text = inferenceState.text,
                            durationMs = durationMs
                        )
                        promptHistory.add(inferenceState.text)
                        if (promptHistory.size > 3) promptHistory.removeAt(0)
                        speak(inferenceState.text)
                        bitmap.recycle()
                        if (_uiState.value.autoMode) startCountdown() else scheduleHideStatus()
                    }
                    is InferenceState.Error -> {
                        Log.e(TAG, "Inference error: ${inferenceState.message}")
                        _uiState.value = _uiState.value.copy(
                            resultText = "エラー: ${inferenceState.message}",
                            showGlow = false,
                            isAnalyzing = false,
                            statusText = "エラー"
                        )
                        bitmap.recycle()
                    }
                    is InferenceState.Idle -> {}
                }
            }
        }
    }

    fun onCaptureError(error: String) {
        _uiState.value = _uiState.value.copy(
            resultText = "キャプチャエラー: $error",
            showGlow = false,
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

    private fun buildPrompt(): String {
        var prompt = PROMPT
        if (promptHistory.isNotEmpty()) {
            val historyText = promptHistory.mapIndexed { i, h -> "${i + 1}回前: $h" }.joinToString("\n")
            prompt += "\n\n過去の観察:\n$historyText\n\n上記と違う点や変化に注目して。同じことは言わないで。"
        }
        return prompt
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

    override fun onCleared() {
        super.onCleared()
        autoJob?.cancel()
        statusHideJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        inference.release()
    }
}
