package com.kazuph.g4cam.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI Glow Effect - ported from web version
 * 4-edge gradient glow with breathing animation
 * Top: purple, Bottom: blue, Left: red, Right: yellow
 */
@Composable
fun AiGlowEffect(isActive: Boolean) {
    if (!isActive) return

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowBreath"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Top edge: purple → transparent
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xB3BC82F3),  // rgba(188,130,243,0.7)
                    Color(0x4DF5B9EA),  // rgba(245,185,234,0.3)
                    Color(0x19BC82F3),  // rgba(188,130,243,0.1)
                    Color(0x05BC82F3),  // rgba(188,130,243,0.02)
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.25f
            ),
            alpha = alpha
        )

        // Bottom edge: blue → transparent
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x05389FFF),
                    Color(0x198D9FFF),
                    Color(0x4D8D9FFF),
                    Color(0xB338B6FF)   // rgba(56,182,255,0.7)
                ),
                startY = h * 0.75f,
                endY = h
            ),
            alpha = alpha
        )

        // Left edge: red → transparent
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xA6FF5064),  // rgba(255,80,100,0.65)
                    Color(0x40FF9650),  // rgba(255,150,80,0.25)
                    Color(0x14FF6778),  // rgba(255,103,120,0.08)
                    Color(0x03FF6778),
                    Color.Transparent
                ),
                startX = 0f,
                endX = w * 0.22f
            ),
            alpha = alpha
        )

        // Right edge: yellow → transparent
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x03BC82F3),
                    Color(0x14BC82F3),
                    Color(0x40FF82C8),
                    Color(0xA6FFC850)   // rgba(255,200,80,0.65)
                ),
                startX = w * 0.78f,
                endX = w
            ),
            alpha = alpha
        )
    }
}

/**
 * Status indicator at top
 */
@Composable
fun StatusBar(
    text: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(12.dp)
            .background(
                Color(0x99000000),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).align(Alignment.CenterStart),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Text(
                text = text,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp)
            )
        } else {
            Text(
                text = text,
                color = Color.White,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Result overlay at bottom - gradient background like web version
 */
@Composable
fun ResultOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    if (text.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xD9000000)  // rgba(0,0,0,0.85)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 17.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Start
        )
    }
}

/**
 * Flash effect on capture
 */
@Composable
fun FlashEffect(isActive: Boolean) {
    if (!isActive) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x4DFFFFFF))  // rgba(255,255,255,0.3)
    )
}
