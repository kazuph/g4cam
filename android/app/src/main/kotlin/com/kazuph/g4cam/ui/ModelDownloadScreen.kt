package com.kazuph.g4cam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazuph.g4cam.model.DownloadState

@Composable
fun ModelDownloadScreen(
    downloadState: DownloadState,
    onStartDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "🤖",
                fontSize = 64.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Gemma 4 E2B",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "オンデバイスAIモデル",
                color = Color(0xFFAAAAAA),
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            when (downloadState) {
                is DownloadState.Idle -> {
                    Text(
                        text = "カメラでAI画像認識を行うには\nモデルのダウンロードが必要です\n(約2.6GB)",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onStartDownload,
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

                is DownloadState.Downloading -> {
                    val percent = (downloadState.progress * 100).toInt()

                    Text(
                        text = "ダウンロード中... $percent%",
                        color = Color.White,
                        fontSize = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFF0096FF),
                        trackColor = Color(0xFF333333),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val downloadedMB = (downloadState.progress * 2580).toInt()
                    Text(
                        text = "${downloadedMB}MB / 2,580MB",
                        color = Color(0xFF888888),
                        fontSize = 12.sp
                    )
                }

                is DownloadState.Completed -> {
                    Text(
                        text = "✅ ダウンロード完了！",
                        color = Color(0xFF00CC66),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                is DownloadState.Error -> {
                    Text(
                        text = "❌ エラー: ${downloadState.message}",
                        color = Color(0xFFFF4444),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onStartDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6060)
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text("再試行")
                    }
                }
            }
        }
    }
}
