package com.kazuph.g4cam.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazuph.g4cam.ai.GemmaInference
import com.kazuph.g4cam.ai.InferenceState
import com.kazuph.g4cam.camera.CameraController
import com.kazuph.g4cam.camera.CameraPreview
import com.kazuph.g4cam.model.DownloadState
import com.kazuph.g4cam.model.ModelDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "G4Cam"
private const val PROMPT = "絵文字1つ＋この画像の説明を1文で。文字が見えたら必ず読む。日本語で短く。"

@Composable
fun G4CamApp(hasCameraPermission: Boolean) {
    val context = LocalContext.current
    val downloader = remember { ModelDownloader(context) }

    var downloadState by remember {
        mutableStateOf(
            if (downloader.isModelDownloaded()) DownloadState.Completed
            else DownloadState.Idle
        )
    }

    val scope = rememberCoroutineScope()

    if (downloadState !is DownloadState.Completed) {
        ModelDownloadScreen(
            downloadState = downloadState,
            onStartDownload = {
                scope.launch {
                    downloader.download().collect { state ->
                        downloadState = state
                    }
                }
            }
        )
        return
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "カメラの権限が必要です",
                color = Color.White,
                fontSize = 18.sp
            )
        }
        return
    }

    CameraScreen(
        modelDownloader = downloader
    )
}

@Composable
private fun CameraScreen(
    modelDownloader: ModelDownloader
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val cameraController = remember { CameraController(context) }
    val inference = remember { GemmaInference() }

    var isAnalyzing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("準備完了") }
    var showStatus by remember { mutableStateOf(true) }
    var showFlash by remember { mutableStateOf(false) }
    var showGlow by remember { mutableStateOf(false) }
    var isEngineReady by remember { mutableStateOf(false) }

    // Initialize engine
    LaunchedEffect(Unit) {
        statusText = "AIエンジンを初期化中..."
        try {
            inference.initialize(modelDownloader.getModelFile())
            isEngineReady = true
            statusText = "準備完了 - タップで解析"
            delay(3000)
            showStatus = false
        } catch (e: Exception) {
            Log.e(TAG, "Engine init failed", e)
            statusText = "エンジン初期化エラー: ${e.message}"
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            inference.release()
        }
    }

    fun analyze() {
        if (isAnalyzing || !isEngineReady) return
        isAnalyzing = true

        // Flash effect
        scope.launch {
            showFlash = true
            delay(150)
            showFlash = false
        }

        showGlow = true
        showStatus = true
        statusText = "解析中..."

        cameraController.captureFrame(
            onCaptured = { bitmap ->
                scope.launch {
                    inference.analyze(bitmap, PROMPT).collect { state ->
                        when (state) {
                            is InferenceState.Loading -> {
                                statusText = "解析中..."
                            }
                            is InferenceState.Streaming -> {
                                resultText = state.text
                            }
                            is InferenceState.Done -> {
                                resultText = state.text
                                showGlow = false
                                isAnalyzing = false
                                statusText = "準備完了 - タップで解析"
                                delay(3000)
                                showStatus = false
                            }
                            is InferenceState.Error -> {
                                Log.e(TAG, "Inference error: ${state.message}")
                                resultText = "エラー: ${state.message}"
                                showGlow = false
                                isAnalyzing = false
                                statusText = "エラー"
                            }
                            is InferenceState.Idle -> {}
                        }
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "Capture error: $error")
                resultText = "キャプチャエラー: $error"
                showGlow = false
                isAnalyzing = false
            }
        )
    }

    // Main UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { analyze() }
            }
    ) {
        // Camera preview
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraController = cameraController,
            lifecycleOwner = lifecycleOwner
        )

        // AI Glow Effect
        AiGlowEffect(isActive = showGlow)

        // Flash Effect
        AnimatedVisibility(
            visible = showFlash,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FlashEffect(isActive = true)
        }

        // Top: Status
        AnimatedVisibility(
            visible = showStatus,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            StatusBar(
                text = statusText,
                isLoading = isAnalyzing
            )
        }

        // Bottom: Result
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            ResultOverlay(text = resultText)
        }
    }
}
