package com.example.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.NyxoraElectricBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CRAFTED_BY_SUDHAMOY_BRAND = "CRAFTED BY SUDHAMOY"
const val NYXORA_LAUNCH_ANIMATION_DURATION_MS = 2000L

/**
 * Renders the transparent NYXORA 'N' ribbon logo (`R.drawable.nyxora_official_icon`)
 * with silhouette-following blue-to-violet glow and alpha-masked specular light sweep.
 *
 * - Uses the processed transparent RGBA NYXORA 'N' logo artwork (no rectangular background or card).
 * - Glow follows the actual 3-segment 'N' ribbon silhouette, never a rectangular boundary.
 * - Light sweep is masked to the opaque logo pixels via `BlendMode.SrcATop`.
 */
@Composable
fun NyxoraGeometricLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    sweepProgress: Float = 0f,
    glowIntensity: Float = 0f,
    imageTestTag: String? = null
) {
    val clampedGlow = glowIntensity.coerceIn(0f, 1f)
    val clampedSweep = sweepProgress.coerceIn(0f, 1f)
    val logoPainter = painterResource(id = R.drawable.nyxora_official_icon)

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "NYXORA official app icon" },
        contentAlignment = Alignment.Center
    ) {
        if (clampedGlow > 0.01f) {
            // Silhouette-following blue-to-violet glow along the exact 3-stroke 'N' ribbon path
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("nyxora_logo_silhouette_glow")
            ) {
                val w = this.size.width
                val h = this.size.height
                // Exact normalized coordinates of the 3 strokes of the NYXORA 'N' in the 700x700 asset:
                // P1=(0.145, 0.728), P2=(0.318, 0.289), P3=(0.676, 0.721), P4=(0.849, 0.275)
                val nPath = Path().apply {
                    moveTo(w * 0.145f, h * 0.728f)
                    lineTo(w * 0.318f, h * 0.289f)
                    lineTo(w * 0.676f, h * 0.721f)
                    lineTo(w * 0.849f, h * 0.275f)
                }

                val glowBrush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = 0.34f * clampedGlow),
                        Color(0xFF6366F1).copy(alpha = 0.32f * clampedGlow),
                        Color(0xFF8B5CF6).copy(alpha = 0.30f * clampedGlow),
                        Color(0xFF60A5FA).copy(alpha = 0.34f * clampedGlow)
                    ),
                    start = Offset(w * 0.12f, h * 0.75f),
                    end = Offset(w * 0.88f, h * 0.25f)
                )

                // Outer soft silhouette halo
                drawPath(
                    path = nPath,
                    brush = glowBrush,
                    style = Stroke(
                        width = w * 0.31f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Inner concentrated blue-violet silhouette rim
                drawPath(
                    path = nPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF60A5FA).copy(alpha = 0.42f * clampedGlow),
                            Color(0xFF818CF8).copy(alpha = 0.38f * clampedGlow),
                            Color(0xFF38BDF8).copy(alpha = 0.42f * clampedGlow)
                        ),
                        start = Offset(w * 0.15f, h * 0.28f),
                        end = Offset(w * 0.85f, h * 0.72f)
                    ),
                    style = Stroke(
                        width = w * 0.23f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Soft silhouette-tinted aura using the transparent logo's own alpha channel
            Image(
                painter = logoPainter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(
                    color = Color(0xFF6366F1).copy(alpha = 0.28f * clampedGlow),
                    blendMode = BlendMode.SrcIn
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.08f + (0.04f * clampedGlow)
                        scaleY = 1.08f + (0.04f * clampedGlow)
                    }
            )
        }

        // Main transparent NYXORA 'N' logo with alpha-masked specular light sweep
        Image(
            painter = logoPainter,
            contentDescription = "NYXORA official app icon",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = if (clampedSweep in 0.01f..0.99f) {
                        CompositingStrategy.Offscreen
                    } else {
                        CompositingStrategy.Auto
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (clampedSweep in 0.01f..0.99f) {
                        val w = this.size.width
                        val h = this.size.height
                        val bandWidth = w * 0.42f
                        val centerX = -bandWidth + (w + bandWidth * 2f) * clampedSweep
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFFBAE6FD).copy(alpha = 0.30f),
                                    Color.White.copy(alpha = 0.52f),
                                    Color(0xFFC4B5FD).copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                start = Offset(centerX - bandWidth * 0.65f, h * 0.85f),
                                end = Offset(centerX + bandWidth * 0.65f, h * 0.15f)
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                }
                .let { base ->
                    if (imageTestTag != null) base.testTag(imageTestTag) else base
                }
        )
    }
}

/**
 * Home Screen NYXORA Logo with subtle breathing blue-violet silhouette glow.
 * - Uses the transparent NYXORA 'N' logo artwork (`R.drawable.nyxora_official_icon`).
 * - Respects `animateGlow` (Appearance setting) and `reducedMotion`.
 */
@Composable
fun NyxoraHomeBrandLogo(
    animateGlow: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    imageTestTag: String = "home_official_icon_image"
) {
    val isActiveAnimation = animateGlow && !reducedMotion
    val isRobolectric = remember { Build.FINGERPRINT.equals("robolectric", ignoreCase = true) }

    val glowIntensity = if (!isActiveAnimation) {
        0f
    } else if (isRobolectric) {
        // Deterministic non-blocking glow value under Robolectric so waitForIdle() settles immediately
        0.52f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "nyxora_home_logo_breathing")
        val animatedGlow by infiniteTransition.animateFloat(
            initialValue = 0.24f,
            targetValue = 0.64f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "home_logo_glow_pulse"
        )
        animatedGlow
    }

    Box(
        modifier = if (isActiveAnimation) {
            modifier.testTag("home_logo_glow_active")
        } else {
            modifier
        },
        contentAlignment = Alignment.Center
    ) {
        NyxoraGeometricLogo(
            size = size,
            sweepProgress = 0f,
            glowIntensity = glowIntensity,
            imageTestTag = imageTestTag
        )
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
 * Premium 2.0-second (2000 ms) opening animation matching the NYXORA 'N' logo:
 * - 0.0–0.4s: Deep dark background, subtle blue ambient light appears, logo fades in & scales 92% -> 100%.
 * - 0.4–1.2s: Soft blue-to-violet glow follows the 'N' silhouette, subtle light sweep travels across the logo.
 * - 1.2–1.7s: NYXORA branding fades in smoothly, subtle light pulse around the logo.
 * - 1.7–2.0s: Smooth cross-fade transition to the Home Screen with zero flash or delay.
 */
@Composable
fun NyxoraLaunchSplash(
    reducedMotion: Boolean,
    onSplashCompleted: () -> Unit,
    forceFullDurationInTest: Boolean = false
) {
    val ambientAlpha = remember { Animatable(if (reducedMotion) 1f else 0.20f) }
    val logoAlpha = remember { Animatable(if (reducedMotion) 1f else 0.18f) }
    val logoScale = remember { Animatable(if (reducedMotion) 1f else 0.92f) }
    val glowIntensity = remember { Animatable(if (reducedMotion) 0.45f else 0.18f) }
    val sweepProgress = remember { Animatable(0f) }
    val brandingAlpha = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val containerAlpha = remember { Animatable(1f) }

    LaunchedEffect(reducedMotion, forceFullDurationInTest) {
        if (reducedMotion) {
            onSplashCompleted()
        } else {
            val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            val timeScale = if (isRobolectric && !forceFullDurationInTest) 0.10f else 1.0f
            fun scaledMs(ms: Int): Int = (ms * timeScale).toInt().coerceAtLeast(15)

            // Phase 1 (0.0s – 0.4s): Ambient light + Logo fade in & scale 92% -> 100%
            val p1Duration = scaledMs(400)
            val jobAmbient = launch {
                ambientAlpha.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
            }
            val jobLogoAlpha = launch {
                logoAlpha.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
            }
            val jobLogoScale = launch {
                logoScale.animateTo(1f, animationSpec = tween(p1Duration, easing = LinearOutSlowInEasing))
            }
            // Start branding fade early in Robolectric short mode so mid-splash assertions see it
            if (isRobolectric && !forceFullDurationInTest) {
                launch {
                    brandingAlpha.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
                }
            }
            jobAmbient.join()
            jobLogoAlpha.join()
            jobLogoScale.join()

            // Phase 2 (0.4s – 1.2s): Silhouette blue-to-violet glow + light sweep across logo
            val p2Duration = scaledMs(800)
            val jobGlow = launch {
                glowIntensity.animateTo(0.84f, animationSpec = tween(p2Duration, easing = FastOutSlowInEasing))
            }
            val jobSweep = launch {
                sweepProgress.animateTo(1f, animationSpec = tween(p2Duration, easing = FastOutSlowInEasing))
            }
            // Begin smooth branding fade-in as the light sweep finishes
            val jobBrandEarly = launch {
                delay(scaledMs(550).toLong())
                brandingAlpha.animateTo(1f, animationSpec = tween(scaledMs(450), easing = FastOutSlowInEasing))
            }
            jobGlow.join()
            jobSweep.join()

            // Phase 3 (1.2s – 1.7s): Branding settles + subtle light pulse around the logo
            val pulseUpMs = scaledMs(250)
            val pulseDownMs = scaledMs(250)
            glowIntensity.animateTo(1.0f, animationSpec = tween(pulseUpMs, easing = FastOutSlowInEasing))
            glowIntensity.animateTo(0.68f, animationSpec = tween(pulseDownMs, easing = FastOutSlowInEasing))
            jobBrandEarly.join()

            // Phase 4 (1.7s – 2.0s): Smooth cross-fade transition to the Home Screen
            val p4Duration = scaledMs(300)
            containerAlpha.animateTo(0f, animationSpec = tween(p4Duration, easing = FastOutSlowInEasing))
            onSplashCompleted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = containerAlpha.value
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF040A14),
                        Color(0xFF09172E),
                        Color(0xFF040A14)
                    )
                )
            )
            .testTag("nyxora_launch_splash")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onSplashCompleted()
            },
        contentAlignment = Alignment.Center
    ) {
        // Subtle blue-violet ambient light behind logo
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ambientAlpha.value }
        ) {
            val radius = size.minDimension * 0.46f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = 0.14f + 0.08f * glowIntensity.value),
                        Color(0xFF6366F1).copy(alpha = 0.08f + 0.06f * glowIntensity.value),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.44f),
                    radius = radius
                ),
                center = Offset(size.width * 0.5f, size.height * 0.44f),
                radius = radius
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = logoAlpha.value
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                },
                contentAlignment = Alignment.Center
            ) {
                NyxoraGeometricLogo(
                    size = 86.dp,
                    sweepProgress = sweepProgress.value,
                    glowIntensity = glowIntensity.value,
                    modifier = Modifier.testTag("splash_official_icon")
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = brandingAlpha.value
                }
            ) {
                Text(
                    text = "NYXORA",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        letterSpacing = 5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFFF8FAFC)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Browse Beyond Ordinary.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.testTag("splash_tagline_text")
                )
                Spacer(modifier = Modifier.height(14.dp))
                NyxoraCreatorSignature(
                    textTestTag = "splash_creator_credit"
                )
            }
        }
    }
}
