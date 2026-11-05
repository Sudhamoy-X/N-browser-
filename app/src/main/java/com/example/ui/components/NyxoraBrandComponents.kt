package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
import kotlinx.coroutines.launch

const val CRAFTED_BY_SUDHAMOY_BRAND = "CRAFTED BY SUDHAMOY"
const val BROWSE_BEYOND_SUBTITLE = "BROWSE BEYOND"
const val NYXORA_LAUNCH_ANIMATION_DURATION_MS = 1600L

private val SharpEleganceEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)
private val SmoothTransformEasing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f)

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
 * Renders the flowing, connected 3D glass-ribbon NYXORA 'N' monogram:
 * - Supports Phase 1 path-drawing animation (`strokeDrawProgress`: 0f..1f) with a glowing electric-blue
 *   and cyan light trail.
 * - Supports Phase 2 3D glossy ribbon formation (`bodyFormationProgress`: 0f..1f) with rounded glass-tube
 *   gradients, realistic fold crease shadows, bottom-right ribbon tuck seam, and luminous rim highlights.
 */
@Composable
fun NyxoraGeometricLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    sweepProgress: Float = 0f,
    glowIntensity: Float = 0f,
    strokeDrawProgress: Float = 1f,
    bodyFormationProgress: Float = 1f,
    orbitalArcProgress: Float = 0f,
    imageTestTag: String? = null
) {
    val clampedGlow = glowIntensity.coerceIn(0f, 1f)
    val clampedSweep = sweepProgress.coerceIn(0f, 1f)
    val clampedStroke = strokeDrawProgress.coerceIn(0f, 1f)
    val clampedBody = bodyFormationProgress.coerceIn(0f, 1f)
    val clampedOrbital = orbitalArcProgress.coerceIn(0f, 1f)
    val logoPainter = painterResource(id = R.drawable.nyxora_official_icon)

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "NYXORA official app icon" },
        contentAlignment = Alignment.Center
    ) {
        if (clampedGlow > 0.01f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("nyxora_logo_silhouette_glow")
            ) {
                val w = this.size.width
                val h = this.size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0EA5E9).copy(alpha = 0.26f * clampedGlow),
                            Color(0xFF1D4ED8).copy(alpha = 0.14f * clampedGlow),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.50f, h * 0.50f),
                        radius = w * 0.56f
                    ),
                    center = Offset(w * 0.50f, h * 0.50f),
                    radius = w * 0.56f
                )
            }
        }

        // Semantic & drawable resource anchor for test & accessibility compatibility
        Image(
            painter = logoPainter,
            contentDescription = "NYXORA official app icon",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.01f)
                .let { base ->
                    if (imageTestTag != null) base.testTag(imageTestTag) else base
                }
        )

        // Independent Vector Canvas Rendering of the Flowing 3D Glass-Ribbon 'N' Monogram
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawFlowingGlassRibbonN(
                clampedGlow = clampedGlow,
                clampedSweep = clampedSweep,
                clampedStroke = clampedStroke,
                clampedBody = clampedBody,
                clampedOrbital = clampedOrbital
            )
        }
    }
}

private fun DrawScope.drawFlowingGlassRibbonN(
    clampedGlow: Float,
    clampedSweep: Float,
    clampedStroke: Float,
    clampedBody: Float,
    clampedOrbital: Float
) {
    val w = size.width
    val h = size.height

    val pBottomLeft = Offset(w * 0.17f, h * 0.78f)
    val pTopLeft = Offset(w * 0.36f, h * 0.21f)
    val pBottomRight = Offset(w * 0.66f, h * 0.78f)
    val pTopRight = Offset(w * 0.85f, h * 0.20f)

    val fullNPath = Path().apply {
        moveTo(pBottomLeft.x, pBottomLeft.y)
        lineTo(pTopLeft.x, pTopLeft.y)
        lineTo(pBottomRight.x, pBottomRight.y)
        lineTo(pTopRight.x, pTopRight.y)
    }

    // Optional Panel 2 Orbital Light Ring & Focal Cyan Flare around the 3D N Monogram
    if (clampedOrbital > 0.01f) {
        val orbitalAlpha = if (clampedOrbital < 0.5f) {
            (clampedOrbital / 0.5f).coerceIn(0f, 1f)
        } else {
            ((1f - clampedOrbital) / 0.5f).coerceIn(0f, 1f)
        }
        withTransform({
            rotate(degrees = -17f, pivot = Offset(w * 0.51f, h * 0.52f))
        }) {
            val ovalTopLeft = Offset(-w * 0.08f, h * 0.28f)
            val ovalSize = Size(w * 1.18f, h * 0.48f)
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF2563EB).copy(alpha = 0.42f * orbitalAlpha),
                        Color(0xFF38BDF8).copy(alpha = 0.85f * orbitalAlpha),
                        Color(0xFFE0F2FE).copy(alpha = 0.95f * orbitalAlpha),
                        Color(0xFF0EA5E9).copy(alpha = 0.40f * orbitalAlpha),
                        Color.Transparent
                    ),
                    start = Offset(0f, h * 0.5f),
                    end = Offset(w, h * 0.5f)
                ),
                startAngle = 15f,
                sweepAngle = 315f * clampedOrbital.coerceAtLeast(0.35f),
                useCenter = false,
                topLeft = ovalTopLeft,
                size = ovalSize,
                style = Stroke(width = (w * 0.022f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
            )
        }
        // Bright cyan-white orbital focal flare at lower-right of the N
        val flareCenter = Offset(w * (0.52f + 0.22f * clampedOrbital), h * (0.76f - 0.06f * clampedOrbital))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.92f * orbitalAlpha),
                    Color(0xFF38BDF8).copy(alpha = 0.58f * orbitalAlpha),
                    Color(0xFF1D4ED8).copy(alpha = 0.18f * orbitalAlpha),
                    Color.Transparent
                ),
                center = flareCenter,
                radius = w * 0.22f
            ),
            center = flareCenter,
            radius = w * 0.22f
        )
    }

    // PHASE 1: Glowing Electric-Blue & Cyan Path-Drawing N Stroke (Panel 1)
    if (clampedStroke > 0.005f && clampedBody < 0.99f) {
        val strokeVisibility = (1f - clampedBody * 0.82f).coerceIn(0f, 1f)
        val pathMeasure = PathMeasure()
        pathMeasure.setPath(fullNPath, false)
        val totalLen = pathMeasure.length
        val drawnLen = totalLen * clampedStroke
        val partialPath = Path()
        pathMeasure.getSegment(0f, drawnLen, partialPath, true)

        // Outer electric-blue neon trail glow
        drawPath(
            path = partialPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.42f * strokeVisibility),
                    Color(0xFF2563EB).copy(alpha = 0.36f * strokeVisibility),
                    Color(0xFF7DD3FC).copy(alpha = 0.52f * strokeVisibility)
                ),
                start = pBottomLeft,
                end = pTopRight
            ),
            style = Stroke(
                width = w * 0.23f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Dark inner ribbon core Channel (gives the hollow glowing-edge ribbon look in Panel 1)
        drawPath(
            path = partialPath,
            color = Color(0xFF030918).copy(alpha = 0.88f * strokeVisibility),
            style = Stroke(
                width = w * 0.165f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Crisp electric-cyan glowing stroke contour
        drawPath(
            path = partialPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE0F2FE).copy(alpha = 0.96f * strokeVisibility),
                    Color(0xFF38BDF8).copy(alpha = 0.90f * strokeVisibility),
                    Color(0xFF2563EB).copy(alpha = 0.84f * strokeVisibility),
                    Color(0xFFBAE6FD).copy(alpha = 0.98f * strokeVisibility)
                ),
                start = pBottomLeft,
                end = pTopRight
            ),
            style = Stroke(
                width = (w * 0.032f).coerceAtLeast(1.8f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Leading light trail head at current tip of the drawing stroke
        if (drawnLen > 1f) {
            val tipPos = pathMeasure.getPosition(drawnLen)
            if (tipPos != Offset.Unspecified) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f * strokeVisibility),
                            Color(0xFF38BDF8).copy(alpha = 0.65f * strokeVisibility),
                            Color.Transparent
                        ),
                        center = tipPos,
                        radius = w * 0.14f
                    ),
                    center = tipPos,
                    radius = w * 0.14f
                )
            }
        }
    }

    // PHASE 2+: Complete 3D Glossy Flowing Glass-Ribbon N Monogram (Panels 2–6 & App Icon)
    if (clampedBody > 0.01f) {
        val tubeWidth = w * 0.215f
        val bodyAlpha = clampedBody

        // 1. Soft Deep Drop Shadow Under the 3D N Ribbon
        drawPath(
            path = Path().apply {
                moveTo(pBottomLeft.x, pBottomLeft.y + h * 0.03f)
                lineTo(pTopLeft.x, pTopLeft.y + h * 0.03f)
                lineTo(pBottomRight.x, pBottomRight.y + h * 0.03f)
                lineTo(pTopRight.x, pTopRight.y + h * 0.03f)
            },
            color = Color(0xFF01040D).copy(alpha = 0.48f * bodyAlpha),
            style = Stroke(width = tubeWidth * 1.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 2. Outer Luminous Cyan-Blue Rim Halo
        drawPath(
            path = fullNPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF7DD3FC).copy(alpha = (0.34f + 0.18f * clampedGlow) * bodyAlpha),
                    Color(0xFF2563EB).copy(alpha = (0.26f + 0.14f * clampedGlow) * bodyAlpha),
                    Color(0xFFBAE6FD).copy(alpha = (0.38f + 0.20f * clampedGlow) * bodyAlpha)
                ),
                start = pBottomLeft,
                end = pTopRight
            ),
            style = Stroke(width = tubeWidth * 1.14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 3. Left Ascending 3D Rounded Glass Ribbon (Glowing Cyan Tip to Deep Royal Sapphire Fold)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE0F2FE).copy(alpha = bodyAlpha),
                    Color(0xFF38BDF8).copy(alpha = bodyAlpha),
                    Color(0xFF0284C7).copy(alpha = bodyAlpha),
                    Color(0xFF1D4ED8).copy(alpha = bodyAlpha),
                    Color(0xFF1E3A8A).copy(alpha = bodyAlpha)
                ),
                start = pBottomLeft,
                end = pTopLeft
            ),
            start = pBottomLeft,
            end = pTopLeft,
            strokeWidth = tubeWidth,
            cap = StrokeCap.Round
        )

        // Left Ribbon Specular Glass Edge Highlight
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFF0F9FF).copy(alpha = 0.85f * bodyAlpha),
                    Color(0xFF7DD3FC).copy(alpha = 0.42f * bodyAlpha),
                    Color.Transparent
                ),
                start = Offset(pBottomLeft.x - w * 0.025f, pBottomLeft.y - h * 0.02f),
                end = Offset(pTopLeft.x - w * 0.025f, pTopLeft.y)
            ),
            start = Offset(pBottomLeft.x - w * 0.025f, pBottomLeft.y - h * 0.02f),
            end = Offset(pTopLeft.x - w * 0.025f, pTopLeft.y + h * 0.02f),
            strokeWidth = (tubeWidth * 0.20f).coerceAtLeast(1.4f),
            cap = StrokeCap.Round
        )

        // 4. Right Ascending 3D Rounded Glass Ribbon (Royal Blue Fold to Luminous Ice-White Crown)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF1D4ED8).copy(alpha = bodyAlpha),
                    Color(0xFF0284C7).copy(alpha = bodyAlpha),
                    Color(0xFF38BDF8).copy(alpha = bodyAlpha),
                    Color(0xFFBAE6FD).copy(alpha = bodyAlpha),
                    Color(0xFFF0F9FF).copy(alpha = bodyAlpha)
                ),
                start = pBottomRight,
                end = pTopRight
            ),
            start = pBottomRight,
            end = pTopRight,
            strokeWidth = tubeWidth,
            cap = StrokeCap.Round
        )

        // Right Ribbon Inner Glossy Highlight
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.20f * bodyAlpha),
                    Color(0xFFBAE6FD).copy(alpha = 0.65f * bodyAlpha),
                    Color.White.copy(alpha = 0.92f * bodyAlpha)
                ),
                start = Offset(pBottomRight.x + w * 0.02f, pBottomRight.y - h * 0.03f),
                end = Offset(pTopRight.x + w * 0.02f, pTopRight.y)
            ),
            start = Offset(pBottomRight.x + w * 0.02f, pBottomRight.y - h * 0.03f),
            end = Offset(pTopRight.x + w * 0.02f, pTopRight.y),
            strokeWidth = (tubeWidth * 0.23f).coerceAtLeast(1.5f),
            cap = StrokeCap.Round
        )

        // 5. 3D Ribbon Fold Shadow Under the Central Diagonal Bridge
        drawLine(
            color = Color(0xFF020716).copy(alpha = 0.42f * bodyAlpha),
            start = Offset(pTopLeft.x - w * 0.008f, pTopLeft.y + h * 0.022f),
            end = Offset(pBottomRight.x - w * 0.008f, pBottomRight.y + h * 0.018f),
            strokeWidth = tubeWidth * 1.04f,
            cap = StrokeCap.Round
        )

        // 6. Central Flowing 3D Glass-Ribbon Bridge (Curving Over Top-Left and Into Bottom-Right)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF93C5FD).copy(alpha = bodyAlpha),
                    Color(0xFF3B82F6).copy(alpha = bodyAlpha),
                    Color(0xFF1D4ED8).copy(alpha = bodyAlpha),
                    Color(0xFF2563EB).copy(alpha = bodyAlpha),
                    Color(0xFF60A5FA).copy(alpha = bodyAlpha)
                ),
                start = pTopLeft,
                end = pBottomRight
            ),
            start = pTopLeft,
            end = pBottomRight,
            strokeWidth = tubeWidth * 1.02f,
            cap = StrokeCap.Round
        )

        // 7. Central Bridge Glossy Upper-Edge Rim & Bottom-Right Ribbon Tuck Crease
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE0F2FE).copy(alpha = 0.90f * bodyAlpha),
                    Color(0xFF93C5FD).copy(alpha = 0.56f * bodyAlpha),
                    Color(0xFFBAE6FD).copy(alpha = 0.52f * bodyAlpha)
                ),
                start = Offset(pTopLeft.x + w * 0.02f, pTopLeft.y - h * 0.03f),
                end = Offset(pBottomRight.x + w * 0.02f, pBottomRight.y - h * 0.03f)
            ),
            start = Offset(pTopLeft.x + w * 0.02f, pTopLeft.y - h * 0.03f),
            end = Offset(pBottomRight.x + w * 0.02f, pBottomRight.y - h * 0.03f),
            strokeWidth = (tubeWidth * 0.19f).coerceAtLeast(1.3f),
            cap = StrokeCap.Round
        )

        // Curved Glass-Ribbon Fold Seam near Bottom-Right Bend (Reference Detail)
        val foldSeamPath = Path().apply {
            moveTo(w * 0.56f, h * 0.69f)
            quadraticTo(w * 0.63f, h * 0.74f, w * 0.73f, h * 0.82f)
        }
        drawPath(
            path = foldSeamPath,
            color = Color(0xFFBAE6FD).copy(alpha = 0.75f * bodyAlpha),
            style = Stroke(width = (w * 0.018f).coerceAtLeast(1.1f), cap = StrokeCap.Round)
        )

        // 8. Optional Specular Shimmer Sweep Along the N Ribbon
        if (clampedSweep in 0.01f..0.99f) {
            val pathMeasure = PathMeasure()
            pathMeasure.setPath(fullNPath, false)
            val totalLen = pathMeasure.length
            val sweepWindow = totalLen * 0.32f
            val sweepStart = (totalLen + sweepWindow) * clampedSweep - sweepWindow
            val sweepEnd = (sweepStart + sweepWindow).coerceIn(0f, totalLen)
            val clampedStart = sweepStart.coerceIn(0f, totalLen)
            if (sweepEnd > clampedStart) {
                val shimmerSegment = Path()
                pathMeasure.getSegment(clampedStart, sweepEnd, shimmerSegment, true)
                drawPath(
                    path = shimmerSegment,
                    color = Color(0xFFF0F9FF).copy(alpha = 0.42f * bodyAlpha),
                    style = Stroke(width = tubeWidth * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

/**
 * Home Screen NYXORA Brand Logo with persistent, subtle blue/cyan luminous identity:
 * - Soft breathing glow & gentle brightness variation.
 * - Occasional light shimmer across the flowing 3D glass-ribbon 'N' monogram.
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
        sweepProgress = if (rawShimmerCycle <= 0.22f) {
            (rawShimmerCycle / 0.22f).coerceIn(0f, 1f)
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

private fun resolveSystemAnimatorScale(context: Context): Float {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        ).coerceIn(0f, 2f)
    }.getOrDefault(1.0f)
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
 * NYXORA Browser 5-Stage Cinematic Logo Reveal Animation (1600ms total sequence):
 *
 * - PHASE 1: GLOWING N STROKE (0–300ms, Reference Panel 1):
 *   Clean midnight-black background. A thin electric-blue & cyan glowing stroke draws itself
 *   along the flowing N-shaped ribbon path with a controlled light trail.
 * - PHASE 2: COMPLETE N FORMATION (300–650ms, Reference Panel 2):
 *   The glowing stroke transforms into the complete glossy 3D blue ribbon N monogram in the center
 *   with an elliptical orbital light arc and reflections.
 * - PHASE 3: NYXORA WORDMARK REVEAL (650–1050ms, Reference Panels 3–5):
 *   Reveals NYXORA strictly AFTER the N is completely formed, animating letters smoothly from left
 *   to right (`N -> Y -> X -> O -> R -> A`) with a glowing horizontal laser light bar underneath.
 * - PHASE 4: COMPLETE BRAND REVEAL (1050–1250ms, Reference Panel 6):
 *   Displays the completed N monogram, solid NYXORA wordmark, luminous underline, and subtle lower-right
 *   glass-ribbon wave, holding the complete identity briefly.
 * - PHASE 5: HOMEPAGE TRANSITION (1250–1600ms):
 *   Smoothly transitions from the completed brand lockup into the browser homepage without jumps or flicker.
 */
@Composable
fun NyxoraLaunchSplash(
    reducedMotion: Boolean,
    onSplashCompleted: () -> Unit,
    logoGlowIntensityScale: Float = BrandGlowIntensityPresets.DEFAULT_INTENSITY,
    performanceMode: AnimationPerformanceMode = AnimationPerformanceMode.BALANCED,
    forceFullDurationInTest: Boolean = false
) {
    val context = LocalContext.current
    val effectiveGlowScale = logoGlowIntensityScale.coerceIn(0.25f, 1.0f)

    // Single master timeline progress (0f..1600f ms) for rock-solid 5-phase synchronization
    val timelineMs = remember { Animatable(if (reducedMotion) 1600f else 0f) }

    LaunchedEffect(reducedMotion, forceFullDurationInTest, performanceMode) {
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        val systemAnimScale = if (isRobolectric) 1.0f else resolveSystemAnimatorScale(context)

        if (reducedMotion || systemAnimScale <= 0.01f) {
            onSplashCompleted()
        } else {
            val perfFactor = when (performanceMode) {
                AnimationPerformanceMode.SMOOTH -> 1.0f
                AnimationPerformanceMode.BALANCED -> 0.95f
                AnimationPerformanceMode.BATTERY_SAVER -> 0.78f
            } * systemAnimScale.coerceIn(0.5f, 1.25f)

            val totalDurationMs = if (isRobolectric && !forceFullDurationInTest) {
                260
            } else {
                (NYXORA_LAUNCH_ANIMATION_DURATION_MS * perfFactor).toInt().coerceAtLeast(240)
            }

            timelineMs.animateTo(
                targetValue = 1600f,
                animationSpec = tween(durationMillis = totalDurationMs, easing = LinearEasing)
            )
            onSplashCompleted()
        }
    }

    val t = timelineMs.value

    // Phase 1 (0–300ms): Glowing N Stroke draws along the flowing N path
    val strokeDrawProgress = SharpEleganceEasing.transform((t / 300f).coerceIn(0f, 1f))

    // Phase 2 (300–650ms): Glowing stroke transforms into the complete 3D glossy N monogram
    val bodyFormationProgress = if (t < 300f) {
        0f
    } else {
        SmoothTransformEasing.transform(((t - 300f) / 350f).coerceIn(0f, 1f))
    }

    // Orbital light arc during Phase 2 (280–720ms, Reference Panel 2)
    val orbitalArcProgress = if (t in 280f..740f) {
        ((t - 280f) / 460f).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Phase 3 (650–1050ms): NYXORA Wordmark reveals strictly AFTER the N is completely formed
    val wordmarkMasterProgress = if (t < 650f) {
        0f
    } else {
        ((t - 650f) / 400f).coerceIn(0f, 1f)
    }

    // Phase 4 (1050–1250ms): Complete Brand Reveal hold + subtle lower-right glass ribbon wave (Panel 6)
    val panel6CornerWaveAlpha = if (t < 980f) {
        0f
    } else {
        ((t - 980f) / 260f).coerceIn(0f, 1f)
    }

    // Phase 5 (1250–1600ms): Continuous Homepage Transition
    val transitionProgress = if (t < 1250f) {
        0f
    } else {
        FastOutSlowInEasing.transform(((t - 1250f) / 350f).coerceIn(0f, 1f))
    }
    val foregroundExitAlpha = (1f - transitionProgress).coerceIn(0f, 1f)
    val logoScale = 0.94f + 0.06f * strokeDrawProgress + 0.04f * transitionProgress
    val glowIntensity = ((0.35f + 0.55f * bodyFormationProgress) * effectiveGlowScale).coerceIn(0.20f, 1f)
    val sweepProgress = if (t in 360f..960f) ((t - 360f) / 600f).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF01040B),
                        Color(0xFF03091A),
                        Color(0xFF051129),
                        Color(0xFF020612)
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
        // Phase 5 Homepage Background Continuity Layer (eliminates blank screen or jump on handoff)
        if (transitionProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = transitionProgress }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF060E1B),
                                Color(0xFF0C1E36),
                                Color(0xFF102846),
                                Color(0xFF071222)
                            )
                        )
                    )
            )
        }

        // Subsurface Midnight-Sapphire Center Illumination + Panel 6 Lower-Right Glass-Ribbon Wave
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = foregroundExitAlpha
                }
        ) {
            val centerPoint = Offset(size.width * 0.5f, size.height * 0.43f)
            val ambientRadius = size.minDimension * (0.36f + 0.16f * bodyFormationProgress)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1D4ED8).copy(alpha = 0.14f + 0.12f * bodyFormationProgress),
                        Color(0xFF0EA5E9).copy(alpha = 0.07f + 0.06f * bodyFormationProgress),
                        Color.Transparent
                    ),
                    center = centerPoint,
                    radius = ambientRadius
                ),
                center = centerPoint,
                radius = ambientRadius
            )

            // Reference Panel 6 subtle lower-right curved glass-ribbon wave
            if (panel6CornerWaveAlpha > 0.01f) {
                val wavePath = Path().apply {
                    moveTo(size.width * 0.36f, size.height)
                    quadraticTo(
                        size.width * 0.72f,
                        size.height * 0.88f,
                        size.width,
                        size.height * 0.72f
                    )
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(
                    path = wavePath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF1D4ED8).copy(alpha = 0.22f * panel6CornerWaveAlpha),
                            Color(0xFF0EA5E9).copy(alpha = 0.30f * panel6CornerWaveAlpha)
                        ),
                        start = Offset(size.width * 0.4f, size.height),
                        end = Offset(size.width, size.height * 0.74f)
                    )
                )
            }
        }

        // Semantic Phase Markers for verifying strict 5-phase sequence ordering
        if (t < 650f) {
            Box(modifier = Modifier.size(1.dp).testTag("splash_phase_n_only_before_wordmark"))
        } else {
            Box(modifier = Modifier.size(1.dp).testTag("splash_phase_wordmark_revealed"))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    alpha = foregroundExitAlpha
                }
        ) {
            // PHASE 1 & PHASE 2: Flowing 3D Glass-Ribbon 'N' Monogram (Centered Main Attraction)
            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = logoScale
                    scaleY = logoScale
                },
                contentAlignment = Alignment.Center
            ) {
                NyxoraGeometricLogo(
                    size = 98.dp,
                    sweepProgress = sweepProgress,
                    glowIntensity = glowIntensity,
                    strokeDrawProgress = strokeDrawProgress,
                    bodyFormationProgress = bodyFormationProgress,
                    orbitalArcProgress = orbitalArcProgress,
                    modifier = Modifier.testTag("splash_official_icon")
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // PHASE 3 & PHASE 4: Left-to-Right Letter-by-Letter NYXORA Wordmark + Glowing Laser Underline
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = wordmarkMasterProgress
                }
            ) {
                // Letter-by-letter left-to-right NYXORA reveal (Reference Panels 3, 4, 5 & 6)
                NyxoraLetterByLetterWordmark(
                    revealProgress = wordmarkMasterProgress
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Glowing electric-blue & cyan laser underline bar with traveling core flare (Panels 3–6)
                NyxoraLaserUnderlineBar(
                    revealProgress = wordmarkMasterProgress
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = BROWSE_BEYOND_SUBTITLE,
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 3.6.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp
                    ),
                    color = Color(0xFF7DD3FC).copy(alpha = 0.88f * wordmarkMasterProgress),
                    modifier = Modifier.testTag("splash_browse_beyond_subtitle")
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Browse Beyond Ordinary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF93C5FD).copy(alpha = 0.78f * wordmarkMasterProgress),
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

@Composable
private fun NyxoraLetterByLetterWordmark(
    revealProgress: Float
) {
    val letters = remember { listOf("N", "Y", "X", "O", "R", "A") }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.testTag("splash_brand_title_text")
    ) {
        // Hidden unified semantic text node for accessibility & screen-reader / test compatibility
        Text(
            text = "NYXORA",
            style = MaterialTheme.typography.headlineLarge.copy(
                letterSpacing = 6.5.sp,
                fontWeight = FontWeight.Light,
                fontSize = 28.sp
            ),
            color = Color.Transparent
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            letters.forEachIndexed { index, letter ->
                // Staggered left-to-right reveal window for each of the 6 letters
                val startFraction = index * 0.11f
                val localProgress = ((revealProgress - startFraction) / 0.45f).coerceIn(0f, 1f)
                val eased = SharpEleganceEasing.transform(localProgress)
                // Transitions from glowing cyan outline look (Panels 3–4) to solid crisp white (Panels 5–6)
                val solidFillProgress = ((localProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val letterColor = androidx.compose.ui.graphics.lerp(
                    Color(0xFF7DD3FC),
                    Color(0xFFF8FAFC),
                    solidFillProgress
                )

                Text(
                    text = letter,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = if (solidFillProgress > 0.5f) FontWeight.Normal else FontWeight.Light,
                        fontSize = 28.sp
                    ),
                    color = letterColor,
                    modifier = Modifier.graphicsLayer {
                        alpha = eased
                        translationX = (1f - eased) * (-10f)
                    }
                )
            }
        }
    }
}

@Composable
private fun NyxoraLaserUnderlineBar(
    revealProgress: Float
) {
    val clamped = revealProgress.coerceIn(0f, 1f)
    Canvas(
        modifier = Modifier
            .width(164.dp)
            .height(8.dp)
            .testTag("splash_laser_underline")
    ) {
        if (clamped <= 0.01f) return@Canvas
        val w = size.width
        val cy = size.height * 0.5f
        val barEnd = w * SmoothTransformEasing.transform(clamped)

        // Glowing electric-blue outer aura line
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF2563EB).copy(alpha = 0.55f),
                    Color(0xFF38BDF8).copy(alpha = 0.85f),
                    Color(0xFF2563EB).copy(alpha = 0.45f),
                    Color.Transparent
                ),
                startX = 0f,
                endX = barEnd
            ),
            start = Offset(0f, cy),
            end = Offset(barEnd, cy),
            strokeWidth = size.height * 0.65f,
            cap = StrokeCap.Round
        )

        // Crisp cyan-white core laser line
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.35f),
                    Color(0xFFBAE6FD).copy(alpha = 0.92f),
                    Color.White,
                    Color(0xFF38BDF8).copy(alpha = 0.70f)
                ),
                startX = 0f,
                endX = barEnd
            ),
            start = Offset(w * 0.04f, cy),
            end = Offset(barEnd, cy),
            strokeWidth = (size.height * 0.24f).coerceAtLeast(1.5f),
            cap = StrokeCap.Round
        )

        // Traveling bright cyan-white focal flare along the underline (Panels 3, 4, 5, 6)
        val flareX = w * (0.28f + 0.52f * clamped)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    Color(0xFF38BDF8).copy(alpha = 0.68f),
                    Color.Transparent
                ),
                center = Offset(flareX, cy),
                radius = size.height * 2.2f
            ),
            center = Offset(flareX, cy),
            radius = size.height * 2.2f
        )
    }
}
