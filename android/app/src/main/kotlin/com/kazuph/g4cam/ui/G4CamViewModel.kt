package com.kazuph.g4cam.ui

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazuph.g4cam.ai.GemmaInference
import com.kazuph.g4cam.ai.InferenceState
import com.kazuph.g4cam.model.DownloadState
import com.kazuph.g4cam.model.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "G4Cam"
private const val PROMPT = "絵文字1つ＋この画像の説明を1文で。文字が見えたら必ず読む。日本語で短く。"
private const val AUTO_INTERVAL_SECONDS = 10

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
)

class G4CamViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    val downloader = ModelDownloader(application)
    val inference = GemmaInference()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var autoJob: Job? = null
    private var statusHideJob: Job? = null

    // Past responses for variety (like web version)
    private val history = mutableListOf<String>()

    init {
        // Check if model already downloaded
        if (downloader.isModelDownloaded()) {
            _downloadState.value = DownloadState.Completed
        }

        // Initialize TTS
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.JAPANESE
                tts?.setSpeechRate(1.2f)
                ttsReady = true
            }
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            downloader.download().collect { state ->
                _downloadState.value = state
            }
        }
    }

    fun initializeEngine() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusText = "AIエンジンを初期化中...")
            try {
                inference.initialize(downloader.getModelFile())
                _uiState.value = _uiState.value.copy(
                    isEngineReady = true,
                    statusText = "準備完了 - タップで解析"
                )
                scheduleHideStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed", e)
                _uiState.value = _uiState.value.copy(
                    statusText = "エンジン初期化エラー: ${e.message}"
                )
            }
        }
    }

    fun onCapturedFrame(bitmap: Bitmap) {
        val state = _uiState.value
        if (state.isAnalyzing || !state.isEngineReady) return

        _uiState.value = state.copy(
            isAnalyzing = true,
            showGlow = true,
            showStatus = true,
            statusText = "解析中..."
        )

        // Flash effect
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(showFlash = true)
            delay(150)
            _uiState.value = _uiState.value.copy(showFlash = false)
        }

        // Build prompt with history for variety
        val promptWithHistory = buildPrompt()

        viewModelScope.launch {
            inference.analyze(bitmap, promptWithHistory).collect { inferenceState ->
                when (inferenceState) {
                    is InferenceState.Loading -> {}
                    is InferenceState.Streaming -> {
                        _uiState.value = _uiState.value.copy(resultText = inferenceState.text)
                    }
                    is InferenceState.Done -> {
                        _uiState.value = _uiState.value.copy(
                            resultText = inferenceState.text,
                            showGlow = false,
                            isAnalyzing = false,
                            statusText = if (_uiState.value.autoMode) "オート解析中" else "準備完了 - タップで解析"
                        )

                        // Save to history
                        history.add(inferenceState.text)
                        if (history.size > 3) history.removeAt(0)

                        // TTS
                        speak(inferenceState.text)

                        // Recycle bitmap
                        bitmap.recycle()

                        // Auto mode: start countdown
                        if (_uiState.value.autoMode) {
                            startCountdown()
                        } else {
                            scheduleHideStatus()
                        }
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
        Log.e(TAG, "Capture error: $error")
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
            statusText = if (newAutoMode) "オート解析中" else "準備完了 - タップで解析",
            countdown = 0
        )

        if (newAutoMode) {
            // Trigger immediately
            requestAnalysis = true
        } else {
            autoJob?.cancel()
            autoJob = null
            tts?.stop()
            scheduleHideStatus()
        }
    }

    // Flag to request analysis from UI (since we can't hold camera reference here)
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

    private fun buildPrompt(): String {
        var prompt = PROMPT
        if (history.isNotEmpty()) {
            val historyText = history.mapIndexed { i, h -> "${i + 1}回前: $h" }.joinToString("\n")
            prompt += "\n\n過去の観察:\n$historyText\n\n上記と違う点や変化に注目して。同じことは言わないで。"
        }
        return prompt
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "g4cam_utterance")
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
