package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NyxoraCyanHighlight
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.theme.NyxoraVibrantViolet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Custom Geometric "N" Logo representing speed, navigation, and forward motion.
 * Adapts automatically: electric-blue-to-violet gradient on dark backgrounds,
 * clean monochrome with subtle cobalt edge in light mode.
 */
@Composable
fun NyxoraGeometricLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    sweepProgress: Float = 0f
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "NYXORA geometric N logo" }
    ) {
        val w = this.size.width
        val h = this.size.height

        val leftBrush = if (isDark) {
            Brush.linearGradient(
                colors = listOf(NyxoraCyanHighlight, NyxoraElectricBlue),
                start = Offset(0f, 0f),
                end = Offset(w * 0.4f, h)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
            )
        }

        val diagBrush = if (isDark) {
            Brush.linearGradient(
                colors = listOf(NyxoraElectricBlue, Color(0xFF6366F1), NyxoraVibrantViolet),
                start = Offset(w * 0.2f, 0f),
                end = Offset(w * 0.8f, h)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(Color(0xFF1E293B), Color(0xFF2563EB))
            )
        }

        val rightBrush = if (isDark) {
            Brush.linearGradient(
                colors = listOf(NyxoraVibrantViolet, Color(0xFF6366F1)),
                start = Offset(w * 0.65f, 0f),
                end = Offset(w, h)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(Color(0xFF0F172A), Color(0xFF334155))
            )
        }

        // Left vertical blade
        val leftPath = Path().apply {
            moveTo(w * 0.16f, h * 0.84f)
            lineTo(w * 0.16f, h * 0.18f)
            lineTo(w * 0.33f, h * 0.18f)
            lineTo(w * 0.33f, h * 0.84f)
            close()
        }
        drawPath(leftPath, brush = leftBrush)

        // Diagonal speed blade
        val diagPath = Path().apply {
            moveTo(w * 0.26f, h * 0.18f)
            lineTo(w * 0.44f, h * 0.18f)
            lineTo(w * 0.74f, h * 0.84f)
            lineTo(w * 0.56f, h * 0.84f)
            close()
        }
        drawPath(diagPath, brush = diagBrush)

        // Right navigation blade with forward apex
        val rightPath = Path().apply {
            moveTo(w * 0.67f, h * 0.84f)
            lineTo(w * 0.67f, h * 0.12f)
            lineTo(w * 0.84f, h * 0.21f)
            lineTo(w * 0.84f, h * 0.84f)
            close()
        }
        drawPath(rightPath, brush = rightBrush)

        // Controlled brief light sweep during splash
        if (sweepProgress > 0.01f && sweepProgress < 0.99f) {
            val sweepX = w * sweepProgress
            drawLine(
                color = Color.White.copy(alpha = 0.45f * (1f - kotlin.math.abs(sweepProgress - 0.5f) * 2f)),
                start = Offset(sweepX - w * 0.15f, h * 0.1f),
                end = Offset(sweepX + w * 0.15f, h * 0.9f),
                strokeWidth = w * 0.08f
            )
        }
    }
}

/**
 * Soft, short (200 ms) Launch Splash Screen:
 * Respects reducedMotion setting, animates only GPU-friendly transform (scale) and opacity
 * via graphicsLayer, and transitions immediately without blocking input.
 */
@Composable
fun NyxoraLaunchSplash(
    reducedMotion: Boolean,
    onSplashCompleted: () -> Unit
) {
    val alphaAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val scaleAnim = remember { Animatable(if (reducedMotion) 1f else 0.94f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            onSplashCompleted()
        } else {
            val animJob1 = launch {
                alphaAnim.animateTo(1f, animationSpec = tween(180, easing = FastOutSlowInEasing))
            }
            val animJob2 = launch {
                scaleAnim.animateTo(1f, animationSpec = tween(200, easing = FastOutSlowInEasing))
            }
            animJob1.join()
            animJob2.join()
            onSplashCompleted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                alpha = alphaAnim.value
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
            }
        ) {
            NyxoraGeometricLogo(
                size = 76.dp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "NYXORA",
                style = MaterialTheme.typography.headlineLarge.copy(
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Browse Beyond Ordinary.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
