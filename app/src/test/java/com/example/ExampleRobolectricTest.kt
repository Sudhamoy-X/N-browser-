package com.example

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.NyxoraLocalDatabase
import com.example.data.local.NyxoraSettingsDataStore
import com.example.data.local.NyxoraSettingsState
import com.example.data.local.SearchEngine
import com.example.data.model.BookmarkItem
import com.example.data.model.HistoryItem
import com.example.data.model.SiteSettingItem
import com.example.data.model.SyncedTabItem
import com.example.domain.browser.BrowserNetworkClient
import com.example.domain.browser.ContentBlockingEngine
import com.example.domain.browser.ReaderModeExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private val testStartNano: Long = System.nanoTime()

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `fresh install opens straight from splash to minimal homepage with Google default and no sign-in prompt in airplane mode`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Simulate Airplane Mode / Offline state (no active network)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivityManager).setDefaultNetworkActive(false)
        shadowOf(connectivityManager).setActiveNetworkInfo(null)
        @Suppress("DEPRECATION")
        assertNull(connectivityManager.activeNetworkInfo)

        // Advance past the 200ms soft GPU launch splash animation
        val frameStartNano = System.nanoTime()
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()
        val frameSettleMs = (System.nanoTime() - frameStartNano) / 1_000_000.0

        val coldStartMs = (System.nanoTime() - testStartNano) / 1_000_000.0
        System.gc()
        val runtime = Runtime.getRuntime()
        val usedHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        val usedHeapMb = usedHeapBytes / (1024.0 * 1024.0)
        println(
            "NYXORA_MEASUREMENT: coldStartMs=${"%.2f".format(coldStartMs)} ms, " +
                "splashFrameSettleMs=${"%.2f".format(frameSettleMs)} ms, " +
                "usedHeapMb=${"%.2f".format(usedHeapMb)} MB ($usedHeapBytes bytes)"
        )

        // 1. Verify minimalist 3-part layout: Top Bar (URL input + progress support), Central Container, and Minimal Homepage
        composeTestRule.onNodeWithTag("nyxora_main_browser_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("main_address_bar_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_logo_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_search_bar_trigger").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Search Google or enter address", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )

        // 2. Verify NO quick buttons, NO tab strip, NO greeting/date, and NO optional homepage sections by default
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("bottom_tab_strip").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("home_greeting_date_text").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("home_shortcuts_section").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("home_frequently_visited_section").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("home_recent_activity_section").fetchSemanticsNodes().size
        )

        // 3. Verify bottom toolbar has Back, Forward, Home, Tabs (with count), and Menu
        composeTestRule.onNodeWithTag("nyxora_bottom_tab_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toolbar_back_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toolbar_forward_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toolbar_home_or_forward_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toolbar_tabs_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").assertIsDisplayed()

        // 4. Verify NO sign-in gate, login button, or "Continue as guest" prompt exists anywhere
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("google_sign_in_button").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("Sign in with Google", substring = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `search engine defaults to Google and persists user selection in DataStore`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = NyxoraSettingsDataStore(context)

        val initial = store.settingsFlow.first()
        assertEquals(SearchEngine.GOOGLE, initial.searchEngine)

        // Switch to DuckDuckGo and verify persistence
        store.updateSettings { it.copy(searchEngine = SearchEngine.DUCKDUCKGO) }
        assertEquals(SearchEngine.DUCKDUCKGO, store.settingsFlow.first().searchEngine)

        // Switch back to Google and verify persistence
        store.updateSettings { it.copy(searchEngine = SearchEngine.GOOGLE) }
        assertEquals(SearchEngine.GOOGLE, store.settingsFlow.first().searchEngine)
    }

    @Test
    fun `measures native HTTP cache installation subresource filter engine and Reader Mode extractor`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(BrowserNetworkClient.ensureHttpCacheInstalled(context))

        // Measure subresource ad & tracker filter engine throughput across 1000 requests
        val testUrls = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js" to true,
            "https://stats.g.doubleclick.net/r/collect" to true,
            "https://www.google-analytics.com/analytics.js" to true,
            "https://cdn.taboola.com/libtrc/impl.js" to true,
            "https://www.wikipedia.org/static/images/project-logos/enwiki.png" to false,
            "https://developer.android.com/static/css/main.css" to false
        )

        val filterStart = System.nanoTime()
        val iterations = 200
        repeat(iterations) {
            for ((url, expectedBlocked) in testUrls) {
                val blocked = ContentBlockingEngine.shouldBlockSubresource(
                    requestUrl = url,
                    pageUrl = "https://www.wikipedia.org/wiki/Kotlin",
                    globalAdBlockingEnabled = true,
                    siteSetting = null
                )
                assertEquals(expectedBlocked, blocked)
            }
        }
        val totalChecks = iterations * testUrls.size
        val totalFilterMs = (System.nanoTime() - filterStart) / 1_000_000.0
        val avgMicrosPerCheck = (totalFilterMs * 1000.0) / totalChecks
        println(
            "NYXORA_FILTER_BENCHMARK: totalChecks=$totalChecks, " +
                "totalMs=${"%.3f".format(totalFilterMs)} ms, " +
                "avgPerSubresourceUs=${"%.3f".format(avgMicrosPerCheck)} us"
        )

        // Verify Reader Mode JS result parser
        val sampleJson = """{"title":"Native Android WebView Architecture","byline":"Android Team","siteName":"developer.android.com","paragraphs":["First paragraph with detailed technical explanation of WebView rendering.","Second paragraph covering subresource interception and custom views."]}"""
        val article = ReaderModeExtractor.parseEvaluateJavascriptResult(sampleJson)
        assertNotNull(article)
        assertEquals("Native Android WebView Architecture", article?.title)
        assertEquals(2, article?.paragraphs?.size)
    }

    @Test
    fun `local bookmarks history tabs and site settings persist offline without an account`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = NyxoraLocalDatabase.createInMemoryForTest(context)

        try {
            val defaultSettings = NyxoraSettingsState()
            assertEquals(SearchEngine.GOOGLE, defaultSettings.searchEngine)
            assertFalse(defaultSettings.showHomeShortcuts)
            assertFalse(defaultSettings.showHomeFreqVisited)
            assertFalse(defaultSettings.showHomeRecentActivity)
            assertFalse(defaultSettings.showHomeGreeting)
            assertFalse(defaultSettings.showAmbientHomeBackground)

            val bookmark = BookmarkItem(
                id = "bm_offline_1",
                userId = "local_user",
                title = "Kotlin Docs",
                url = "https://kotlinlang.org",
                folder = "Dev",
                isShortcut = true,
                createdAtMillis = 1700000000000L
            )
            db.bookmarkDao().upsertBookmark(bookmark)
            val savedBookmarks = db.bookmarkDao().observeAllBookmarks().first()
            assertEquals(1, savedBookmarks.size)
            assertEquals("https://kotlinlang.org", savedBookmarks.first().url)

            val historyItem = HistoryItem(
                id = "hist_offline_1",
                userId = "local_user",
                title = "Android Developers",
                url = "https://developer.android.com",
                visitedAt = 1700000000000L
            )
            db.historyDao().insertHistory(historyItem)
            val savedHistory = db.historyDao().observeHistory().first()
            assertEquals(1, savedHistory.size)
            assertEquals("Android Developers", savedHistory.first().title)

            val tabItem = SyncedTabItem(
                id = "tab_offline_1",
                userId = "local_user",
                title = "MDN Web Docs",
                url = "https://developer.mozilla.org",
                groupName = "Docs",
                isActive = true,
                desktopMode = false,
                textZoom = 100,
                updatedAtMillis = 1700000000000L
            )
            db.savedTabDao().upsertSavedTab(tabItem)
            val savedTabs = db.savedTabDao().observeSavedTabs().first()
            assertEquals(1, savedTabs.size)
            assertTrue(savedTabs.first().isActive)

            val siteSetting = SiteSettingItem(
                id = "developer.mozilla.org",
                userId = "local_user",
                host = "developer.mozilla.org",
                desktopMode = true,
                textZoom = 115,
                allowAds = false,
                allowPopups = false,
                allowRedirects = false
            )
            db.siteSettingDao().upsertSiteSetting(siteSetting)
            val savedSiteSettings = db.siteSettingDao().observeAllSiteSettings().first()
            assertEquals(1, savedSiteSettings.size)
            assertEquals(115, savedSiteSettings.first().textZoom)
        } finally {
            db.close()
        }
    }

    @Test
    fun `real Android WebView mounts in central container and supports pull to refresh`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        // Navigate to a web page using the top URL bar
        composeTestRule.onNodeWithTag("main_address_bar_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("main_address_bar_input").performTextReplacement("https://example.com")
        composeTestRule.onNodeWithTag("main_address_bar_input").performImeAction()
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        // Verify the real AndroidView(WebView) and pull-to-refresh box are mounted in the central browser container
        composeTestRule.onNodeWithTag("nyxora_pull_to_refresh_box").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_webview_instance").assertIsDisplayed()
        assertEquals(
            1,
            composeTestRule.onAllNodesWithTag("nyxora_pull_to_refresh_indicator", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )

        // Perform pull-to-refresh downward swipe gesture on the browser viewport
        composeTestRule.onNodeWithTag("nyxora_pull_to_refresh_box").performTouchInput {
            swipeDown()
        }
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nyxora_webview_instance").assertIsDisplayed()
    }
}
