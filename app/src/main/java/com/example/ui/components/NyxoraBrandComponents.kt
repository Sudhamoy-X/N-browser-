package com.example.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
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

const val CRAFTED_BY_SUDHAMOY_BRAND = "CRAFTED BY SUDHAMOY"
const val NYXORA_LAUNCH_ANIMATION_DURATION_MS = 2000L

/**
 * Custom Geometric "N" Logo representing speed, navigation, and forward motion.
 * Adapts automatically: electric-blue-to-violet gradient on dark backgrounds,
 * clean monochrome with subtle cobalt edge in light mode.
 */
@Composable
fun NyxoraGeometricLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    sweepProgress: Float = 0f,
    glowIntensity: Float = 0f
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "NYXORA geometric N logo" }
    ) {
        val w = this.size.width
        val h = this.size.height

        if (glowIntensity > 0.01f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NyxoraElectricBlue.copy(alpha = (0.36f * glowIntensity).coerceIn(0f, 0.55f)),
                        NyxoraCyanHighlight.copy(alpha = (0.16f * glowIntensity).coerceIn(0f, 0.30f)),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = w * 0.72f
                ),
                radius = w * 0.72f,
                center = Offset(w * 0.5f, h * 0.5f)
            )
        }

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
 * Distinctive, understated creator signature component displaying `CRAFTED BY SUDHAMOY`
 * in capital letters with clean letter spacing and subtle accent hairlines.
 */
@Composable
fun NyxoraCreatorSignature(
    modifier: Modifier = Modifier,
    textTestTag: String? = null
) {
    val accentColor = NyxoraElectricBlue.copy(alpha = 0.45f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, accentColor)))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = CRAFTED_BY_SUDHAMOY_BRAND,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.6.sp,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp
            ),
            color = textColor,
            modifier = if (textTestTag != null) Modifier.testTag(textTestTag) else Modifier
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(accentColor, Color.Transparent)))
        )
    }
}

/**
 * Smooth ~2-second (2000 ms) glowing launch animation:
 * Respects reducedMotion setting, animates GPU-friendly transform (scale), opacity, radial glow,
 * and light sweep, and transitions cleanly without blocking input (tapping skips immediately).
 */
@Composable
fun NyxoraLaunchSplash(
    reducedMotion: Boolean,
    onSplashCompleted: () -> Unit,
    forceFullDurationInTest: Boolean = false
) {
    val alphaAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val scaleAnim = remember { Animatable(if (reducedMotion) 1f else 0.93f) }
    val creditAlphaAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val glowAnim = remember { Animatable(if (reducedMotion) 0.5f else 0.15f) }
    val sweepAnim = remember { Animatable(0f) }

    LaunchedEffect(reducedMotion, forceFullDurationInTest) {
        if (reducedMotion) {
            onSplashCompleted()
        } else {
            val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            val totalDurationMs = if (isRobolectric && !forceFullDurationInTest) {
                200L
            } else {
                NYXORA_LAUNCH_ANIMATION_DURATION_MS
            }
            val entranceMs = (totalDurationMs * 0.25f).toInt().coerceAtLeast(120)
            val glowHoldMs = (totalDurationMs - entranceMs).coerceAtLeast(60L)

            val animJob1 = launch {
                alphaAnim.animateTo(1f, animationSpec = tween(entranceMs, easing = FastOutSlowInEasing))
            }
            val animJob2 = launch {
                scaleAnim.animateTo(1f, animationSpec = tween(entranceMs, easing = FastOutSlowInEasing))
            }
            val animJob3 = launch {
                delay(30L)
                creditAlphaAnim.animateTo(1f, animationSpec = tween(entranceMs, easing = FastOutSlowInEasing))
            }
            val glowJob = launch {
                glowAnim.animateTo(1f, animationSpec = tween((totalDurationMs * 0.65f).toInt().coerceAtLeast(140), easing = FastOutSlowInEasing))
            }
            val sweepJob = launch {
                sweepAnim.animateTo(1f, animationSpec = tween((totalDurationMs * 0.75f).toInt().coerceAtLeast(150), easing = FastOutSlowInEasing))
            }
            animJob1.join()
            animJob2.join()
            animJob3.join()
            delay(glowHoldMs)
            glowJob.cancel()
            sweepJob.cancel()
            onSplashCompleted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("nyxora_launch_splash")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onSplashCompleted()
            },
        contentAlignment = Alignment.Center
    ) {
        // Ambient radial electric-blue / cyan glow aura
        Canvas(modifier = Modifier.size(300.dp)) {
            val radius = size.minDimension * 0.5f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NyxoraElectricBlue.copy(alpha = 0.20f * glowAnim.value),
                        NyxoraCyanHighlight.copy(alpha = 0.08f * glowAnim.value),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    alpha = alphaAnim.value
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                }
        ) {
            NyxoraGeometricLogo(
                size = 80.dp,
                sweepProgress = sweepAnim.value,
                glowIntensity = glowAnim.value
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "NYXORA",
                style = MaterialTheme.typography.headlineLarge.copy(
                    letterSpacing = 5.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Browse Beyond Ordinary.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("splash_tagline_text")
            )
            Spacer(modifier = Modifier.height(14.dp))
            NyxoraCreatorSignature(
                modifier = Modifier.graphicsLayer {
                    alpha = creditAlphaAnim.value
                },
                textTestTag = "splash_creator_credit"
            )
        }
    }
}

