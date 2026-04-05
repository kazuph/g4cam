package com.kazuph.g4cam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kazuph.g4cam.camera.CameraController
import com.kazuph.g4cam.camera.CameraPreview
import kotlinx.coroutines.flow.filter

@Composable
fun G4CamApp(
    hasCameraPermission: Boolean,
    viewModel: G4CamViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Model unavailable screen
    if (uiState.modelUnavailable) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(text = "⚠️", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Gemini Nanoが\nこのデバイスでは利用できません",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "AICore Developer Preview対応の\nデバイスが必要です\n\n${uiState.statusText}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // Model needs download - ask user first
    if (uiState.needsModelDownload || uiState.isDownloading) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(text = "🤖", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Gemma 4 E2B",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.isDownloading) {
                    CircularProgressIndicator(color = Color(0xFF0096FF))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.statusText,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = "AIモデルのダウンロードが必要です\nWi-Fi接続を推奨します",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.startModelDownload() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0096FF)
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text(
                            text = "ダウンロード開始",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        return
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
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

    CameraScreen(viewModel = viewModel)
}

@Composable
private fun CameraScreen(viewModel: G4CamViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    val cameraController = remember { CameraController(context) }

    // Status bar insets to avoid overlap
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    LaunchedEffect(Unit) {
        viewModel.initializeEngine()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.requestAnalysis }
            .filter { it }
            .collect {
                viewModel.consumeAnalysisRequest()
                triggerAnalysis(cameraController, viewModel)
            }
    }

    fun analyze() {
        triggerAnalysis(cameraController, viewModel)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { analyze() }
            }
    ) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraController = cameraController,
            lifecycleOwner = lifecycleOwner
        )

        AiGlowEffect(isActive = uiState.showGlow)

        AnimatedVisibility(
            visible = uiState.showFlash,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FlashEffect(isActive = true)
        }

        // Top: Status - with status bar padding to avoid overlap
        AnimatedVisibility(
            visible = uiState.showStatus,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            StatusBar(
                text = uiState.statusText,
                isLoading = uiState.isAnalyzing
            )
        }

        // Top right: Countdown - with status bar padding
        if (uiState.autoMode && uiState.countdown > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = statusBarPadding.calculateTopPadding() + 12.dp,
                        end = 12.dp
                    )
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${uiState.countdown}s",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }

        // Bottom: Auto mode toggle + Result
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            ResultOverlay(text = uiState.resultText)

            if (uiState.isEngineReady) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(bottom = 8.dp)
                ) {
                    AutoModeButton(
                        isAutoMode = uiState.autoMode,
                        onClick = { viewModel.toggleAutoMode() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoModeButton(
    isAutoMode: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .background(
                if (isAutoMode) Color(0xCCFF3C3C) else Color(0xCC0096FF),
                RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Text(
            text = if (isAutoMode) "⏹ 停止" else "▶ 開始",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun triggerAnalysis(
    cameraController: CameraController,
    viewModel: G4CamViewModel
) {
    cameraController.captureFrame(
        onCaptured = { bitmap -> viewModel.onCapturedFrame(bitmap) },
        onError = { error -> viewModel.onCaptureError(error) }
    )
}
