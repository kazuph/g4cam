package com.kazuph.g4cam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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

    // Backend selection screen
    if (uiState.showBackendSelector) {
        BackendSelectorScreen(onSelect = { viewModel.selectBackend(it) })
        return
    }

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
                    text = "AICore Preview が利用できません",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.statusText,
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.switchToLiteRTFallback() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0096FF)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Text("LiteRT-LM版で試す", fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("モデルDLが必要（約2.6GB・Wi-Fi推奨）",
                    color = Color(0xFF888888), fontSize = 12.sp)
            }
        }
        return
    }

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
                Text("Gemma 4 E2B", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                if (uiState.isDownloading) {
                    CircularProgressIndicator(color = Color(0xFF0096FF))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(uiState.statusText, color = Color.White, fontSize = 14.sp)
                } else {
                    Text("AIモデルのダウンロードが必要です\nWi-Fi接続を推奨します",
                        color = Color(0xFFCCCCCC), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.startModelDownload() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0096FF)),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text("ダウンロード開始", fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
        }
        return
    }

    if (uiState.needsLiteRTInit) {
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
                Text("モデル準備完了", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text("エンジンの初期化に\n30〜60秒かかります",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.startLiteRTInit() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00CC66)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Text("初期化開始", fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
        return
    }

    if (uiState.isLiteRTInitializing) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF00CC66), modifier = Modifier.padding(16.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(uiState.statusText, color = Color.White, fontSize = 16.sp,
                    textAlign = TextAlign.Center, lineHeight = 24.sp)
            }
        }
        return
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("カメラの権限が必要です", color = Color.White, fontSize = 18.sp)
        }
        return
    }

    // History overlay
    if (uiState.showHistory) {
        HistoryScreen(viewModel = viewModel)
        return
    }

    CameraScreen(viewModel = viewModel)
}

@Composable
private fun HistoryScreen(viewModel: G4CamViewModel) {
    val historyItems by viewModel.historyItems.collectAsState()
    var selectedItem by remember { mutableStateOf<HistoryItem?>(null) }

    // Full screen image view when item selected
    if (selectedItem != null) {
        val item = selectedItem!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { selectedItem = null }
        ) {
            // Analyzed image (256px, same as sent to model)
            Image(
                bitmap = item.analyzedImage.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            // Text overlay at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE6000000))
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Column {
                    Text(
                        text = item.text,
                        color = Color.White,
                        fontSize = 22.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${item.durationMs / 1000}.${(item.durationMs % 1000) / 100}秒",
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp
                    )
                }
            }
            // Close hint
            Text(
                text = "タップで閉じる",
                color = Color(0x99FFFFFF),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
            )
        }
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("履歴", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .clickable { viewModel.toggleHistory() }
                        .background(Color(0x66FFFFFF), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("閉じる", color = Color.White, fontSize = 16.sp)
                }
            }

            if (historyItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("まだ解析履歴がありません", color = Color(0xFF888888), fontSize = 18.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(historyItems.reversed()) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                                .clickable { selectedItem = item }
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Image(
                                bitmap = item.thumbnail.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.text,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${item.durationMs / 1000}.${(item.durationMs % 1000) / 100}秒",
                                    color = Color(0xFF888888),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraScreen(viewModel: G4CamViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    val cameraController = remember { CameraController(context) }
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
        // Camera preview
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraController = cameraController,
            lifecycleOwner = lifecycleOwner
        )

        // AI Glow Effect
        AiGlowEffect(isActive = uiState.showGlow)

        // Flash Effect
        AnimatedVisibility(visible = uiState.showFlash, enter = fadeIn(), exit = fadeOut()) {
            FlashEffect(isActive = true)
        }

        // === TOP LEFT: Status (animated visibility) ===
        AnimatedVisibility(
            visible = uiState.showStatus,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    top = statusBarPadding.calculateTopPadding() + 8.dp,
                    start = 12.dp
                )
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(uiState.statusText, color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // === TOP RIGHT: Buttons (always visible, fixed position) ===
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = statusBarPadding.calculateTopPadding() + 8.dp,
                    end = 12.dp
                )
        ) {
            // Countdown
            if (uiState.autoMode && uiState.countdown > 0) {
                Box(
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("${uiState.countdown}s", color = Color.White, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // History button
            val historyItems by viewModel.historyItems.collectAsState()
            if (historyItems.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clickable { viewModel.toggleHistory() }
                        .background(Color(0x99000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("📋 ${historyItems.size}", color = Color.White, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Auto mode button (always visible when engine ready)
            if (uiState.isEngineReady) {
                Box(
                    modifier = Modifier
                        .clickable { viewModel.toggleAutoMode() }
                        .background(
                            if (uiState.autoMode) Color(0xCCFF3C3C) else Color(0xCC0096FF),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (uiState.autoMode) "⏹ 停止" else "▶ 開始",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // === BOTTOM: Result text (large, accessible) ===
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE6000000))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 32.dp)
        ) {
            if (uiState.resultText.isNotEmpty()) {
                Text(
                    text = uiState.resultText,
                    color = Color.White,
                    fontSize = 24.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

@Composable
private fun BackendSelectorScreen(onSelect: (BackendChoice) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = statusBarPadding.calculateTopPadding() + 16.dp,
                    start = 24.dp,
                    end = 24.dp
                )
        ) {
            Text("g4cam", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("推論バックエンドを選択", color = Color(0xFFAAAAAA), fontSize = 16.sp)
            Spacer(modifier = Modifier.height(32.dp))

            Text("AICore (Gemini Nano)", color = Color(0xFF0096FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            BackendButton("E2B Fast (推奨)", "高速・NPU活用", Color(0xFF0096FF)) {
                onSelect(BackendChoice.AICORE_FAST)
            }
            Spacer(modifier = Modifier.height(8.dp))
            BackendButton("E4B Full", "高精度・やや遅い", Color(0xFF0066AA)) {
                onSelect(BackendChoice.AICORE_FULL)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("LiteRT-LM (要モデルDL 2.6GB)", color = Color(0xFF00CC66), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            BackendButton("GPU", "メモリ効率良い", Color(0xFF00CC66)) {
                onSelect(BackendChoice.LITERT_GPU)
            }
            Spacer(modifier = Modifier.height(8.dp))
            BackendButton("NPU", "ハードウェア最適化", Color(0xFF00AA55)) {
                onSelect(BackendChoice.LITERT_NPU)
            }
            Spacer(modifier = Modifier.height(8.dp))
            BackendButton("CPU", "最も互換性高い", Color(0xFF008844)) {
                onSelect(BackendChoice.LITERT_CPU)
            }
        }
    }
}

@Composable
private fun BackendButton(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 13.sp)
            }
            Text("→", color = color, fontSize = 20.sp)
        }
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
