package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Gentle momentum-based scrolling physics for the NYXORA Browser main content area.
 *
 * Balances a lower-friction exponential decay glide with soft-knee velocity shaping so
 * vertical navigation across the Home Screen, WebView pages, and Reader Mode feels fluid,
 * weighted, and controlled without abrupt stops or jarring high-velocity jumps.
 */
object NyxoraMomentumScrollPhysics {
    const val DEFAULT_FRICTION_MULTIPLIER = 0.72f
    const val REDUCED_MOTION_FRICTION_MULTIPLIER = 1.15f
    const val VELOCITY_SCALE_FACTOR = 0.86f
    const val REDUCED_MOTION_VELOCITY_SCALE = 0.75f
    const val MAX_FLING_VELOCITY_PX_PER_SEC = 5400f
    const val MIN_ABS_VELOCITY_THRESHOLD = 0.35f
    const val MAX_ELASTIC_OVERSCROLL_PX = 24f

    /**
     * Shapes raw touch release velocity using a gentle scale factor and a soft-knee hyperbolic
     * saturation curve (`tanh`) so moderate swipes glide smoothly while extreme flick spikes
     * are softly cushioned.
     */
    fun computeGentleMomentumVelocity(
        initialVelocity: Float,
        reducedMotion: Boolean = false,
        maxVelocityPxPerSec: Float = MAX_FLING_VELOCITY_PX_PER_SEC
    ): Float {
        if (!initialVelocity.isFinite() || abs(initialVelocity) < 1f) return 0f
        val safeMax = maxVelocityPxPerSec.coerceAtLeast(500f)
        val scale = if (reducedMotion) REDUCED_MOTION_VELOCITY_SCALE else VELOCITY_SCALE_FACTOR
        val scaled = initialVelocity * scale
        return (safeMax * tanh((scaled / safeMax).toDouble())).toFloat()
    }

    /**
     * Shapes WebView fling velocities (in pixels/second) for smooth, weighted page momentum.
     */
    fun computeGentleWebViewFlingVelocity(
        velocityPxPerSec: Int,
        reducedMotion: Boolean = false
    ): Int {
        return computeGentleMomentumVelocity(
            initialVelocity = velocityPxPerSec.toFloat(),
            reducedMotion = reducedMotion
        ).roundToInt()
    }

    /**
     * Computes a subtle, damped vertical overscroll offset when momentum reaches the top or
     * bottom boundary of the main browser content area.
     */
    fun computeElasticOverscrollOffset(
        rawOverscrollPx: Float,
        reducedMotion: Boolean = false,
        maxOverscrollPx: Float = MAX_ELASTIC_OVERSCROLL_PX
    ): Float {
        if (reducedMotion || !rawOverscrollPx.isFinite() || abs(rawOverscrollPx) < 0.5f) return 0f
        val safeMax = maxOverscrollPx.coerceAtLeast(1f)
        val normalized = rawOverscrollPx / (safeMax * 2.8f)
        return (safeMax * tanh(normalized.toDouble())).toFloat()
    }
}

/**
 * Custom [FlingBehavior] that applies [NyxoraMomentumScrollPhysics] velocity shaping and a
 * low-friction exponential decay curve for a smooth, weighted momentum glide.
 */
class NyxoraGentleMomentumFlingBehavior(
    private val decaySpec: DecayAnimationSpec<Float>,
    private val reducedMotion: Boolean = false
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val shapedVelocity = NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(
            initialVelocity = initialVelocity,
            reducedMotion = reducedMotion
        )
        if (abs(shapedVelocity) <= 1f) {
            return 0f
        }

        var velocityLeft = shapedVelocity
        var lastValue = 0f
        val animationState = AnimationState(
            initialValue = 0f,
            initialVelocity = shapedVelocity
        )

        animationState.animateDecay(decaySpec) {
            val delta = value - lastValue
            val consumed = scrollBy(delta)
            lastValue = value
            velocityLeft = this.velocity
            // Stop decay cleanly if the scroll container hits its top or bottom boundary
            if (abs(delta - consumed) > 0.5f) {
                this.cancelAnimation()
            }
        }
        return velocityLeft
    }
}

@Composable
fun rememberNyxoraGentleMomentumFlingBehavior(
    reducedMotion: Boolean = false
): FlingBehavior {
    val friction = if (reducedMotion) {
        NyxoraMomentumScrollPhysics.REDUCED_MOTION_FRICTION_MULTIPLIER
    } else {
        NyxoraMomentumScrollPhysics.DEFAULT_FRICTION_MULTIPLIER
    }
    return remember(reducedMotion, friction) {
        val decay: DecayAnimationSpec<Float> = exponentialDecay(
            frictionMultiplier = friction,
            absVelocityThreshold = NyxoraMomentumScrollPhysics.MIN_ABS_VELOCITY_THRESHOLD
        )
        NyxoraGentleMomentumFlingBehavior(
            decaySpec = decay,
            reducedMotion = reducedMotion
        )
    }
}

/**
 * Coordinates gentle vertical momentum and subtle boundary settling across the main browser
 * content container without interfering with horizontal edge-swipe navigation or pull-to-refresh.
 */
class NyxoraBrowserMomentumNestedScrollConnection(
    private val coroutineScope: CoroutineScope,
    private val reducedMotionProvider: () -> Boolean,
    private val onOverscrollOffsetChanged: (Float) -> Unit
) : NestedScrollConnection {

    private var rawAccumulatedOverscroll = 0f
    private val settleAnimatable = Animatable(0f)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && abs(rawAccumulatedOverscroll) > 0.5f) {
            val reversesOverscroll = (rawAccumulatedOverscroll > 0f && available.y < 0f) ||
                (rawAccumulatedOverscroll < 0f && available.y > 0f)
            if (reversesOverscroll) {
                val previous = rawAccumulatedOverscroll
                val next = previous + available.y
                rawAccumulatedOverscroll = if (sign(previous) != sign(next)) 0f else next
                val damped = NyxoraMomentumScrollPhysics.computeElasticOverscrollOffset(
                    rawOverscrollPx = rawAccumulatedOverscroll,
                    reducedMotion = reducedMotionProvider()
                )
                onOverscrollOffsetChanged(damped)
                val consumedY = (previous - rawAccumulatedOverscroll).coerceIn(
                    minimumValue = minOf(available.y, 0f),
                    maximumValue = maxOf(available.y, 0f)
                )
                return Offset(0f, consumedY)
            }
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (reducedMotionProvider()) {
            if (rawAccumulatedOverscroll != 0f) {
                rawAccumulatedOverscroll = 0f
                onOverscrollOffsetChanged(0f)
            }
            return Offset.Zero
        }

        // Apply subtle elastic boundary cushion when scrolling upward at the bottom boundary
        // (Downward pull at top is reserved for PullToRefreshBox on web pages)
        if (source == NestedScrollSource.UserInput && available.y < -0.5f && abs(available.y) > abs(available.x)) {
            rawAccumulatedOverscroll = (rawAccumulatedOverscroll + available.y * 0.45f)
                .coerceIn(-NyxoraMomentumScrollPhysics.MAX_ELASTIC_OVERSCROLL_PX * 3f, 0f)
            val damped = NyxoraMomentumScrollPhysics.computeElasticOverscrollOffset(
                rawOverscrollPx = rawAccumulatedOverscroll,
                reducedMotion = false
            )
            onOverscrollOffsetChanged(damped)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val rawY = available.y
        if (!rawY.isFinite() || abs(rawY) < 1f) return Velocity.Zero
        val shapedY = NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(
            initialVelocity = rawY,
            reducedMotion = reducedMotionProvider()
        )
        // Consume the excess spike velocity above the gentle momentum curve so child scrollables
        // receive the smoothed velocity profile.
        val excessY = rawY - shapedY
        return Velocity(0f, excessY)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (abs(rawAccumulatedOverscroll) > 0.5f) {
            val startOffset = NyxoraMomentumScrollPhysics.computeElasticOverscrollOffset(
                rawOverscrollPx = rawAccumulatedOverscroll,
                reducedMotion = reducedMotionProvider()
            )
            rawAccumulatedOverscroll = 0f
            coroutineScope.launch {
                settleAnimatable.snapTo(startOffset)
                settleAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) {
                    onOverscrollOffsetChanged(value)
                }
                onOverscrollOffsetChanged(0f)
            }
        } else {
            rawAccumulatedOverscroll = 0f
            onOverscrollOffsetChanged(0f)
        }
        return Velocity.Zero
    }
}

@Composable
fun rememberNyxoraBrowserMomentumConnection(
    reducedMotion: Boolean,
    onOverscrollOffsetChanged: (Float) -> Unit
): NestedScrollConnection {
    val scope = rememberCoroutineScope()
    return remember(scope, reducedMotion) {
        NyxoraBrowserMomentumNestedScrollConnection(
            coroutineScope = scope,
            reducedMotionProvider = { reducedMotion },
            onOverscrollOffsetChanged = onOverscrollOffsetChanged
        )
    }
}
