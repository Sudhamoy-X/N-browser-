package com.example

import com.example.ui.components.NyxoraMomentumScrollPhysics
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/**
 * Local unit tests for NYXORA Browser core utilities and momentum scrolling physics.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun gentleMomentumScrollPhysics_shapesVelocityAndDampsOverscrollSmoothly() {
        // 1. Sub-threshold and non-finite velocities return 0f cleanly
        assertEquals(0f, NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(0.4f), 0.001f)
        assertEquals(0f, NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(Float.NaN), 0.001f)

        // 2. Moderate swipe velocity is gently scaled and preserves direction
        val moderatePositive = NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(1200f)
        val moderateNegative = NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(-1200f)
        assertTrue(moderatePositive in 800f..1100f)
        assertEquals(-moderatePositive, moderateNegative, 0.01f)

        // 3. Extreme flick spike is softly saturated below MAX_FLING_VELOCITY_PX_PER_SEC
        val extremeFlick = NyxoraMomentumScrollPhysics.computeGentleMomentumVelocity(25000f)
        assertTrue(extremeFlick > 4500f && extremeFlick <= NyxoraMomentumScrollPhysics.MAX_FLING_VELOCITY_PX_PER_SEC)

        // 4. WebView fling velocity scaling matches gentle momentum curve and respects reduced motion
        val webNormal = NyxoraMomentumScrollPhysics.computeGentleWebViewFlingVelocity(2400, reducedMotion = false)
        val webReduced = NyxoraMomentumScrollPhysics.computeGentleWebViewFlingVelocity(2400, reducedMotion = true)
        assertTrue(webNormal in 1400..2200)
        assertTrue(abs(webReduced) < abs(webNormal))

        // 5. Elastic overscroll offset is smoothly damped and disabled in reducedMotion mode
        val overscrollNormal = NyxoraMomentumScrollPhysics.computeElasticOverscrollOffset(-60f, reducedMotion = false)
        val overscrollReduced = NyxoraMomentumScrollPhysics.computeElasticOverscrollOffset(-60f, reducedMotion = true)
        assertTrue(overscrollNormal < 0f && abs(overscrollNormal) <= NyxoraMomentumScrollPhysics.MAX_ELASTIC_OVERSCROLL_PX)
        assertEquals(0f, overscrollReduced, 0.001f)
    }
}

