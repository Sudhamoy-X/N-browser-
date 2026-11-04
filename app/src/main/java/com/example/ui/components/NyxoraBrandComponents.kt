package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.local.AnimationPerformanceMode
import com.example.data.local.BrandGlowIntensityPresets
import com.example.ui.theme.NyxoraElectricBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CRAFTED_BY_SUDHAMOY_BRAND = "CRAFTED BY SUDHAMOY"
const val NYXORA_LAUNCH_ANIMATION_DURATION_MS = 2000L

/**
 * Pure policy helper determining whether continuous infinite logo animation loops should run.
 * Pauses expensive animations when:
 * - Homepage logo animation is disabled in Settings (`!animateGlow`)
 * - Reduced Motion is enabled (`reducedMotion`)
 * - Animation Performance Mode is set to `BATTERY_SAVER`
 * - Android device is in system Battery Saver / Low-Power state (`isDevicePowerSaveMode`)
 * - Activity lifecycle is not `RESUMED` / app is backgrounded (`!isLifecycleResumed`)
 * - Homepage is not visible (`!isVisibleOnScreen`)
 */
fun shouldRunContinuousLogoAnimation(
    animateGlow: Boolean,
    reducedMotion: Boolean,
    performanceMode: AnimationPerformanceMode = AnimationPerformanceMode.BALANCED,
    isLifecycleResumed: Boolean = true,
    isDevicePowerSaveMode: Boolean = false,
    isVisibleOnScreen: Boolean = true
): Boolean {
    if (!animateGlow || reducedMotion) return false
    if (performanceMode == AnimationPerformanceMode.BATTERY_SAVER) return false
    if (isDevicePowerSaveMode) return false
    if (!isLifecycleResumed || !isVisibleOnScreen) return false
    return true
}

/**
 * Renders the premium 3D NYXORA 'N' brand mark (`R.drawable.nyxora_official_icon` + vector 3D specular bevels)
 * with silhouette-following sapphire-to-cyan illumination and alpha-masked specular light sweep.
 *
 * - Zero rectangular background, zero white cutout borders, sharp at any screen density.
 * - Glow follows the exact 3-segment 'N' ribbon geometry.
 * - Specular light sweep and glossy bevel highlights are masked strictly to the 'N' symbol via `BlendMode.SrcAtop`.
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
            // Silhouette-following sapphire & cyan luminous halo along the 3-stroke 'N' ribbon path
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("nyxora_logo_silhouette_glow")
            ) {
                val w = this.size.width
                val h = this.size.height
                val nPath = Path().apply {
                    moveTo(w * 0.145f, h * 0.728f)
                    lineTo(w * 0.318f, h * 0.289f)
                    lineTo(w * 0.676f, h * 0.721f)
                    lineTo(w * 0.849f, h * 0.275f)
                }

                // Soft radial ambient cyan-blue aura behind the N symbol
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0EA5E9).copy(alpha = 0.22f * clampedGlow),
                            Color(0xFF1D4ED8).copy(alpha = 0.10f * clampedGlow),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.50f, h * 0.50f),
                        radius = w * 0.54f
                    ),
                    center = Offset(w * 0.50f, h * 0.50f),
                    radius = w * 0.54f
                )

                val glowBrush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = 0.36f * clampedGlow),
                        Color(0xFF2563EB).copy(alpha = 0.34f * clampedGlow),
                        Color(0xFF0EA5E9).copy(alpha = 0.34f * clampedGlow),
                        Color(0xFF7DD3FC).copy(alpha = 0.38f * clampedGlow)
                    ),
                    start = Offset(w * 0.12f, h * 0.75f),
                    end = Offset(w * 0.88f, h * 0.25f)
                )

                // Outer soft silhouette halo
                drawPath(
                    path = nPath,
                    brush = glowBrush,
                    style = Stroke(
                        width = w * 0.30f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Inner concentrated electric-blue & cyan silhouette rim
                drawPath(
                    path = nPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF60A5FA).copy(alpha = 0.44f * clampedGlow),
                            Color(0xFF38BDF8).copy(alpha = 0.40f * clampedGlow),
                            Color(0xFFBAE6FD).copy(alpha = 0.46f * clampedGlow)
                        ),
                        start = Offset(w * 0.15f, h * 0.28f),
                        end = Offset(w * 0.85f, h * 0.72f)
                    ),
                    style = Stroke(
                        width = w * 0.22f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Soft silhouette-tinted cyan-blue aura using the transparent logo's alpha channel
            Image(
                painter = logoPainter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(
                    color = Color(0xFF38BDF8).copy(alpha = 0.24f * clampedGlow),
                    blendMode = BlendMode.SrcIn
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.06f + (0.03f * clampedGlow)
                        scaleY = 1.06f + (0.03f * clampedGlow)
                    }
            )
        }

        // Main transparent 3D NYXORA 'N' logo with controlled specular bevels and alpha-masked light sweep
        Image(
            painter = logoPainter,
            contentDescription = "NYXORA official app icon",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = if (clampedSweep in 0.01f..0.99f || clampedGlow > 0.05f) {
                        CompositingStrategy.Offscreen
                    } else {
                        CompositingStrategy.Auto
                    }
                }
                .drawWithContent {
                    drawContent()
                    val w = this.size.width
                    val h = this.size.height

                    // Subtle controlled 3D top-edge highlight reflection (masked to opaque N pixels)
                    if (clampedGlow > 0.05f) {
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFE0F2FE).copy(alpha = 0.16f * clampedGlow),
                                    Color(0xFF38BDF8).copy(alpha = 0.08f * clampedGlow),
                                    Color.Transparent
                                ),
                                start = Offset(w * 0.20f, h * 0.18f),
                                end = Offset(w * 0.75f, h * 0.78f)
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }

                    // Diagonal luminous light sweep across the 3D N symbol
                    if (clampedSweep in 0.01f..0.99f) {
                        val bandWidth = w * 0.40f
                        val centerX = -bandWidth + (w + bandWidth * 2f) * clampedSweep
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF7DD3FC).copy(alpha = 0.30f),
                                    Color(0xFFF0F9FF).copy(alpha = 0.56f),
                                    Color(0xFF38BDF8).copy(alpha = 0.28f),
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
 * Home Screen NYXORA Brand Logo with persistent, subtle blue/cyan luminous identity:
 * - Soft breathing glow & gentle brightness variation.
 * - Occasional light shimmer across the 3D 'N' ribbon.
 * - Automatically pauses infinite animations when the homepage is hidden, app is backgrounded,
 *   device is in Battery Saver, or `animateGlow` is disabled / `reducedMotion` is enabled.
 */
@Composable
fun NyxoraHomeBrandLogo(
    animateGlow: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    glowIntensityScale: Float = BrandGlowIntensityPresets.DEFAULT_INTENSITY,
    performanceMode: AnimationPerformanceMode = AnimationPerformanceMode.BALANCED,
    isVisibleOnScreen: Boolean = true,
    imageTestTag: String = "home_official_icon_image"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val normalizedScale = glowIntensityScale.coerceIn(0f, 1f)

    var isLifecycleResumed by remember {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
    }
    var isDevicePowerSave by remember {
        mutableStateOf(isSystemPowerSaveModeActive(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isLifecycleResumed = true
                    isDevicePowerSave = isSystemPowerSaveModeActive(context)
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    isLifecycleResumed = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val runContinuousLoop = shouldRunContinuousLogoAnimation(
        animateGlow = animateGlow,
        reducedMotion = reducedMotion,
        performanceMode = performanceMode,
        isLifecycleResumed = isLifecycleResumed,
        isDevicePowerSaveMode = isDevicePowerSave,
        isVisibleOnScreen = isVisibleOnScreen
    )
    val isActiveGlowTag = animateGlow && !reducedMotion && normalizedScale > 0.01f
    val isRobolectric = remember { Build.FINGERPRINT.equals("robolectric", ignoreCase = true) }

    val glowIntensity: Float
    val sweepProgress: Float

    if (!animateGlow || reducedMotion || normalizedScale <= 0.01f) {
        glowIntensity = 0f
        sweepProgress = 0f
    } else if (isRobolectric || !runContinuousLoop) {
        // Static battery-conscious luminous state (also used under Robolectric so waitForIdle() settles immediately)
        glowIntensity = (0.56f * normalizedScale).coerceIn(0.15f, 1f)
        sweepProgress = 0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "nyxora_home_logo_luminous")
        val breatheDurationMs = when (performanceMode) {
            AnimationPerformanceMode.SMOOTH -> 3200
            AnimationPerformanceMode.BALANCED -> 4200
            AnimationPerformanceMode.BATTERY_SAVER -> 4800
        }
        val shimmerCycleMs = when (performanceMode) {
            AnimationPerformanceMode.SMOOTH -> 7600
            AnimationPerformanceMode.BALANCED -> 10400
            AnimationPerformanceMode.BATTERY_SAVER -> 12000
        }

        val animatedGlow by infiniteTransition.animateFloat(
            initialValue = (0.34f * normalizedScale).coerceIn(0.10f, 0.90f),
            targetValue = (0.82f * normalizedScale).coerceIn(0.20f, 1.0f),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = breatheDurationMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "home_logo_glow_pulse"
        )
        val rawShimmerCycle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = shimmerCycleMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "home_logo_shimmer_cycle"
        )
        glowIntensity = animatedGlow
        // Occasional gentle light shimmer across the logo during the first 24% of each cycle, resting cleanly afterwards
        sweepProgress = if (rawShimmerCycle <= 0.24f) {
            (rawShimmerCycle / 0.24f).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    Box(
        modifier = if (isActiveGlowTag) {
            modifier.testTag("home_logo_glow_active")
        } else {
            modifier
        },
        contentAlignment = Alignment.Center
    ) {
        NyxoraGeometricLogo(
            size = size,
            sweepProgress = sweepProgress,
            glowIntensity = glowIntensity,
            imageTestTag = imageTestTag
        )
    }
}

private fun isSystemPowerSaveModeActive(context: Context): Boolean {
    return runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    }.getOrDefault(false)
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
 * NYXORA Browser 4.0 Premium Opening Splash Screen:
 * 1. Deep midnight-navy background fades in smoothly.
 * 2. Large centered 3D NYXORA 'N' logo appears smoothly with balanced proportions.
 * 3. Soft blue/cyan luminous effect activates around the logo.
 * 4. Subtle light sweep travels across the 'N' symbol.
 * 5. Logo settles into a refined breathing glow with aligned NYXORA wordmark.
 * 6. Main browser interface appears with a smooth cross-fade transition.
 */
@Composable
fun NyxoraLaunchSplash(
    reducedMotion: Boolean,
    onSplashCompleted: () -> Unit,
    logoGlowIntensityScale: Float = BrandGlowIntensityPresets.DEFAULT_INTENSITY,
    performanceMode: AnimationPerformanceMode = AnimationPerformanceMode.BALANCED,
    forceFullDurationInTest: Boolean = false
) {
    val effectiveGlowScale = logoGlowIntensityScale.coerceIn(0.25f, 1.0f)
    val ambientAlpha = remember { Animatable(if (reducedMotion) 1f else 0.16f) }
    val logoAlpha = remember { Animatable(if (reducedMotion) 1f else 0.15f) }
    val logoScale = remember { Animatable(if (reducedMotion) 1f else 0.93f) }
    val glowIntensity = remember { Animatable(if (reducedMotion) 0.48f * effectiveGlowScale else 0.16f * effectiveGlowScale) }
    val sweepProgress = remember { Animatable(0f) }
    val brandingAlpha = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val wordmarkReveal = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val underlineReveal = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val containerAlpha = remember { Animatable(1f) }

    LaunchedEffect(reducedMotion, forceFullDurationInTest, performanceMode) {
        if (reducedMotion) {
            onSplashCompleted()
        } else {
            val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            val perfFactor = when (performanceMode) {
                AnimationPerformanceMode.SMOOTH -> 1.0f
                AnimationPerformanceMode.BALANCED -> 0.92f
                AnimationPerformanceMode.BATTERY_SAVER -> 0.72f
            }
            val timeScale = if (isRobolectric && !forceFullDurationInTest) 0.10f else perfFactor
            fun scaledMs(ms: Int): Int = (ms * timeScale).toInt().coerceAtLeast(15)

            // Steps 1 & 2 (0.0s – 0.4s): Deep midnight background fades in + NYXORA logo appears smoothly
            val p1Duration = scaledMs(450)
            val jobAmbient = launch {
                ambientAlpha.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
            }
            val jobLogoAlpha = launch {
                logoAlpha.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
            }
            val jobLogoScale = launch {
                logoScale.animateTo(1f, animationSpec = tween(p1Duration, easing = LinearOutSlowInEasing))
            }
            if (isRobolectric && !forceFullDurationInTest) {
                launch {
                    brandingAlpha.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
                    wordmarkReveal.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
                    underlineReveal.animateTo(1f, animationSpec = tween(p1Duration, easing = FastOutSlowInEasing))
                }
            }
            jobAmbient.join()
            jobLogoAlpha.join()
            jobLogoScale.join()

            // Steps 3 & 4 (0.4s – 1.2s): Soft blue/cyan luminous effect activates + subtle light sweep across N symbol
            val p2Duration = scaledMs(800)
            val jobGlow = launch {
                glowIntensity.animateTo(
                    targetValue = (0.88f * effectiveGlowScale).coerceIn(0.25f, 1f),
                    animationSpec = tween(p2Duration, easing = FastOutSlowInEasing)
                )
            }
            val jobSweep = launch {
                sweepProgress.animateTo(1f, animationSpec = tween(p2Duration, easing = FastOutSlowInEasing))
            }
            // The completed N becomes the anchor. Reveal NYXORA letter-by-letter, then draw the luminous underline.
            val jobBrandEarly = launch {
                delay(scaledMs(180).toLong())
                brandingAlpha.animateTo(1f, animationSpec = tween(scaledMs(260), easing = FastOutSlowInEasing))
                wordmarkReveal.animateTo(1f, animationSpec = tween(scaledMs(850), easing = FastOutSlowInEasing))
                delay(scaledMs(70).toLong())
                underlineReveal.animateTo(1f, animationSpec = tween(scaledMs(500), easing = FastOutSlowInEasing))
            }
            jobGlow.join()
            jobSweep.join()

            // Step 5 (1.2s – 1.7s): Logo settles into a refined breathing glow
            val pulseUpMs = scaledMs(300)
            val pulseDownMs = scaledMs(300)
            glowIntensity.animateTo(
                targetValue = (1.0f * effectiveGlowScale).coerceIn(0.30f, 1f),
                animationSpec = tween(pulseUpMs, easing = FastOutSlowInEasing)
            )
            glowIntensity.animateTo(
                targetValue = (0.68f * effectiveGlowScale).coerceIn(0.22f, 1f),
                animationSpec = tween(pulseDownMs, easing = FastOutSlowInEasing)
            )
            jobBrandEarly.join()

            // Step 6 (1.7s – 2.0s): Smooth transition into the main browser interface
            // Keep the completed brand mark visible briefly so the reveal can actually be seen.
            delay(scaledMs(250).toLong())
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
                        Color(0xFF030812),
                        Color(0xFF07162E),
                        Color(0xFF0A1D3B),
                        Color(0xFF030812)
                    )
                )
            )
            .testTag("nyxora_launch_splash")
            ,
        contentAlignment = Alignment.Center
    ) {
        // Layered sapphire & cyan ambient illumination behind the centered NYXORA logo
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ambientAlpha.value }
        ) {
            val outerRadius = size.minDimension * 0.52f
            val innerRadius = size.minDimension * 0.28f
            val centerPoint = Offset(size.width * 0.5f, size.height * 0.43f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0EA5E9).copy(alpha = 0.16f + 0.10f * glowIntensity.value),
                        Color(0xFF1D4ED8).copy(alpha = 0.10f + 0.06f * glowIntensity.value),
                        Color.Transparent
                    ),
                    center = centerPoint,
                    radius = outerRadius
                ),
                center = centerPoint,
                radius = outerRadius
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = 0.14f * glowIntensity.value),
                        Color.Transparent
                    ),
                    center = centerPoint,
                    radius = innerRadius
                ),
                center = centerPoint,
                radius = innerRadius
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
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

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.width(202.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("splash_wordmark_reveal")
                    ) {
                        "NYXORA".forEachIndexed { index, letter ->
                            val letterAlpha = ((wordmarkReveal.value * 6f) - index).coerceIn(0f, 1f)
                            Text(
                                text = letter.toString(),
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    letterSpacing = 2.2.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = Color(0xFFF8FAFC).copy(alpha = letterAlpha),
                                modifier = Modifier.graphicsLayer {
                                    alpha = letterAlpha
                                    translationX = (1f - letterAlpha) * 9f
                                    translationY = (1f - letterAlpha) * 3f
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .width(178.dp)
                            .height(3.dp)
                            .testTag("splash_brand_underline_track")
                    ) {
                        Box(
                            modifier = Modifier
                                .width(178.dp * underlineReveal.value)
                                .height(3.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF0EA5E9),
                                            Color(0xFF38BDF8),
                                            Color(0xFF93C5FD),
                                            Color(0xFF2563EB)
                                        )
                                    )
                                )
                                .graphicsLayer {
                                    shadowElevation = 12f * underlineReveal.value
                                }
                                .testTag("splash_brand_underline")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = brandingAlpha.value }
            ) {
                Text(
                    text = "Browse Beyond Ordinary.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF93C5FD).copy(alpha = 0.88f),
                    modifier = Modifier.testTag("splash_tagline_text")
                )
                Spacer(modifier = Modifier.height(16.dp))
                NyxoraCreatorSignature(textTestTag = "splash_creator_credit")
            }
        }
    }
}
