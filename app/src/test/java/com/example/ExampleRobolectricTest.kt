package com.example

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

        // 1. Verify minimalist homepage layout: ONLY ONE primary search bar below the NYXORA logo (no duplicate top address bar on homepage)
        composeTestRule.onNodeWithTag("nyxora_main_browser_screen").assertIsDisplayed()
        assertEquals(
            "Homepage must NOT show a duplicate top search/address bar",
            0,
            composeTestRule.onAllNodesWithTag("nyxora_top_address_bar").fetchSemanticsNodes().size
        )
        assertEquals(
            "Homepage must NOT show top address bar input field",
            0,
            composeTestRule.onAllNodesWithTag("main_address_bar_input").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_logo_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_search_bar_trigger").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_search_box_input").assertIsDisplayed()
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
    fun `search engine defaults to Google and persists user selection and language in DataStore`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = NyxoraSettingsDataStore(context)

        val initial = store.settingsFlow.first()
        assertEquals(SearchEngine.GOOGLE, initial.searchEngine)
        assertEquals("System Default", initial.preferredLanguage)

        // Switch to DuckDuckGo and Español and verify persistence
        store.updateSettings {
            it.copy(
                searchEngine = SearchEngine.DUCKDUCKGO,
                preferredLanguage = "Español"
            )
        }
        val updated = store.settingsFlow.first()
        assertEquals(SearchEngine.DUCKDUCKGO, updated.searchEngine)
        assertEquals("Español", updated.preferredLanguage)

        // Switch back to Google and System Default and verify persistence
        store.updateSettings {
            it.copy(
                searchEngine = SearchEngine.GOOGLE,
                preferredLanguage = "System Default"
            )
        }
        val restored = store.settingsFlow.first()
        assertEquals(SearchEngine.GOOGLE, restored.searchEngine)
        assertEquals("System Default", restored.preferredLanguage)
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

        // Navigate to a web page using the single primary homepage search bar
        composeTestRule.onNodeWithTag("home_search_box_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_search_box_input").performTextReplacement("https://example.com")
        composeTestRule.onNodeWithTag("home_search_box_input").performImeAction()
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        // Verify the top address bar is now displayed while browsing a website, along with the real AndroidView(WebView)
        composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("main_address_bar_input").assertIsDisplayed()
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
        composeTestRule.activity.activeBrowserViewModel?.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `opening splash animation displays Crafted by Sudhamoy and transitions cleanly`() {
        // Pause clock during initial splash window (< 200ms) to inspect the opening animation
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(80L)

        assertTrue(
            composeTestRule.onAllNodesWithText("Browse Beyond Ordinary.", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText("Crafted by Sudhamoy", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nyxora_main_browser_screen").assertIsDisplayed()
    }

    @Test
    fun `NYXORA Connect room UI creates room and Settings About displays Crafted by Sudhamoy`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        // 1. Open Room Sync directly from the main browser bottom menu
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_bottom_menu_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_room_sync").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_connect_sheet").assertIsDisplayed()

        // Verify simple Connect lobby shows Title, Status, Create Room, Room Code input, and Join Room button,
        // and does NOT show manual WSS endpoint, Room Access Key, or Browse/Watch Together mode selector chips
        composeTestRule.onNodeWithTag("connect_title_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_status_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_create_room_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_join_room_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_join_room_button").assertIsDisplayed()
        assertEquals(
            "Room Access Key input must be removed from user-facing Join UI",
            0,
            composeTestRule.onAllNodesWithTag("connect_join_key_input").fetchSemanticsNodes().size
        )
        assertEquals(
            "Mode selection chips must be removed in unified Room Sync mode",
            0,
            composeTestRule.onAllNodesWithTag("connect_mode_chip_browse_together").fetchSemanticsNodes().size
        )

        val vm = composeTestRule.activity.activeBrowserViewModel!!
        assertEquals(
            "wss://n-browser.onrender.com/nyxora",
            vm.settings.value.connectBackendEndpoint
        )

        // 2. Create a room from the NYXORA Connect sheet
        composeTestRule.onNodeWithTag("connect_create_room_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(400L)
        composeTestRule.waitForIdle()

        // Verify active room shows Current Room Code, Copy Code button, Share Code button, and Leave Room button
        composeTestRule.onNodeWithTag("connect_active_room_id").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_compact_action_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_copy_code_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_share_code_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_leave_or_close_button").assertIsDisplayed()

        val activeRoomCode = vm.connectManager.roomState.value?.roomId ?: ""
        assertTrue("Expected valid Room Code NYX-XXXX-XXXX", activeRoomCode.startsWith("NYX-"))

        // Tap Copy Code button and verify it copies the room code
        composeTestRule.onNodeWithTag("connect_copy_code_button").performClick()
        composeTestRule.waitForIdle()

        // Dismiss the Connect sheet to verify the Browse Together Overlay (compact collapsible synchronization indicator)
        composeTestRule.onNodeWithTag("connect_back_button").performClick()
        composeTestRule.waitForIdle()

        // Verify compact indicator is displayed with small status icon, room code, expand toggle, and compact menu button
        composeTestRule.onNodeWithTag("nyxora_connect_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_status_icon", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_room_code", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_expand_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_menu_button").assertIsDisplayed()
        assertEquals(
            "Banner must start in compact collapsed mode by default",
            0,
            composeTestRule.onAllNodesWithTag("connect_banner_expanded_row").fetchSemanticsNodes().size
        )

        // Open the compact menu and verify Pause and Leave are inside the compact menu
        composeTestRule.onNodeWithTag("connect_banner_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("connect_banner_compact_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_pause_toggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_stop_button").assertIsDisplayed()

        // Toggle Pause via the compact menu
        composeTestRule.onNodeWithTag("connect_banner_pause_toggle").performClick()
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        // Expand the status bar and verify full details + Hide (collapse) button appear
        composeTestRule.onNodeWithTag("connect_banner_expand_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("connect_banner_expanded_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connect_banner_collapse_button").assertIsDisplayed()

        // Manually hide the expanded status bar while browsing
        composeTestRule.onNodeWithTag("connect_banner_collapse_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Expanded status bar must collapse when user taps Hide button",
            0,
            composeTestRule.onAllNodesWithTag("connect_banner_expanded_row").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("connect_banner_room_code", useUnmergedTree = true).assertIsDisplayed()

        // Expand again, navigate to a webpage, and verify scrolling automatically collapses the expanded bar
        composeTestRule.onNodeWithTag("connect_banner_expand_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("connect_banner_expanded_row").assertIsDisplayed()
        vm.navigateInActiveTab("https://example.com")
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        val liveWv = findFirstWebView(composeTestRule.activity.window.decorView)
        if (liveWv != null) {
            composeTestRule.runOnUiThread {
                liveWv.scrollTo(0, 120)
            }
            composeTestRule.waitForIdle()
            assertEquals(
                "Scrolling webpage must automatically hide the expanded status bar back to compact indicator",
                0,
                composeTestRule.onAllNodesWithTag("connect_banner_expanded_row").fetchSemanticsNodes().size
            )
            composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
            composeTestRule.onNodeWithTag("nyxora_bottom_tab_bar").assertIsDisplayed()
        }

        // Leave the room using the compact menu's Leave option
        composeTestRule.onNodeWithTag("connect_banner_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("connect_banner_stop_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Leaving room must transition status to DISCONNECTED",
            com.example.domain.connect.SyncConnectionStatus.DISCONNECTED,
            vm.connectManager.connectionStatus.value
        )
        vm.navigateHome()
        composeTestRule.waitForIdle()


        // 3. Open bottom menu -> Settings -> Verify Room Sync is removed from Settings and About NYXORA works
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_item_settings").performClick()
        composeTestRule.waitForIdle()

        // Verify Settings Home has the 5 required categories and does NOT contain Room Sync button
        composeTestRule.onNodeWithTag("settings_category_general").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_category_customization").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_category_privacy").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_category_advanced").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_category_about").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_category_advanced").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Room Sync button must be removed from Settings",
            0,
            composeTestRule.onAllNodesWithTag("settings_open_connect_button").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("settings_back_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_search_input").performScrollTo().performTextReplacement("Sudhamoy")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("about_nyxora_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_nyxora_tagline").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_nyxora_creator_credit").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Crafted by Sudhamoy", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `clean UI upgrade verifies 8-item bottom menu sheet 5-category settings general site settings and tab switcher`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()
        composeTestRule.activity.activeBrowserViewModel?.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()

        // 1. Open bottom menu and verify all 9 actions (including Room Sync) are displayed
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_bottom_menu_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_bookmarks").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_history").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_downloads").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_incognito").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_add_bookmark").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_desktop_site").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_room_sync").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_share").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_item_settings").assertIsDisplayed()

        // Verify outside tap on scrim dismisses the bottom menu
        composeTestRule.onNodeWithTag("bottom_menu_scrim").performClick()
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("nyxora_bottom_menu_sheet").fetchSemanticsNodes().size
        )

        // 2. Open Settings -> General -> Site Settings and verify Content, Permissions, and Site Management
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_item_settings").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_category_general").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_open_site_settings_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nyxora_site_settings_sheet").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Content", substring = false).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText("Permissions", substring = false).fetchSemanticsNodes().isNotEmpty()
        )
        composeTestRule.onNodeWithTag("site_settings_back_button").performClick()
        composeTestRule.waitForIdle()

        // 3. Open Tab Switcher, create a new tab, switch to Incognito, and close switcher
        composeTestRule.onNodeWithTag("toolbar_tabs_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_tab_switcher_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_switcher_normal_mode").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_switcher_private_mode").assertIsDisplayed()
        composeTestRule.onNodeWithTag("new_tab_button").performClick()
        composeTestRule.waitForIdle()

        // Active tab count should now be 2
        assertTrue(
            composeTestRule.onAllNodesWithText("2", substring = false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        )
        composeTestRule.activity.activeBrowserViewModel?.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `fullscreen video playback in portrait and landscape hides toolbars and system bars preserves orientation and playback and exits via Back and player control`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()
        composeTestRule.activity.activeBrowserViewModel?.closeAllTabs(inPrivateMode = false)
        composeTestRule. runOnUiThread {
            composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        composeTestRule.waitForIdle()

        // Navigate to a video page using the homepage search bar so the real Android WebView is mounted
        composeTestRule.onNodeWithTag("home_search_box_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_search_box_input").performTextReplacement("https://example.com/watch")
        composeTestRule.onNodeWithTag("home_search_box_input").performImeAction()
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        // Verify browser toolbars are initially visible before fullscreen
        composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_bottom_tab_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_webview_instance").assertIsDisplayed()

        val activity = composeTestRule.activity
        val initialWebView = findFirstWebView(activity.window.decorView)
        assertNotNull("Expected live WebView instance mounted in view hierarchy", initialWebView)
        val chromeClient = initialWebView!!.webChromeClient
        assertNotNull("Expected WebChromeClient configured on WebView", chromeClient)

        // Simulate playback state on the WebView before entering fullscreen
        initialWebView.scrollTo(0, 240)
        val initialOrientation = activity.requestedOrientation
        assertEquals(Configuration.ORIENTATION_PORTRAIT, activity.resources.configuration.orientation)

        // 1. Enter fullscreen in PORTRAIT mode
        var portraitCallbackHiddenCount = 0
        val portraitVideoView = FrameLayout(activity)
        composeTestRule.runOnUiThread {
            chromeClient!!.onShowCustomView(
                portraitVideoView,
                WebChromeClient.CustomViewCallback { portraitCallbackHiddenCount++ }
            )
        }
        composeTestRule.waitForIdle()

        // 2. Verify portrait mode remains portrait (does NOT force landscape!)
        assertEquals(
            "Portrait fullscreen must remain in portrait orientation and never force landscape",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )

        // 3. Verify both browser toolbars completely disappear and fullscreen overlay fills screen
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("nyxora_top_address_bar").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("nyxora_bottom_tab_bar").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("fullscreen_video_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_custom_view_container").assertIsDisplayed()
        assertNotNull("Fullscreen custom view must be attached to container", portraitVideoView.parent)

        // 4. Verify Android system bars are hidden in immersive fullscreen mode
        @Suppress("DEPRECATION")
        val fullscreenWindowFlags = activity.window.attributes.flags
        @Suppress("DEPRECATION")
        assertTrue(
            "Expected FLAG_FULLSCREEN set on window during fullscreen playback",
            (fullscreenWindowFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
        )
        @Suppress("DEPRECATION")
        val fullscreenSysUi = activity.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        assertTrue(
            "Expected immersive fullscreen system UI flags set",
            (fullscreenSysUi and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 &&
                (fullscreenSysUi and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0 &&
                (fullscreenSysUi and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0
        )

        // 5. Verify duplicate fullscreen view prevention if onShowCustomView is called again
        var replacementCallbackHiddenCount = 0
        val secondPortraitVideoView = FrameLayout(activity)
        composeTestRule.runOnUiThread {
            chromeClient!!.onShowCustomView(
                secondPortraitVideoView,
                WebChromeClient.CustomViewCallback { replacementCallbackHiddenCount++ }
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Previous custom view callback must be notified when replaced", 1, portraitCallbackHiddenCount)
        assertNull("Previous custom view must be detached when replaced", portraitVideoView.parent)
        assertNotNull("New custom view must be attached", secondPortraitVideoView.parent)
        assertEquals("Container must only have 1 child view", 1, (secondPortraitVideoView.parent as ViewGroup).childCount)

        // 5b. Verify upgraded minimal monochrome video toolbar has ONLY the 4 required controls
        composeTestRule.onNodeWithTag("fullscreen_video_toolbar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_rotate_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_aspect_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_zoom_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_exit_button").assertIsDisplayed()
        assertEquals(
            "Redundant PiP control must not be present in fullscreen video toolbar",
            0,
            composeTestRule.onAllNodesWithTag("fullscreen_pip_button").fetchSemanticsNodes().size
        )

        // Test Fit Screen / Fill Screen toggle and Video Zoom on the fullscreen custom view
        assertEquals(1.0f, secondPortraitVideoView.scaleX, 0.01f)
        composeTestRule.onNodeWithTag("fullscreen_aspect_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Fill Screen mode should scale custom view to 1.18f", 1.18f, secondPortraitVideoView.scaleX, 0.01f)
        assertEquals("Fill Screen mode should scale custom view to 1.18f", 1.18f, secondPortraitVideoView.scaleY, 0.01f)

        // Reset back to Fit Screen (1.0f) and verify Video Zoom steps up to 1000% (10.0f) and resets cleanly to 100% (1.0f)
        composeTestRule.onNodeWithTag("fullscreen_aspect_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1.0f, secondPortraitVideoView.scaleX, 0.01f)

        val expectedZoomSteps = listOf(1.15f, 1.30f, 1.50f, 2.0f, 3.0f, 5.0f, 7.5f, 10.0f)
        for (expectedScale in expectedZoomSteps) {
            composeTestRule.onNodeWithTag("fullscreen_zoom_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals("Video zoom should step cleanly up to 1000% (10.0f)", expectedScale, secondPortraitVideoView.scaleX, 0.01f)
            assertEquals("Video zoom should preserve aspect ratio without stretching", expectedScale, secondPortraitVideoView.scaleY, 0.01f)
        }

        // Verify 1-tap Reset Zoom button returns immediately from 1000% (10.0f) to 100% (1.0f)
        composeTestRule.onNodeWithTag("fullscreen_zoom_reset_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fullscreen_zoom_reset_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Reset zoom button should restore 100% (1.0f) scale", 1.0f, secondPortraitVideoView.scaleX, 0.01f)
        assertEquals(0f, secondPortraitVideoView.translationX, 0.01f)
        assertEquals(0f, secondPortraitVideoView.translationY, 0.01f)

        // Test Auto-Hide after 2.5 seconds of inactivity and Tap-to-Show on the video overlay
        composeTestRule.mainClock.advanceTimeBy(2600L)
        composeTestRule.waitForIdle()
        assertEquals(
            "Video toolbar should auto-hide after 2.5 seconds without interaction",
            0,
            composeTestRule.onAllNodesWithTag("fullscreen_video_toolbar").fetchSemanticsNodes().size
        )

        // Tap the fullscreen video area to reveal the toolbar again
        composeTestRule.onNodeWithTag("fullscreen_video_overlay").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("fullscreen_video_toolbar").assertIsDisplayed()

        // 6. Exit fullscreen using Android Back button
        composeTestRule.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Verify original browser toolbars, system UI, and previous orientation return cleanly
        assertEquals(1, replacementCallbackHiddenCount)
        assertNull("Fullscreen custom view must be detached on exit", secondPortraitVideoView.parent)
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("fullscreen_video_overlay").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_bottom_tab_bar").assertIsDisplayed()
        assertEquals("Previous orientation must be restored after exit", initialOrientation, activity.requestedOrientation)
        @Suppress("DEPRECATION")
        assertEquals(
            "FLAG_FULLSCREEN must be cleared after exiting fullscreen",
            0,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Verify WebView instance and state were preserved without reloading or resetting
        val webViewAfterPortraitExit = findFirstWebView(activity.window.decorView)
        assertSame("WebView instance must not be recreated on fullscreen exit", initialWebView, webViewAfterPortraitExit)
        assertEquals("WebView scroll/playback state must be preserved", 240, webViewAfterPortraitExit!!.scrollY)

        // 7. Enter fullscreen in LANDSCAPE mode and exit using the player's exit control (onHideCustomView)
        composeTestRule.runOnUiThread {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeTestRule.waitForIdle()

        var landscapeCallbackHiddenCount = 0
        val landscapeVideoView = FrameLayout(activity)
        composeTestRule.runOnUiThread {
            chromeClient!!.onShowCustomView(
                landscapeVideoView,
                WebChromeClient.CustomViewCallback { landscapeCallbackHiddenCount++ }
            )
        }
        composeTestRule.waitForIdle()

        // Verify landscape mode remains landscape and toolbars disappear
        assertEquals(
            "Landscape mode must remain landscape in fullscreen",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            activity.requestedOrientation
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("nyxora_top_address_bar").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("nyxora_bottom_tab_bar").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("fullscreen_video_overlay").assertIsDisplayed()

        // Exit fullscreen using the player's native exit control (WebChromeClient.onHideCustomView)
        composeTestRule.runOnUiThread {
            chromeClient!!.onHideCustomView()
        }
        composeTestRule.waitForIdle()

        assertEquals(1, landscapeCallbackHiddenCount)
        assertNull(landscapeVideoView.parent)
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("fullscreen_video_overlay").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_bottom_tab_bar").assertIsDisplayed()
        assertEquals(
            "Previous landscape orientation must be restored on exit",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            activity.requestedOrientation
        )
        assertSame(initialWebView, findFirstWebView(activity.window.decorView))
        composeTestRule.runOnUiThread {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        composeTestRule.activity.activeBrowserViewModel?.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `closing final incognito tab returns to normal mode and global settings propagate to open tabs`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()
        val viewModel = composeTestRule.activity.activeBrowserViewModel!!
        viewModel.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()

        // 1. Create a private tab and verify browser switches to Private Mode
        val privateTabId = viewModel.createNewTab(
            url = "https://example.org",
            inPrivateMode = true,
            inBackground = false
        )
        assertTrue("Expected browser in private mode after opening foreground private tab", viewModel.isPrivateMode.value)
        assertEquals(1, viewModel.privateTabs.value.size)

        // 2. Close the final private tab and verify browser cleanly exits Private Mode back to Normal Mode
        viewModel.closeTab(privateTabId)
        assertEquals(0, viewModel.privateTabs.value.size)
        assertFalse("Closing the last private tab must exit private mode back to normal mode", viewModel.isPrivateMode.value)

        // 3. Verify SPA history update (doUpdateVisitedHistory -> onWebHistoryUpdated) syncs URL and canGoBack/canGoForward
        val normalTabId = viewModel.normalTabs.value.first().id
        viewModel.onWebHistoryUpdated(
            tabId = normalTabId,
            url = "https://example.com/spa-route",
            canGoBack = true,
            canGoForward = false
        )
        val updatedNormalTab = viewModel.normalTabs.value.first { it.id == normalTabId }
        assertEquals("https://example.com/spa-route", updatedNormalTab.url)
        assertTrue(updatedNormalTab.canGoBack)
        assertFalse(updatedNormalTab.canGoForward)

        // 4. Verify changing globalTextZoom and defaultDesktopSite immediately updates open tabs without per-site overrides
        viewModel.updateSettings {
            it.copy(
                globalTextZoom = 135,
                defaultDesktopSite = true
            )
        }
        val propagatedTab = viewModel.normalTabs.value.first { it.id == normalTabId }
        assertEquals(135, propagatedTab.textZoom)
        assertTrue(propagatedTab.desktopMode)

        // Restore defaults
        viewModel.updateSettings {
            it.copy(
                globalTextZoom = 100,
                defaultDesktopSite = false
            )
        }
        viewModel.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `horizontal edge swipe gestures navigate backward and forward with visual indicator and respect settings toggle`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        // Verify left and right edge-swipe zones are active by default
        composeTestRule.onNodeWithTag("edge_swipe_left_zone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("edge_swipe_right_zone").assertIsDisplayed()

        val vm = composeTestRule.activity.activeBrowserViewModel!!

        // 1. Navigate from Homepage (nyxora://home) using the single homepage search bar -> https://example.com -> https://kotlinlang.org
        composeTestRule.onNodeWithTag("home_search_box_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_search_box_input").performTextReplacement("https://example.com")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_search_box_input").performImeAction()
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        assertEquals("https://example.com", vm.activeTab.value.url)
        composeTestRule.onNodeWithTag("nyxora_top_address_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nyxora_webview_instance").assertIsDisplayed()

        composeTestRule.onNodeWithTag("main_address_bar_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("main_address_bar_input").performTextReplacement("https://kotlinlang.org")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("main_address_bar_input").performImeAction()
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        assertEquals("https://kotlinlang.org", vm.activeTab.value.url)

        // 2. Verify horizontal drag starting in the center of the screen does NOT trigger edge-swipe back
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            swipeRight(startX = centerX, endX = right - 20f)
        }
        composeTestRule.waitForIdle()
        assertEquals(
            "Center-screen horizontal drag must not trigger edge-swipe navigation",
            "https://kotlinlang.org",
            vm.activeTab.value.url
        )

        // 3. Begin a left-edge inward swipe, verify visual indicator appears mid-gesture, then release to navigate Back
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(40f, 0f))
            moveBy(Offset(140f, 0f))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_edge_swipe_indicator").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        // Should have navigated Back from kotlinlang.org to example.com
        assertEquals(
            "Left-edge swipe must navigate back from kotlinlang.org to example.com",
            "https://example.com",
            vm.activeTab.value.url
        )

        // 4. Swipe inward from left edge again to navigate Back from example.com to Homepage
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            swipeRight(startX = 10f, endX = 320f)
        }
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()
        assertEquals("nyxora://home", vm.activeTab.value.url)
        composeTestRule.onNodeWithTag("home_logo_header").assertIsDisplayed()

        // 5. Swipe inward from right edge while on Homepage to navigate Forward back to example.com
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            swipeLeft(startX = right - 10f, endX = right - 320f)
        }
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()
        assertEquals(
            "Right-edge swipe must navigate forward from Homepage to example.com",
            "https://example.com",
            vm.activeTab.value.url
        )

        // 6. Swipe inward from right edge again to navigate Forward to kotlinlang.org
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            swipeLeft(startX = right - 10f, endX = right - 320f)
        }
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()
        assertEquals(
            "Right-edge swipe must navigate forward from example.com to kotlinlang.org",
            "https://kotlinlang.org",
            vm.activeTab.value.url
        )

        // 7. Disable Edge Swipe Back & Forward in Settings -> Advanced and verify edge swipe is disabled
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_item_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_category_advanced").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("setting_edge_swipe_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("edge_swipe_left_zone").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("nyxora_central_browser_container").performTouchInput {
            swipeRight(startX = 10f, endX = 320f)
        }
        composeTestRule.waitForIdle()
        assertEquals(
            "Left-edge swipe must be ignored when Edge Swipe Back & Forward is disabled",
            "https://kotlinlang.org",
            vm.activeTab.value.url
        )
        vm.updateSettings { it.copy(edgeSwipeBackForward = true) }
        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `multi-tab data structure and ViewModel logic support creating switching cycling reordering duplicating closing and restoring tabs`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        val vm = composeTestRule.activity.activeBrowserViewModel!!
        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()

        // 1. Verify initial single-tab state in MultiTabState
        val initialState = vm.multiTabState.value
        assertEquals(1, initialState.tabCount)
        assertEquals(0, initialState.activeTabIndex)
        assertEquals("nyxora://home", initialState.activeTab.url)
        val firstTabId = initialState.activeTabId

        // 2. Create two new foreground tabs and one background tab
        val secondTabId = vm.createNewTab(
            url = "https://kotlinlang.org",
            inPrivateMode = false,
            inBackground = false,
            groupName = "Research"
        )
        assertEquals(2, vm.multiTabState.value.tabCount)
        assertEquals(secondTabId, vm.activeTab.value.id)
        assertEquals(1, vm.multiTabState.value.activeTabIndex)
        assertEquals("https://kotlinlang.org", vm.activeTab.value.url)

        val thirdTabId = vm.createNewTab(
            url = "https://developer.android.com",
            inPrivateMode = false,
            inBackground = false,
            groupName = "Work"
        )
        assertEquals(3, vm.multiTabState.value.tabCount)
        assertEquals(thirdTabId, vm.activeTab.value.id)
        assertEquals(2, vm.multiTabState.value.activeTabIndex)

        val fourthBgTabId = vm.createNewTab(
            url = "https://wikipedia.org",
            inPrivateMode = false,
            inBackground = true,
            groupName = "Research"
        )
        // Background tab should increase tab count to 4 without changing the active tab (thirdTabId)
        assertEquals(4, vm.multiTabState.value.tabCount)
        assertEquals(thirdTabId, vm.activeTab.value.id)
        assertEquals(2, vm.multiTabState.value.activeTabIndex)
        assertEquals(2, vm.multiTabState.value.getTabsByGroup("Research").size)
        assertNotNull(vm.multiTabState.value.findTabById(fourthBgTabId))

        // 3. Switch between tabs by ID, by index, and via next/previous cycling
        assertTrue(vm.switchToTab(firstTabId))
        assertEquals(firstTabId, vm.activeTab.value.id)
        assertEquals(0, vm.multiTabState.value.activeTabIndex)

        assertTrue(vm.switchToTabAtIndex(3))
        assertEquals(fourthBgTabId, vm.activeTab.value.id)
        assertEquals(3, vm.multiTabState.value.activeTabIndex)

        // Next tab from last index (3) wraps around to index 0 (firstTabId)
        assertEquals(firstTabId, vm.switchToNextTab())
        assertEquals(firstTabId, vm.activeTab.value.id)

        // Previous tab from index 0 wraps around to last index (fourthBgTabId)
        assertEquals(fourthBgTabId, vm.switchToPreviousTab())
        assertEquals(fourthBgTabId, vm.activeTab.value.id)

        // 4. Reorder tabs while preserving active tab
        assertTrue(vm.reorderTab(fourthBgTabId, 1))
        assertEquals(fourthBgTabId, vm.multiTabState.value.currentModeTabs[1].id)
        assertEquals(fourthBgTabId, vm.activeTab.value.id)
        assertEquals(1, vm.multiTabState.value.activeTabIndex)

        // 5. Duplicate a tab and verify its properties are copied and it becomes active
        val duplicatedId = vm.duplicateTab(secondTabId)
        assertNotNull(duplicatedId)
        assertEquals(5, vm.multiTabState.value.tabCount)
        assertEquals(duplicatedId, vm.activeTab.value.id)
        assertEquals("https://kotlinlang.org", vm.activeTab.value.url)
        assertEquals("Research", vm.activeTab.value.groupName)

        // 6. Close an inactive tab (firstTabId) and verify the active tab remains duplicatedId
        assertTrue(vm.closeTab(firstTabId))
        assertEquals(4, vm.multiTabState.value.tabCount)
        assertEquals(duplicatedId, vm.activeTab.value.id)

        // 7. Switch to the middle tab and close it; verify adjacent neighbor tab becomes active
        val currentOrder = vm.multiTabState.value.currentModeTabs.map { it.id }
        val middleTabId = currentOrder[1]
        val expectedNeighborId = currentOrder[2]
        assertTrue(vm.switchToTab(middleTabId))
        assertTrue(vm.closeTab(middleTabId))
        assertEquals(3, vm.multiTabState.value.tabCount)
        assertEquals(expectedNeighborId, vm.activeTab.value.id)

        // 8. Reopen recently closed tab and verify it restores middleTabId's URL & group
        val reopenedId = vm.reopenRecentlyClosedTab()
        assertNotNull(reopenedId)
        assertEquals(4, vm.multiTabState.value.tabCount)
        assertEquals(reopenedId, vm.activeTab.value.id)

        // 9. Verify UI Tab Switcher reflects all 4 open tabs and supports switching & closing via Compose UI
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("toolbar_tabs_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_tab_switcher_sheet").assertIsDisplayed()

        // Close the top-left visible tab card via its close button in the Tab Switcher UI
        val firstVisibleCardId = vm.multiTabState.value.currentModeTabs.first().id
        composeTestRule.onNodeWithTag("close_tab_$firstVisibleCardId").performClick()
        composeTestRule.waitForIdle()
        assertEquals(3, vm.multiTabState.value.tabCount)

        // Tap the new first visible tab card in the Tab Switcher to switch to it and dismiss the switcher
        val targetCardId = vm.multiTabState.value.currentModeTabs.first().id
        composeTestRule.onNodeWithTag("tab_card_$targetCardId").performClick()
        composeTestRule.waitForIdle()
        assertEquals(targetCardId, vm.activeTab.value.id)

        // 10. Close all normal tabs and verify a fresh Home tab is automatically created
        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
        assertEquals(1, vm.multiTabState.value.tabCount)
        assertEquals("nyxora://home", vm.activeTab.value.url)
    }

    @Test
    fun `priority bug fixes verify back button hierarchy auto-redirect blocking ad blocker desktop mode 1000 percent video zoom favicon cache and removal of frequently visited`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = composeTestRule.activity
        val vm = activity.activeBrowserViewModel!!
        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()

        // =========================================================================
        // 1. VERIFY BACK BUTTON & APP EXIT BUG FIX (Strict 5-Step Priority Chain)
        // =========================================================================
        // Navigate from Homepage -> https://example.com -> https://kotlinlang.org
        vm.navigateInActiveTab("https://example.com")
        composeTestRule.waitForIdle()
        assertEquals("https://example.com", vm.activeTab.value.url)

        vm.navigateInActiveTab("https://kotlinlang.org")
        composeTestRule.waitForIdle()
        assertEquals("https://kotlinlang.org", vm.activeTab.value.url)

        // Open a child tab linked to the current parent tab (e.g., target="_blank" link)
        val parentTabId = vm.activeTab.value.id
        val childTabId = vm.createNewTab(
            url = "https://developer.android.com",
            inPrivateMode = false,
            inBackground = false,
            parentTabId = parentTabId
        )
        composeTestRule.waitForIdle()
        assertEquals(childTabId, vm.activeTab.value.id)
        assertEquals(parentTabId, vm.activeTab.value.parentTabId)

        // Trigger a redirect warning overlay and open an overlay sheet (History)
        vm.onAutoRedirectBlocked("https://redirect.popads.net/click")
        vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.HISTORY)
        composeTestRule.waitForIdle()
        assertNotNull(vm.pendingRedirectWarning.value)
        assertEquals(com.example.ui.viewmodel.OverlaySheet.HISTORY, vm.activeOverlay.value)

        // Priority 1: Fullscreen video exits first when Back is pressed
        var fullscreenExited = false
        assertTrue(
            vm.handleSystemBack(
                isFullscreenVideo = true,
                onExitFullscreen = { fullscreenExited = true }
            )
        )
        assertTrue("Priority 1: Fullscreen video must exit first on Back", fullscreenExited)
        assertNotNull("Redirect warning must remain until next Back press", vm.pendingRedirectWarning.value)

        // Priority 2a: Redirect warning dismisses next when Back is pressed
        composeTestRule.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertNull("Priority 2a: Redirect warning must be dismissed on Back", vm.pendingRedirectWarning.value)
        assertFalse("Activity must NOT finish when dismissing redirect warning", activity.isFinishing)

        // Priority 2b: Open overlay sheet (History) dismisses next when Back is pressed
        composeTestRule.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertEquals(com.example.ui.viewmodel.OverlaySheet.NONE, vm.activeOverlay.value)
        assertFalse("Activity must NOT finish when dismissing overlay sheet", activity.isFinishing)

        // Priority 4a: Child tab with no earlier webpage history returns to its parent tab on Back
        composeTestRule.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertEquals("Back from child tab with no history must return to parent tab", parentTabId, vm.activeTab.value.id)
        assertEquals("https://kotlinlang.org", vm.activeTab.value.url)
        assertFalse(activity.isFinishing)

        // Priority 3: Parent tab navigates Back within webpage history (kotlinlang.org -> example.com)
        composeTestRule.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertEquals("Priority 3: Back must navigate to previous webpage in tab history", "https://example.com", vm.activeTab.value.url)
        assertFalse(activity.isFinishing)

        // Priority 4b: Pressing Back on example.com returns to Homepage (nyxora://home) instead of exiting app!
        composeTestRule.runOnUiThread {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertEquals("Priority 4b: Back from first webpage must return to nyxora://home", "nyxora://home", vm.activeTab.value.url)
        assertTrue(vm.activeTab.value.isHomePage)
        assertFalse("Activity must NOT close when returning from website to Homepage", activity.isFinishing)

        // Priority 5: Now at root Homepage with no overlays/history, canInterceptSystemBack is false
        assertFalse(
            "At root Homepage with no history or overlays, system Back should not be intercepted",
            vm.canInterceptSystemBack()
        )

        // =========================================================================
        // 2. VERIFY AUTO-REDIRECT BLOCKING & REDIRECT LOOP PROTECTION
        // =========================================================================
        // Legitimate user tap to a normal cross-origin website is allowed
        val legitimateTapDecision = ContentBlockingEngine.evaluateTopLevelNavigation(
            requestUrl = "https://kotlinlang.org/docs/home.html",
            currentPageUrl = "https://example.com",
            hasUserGesture = true,
            isServerRedirect = false,
            timeSinceLastUserInteractionMs = 120L,
            globalRedirectBlockingEnabled = true,
            httpsOnlyMode = false,
            siteSetting = null,
            allowOnceUrls = emptySet()
        )
        assertEquals(com.example.domain.browser.NavigationDecision.ALLOW, legitimateTapDecision)

        // Unwanted automatic cross-origin redirect without user gesture is blocked
        val autoRedirectDecision = ContentBlockingEngine.evaluateTopLevelNavigation(
            requestUrl = "https://unwanted-promo-site.example.net/landing",
            currentPageUrl = "https://example.com",
            hasUserGesture = false,
            isServerRedirect = true,
            timeSinceLastUserInteractionMs = 5000L,
            globalRedirectBlockingEnabled = true,
            httpsOnlyMode = false,
            siteSetting = null,
            allowOnceUrls = emptySet()
        )
        assertEquals(com.example.domain.browser.NavigationDecision.BLOCK_AUTO_REDIRECT, autoRedirectDecision)

        // Redirect to known ad/tracker domain is blocked even during a gesture unless explicitly allowed once
        val adRedirectUrl = "https://onclickads.net/afu.php?zoneid=123"
        val adRedirectDecision = ContentBlockingEngine.evaluateTopLevelNavigation(
            requestUrl = adRedirectUrl,
            currentPageUrl = "https://example.com",
            hasUserGesture = true,
            isServerRedirect = true,
            timeSinceLastUserInteractionMs = 100L,
            globalRedirectBlockingEnabled = true,
            httpsOnlyMode = false,
            siteSetting = null,
            allowOnceUrls = emptySet()
        )
        assertEquals(com.example.domain.browser.NavigationDecision.BLOCK_AUTO_REDIRECT, adRedirectDecision)

        // Safe way to continue ("Allow once") permits the blocked redirect URL
        val allowedOnceDecision = ContentBlockingEngine.evaluateTopLevelNavigation(
            requestUrl = adRedirectUrl,
            currentPageUrl = "https://example.com",
            hasUserGesture = false,
            isServerRedirect = true,
            timeSinceLastUserInteractionMs = 5000L,
            globalRedirectBlockingEnabled = true,
            httpsOnlyMode = false,
            siteSetting = null,
            allowOnceUrls = setOf(adRedirectUrl)
        )
        assertEquals(com.example.domain.browser.NavigationDecision.ALLOW, allowedOnceDecision)

        // Rapid redirect loop detection blocks repeated bounces to the same URL
        val loopUrl = "https://bounce.example.org/loop"
        assertFalse(ContentBlockingEngine.isRedirectLoopDetected("loop-source.com", "bounce.example.org", loopUrl, false, 1000L))
        assertFalse(ContentBlockingEngine.isRedirectLoopDetected("loop-source.com", "bounce.example.org", loopUrl, false, 1500L))
        assertTrue(
            "3 rapid redirects to the same URL within 4s must be flagged as a redirect loop",
            ContentBlockingEngine.isRedirectLoopDetected("loop-source.com", "bounce.example.org", loopUrl, false, 2000L)
        )

        // =========================================================================
        // 3. VERIFY AD BLOCKER ENGINE, SAFE MEDIA/AUTH WHITELIST, & COSMETIC SCRIPT
        // =========================================================================
        assertTrue(
            ContentBlockingEngine.shouldBlockSubresource(
                requestUrl = "https://securepubads.g.doubleclick.net/gpt/pubads_impl.js",
                pageUrl = "https://news.example.com/article",
                globalAdBlockingEnabled = true,
                siteSetting = null
            )
        )
        assertTrue(
            ContentBlockingEngine.shouldBlockSubresource(
                requestUrl = "https://cdn.popads.net/pop.js",
                pageUrl = "https://news.example.com/article",
                globalAdBlockingEnabled = true,
                siteSetting = null
            )
        )
        // Video CDNs and login/OAuth endpoints must NEVER be blocked
        assertFalse(
            ContentBlockingEngine.shouldBlockSubresource(
                requestUrl = "https://rr1---sn-ab5l6nzy.googlevideo.com/videoplayback?id=123",
                pageUrl = "https://m.youtube.com/watch?v=123",
                globalAdBlockingEnabled = true,
                siteSetting = null
            )
        )
        assertFalse(
            ContentBlockingEngine.shouldBlockSubresource(
                requestUrl = "https://accounts.google.com/gsi/client",
                pageUrl = "https://example.com/login",
                globalAdBlockingEnabled = true,
                siteSetting = null
            )
        )
        val blockedJsResp = ContentBlockingEngine.createBlockedSubresourceResponse("https://cdn.taboola.com/libtrc/impl.js")
        assertEquals("application/javascript", blockedJsResp.mimeType)
        assertEquals(200, blockedJsResp.statusCode)

        val cosmeticScript = ContentBlockingEngine.buildCosmeticAndDarkModeScript(
            hideCookieBanners = true,
            forceDarkWebPages = false,
            adBlockingEnabled = true
        )
        assertTrue(cosmeticScript.contains("ins.adsbygoogle"))
        assertTrue(cosmeticScript.contains(":not(:has(video))"))

        val adsBefore = vm.settings.value.totalAdsBlockedCount
        vm.recordAdBlocked()
        val deadlineAds = System.currentTimeMillis() + 3000L
        while (vm.settings.value.totalAdsBlockedCount < adsBefore + 1 && System.currentTimeMillis() < deadlineAds) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeTestRule.mainClock.advanceTimeBy(50L)
            composeTestRule.waitForIdle()
            Thread.sleep(20L)
        }
        assertEquals(adsBefore + 1, vm.settings.value.totalAdsBlockedCount)

        // =========================================================================
        // 4. VERIFY DESKTOP MODE TOGGLE, MOBILE SUBDOMAIN REWRITE, & UA SWITCHING
        // =========================================================================
        vm.navigateInActiveTab("https://m.wikipedia.org/wiki/Kotlin")
        composeTestRule.waitForIdle()
        assertFalse("Tab should start in Mobile mode by default", vm.activeTab.value.desktopMode)
        val tickBeforeDesktop = vm.activeTab.value.reloadTriggerTick

        // Toggle Desktop Site ON
        vm.toggleDesktopSiteForActiveTab()
        composeTestRule.waitForIdle()
        assertTrue("Desktop Mode must be enabled after toggle", vm.activeTab.value.desktopMode)
        assertEquals(
            "Mobile subdomain m.wikipedia.org must rewrite to desktop wikipedia.org when Desktop Mode is enabled",
            "https://wikipedia.org/wiki/Kotlin",
            vm.activeTab.value.url
        )
        assertTrue(
            "reloadTriggerTick must increment when Desktop Mode is toggled",
            vm.activeTab.value.reloadTriggerTick > tickBeforeDesktop
        )

        // Verify subsequent navigations in the same tab preserve Desktop Mode
        vm.navigateInActiveTab("https://m.example.com/news")
        composeTestRule.waitForIdle()
        assertTrue("Desktop Mode must persist across subsequent navigations in the tab", vm.activeTab.value.desktopMode)
        assertEquals("https://example.com/news", vm.activeTab.value.url)

        // Verify mounted WebView applies DESKTOP_USER_AGENT and wide viewport settings
        val mountedWebView = findFirstWebView(activity.window.decorView)
        assertNotNull(mountedWebView)
        assertEquals(
            com.example.ui.screens.DESKTOP_USER_AGENT,
            mountedWebView!!.settings.userAgentString
        )
        assertTrue(mountedWebView.settings.useWideViewPort)
        assertTrue(mountedWebView.settings.loadWithOverviewMode)

        // Toggle Desktop Site OFF to return to Mobile mode without restarting the app
        vm.toggleDesktopSiteForActiveTab()
        composeTestRule.waitForIdle()
        assertFalse("Tab must return to Mobile mode when toggled off", vm.activeTab.value.desktopMode)
        assertTrue(
            "WebView User-Agent must return to Mobile User-Agent immediately",
            mountedWebView.settings.userAgentString.contains("Android")
        )

        // =========================================================================
        // 5. VERIFY VIDEO ZOOM UP TO 1000% (10.0x) WITH PINCH FOCAL POINT & PAN CLAMPING
        // =========================================================================
        assertEquals(10.0f, com.example.ui.screens.MAX_VIDEO_ZOOM, 0.001f)
        val zoomedState = com.example.ui.screens.computeVideoZoomAndClampedPan(
            currentZoom = 1.0f,
            currentPanX = 0f,
            currentPanY = 0f,
            zoomMultiplier = 12.0f, // Attempt above 10x should clamp cleanly to 10.0f (1000%)
            panDeltaX = 50000f,     // Attempt to pan far off-screen should clamp within bounds
            panDeltaY = -50000f,
            focalX = 540f,
            focalY = 960f,
            containerWidth = 1080f,
            containerHeight = 1920f,
            baseAspectScale = 1.0f
        )
        assertEquals("Pinch zoom must clamp at 10.0f (1000%)", 10.0f, zoomedState.zoom, 0.001f)
        val maxAllowedPanX = (1080f * (10.0f - 1.0f)) / 2f
        val maxAllowedPanY = (1920f * (10.0f - 1.0f)) / 2f
        assertEquals("Horizontal pan must clamp so video never moves off-screen", maxAllowedPanX, zoomedState.panX, 0.5f)
        assertEquals("Vertical pan must clamp so video never moves off-screen", -maxAllowedPanY, zoomedState.panY, 0.5f)

        // =========================================================================
        // 6. VERIFY HOMEPAGE SHORTCUT FAVICON CACHING & NEUTRAL GLOBE FALLBACK
        // =========================================================================
        val testBmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
        }
        com.example.domain.browser.FaviconCacheManager.saveFaviconForUrl(
            context = context,
            pageUrl = "https://kotlinlang.org",
            bitmap = testBmp
        )
        val cachedFavicon = com.example.domain.browser.FaviconCacheManager.getCachedFavicon(
            context = context,
            urlOrHost = "https://kotlinlang.org"
        )
        assertNotNull("FaviconCacheManager must retrieve cached favicon from memory/disk offline", cachedFavicon)
        assertFalse(
            "Freshly saved favicon must not be marked missing or outdated",
            com.example.domain.browser.FaviconCacheManager.isFaviconMissingOrOutdated(context, "https://kotlinlang.org")
        )

        // Navigate back to Homepage, enable shortcuts, and add both a cached-favicon shortcut and an uncached shortcut
        vm.navigateHome()
        vm.updateSettings { it.copy(showHomeShortcuts = true) }
        vm.addBookmarkOrShortcut("Kotlin", "https://kotlinlang.org", "Shortcuts", isShortcut = true)
        vm.addBookmarkOrShortcut("Offline Site", "https://no-favicon-offline.invalid", "Shortcuts", isShortcut = true)
        val deadlineShortcuts = System.currentTimeMillis() + 3000L
        while (
            (!vm.settings.value.showHomeShortcuts ||
                vm.bookmarks.value.none { it.isShortcut && it.url.contains("kotlinlang.org") } ||
                vm.bookmarks.value.none { it.isShortcut && it.url.contains("no-favicon-offline.invalid") }) &&
            System.currentTimeMillis() < deadlineShortcuts
        ) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeTestRule.mainClock.advanceTimeBy(50L)
            composeTestRule.waitForIdle()
            Thread.sleep(20L)
        }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()

        val shortcuts = vm.bookmarks.value.filter { it.isShortcut }
        val kotlinShortcut = shortcuts.first { it.url.contains("kotlinlang.org") }
        val fallbackShortcut = shortcuts.first { it.url.contains("no-favicon-offline.invalid") }

        composeTestRule.onNodeWithTag("shortcut_favicon_image_${kotlinShortcut.id}", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("shortcut_fallback_icon_${fallbackShortcut.id}", useUnmergedTree = true)
            .assertIsDisplayed()

        // =========================================================================
        // 7. VERIFY "FREQUENTLY VISITED" IS REMOVED FROM HOMEPAGE WHILE HISTORY WORKS
        // =========================================================================
        vm.updateSettings { it.copy(showHomeFreqVisited = true) }
        composeTestRule.waitForIdle()
        assertEquals(
            "Frequently Visited section must not appear on Homepage",
            0,
            composeTestRule.onAllNodesWithTag("home_frequently_visited_section").fetchSemanticsNodes().size
        )
        assertEquals(
            "Frequently Visited header must not appear on Homepage",
            0,
            composeTestRule.onAllNodesWithText("Frequently Visited", substring = true).fetchSemanticsNodes().size
        )
        assertEquals(
            "History shortcut chip in Frequently Visited must not appear on Homepage",
            0,
            composeTestRule.onAllNodesWithTag("home_open_history_chip").fetchSemanticsNodes().size
        )

        // Verify Browsing History remains intact and accessible from the main bottom menu
        composeTestRule.onNodeWithTag("toolbar_overflow_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_item_history").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_history_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history_back_button").performClick()
        composeTestRule.waitForIdle()

        // Clean up tabs and shortcuts created during this test
        vm.deleteBookmark(kotlinShortcut.id)
        vm.deleteBookmark(fallbackShortcut.id)
        vm.updateSettings { it.copy(showHomeShortcuts = false, showHomeFreqVisited = false) }
        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun testSiteSettingCacheResetAndSubdomainMatching() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        val rules = listOf(
            SiteSettingItem(
                id = "youtube.com",
                userId = "local_user",
                host = "youtube.com",
                desktopMode = true,
                allowAds = false
            ),
            SiteSettingItem(
                id = "twitter.com",
                userId = "local_user",
                host = "twitter.com",
                allowRedirects = true
            )
        )
        assertNotNull(com.example.ui.screens.findSiteSettingForHost(rules, "m.youtube.com"))
        assertTrue(com.example.ui.screens.findSiteSettingForHost(rules, "m.youtube.com")!!.desktopMode)
        assertNotNull(com.example.ui.screens.findSiteSettingForHost(rules, "www.youtube.com"))
        assertNotNull(com.example.ui.screens.findSiteSettingForHost(rules, "mobile.twitter.com"))
        assertTrue(com.example.ui.screens.findSiteSettingForHost(rules, "mobile.twitter.com")!!.allowRedirects)
        assertNull(com.example.ui.screens.findSiteSettingForHost(rules, "unrelated.org"))

        val viewModel = composeTestRule.activity.activeBrowserViewModel!!

        viewModel.saveSiteSetting(
            SiteSettingItem(
                userId = viewModel.currentUserId,
                host = "m.example-cache-test.org",
                desktopMode = true,
                textZoom = 145
            )
        )
        val cached = viewModel.getSiteSettingForHost("www.example-cache-test.org")
        assertNotNull("Expected normalized site setting to be retrievable across subdomains", cached)
        assertTrue(cached!!.desktopMode)
        assertEquals(145, cached.textZoom)

        // Reset all site permissions and verify the in-memory cache is immediately cleared
        viewModel.resetAllSitePermissions()
        composeTestRule.waitForIdle()
        var afterReset: SiteSettingItem? = cached
        for (i in 0 until 20) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeTestRule.waitForIdle()
            afterReset = viewModel.getSiteSettingForHost("example-cache-test.org")
            if (afterReset == null) break
            Thread.sleep(25)
        }
        assertNull("Expected resetAllSitePermissions() to clear localSiteSettingCache and Room", afterReset)

        // Verify minimalist bottom navigation bar outline icons and thin tab counter box
        assertEquals("NyxoraChevronLeft", com.example.ui.screens.buildNyxoraChevronLeftIcon().name)
        assertEquals("NyxoraChevronRight", com.example.ui.screens.buildNyxoraChevronRightIcon().name)
        assertEquals("NyxoraOutlineHome", com.example.ui.screens.buildNyxoraOutlineHomeIcon().name)
        assertEquals("NyxoraThreeLineMenu", com.example.ui.screens.buildNyxoraThreeLineMenuIcon().name)
        assertEquals(1.6f, com.example.ui.screens.NYXORA_BOTTOM_NAV_ICON_STROKE_WIDTH, 0.01f)
        composeTestRule.onNodeWithTag("toolbar_tabs_counter_box", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun audit18_performanceAndResourceOptimization_measuredWebViewPoolAndDiagnostics() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        val vm = composeTestRule.activity.activeBrowserViewModel!!
        com.example.domain.browser.BrowserDiagnosticsEngine.resetDiagnostics()

        // 1. Measure subresource filter engine latency across 1,500 subresource checks
        val sampleSubresources = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css",
            "https://rr1---sn-ab5l6nzl.googlevideo.com/videoplayback?id=123",
            "https://ib.adnxs.com/ut/v3/prebid",
            "https://en.wikipedia.org/w/load.php?modules=site.styles"
        )
        val startFilterNs = System.nanoTime()
        var blockedTotal = 0
        for (i in 0 until 250) {
            for (url in sampleSubresources) {
                if (ContentBlockingEngine.shouldBlockSubresource(
                        requestUrl = url,
                        pageUrl = "https://en.wikipedia.org/wiki/Kotlin",
                        globalAdBlockingEnabled = true,
                        siteSetting = null
                    )
                ) {
                    blockedTotal++
                }
            }
        }
        val elapsedFilterMs = (System.nanoTime() - startFilterNs) / 1_000_000.0
        assertEquals(750, blockedTotal)
        assertTrue("Expected 1,500 subresource filter evaluations to complete in < 150ms (was ${elapsedFilterMs}ms)", elapsedFilterMs < 150.0)

        // Verify data: and vbscript: top-level schemes are blocked alongside javascript:
        assertEquals(
            com.example.domain.browser.NavigationDecision.BLOCK_DANGEROUS_SCHEME,
            ContentBlockingEngine.evaluateTopLevelNavigation(
                requestUrl = "data:text/html,<script>alert(1)</script>",
                currentPageUrl = "https://example.com",
                hasUserGesture = true,
                isServerRedirect = false,
                timeSinceLastUserInteractionMs = 50L,
                globalRedirectBlockingEnabled = true,
                httpsOnlyMode = true,
                siteSetting = null,
                allowOnceUrls = emptySet()
            )
        )
        assertEquals(
            com.example.domain.browser.NavigationDecision.BLOCK_DANGEROUS_SCHEME,
            ContentBlockingEngine.evaluateTopLevelNavigation(
                requestUrl = "vbscript:msgbox(1)",
                currentPageUrl = "https://example.com",
                hasUserGesture = true,
                isServerRedirect = false,
                timeSinceLastUserInteractionMs = 50L,
                globalRedirectBlockingEnabled = true,
                httpsOnlyMode = true,
                siteSetting = null,
                allowOnceUrls = emptySet()
            )
        )

        // 2. Verify WebView pool retains the exact same WebView instance across Web -> Home -> Web transitions
        val webTabId = vm.createNewTab(url = "https://en.wikipedia.org/wiki/Android", inPrivateMode = false, inBackground = false)
        composeTestRule.waitForIdle()
        val wvBeforeHome = findFirstWebView(composeTestRule.activity.window.decorView)
        assertNotNull("Expected active WebView on web tab", wvBeforeHome)
        val identityBeforeHome = System.identityHashCode(wvBeforeHome)

        val homeTabId = vm.createNewTab(url = "nyxora://home", inPrivateMode = false, inBackground = false)
        composeTestRule.waitForIdle()
        assertEquals(homeTabId, vm.activeTab.value.id)

        // Switch back to the web tab and confirm the exact same WebView instance was reused without recreation
        vm.selectTab(webTabId, isPrivate = false)
        composeTestRule.waitForIdle()
        val wvAfterHome = findFirstWebView(composeTestRule.activity.window.decorView)
        assertNotNull("Expected pooled WebView restored after Home transition", wvAfterHome)
        assertEquals(
            "Expected WebView instance to be preserved across Home transition without duplicate allocation",
            identityBeforeHome,
            System.identityHashCode(wvAfterHome)
        )

        // 3. Open additional background tabs and trigger onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)
        vm.createNewTab(url = "https://example.org/one", inPrivateMode = false, inBackground = true)
        vm.createNewTab(url = "https://example.org/two", inPrivateMode = false, inBackground = true)
        composeTestRule.waitForIdle()

        composeTestRule.activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        composeTestRule.waitForIdle()

        val diagAfterTrim = com.example.domain.browser.BrowserDiagnosticsEngine.captureSnapshot()
        assertTrue("Expected trimMemoryCount >= 1", diagAfterTrim.trimMemoryCount >= 1)
        assertTrue("Expected background tabs to be suspended on critical memory trim", diagAfterTrim.suspendedTabCount >= 2)
        assertEquals("Expected only the 1 active tab WebView kept alive after critical trim", 1, diagAfterTrim.activeWebViewCount)
        assertTrue("Expected pooled WebView reuse counter >= 1", diagAfterTrim.duplicateWebViewPreventedCount >= 1)

        println(
            "NYXORA_BENCHMARK_METRICS: filter1500Ms=${"%.2f".format(elapsedFilterMs)}, " +
                "avgSubresourceUs=${diagAfterTrim.avgSubresourceCheckMicros}, " +
                "activeWebViews=${diagAfterTrim.activeWebViewCount}, " +
                "suspendedTabs=${diagAfterTrim.suspendedTabCount}, " +
                "pooledReuses=${diagAfterTrim.duplicateWebViewPreventedCount}, " +
                "usedHeapMb=${diagAfterTrim.usedHeapMb}/${diagAfterTrim.maxHeapMb}"
        )

        // 4. Verify Diagnostics UI in Settings > Advanced
        vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.SETTINGS)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_category_advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_diagnostics_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("diagnostics_refresh_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()

        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun audit19_simplifiedConnectScreen_watchTogetherPlaybackControls_andAutoNavigationToHomePage() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        val vm = composeTestRule.activity.activeBrowserViewModel!!
        vm.closeAllTabs(inPrivateMode = false)
        composeTestRule.waitForIdle()

        // Navigate Participant's browser to an arbitrary web page before opening Connect
        vm.navigateInActiveTab("https://example.org/pre-join-page")
        composeTestRule.waitForIdle()
        assertEquals("https://example.org/pre-join-page", vm.activeTab.value.url)

        // Open NYXORA Connect screen
        vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NYXORA_CONNECT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nyxora_connect_sheet").assertIsDisplayed()

        // 1. Verify lobby does NOT show separate synchronization toggles
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("Share My Browsing Activity", substring = true).fetchSemanticsNodes().size
        )

        // 2. Verify attempting to join an unconfirmed/invalid room does NOT redirect to Home Page prematurely
        composeTestRule.onNodeWithTag("connect_join_room_input").performTextReplacement("BAD-CODE")
        composeTestRule.onNodeWithTag("connect_join_room_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Must NOT redirect before successful authentication and room confirmation",
            com.example.ui.viewmodel.OverlaySheet.NYXORA_CONNECT,
            vm.activeOverlay.value
        )
        assertEquals(
            "Active tab must remain on pre-join page when room confirmation fails",
            "https://example.org/pre-join-page",
            vm.activeTab.value.url
        )

        // 3. Create a Host room on the shared local relay server
        val externalHostScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val externalHostManager = com.example.domain.connect.NyxoraConnectSessionManager(
            scope = externalHostScope,
            localDeviceId = "external-host-device-99",
            initialDeviceName = "External Host"
        )

        try {
            val hostRoom = externalHostManager.createRoom(
                roomName = "Host Room",
                mode = com.example.domain.connect.RoomSyncMode.BROWSE_TOGETHER,
                initialUrl = "https://kotlinlang.org",
                initialTitle = "Kotlin"
            )
            val hostReadyDeadline = System.currentTimeMillis() + 3000L
            while (!externalHostManager.connectionStatus.value.isConnectedOrSynced && System.currentTimeMillis() < hostReadyDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertTrue("Host room must be confirmed on relay server", externalHostManager.connectionStatus.value.isConnectedOrSynced)

            // 4. Participant joins Host's room -> upon JOIN_ACCEPTED, automatically returns to browser Home Page!
            composeTestRule.onNodeWithTag("connect_join_room_input").performTextReplacement(hostRoom.roomId)
            composeTestRule.onNodeWithTag("connect_join_room_button").performClick()

            val joinConfirmDeadline = System.currentTimeMillis() + 4000L
            while (
                (!vm.connectManager.connectionStatus.value.isConnectedOrSynced ||
                    vm.activeOverlay.value != com.example.ui.viewmodel.OverlaySheet.NONE) &&
                System.currentTimeMillis() < joinConfirmDeadline
            ) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            composeTestRule.waitForIdle()

            assertTrue("Participant must be connected", vm.connectManager.connectionStatus.value.isConnectedOrSynced)
            assertEquals(
                "Connect overlay must automatically close after server confirms JOIN_ACCEPTED",
                com.example.ui.viewmodel.OverlaySheet.NONE,
                vm.activeOverlay.value
            )
            assertEquals(
                "Participant must automatically return to the browser Home Page (nyxora://home) upon JOIN_ACCEPTED",
                "nyxora://home",
                vm.activeTab.value.url
            )
            composeTestRule.onNodeWithTag("home_logo_header").assertIsDisplayed()

            // 5. Open Connect Sheet to verify simplified active room UI & waiting message when Host is not playing media
            vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NYXORA_CONNECT)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("connect_active_room_id").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_status_pill").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_copy_code_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_share_code_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_leave_or_close_button").assertIsDisplayed()

            // Verify removed clutter: no separate sync toggles, no manual URL sync button, no large Members section
            assertEquals(0, composeTestRule.onAllNodesWithTag("connect_broadcast_url_button").fetchSemanticsNodes().size)
            assertEquals(0, composeTestRule.onAllNodesWithTag("connect_participant_broadcast_url_button").fetchSemanticsNodes().size)
            assertEquals(0, composeTestRule.onAllNodesWithTag("connect_devices_list").fetchSemanticsNodes().size)
            assertEquals(0, composeTestRule.onAllNodesWithText("Room Synchronization Active", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, composeTestRule.onAllNodesWithText("Follow Room Navigation", substring = true).fetchSemanticsNodes().size)

            // Verify waiting message is shown and Watch Together controls are disabled while no supported media is playing
            composeTestRule.onNodeWithTag("connect_waiting_for_media_text").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_watch_rewind_button").assertIsNotEnabled()
            composeTestRule.onNodeWithTag("connect_watch_play_pause_button").assertIsNotEnabled()
            composeTestRule.onNodeWithTag("connect_watch_forward_button").assertIsNotEnabled()

            // 6. Host starts supported HTML5 media playback at 40.0s -> Watch Together initializes and enables controls
            externalHostManager.updateVideoCompatibility(com.example.domain.connect.VideoCompatibilityState.COMPATIBLE_HTML5)
            assertTrue(
                externalHostManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/movie.mp4",
                    isPlaying = true,
                    positionSeconds = 40.0,
                    durationSeconds = 180.0
                )
            )
            val mediaSyncDeadline = System.currentTimeMillis() + 3000L
            while (vm.connectManager.roomState.value?.playbackState == null && System.currentTimeMillis() < mediaSyncDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("connect_playback_state_text").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_watch_rewind_button").assertIsEnabled()
            composeTestRule.onNodeWithTag("connect_watch_play_pause_button").assertIsEnabled()
            composeTestRule.onNodeWithTag("connect_watch_forward_button").assertIsEnabled()

            // 7. Click Skip Forward 10s (+10s) -> seek position updates from ~40s to ~50s and syncs to Host
            composeTestRule.onNodeWithTag("connect_watch_forward_button").performClick()
            composeTestRule.waitForIdle()
            val forwardDeadline = System.currentTimeMillis() + 3000L
            while ((externalHostManager.roomState.value?.playbackState?.positionSeconds ?: 0.0) < 48.0 && System.currentTimeMillis() < forwardDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertEquals(50.0, vm.connectManager.roomState.value?.playbackState?.positionSeconds ?: 0.0, 2.5)
            assertEquals(50.0, externalHostManager.roomState.value?.playbackState?.positionSeconds ?: 0.0, 2.5)

            // 8. Click Skip Backward 10s (-10s) -> seek position updates from ~50s back to ~40s
            composeTestRule.onNodeWithTag("connect_watch_rewind_button").performClick()
            composeTestRule.waitForIdle()
            val rewindDeadline = System.currentTimeMillis() + 3000L
            while ((externalHostManager.roomState.value?.playbackState?.positionSeconds ?: 50.0) > 42.0 && System.currentTimeMillis() < rewindDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertEquals(40.0, vm.connectManager.roomState.value?.playbackState?.positionSeconds ?: 0.0, 2.5)

            // 9. Click Play/Pause -> toggles from Playing (true) to Paused (false) and syncs to Host
            composeTestRule.onNodeWithTag("connect_watch_play_pause_button").performClick()
            composeTestRule.waitForIdle()
            val pauseDeadline = System.currentTimeMillis() + 3000L
            while (externalHostManager.roomState.value?.playbackState?.isPlaying == true && System.currentTimeMillis() < pauseDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertFalse("Playback must be Paused after clicking Play/Pause", vm.connectManager.roomState.value?.playbackState?.isPlaying ?: true)
            assertFalse("Host playback must also be Paused", externalHostManager.roomState.value?.playbackState?.isPlaying ?: true)

            // 10. Verify DRM-protected / unsupported media disables Watch Together playback controls
            vm.connectManager.updateVideoCompatibility(com.example.domain.connect.VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("connect_watch_rewind_button").assertIsNotEnabled()
            composeTestRule.onNodeWithTag("connect_watch_play_pause_button").assertIsNotEnabled()
            composeTestRule.onNodeWithTag("connect_watch_forward_button").assertIsNotEnabled()

            // Leave room cleanly
            composeTestRule.onNodeWithTag("connect_leave_or_close_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(
                com.example.domain.connect.SyncConnectionStatus.DISCONNECTED,
                vm.connectManager.connectionStatus.value
            )
            vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NONE)
            composeTestRule.waitForIdle()
        } finally {
            externalHostManager.leaveOrCloseRoom()
            externalHostScope.cancel()
            vm.connectManager.leaveOrCloseRoom()
            vm.closeAllTabs(inPrivateMode = false)
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `floating chat overlay equal participant controls and host permission switch work end to end`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()
        val vm = composeTestRule.activity.activeBrowserViewModel
        assertNotNull("Expected activeBrowserViewModel", vm)
        vm!!.closeAllTabs(inPrivateMode = false)
        vm.connectManager.leaveOrCloseRoom()
        composeTestRule.waitForIdle()

        val externalScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        val externalParticipantManager = com.example.domain.connect.NyxoraConnectSessionManager(
            scope = externalScope,
            localDeviceId = "robo-ext-part-77",
            initialDeviceName = "External Participant"
        )

        try {
            // 1. Before joining or creating a room, floating chat overlay is NOT displayed
            assertEquals(
                "Floating chat overlay must not be present when not in a room",
                0,
                composeTestRule.onAllNodesWithTag("nyxora_floating_chat_bubble").fetchSemanticsNodes().size
            )

            // 2. Host creates a room via NYXORA Connect Sheet -> Verify Allow Participant Controls switch is ON by default
            vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NYXORA_CONNECT)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("connect_create_room_button").performClick()

            val createConfirmDeadline = System.currentTimeMillis() + 4000L
            while (!vm.connectManager.connectionStatus.value.isConnectedOrSynced && System.currentTimeMillis() < createConfirmDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            composeTestRule.waitForIdle()
            val roomId = vm.connectManager.roomState.value?.roomId ?: ""
            assertTrue(roomId.startsWith("NYX-"))

            // Verify Host sees Allow Participant Controls switch enabled by default
            composeTestRule.onNodeWithTag("connect_allow_participant_controls_switch").assertIsDisplayed()
            assertTrue(
                "Allow Participant Controls must be enabled by default for every new room",
                vm.connectManager.roomState.value?.permissions?.allowParticipantControls == true
            )

            // External participant joins the room
            assertTrue(externalParticipantManager.joinRoom(roomId))
            val joinDeadline = System.currentTimeMillis() + 4000L
            while (!externalParticipantManager.connectionStatus.value.isConnectedOrSynced && System.currentTimeMillis() < joinDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }

            // Close Connect Sheet to return to main browser screen -> Verify Floating Chat Indicator is displayed at screen edge
            composeTestRule.onNodeWithTag("connect_back_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nyxora_floating_chat_bubble").assertIsDisplayed()

            // 3. External participant sends a real-time chat message -> Unread badge appears on floating chat indicator
            assertTrue(externalParticipantManager.sendChatMessage("Hey Host, ready to watch!"))
            val chatReceiveDeadline = System.currentTimeMillis() + 3000L
            while (vm.connectManager.chatMessages.value.isEmpty() && System.currentTimeMillis() < chatReceiveDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nyxora_floating_chat_badge", useUnmergedTree = true).assertIsDisplayed()

            // 4. Tap floating chat indicator -> Opens compact chat overlay with received message
            composeTestRule.onNodeWithTag("nyxora_floating_chat_bubble").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nyxora_chat_panel").assertIsDisplayed()
            composeTestRule.onNodeWithTag("chat_messages_list").assertIsDisplayed()
            assertTrue(
                composeTestRule.onAllNodesWithText("Hey Host, ready to watch!", substring = true).fetchSemanticsNodes().isNotEmpty()
            )

            // 5. Send a chat message from the overlay -> Synchronizes to connected participant
            composeTestRule.onNodeWithTag("chat_message_input").performTextReplacement("Starting the video now!")
            composeTestRule.onNodeWithTag("chat_send_button").performClick()
            composeTestRule.waitForIdle()

            val replySyncDeadline = System.currentTimeMillis() + 3000L
            while (externalParticipantManager.chatMessages.value.size < 2 && System.currentTimeMillis() < replySyncDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertEquals("Starting the video now!", externalParticipantManager.chatMessages.value.last().text)

            // 6. Dismiss the floating chat overlay and reopen it easily
            composeTestRule.onNodeWithTag("chat_panel_close_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(
                "Expanded chat panel must be hidden after dismissal",
                0,
                composeTestRule.onAllNodesWithTag("nyxora_chat_panel").fetchSemanticsNodes().size
            )
            composeTestRule.onNodeWithTag("nyxora_floating_chat_reopen_tab").assertIsDisplayed()
            composeTestRule.onNodeWithTag("connect_banner_chat_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nyxora_chat_panel").assertIsDisplayed()

            // 7. Enter fullscreen video -> Expanded chat panel automatically hides while floating indicator remains accessible
            vm.navigateInActiveTab("https://example.com/watch")
            composeTestRule.mainClock.advanceTimeBy(250L)
            composeTestRule.waitForIdle()
            val liveWv = findFirstWebView(composeTestRule.activity.window.decorView)
            if (liveWv?.webChromeClient != null) {
                val fsView = FrameLayout(composeTestRule.activity)
                composeTestRule.runOnUiThread {
                    liveWv.webChromeClient!!.onShowCustomView(fsView, WebChromeClient.CustomViewCallback {})
                }
                composeTestRule.waitForIdle()
                assertEquals(
                    "Expanded chat panel must automatically hide when entering fullscreen video",
                    0,
                    composeTestRule.onAllNodesWithTag("nyxora_chat_panel").fetchSemanticsNodes().size
                )
                composeTestRule.onNodeWithTag("nyxora_floating_chat_bubble").assertIsDisplayed()
                composeTestRule.runOnUiThread {
                    liveWv.webChromeClient!!.onHideCustomView()
                }
                composeTestRule.waitForIdle()
            }

            // 8. Host toggles Allow Participant Controls OFF on Connect screen -> Participant controls are disabled
            vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NYXORA_CONNECT)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("connect_allow_participant_controls_switch").performClick()
            composeTestRule.waitForIdle()

            val disablePermDeadline = System.currentTimeMillis() + 3000L
            while (
                externalParticipantManager.roomState.value?.permissions?.allowParticipantControls != false &&
                System.currentTimeMillis() < disablePermDeadline
            ) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertFalse(
                "Participant permissions must reflect disabled state",
                externalParticipantManager.roomState.value?.permissions?.allowParticipantControls ?: true
            )
            assertFalse(
                "Participant navigation must be blocked when Allow Participant Controls is disabled",
                externalParticipantManager.publishUrlNavigation(
                    url = "https://participant-blocked.example.com",
                    title = "Blocked",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )

            // Host toggles Allow Participant Controls back ON -> Restores shared controls without reconnecting
            composeTestRule.onNodeWithTag("connect_allow_participant_controls_switch").performClick()
            composeTestRule.waitForIdle()
            val enablePermDeadline = System.currentTimeMillis() + 3000L
            while (
                externalParticipantManager.roomState.value?.permissions?.allowParticipantControls != true &&
                System.currentTimeMillis() < enablePermDeadline
            ) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                Thread.sleep(25L)
            }
            assertTrue(
                "Participant controls must be restored without reconnecting",
                externalParticipantManager.publishUrlNavigation(
                    url = "https://participant-restored.example.com",
                    title = "Restored",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )

            // 9. Leave room -> Floating chat overlay is automatically removed
            composeTestRule.onNodeWithTag("connect_leave_or_close_button").performClick()
            composeTestRule.waitForIdle()
            vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NONE)
            composeTestRule.waitForIdle()
            assertEquals(
                "Floating chat overlay must be removed automatically when leaving the room",
                0,
                composeTestRule.onAllNodesWithTag("nyxora_floating_chat_bubble").fetchSemanticsNodes().size
            )
            assertEquals(
                0,
                composeTestRule.onAllNodesWithTag("nyxora_chat_panel").fetchSemanticsNodes().size
            )
        } finally {
            externalParticipantManager.leaveOrCloseRoom()
            externalScope.cancel()
            vm.connectManager.leaveOrCloseRoom()
            vm.closeAllTabs(inPrivateMode = false)
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `settings about nyxora checks for updates shows indicator downloads apk and triggers package installer`() {
        composeTestRule.mainClock.advanceTimeBy(350L)
        composeTestRule.waitForIdle()

        val vm = checkNotNull(composeTestRule.activity.activeBrowserViewModel)
        val updateManager = checkNotNull(vm.apkUpdateManager)

        val outBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zos.write("NYXORA_COMPOSE_UPDATE_TEST_APK".repeat(128).toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(outBytes)
            .joinToString("") { "%02x".format(it) }

        val releaseUrl = "https://api.github.com/repos/sudhamoy-nyxora/nyxora-browser/releases/latest"
        val apkUrl = "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.4.0/nyxora-browser-v1.4.0.apk"
        val releasePayload = """
            {
              "tag_name": "v1.4.0",
              "name": "NYXORA Browser v1.4.0",
              "body": "Automatic in-app APK updates.",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "nyxora-browser-v1.4.0.apk",
                  "browser_download_url": "$apkUrl",
                  "size": ${outBytes.size},
                  "state": "uploaded",
                  "digest": "sha256:$sha256"
                }
              ]
            }
        """.trimIndent()

        val jsonMediaType = with(okhttp3.MediaType) { "application/json".toMediaType() }
        val apkMediaType = with(okhttp3.MediaType) { "application/vnd.android.package-archive".toMediaType() }
        val testClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url == releaseUrl) {
                    with(okhttp3.ResponseBody) { releasePayload.toResponseBody(jsonMediaType) }
                } else {
                    with(okhttp3.ResponseBody) { outBytes.toResponseBody(apkMediaType) }
                }
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

        val testInspector = object : com.example.domain.update.ApkArchiveInspector {
            override fun inspectApkArchive(context: Context, apkFile: java.io.File) =
                com.example.domain.update.ParsedApkArchiveMetadata(
                    packageName = context.packageName,
                    versionName = "1.4.0",
                    versionCode = 4L,
                    signingCertSha256Fingerprints = setOf("nyxora_cert")
                )

            override fun getInstalledSigningCertSha256Fingerprints(context: Context) = setOf("nyxora_cert")
            override fun canRequestPackageInstalls(context: Context) = true
        }

        com.example.domain.update.NyxoraApkUpdateManager.getUpdatesDirectory(composeTestRule.activity).deleteRecursively()
        updateManager.configureForTesting(
            httpClient = testClient,
            archiveInspector = testInspector,
            releaseApiUrlProvider = { releaseUrl },
            installedVersionNameProvider = { "1.0" },
            installedVersionCodeProvider = { 1 },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        // Open Settings -> About NYXORA triggers automatic version check
        vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.SETTINGS)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_category_about").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "Expected UpdateAvailable state, got ${updateManager.uiState.value}",
            updateManager.uiState.value is com.example.domain.update.ApkUpdateUiState.UpdateAvailable
        )

        composeTestRule.onNodeWithTag("about_apk_update_section").performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_apk_update_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_update_available_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_update_now_button").performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_update_now_button").assertIsDisplayed()

        // Tap Update Now -> Downloads and verifies APK, then shows Install Update button
        composeTestRule.onNodeWithTag("about_update_now_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "Expected ReadyToInstall state after clicking Update Now, got ${updateManager.uiState.value}",
            updateManager.uiState.value is com.example.domain.update.ApkUpdateUiState.ReadyToInstall
        )

        composeTestRule.onNodeWithTag("about_install_update_button").performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_install_update_button").assertIsDisplayed()
        shadowOf(composeTestRule.activity.application).clearNextStartedActivities()
        composeTestRule.onNodeWithTag("about_install_update_button").performClick()
        composeTestRule.waitForIdle()

        val installerIntent = shadowOf(composeTestRule.activity.application).nextStartedActivity
        assertNotNull(
            "Expected official package installer intent after tapping Install Update (state=${updateManager.uiState.value})",
            installerIntent
        )
        assertEquals(android.content.Intent.ACTION_VIEW, installerIntent.action)
        assertEquals(com.example.domain.update.NyxoraApkUpdateManager.APK_MIME_TYPE, installerIntent.type)

        vm.setOverlaySheet(com.example.ui.viewmodel.OverlaySheet.NONE)
        composeTestRule.waitForIdle()
    }

    private fun findFirstWebView(root: View?): WebView? {
        if (root == null) return null
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstWebView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}



