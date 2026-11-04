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

    @Test
    fun smartSearchSuggestionEngine_generatesProviderUrlsParsesPayloadsDeduplicatesAndProtectsPrivacy() {
        com.example.domain.browser.SmartSearchSuggestionEngine.clearMemoryCache()

        // 1. Verify provider URL generation for Google, Bing, DuckDuckGo, Brave, Startpage, Ecosia
        val googleUrl = com.example.domain.browser.SmartSearchSuggestionEngine.buildSuggestionEndpointUrl(
            com.example.data.local.SearchEngine.GOOGLE,
            "kotlin compose"
        )
        val bingUrl = com.example.domain.browser.SmartSearchSuggestionEngine.buildSuggestionEndpointUrl(
            com.example.data.local.SearchEngine.BING,
            "kotlin compose"
        )
        val ddgUrl = com.example.domain.browser.SmartSearchSuggestionEngine.buildSuggestionEndpointUrl(
            com.example.data.local.SearchEngine.DUCKDUCKGO,
            "kotlin compose"
        )
        val braveUrl = com.example.domain.browser.SmartSearchSuggestionEngine.buildSuggestionEndpointUrl(
            com.example.data.local.SearchEngine.BRAVE,
            "kotlin compose"
        )
        val startpageUrl = com.example.domain.browser.SmartSearchSuggestionEngine.buildSuggestionEndpointUrl(
            com.example.data.local.SearchEngine.STARTPAGE,
            "kotlin compose"
        )
        val ecosiaUrl = com.example.domain.browser.SmartSearchSuggestionEngine.buildSuggestionEndpointUrl(
            com.example.data.local.SearchEngine.ECOSIA,
            "kotlin compose"
        )
        assertTrue(googleUrl.startsWith("https://suggestqueries.google.com/complete/search?client=firefox&q="))
        assertTrue(bingUrl.startsWith("https://api.bing.com/osjson.aspx?query="))
        assertTrue(ddgUrl.startsWith("https://ac.duckduckgo.com/ac/?q="))
        assertTrue(braveUrl.startsWith("https://search.brave.com/api/suggest?q="))
        assertTrue(startpageUrl.startsWith("https://www.startpage.com/osuggestions?q="))
        assertTrue(ecosiaUrl.startsWith("https://ac.ecosia.org/autocomplete?q="))

        // 2. Parse OpenSearch array payload and DuckDuckGo object array payload with deduplication
        val openSearchPayload = """["kotlin",["kotlin compose","Kotlin Compose","kotlin coroutines","kotlin flow"]]"""
        val parsedOpenSearch = com.example.domain.browser.SmartSearchSuggestionEngine.parseRemoteSuggestionPayload(
            rawJson = openSearchPayload
        )
        assertEquals(listOf("kotlin compose", "kotlin coroutines", "kotlin flow"), parsedOpenSearch)

        val ddgPayload = """[{"phrase":"android browser"},{"phrase":"android compose"},{"phrase":"ANDROID BROWSER"}]"""
        val parsedDdg = com.example.domain.browser.SmartSearchSuggestionEngine.parseRemoteSuggestionPayload(
            rawJson = ddgPayload
        )
        assertEquals(listOf("android browser", "android compose"), parsedDdg)

        // 3. Privacy & Security checks: private mode, disabled setting, and sensitive URL parameters
        assertFalse(
            "Remote suggestions must be blocked when suggestionsEnabled is false",
            com.example.domain.browser.SmartSearchSuggestionEngine.shouldFetchRemoteSuggestions(
                query = "weather",
                suggestionsEnabled = false,
                isPrivateMode = false,
                privateModeSuggestionsEnabled = false
            )
        )
        assertFalse(
            "Remote suggestions must be blocked in Private Mode by default",
            com.example.domain.browser.SmartSearchSuggestionEngine.shouldFetchRemoteSuggestions(
                query = "weather",
                suggestionsEnabled = true,
                isPrivateMode = true,
                privateModeSuggestionsEnabled = false
            )
        )
        assertTrue(
            "Remote suggestions may be fetched in Private Mode only when explicitly enabled",
            com.example.domain.browser.SmartSearchSuggestionEngine.shouldFetchRemoteSuggestions(
                query = "weather",
                suggestionsEnabled = true,
                isPrivateMode = true,
                privateModeSuggestionsEnabled = true
            )
        )
        assertFalse(
            "Sensitive URLs or credentials must never be sent to remote suggestion servers",
            com.example.domain.browser.SmartSearchSuggestionEngine.shouldFetchRemoteSuggestions(
                query = "https://example.com/callback?access_token=secret123",
                suggestionsEnabled = true,
                isPrivateMode = false,
                privateModeSuggestionsEnabled = false
            )
        )
        assertTrue(
            com.example.domain.browser.SmartSearchSuggestionEngine.containsSensitiveParametersOrCredentials(
                "user:pass@internal.corp.local/login"
            )
        )

        // 4. Private mode queries must NEVER be recorded in recent search history
        val existingJson = """["kotlin flow"]"""
        val afterPrivateSearch = com.example.domain.browser.SmartSearchSuggestionEngine.recordSearchQuery(
            existingJson = existingJson,
            rawQuery = "private secret search",
            isPrivateMode = true
        )
        assertEquals(existingJson, afterPrivateSearch)

        val afterNormalSearch = com.example.domain.browser.SmartSearchSuggestionEngine.recordSearchQuery(
            existingJson = existingJson,
            rawQuery = "jetpack compose",
            isPrivateMode = false
        )
        assertEquals(
            listOf("jetpack compose", "kotlin flow"),
            com.example.domain.browser.SmartSearchSuggestionEngine.parseRecentSearchQueries(afterNormalSearch)
        )
    }

    @Test
    fun universalTextScalePresets_defaultsTo100AndSupportsAllRequiredPresets() {
        assertEquals(100, com.example.data.local.UniversalTextScalePresets.DEFAULT_SCALE)
        assertEquals(
            listOf(75, 85, 90, 100, 110, 115, 120, 125, 130, 150, 175, 200),
            com.example.data.local.UniversalTextScalePresets.PRESETS
        )
        assertEquals(100, com.example.data.local.NyxoraSettingsState().globalTextZoom)
        assertEquals(50, com.example.data.local.UniversalTextScalePresets.normalizeScale(30))
        assertEquals(100, com.example.data.local.UniversalTextScalePresets.normalizeScale(100))
        assertEquals(250, com.example.data.local.UniversalTextScalePresets.normalizeScale(300))
    }

    @Test
    fun brandingAndAnimationV4_pausesContinuousLogoAnimationsOnBackgroundHiddenOrBatterySaverAndRestoresDefaults() {
        // 1. Continuous logo animation runs when visible, resumed, enabled, and not in battery saver
        assertTrue(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = true,
                reducedMotion = false,
                performanceMode = com.example.data.local.AnimationPerformanceMode.BALANCED,
                isVisibleOnScreen = true,
                isLifecycleResumed = true,
                isDevicePowerSaveMode = false
            )
        )

        // 2. Pauses when homepage is hidden / covered by an overlay
        assertFalse(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = true,
                reducedMotion = false,
                performanceMode = com.example.data.local.AnimationPerformanceMode.SMOOTH,
                isVisibleOnScreen = false,
                isLifecycleResumed = true,
                isDevicePowerSaveMode = false
            )
        )

        // 3. Pauses when app is backgrounded (!isLifecycleResumed)
        assertFalse(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = true,
                reducedMotion = false,
                performanceMode = com.example.data.local.AnimationPerformanceMode.SMOOTH,
                isVisibleOnScreen = true,
                isLifecycleResumed = false,
                isDevicePowerSaveMode = false
            )
        )

        // 4. Pauses when AnimationPerformanceMode.BATTERY_SAVER or Android Power Save Mode is active
        assertFalse(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = true,
                reducedMotion = false,
                performanceMode = com.example.data.local.AnimationPerformanceMode.BATTERY_SAVER,
                isVisibleOnScreen = true,
                isLifecycleResumed = true,
                isDevicePowerSaveMode = false
            )
        )
        assertFalse(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = true,
                reducedMotion = false,
                performanceMode = com.example.data.local.AnimationPerformanceMode.SMOOTH,
                isVisibleOnScreen = true,
                isLifecycleResumed = true,
                isDevicePowerSaveMode = true
            )
        )

        // 5. Pauses when reducedMotion is enabled or animateGlow is disabled
        assertFalse(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = true,
                reducedMotion = true,
                performanceMode = com.example.data.local.AnimationPerformanceMode.SMOOTH,
                isVisibleOnScreen = true,
                isLifecycleResumed = true,
                isDevicePowerSaveMode = false
            )
        )
        assertFalse(
            com.example.ui.components.shouldRunContinuousLogoAnimation(
                animateGlow = false,
                reducedMotion = false,
                performanceMode = com.example.data.local.AnimationPerformanceMode.SMOOTH,
                isVisibleOnScreen = true,
                isLifecycleResumed = true,
                isDevicePowerSaveMode = false
            )
        )

        // 6. Verify BrandGlowIntensityPresets and withDefaultBranding() restoration
        val customized = com.example.data.local.NyxoraSettingsState(
            launchAnimationEnabled = false,
            homeLogoAnimationEnabled = false,
            logoGlowIntensity = com.example.data.local.BrandGlowIntensityPresets.SUBTLE,
            animationPerformanceMode = com.example.data.local.AnimationPerformanceMode.BATTERY_SAVER,
            homeWallpaperStyle = "EMERALD_MIST",
            showAmbientHomeBackground = true
        )
        val restored = customized.withDefaultBranding()
        assertTrue(restored.launchAnimationEnabled)
        assertTrue(restored.homeLogoAnimationEnabled)
        assertEquals(
            com.example.data.local.BrandGlowIntensityPresets.DEFAULT_INTENSITY,
            restored.logoGlowIntensity,
            0.001f
        )
        assertEquals(
            com.example.data.local.AnimationPerformanceMode.BALANCED,
            restored.animationPerformanceMode
        )
        assertEquals("AURORA_BLUE", restored.homeWallpaperStyle)
        assertFalse(restored.showAmbientHomeBackground)
    }
}
