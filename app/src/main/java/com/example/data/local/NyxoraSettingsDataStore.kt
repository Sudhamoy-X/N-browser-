package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nyxoraDataStore: DataStore<Preferences> by preferencesDataStore(name = "nyxora_settings")

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

enum class SearchEngine(val label: String, val searchUrlTemplate: String, val suggestUrlTemplate: String) {
    GOOGLE(
        "Google",
        "https://www.google.com/search?q=%s",
        "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"
    ),
    DUCKDUCKGO(
        "DuckDuckGo",
        "https://duckduckgo.com/?q=%s",
        "https://ac.duckduckgo.com/ac/?q=%s&type=list"
    ),
    BRAVE(
        "Brave Search",
        "https://search.brave.com/search?q=%s",
        "https://search.brave.com/api/suggest?q=%s"
    ),
    STARTPAGE(
        "Startpage",
        "https://www.startpage.com/do/dsearch?query=%s",
        "https://www.startpage.com/osuggestions?q=%s"
    ),
    ECOSIA(
        "Ecosia",
        "https://www.ecosia.org/search?q=%s",
        "https://ac.ecosia.org/autocomplete?q=%s&type=list"
    )
}

enum class FullscreenOrientationMode(val label: String) {
    KEEP_CURRENT("Keep Current Orientation"),
    PORTRAIT("Portrait"),
    ALWAYS_LANDSCAPE("Always Landscape"),
    AUTO("Auto (Preserve Current Orientation)")
}

enum class VideoAspectMode(val label: String) {
    FIT("Fit to Screen"),
    FILL("Fill / Crop"),
    ORIGINAL("Original Ratio")
}

data class NyxoraSettingsState(
    // Appearance & Customization (Optional homepage sections default to false for clean fresh start)
    val themeMode: ThemeMode = ThemeMode.DARK,
    val homepageUrl: String = "nyxora://home",
    val homepageShortcutsEnabled: Boolean = true,
    val restoreTabsOnStartup: Boolean = true,
    val showHomeGreeting: Boolean = false,
    val showHomeShortcuts: Boolean = false,
    val showHomeFreqVisited: Boolean = false,
    val showHomeRecentActivity: Boolean = false,
    val showHomeQuickTools: Boolean = false,
    val showAmbientHomeBackground: Boolean = false,
    val globalTextZoom: Int = 100,
    val compactBottomToolbar: Boolean = false,
    val reducedMotion: Boolean = false,
    val preferredLanguage: String = "System Default",
    // Search (Google is the default search engine; other engines stay selectable in Settings)
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val searchSuggestionsEnabled: Boolean = true,
    // Optional Account & Sync (OFF by default — never required or prompted at launch)
    val cloudSyncEnabled: Boolean = false,
    // Site Content & Permissions Defaults
    val javascriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val doNotTrackEnabled: Boolean = true,
    val clipboardAccessEnabled: Boolean = true,
    val defaultLocationPermission: String = "ASK",
    val defaultCameraPermission: String = "ASK",
    val defaultMicPermission: String = "ASK",
    val defaultNotificationPermission: String = "ASK",
    // Privacy & Security
    val adBlockingEnabled: Boolean = true,
    val popupBlockingEnabled: Boolean = true,
    val redirectBlockingEnabled: Boolean = true,
    val showBlockedMessages: Boolean = true,
    val httpsOnlyMode: Boolean = false,
    val blockThirdPartyCookies: Boolean = true,
    val incognitoBlockScreenshots: Boolean = true,
    val privateTabsLockEnabled: Boolean = false,
    val privateTabsPinHash: String = "",
    // Blocking Metrics
    val totalAdsBlockedCount: Long = 0L,
    val totalPopupsBlockedCount: Long = 0L,
    val totalRedirectsBlockedCount: Long = 0L,
    val lastFilterUpdateMillis: Long = 0L,
    val filterRuleCount: Int = 48,
    // Downloads
    val downloadConfirmRiskyFiles: Boolean = true,
    val downloadNotificationsEnabled: Boolean = true,
    // Gestures
    val edgeSwipeBackForward: Boolean = true,
    val pullToRefreshEnabled: Boolean = true,
    // Video
    val fullscreenOrientationMode: FullscreenOrientationMode = FullscreenOrientationMode.KEEP_CURRENT,
    val defaultVideoAspectMode: VideoAspectMode = VideoAspectMode.FIT,
    val pipEnabled: Boolean = true,
    val keepScreenOnWhilePlaying: Boolean = true,
    val backgroundAudioEnabled: Boolean = false,
    // Advanced
    val defaultDesktopSite: Boolean = false,
    val dataSaverBlockImages: Boolean = false,
    val forceDarkWebPages: Boolean = false,
    val hideCookieConsentBanners: Boolean = true,
    // NYXORA Connect Permanent Backend Configuration
    val connectBackendEndpoint: String = com.example.domain.connect.NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT
)

class NyxoraSettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HOMEPAGE_URL = stringPreferencesKey("homepage_url")
        val HOMEPAGE_SHORTCUTS_ENABLED = booleanPreferencesKey("homepage_shortcuts_enabled")
        val RESTORE_TABS_ON_STARTUP = booleanPreferencesKey("restore_tabs_on_startup")
        val SHOW_HOME_GREETING = booleanPreferencesKey("show_home_greeting")
        val SHOW_HOME_SHORTCUTS = booleanPreferencesKey("show_home_shortcuts")
        val SHOW_HOME_FREQ = booleanPreferencesKey("show_home_freq")
        val SHOW_HOME_RECENT = booleanPreferencesKey("show_home_recent")
        val SHOW_HOME_QUICK_TOOLS = booleanPreferencesKey("show_home_quick_tools")
        val SHOW_AMBIENT_BG = booleanPreferencesKey("show_ambient_bg")
        val GLOBAL_TEXT_ZOOM = intPreferencesKey("global_text_zoom")
        val COMPACT_TOOLBAR = booleanPreferencesKey("compact_toolbar")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")

        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val SEARCH_SUGGESTIONS = booleanPreferencesKey("search_suggestions")

        val CLOUD_SYNC_ENABLED = booleanPreferencesKey("cloud_sync_enabled")

        val JAVASCRIPT_ENABLED = booleanPreferencesKey("javascript_enabled")
        val COOKIES_ENABLED = booleanPreferencesKey("cookies_enabled")
        val DO_NOT_TRACK_ENABLED = booleanPreferencesKey("do_not_track_enabled")
        val CLIPBOARD_ACCESS_ENABLED = booleanPreferencesKey("clipboard_access_enabled")
        val DEFAULT_LOCATION_PERMISSION = stringPreferencesKey("default_location_permission")
        val DEFAULT_CAMERA_PERMISSION = stringPreferencesKey("default_camera_permission")
        val DEFAULT_MIC_PERMISSION = stringPreferencesKey("default_mic_permission")
        val DEFAULT_NOTIFICATION_PERMISSION = stringPreferencesKey("default_notification_permission")

        val AD_BLOCKING = booleanPreferencesKey("ad_blocking")
        val POPUP_BLOCKING = booleanPreferencesKey("popup_blocking")
        val REDIRECT_BLOCKING = booleanPreferencesKey("redirect_blocking")
        val SHOW_BLOCKED_MSG = booleanPreferencesKey("show_blocked_msg")
        val HTTPS_ONLY = booleanPreferencesKey("https_only")
        val BLOCK_3P_COOKIES = booleanPreferencesKey("block_3p_cookies")
        val INCOGNITO_BLOCK_SCREENSHOTS = booleanPreferencesKey("incognito_block_screenshots")
        val PRIVATE_TABS_LOCK = booleanPreferencesKey("private_tabs_lock")
        val PRIVATE_TABS_PIN = stringPreferencesKey("private_tabs_pin")

        val ADS_BLOCKED_COUNT = longPreferencesKey("ads_blocked_count")
        val POPUPS_BLOCKED_COUNT = longPreferencesKey("popups_blocked_count")
        val REDIRECTS_BLOCKED_COUNT = longPreferencesKey("redirects_blocked_count")
        val LAST_FILTER_UPDATE = longPreferencesKey("last_filter_update")
        val FILTER_RULE_COUNT = intPreferencesKey("filter_rule_count")

        val CONFIRM_RISKY_FILES = booleanPreferencesKey("confirm_risky_files")
        val DOWNLOAD_NOTIFICATIONS = booleanPreferencesKey("download_notifications")

        val EDGE_SWIPE = booleanPreferencesKey("edge_swipe")
        val PULL_TO_REFRESH = booleanPreferencesKey("pull_to_refresh")

        val FULLSCREEN_ORIENTATION = stringPreferencesKey("fullscreen_orientation")
        val VIDEO_ASPECT_MODE = stringPreferencesKey("video_aspect_mode")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BG_AUDIO = booleanPreferencesKey("bg_audio")

        val DEFAULT_DESKTOP = booleanPreferencesKey("default_desktop")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val FORCE_DARK_WEB = booleanPreferencesKey("force_dark_web")
        val HIDE_COOKIE_BANNERS = booleanPreferencesKey("hide_cookie_banners")
        val CONNECT_BACKEND_ENDPOINT = stringPreferencesKey("connect_backend_endpoint")
    }

    val settingsFlow: Flow<NyxoraSettingsState> = context.nyxoraDataStore.data.map { prefs ->
        mapPreferencesToState(prefs)
    }

    private fun mapPreferencesToState(prefs: Preferences): NyxoraSettingsState {
        val rawConnectEndpoint = (prefs[Keys.CONNECT_BACKEND_ENDPOINT] ?: "").trim()
        val resolvedConnectEndpoint = if (
            rawConnectEndpoint.isBlank() ||
            rawConnectEndpoint.equals(com.example.domain.connect.NyxoraConnectSecurity.LEGACY_PRODUCTION_WSS_ENDPOINT, ignoreCase = true)
        ) {
            com.example.domain.connect.NyxoraConnectSecurity.configuredPublicWssEndpoint()
        } else {
            rawConnectEndpoint
        }
        return NyxoraSettingsState(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK),
            homepageUrl = (prefs[Keys.HOMEPAGE_URL] ?: "nyxora://home").ifBlank { "nyxora://home" },
            homepageShortcutsEnabled = prefs[Keys.HOMEPAGE_SHORTCUTS_ENABLED] ?: true,
            restoreTabsOnStartup = prefs[Keys.RESTORE_TABS_ON_STARTUP] ?: true,
            showHomeGreeting = prefs[Keys.SHOW_HOME_GREETING] ?: false,
            showHomeShortcuts = prefs[Keys.SHOW_HOME_SHORTCUTS] ?: false,
            showHomeFreqVisited = prefs[Keys.SHOW_HOME_FREQ] ?: false,
            showHomeRecentActivity = prefs[Keys.SHOW_HOME_RECENT] ?: false,
            showHomeQuickTools = prefs[Keys.SHOW_HOME_QUICK_TOOLS] ?: false,
            showAmbientHomeBackground = prefs[Keys.SHOW_AMBIENT_BG] ?: false,
            globalTextZoom = (prefs[Keys.GLOBAL_TEXT_ZOOM] ?: 100).coerceIn(50, 250),
            compactBottomToolbar = prefs[Keys.COMPACT_TOOLBAR] ?: false,
            reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
            preferredLanguage = (prefs[Keys.PREFERRED_LANGUAGE] ?: "System Default").ifBlank { "System Default" },
            searchEngine = runCatching { SearchEngine.valueOf(prefs[Keys.SEARCH_ENGINE] ?: SearchEngine.GOOGLE.name) }.getOrDefault(SearchEngine.GOOGLE),
            searchSuggestionsEnabled = prefs[Keys.SEARCH_SUGGESTIONS] ?: true,
            cloudSyncEnabled = prefs[Keys.CLOUD_SYNC_ENABLED] ?: false,
            javascriptEnabled = prefs[Keys.JAVASCRIPT_ENABLED] ?: true,
            cookiesEnabled = prefs[Keys.COOKIES_ENABLED] ?: true,
            doNotTrackEnabled = prefs[Keys.DO_NOT_TRACK_ENABLED] ?: true,
            clipboardAccessEnabled = prefs[Keys.CLIPBOARD_ACCESS_ENABLED] ?: true,
            defaultLocationPermission = prefs[Keys.DEFAULT_LOCATION_PERMISSION] ?: "ASK",
            defaultCameraPermission = prefs[Keys.DEFAULT_CAMERA_PERMISSION] ?: "ASK",
            defaultMicPermission = prefs[Keys.DEFAULT_MIC_PERMISSION] ?: "ASK",
            defaultNotificationPermission = prefs[Keys.DEFAULT_NOTIFICATION_PERMISSION] ?: "ASK",
            adBlockingEnabled = prefs[Keys.AD_BLOCKING] ?: true,
            popupBlockingEnabled = prefs[Keys.POPUP_BLOCKING] ?: true,
            redirectBlockingEnabled = prefs[Keys.REDIRECT_BLOCKING] ?: true,
            showBlockedMessages = prefs[Keys.SHOW_BLOCKED_MSG] ?: true,
            httpsOnlyMode = prefs[Keys.HTTPS_ONLY] ?: false,
            blockThirdPartyCookies = prefs[Keys.BLOCK_3P_COOKIES] ?: true,
            incognitoBlockScreenshots = prefs[Keys.INCOGNITO_BLOCK_SCREENSHOTS] ?: true,
            privateTabsLockEnabled = prefs[Keys.PRIVATE_TABS_LOCK] ?: false,
            privateTabsPinHash = prefs[Keys.PRIVATE_TABS_PIN] ?: "",
            totalAdsBlockedCount = prefs[Keys.ADS_BLOCKED_COUNT] ?: 0L,
            totalPopupsBlockedCount = prefs[Keys.POPUPS_BLOCKED_COUNT] ?: 0L,
            totalRedirectsBlockedCount = prefs[Keys.REDIRECTS_BLOCKED_COUNT] ?: 0L,
            lastFilterUpdateMillis = prefs[Keys.LAST_FILTER_UPDATE] ?: 0L,
            filterRuleCount = prefs[Keys.FILTER_RULE_COUNT] ?: 48,
            downloadConfirmRiskyFiles = prefs[Keys.CONFIRM_RISKY_FILES] ?: true,
            downloadNotificationsEnabled = prefs[Keys.DOWNLOAD_NOTIFICATIONS] ?: true,
            edgeSwipeBackForward = prefs[Keys.EDGE_SWIPE] ?: true,
            pullToRefreshEnabled = prefs[Keys.PULL_TO_REFRESH] ?: true,
            fullscreenOrientationMode = runCatching {
                FullscreenOrientationMode.valueOf(prefs[Keys.FULLSCREEN_ORIENTATION] ?: FullscreenOrientationMode.KEEP_CURRENT.name)
            }.getOrDefault(FullscreenOrientationMode.KEEP_CURRENT),
            defaultVideoAspectMode = runCatching {
                VideoAspectMode.valueOf(prefs[Keys.VIDEO_ASPECT_MODE] ?: VideoAspectMode.FIT.name)
            }.getOrDefault(VideoAspectMode.FIT),
            pipEnabled = prefs[Keys.PIP_ENABLED] ?: true,
            keepScreenOnWhilePlaying = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            backgroundAudioEnabled = prefs[Keys.BG_AUDIO] ?: false,
            defaultDesktopSite = prefs[Keys.DEFAULT_DESKTOP] ?: false,
            dataSaverBlockImages = prefs[Keys.DATA_SAVER] ?: false,
            forceDarkWebPages = prefs[Keys.FORCE_DARK_WEB] ?: false,
            hideCookieConsentBanners = prefs[Keys.HIDE_COOKIE_BANNERS] ?: true,
            connectBackendEndpoint = resolvedConnectEndpoint
        )
    }

    suspend fun updateSettings(transform: (NyxoraSettingsState) -> NyxoraSettingsState) {
        context.nyxoraDataStore.edit { prefs ->
            val current = mapPreferencesToState(prefs)
            val updated = transform(current)
            prefs[Keys.THEME_MODE] = updated.themeMode.name
            prefs[Keys.HOMEPAGE_URL] = updated.homepageUrl.ifBlank { "nyxora://home" }
            prefs[Keys.HOMEPAGE_SHORTCUTS_ENABLED] = updated.homepageShortcutsEnabled
            prefs[Keys.RESTORE_TABS_ON_STARTUP] = updated.restoreTabsOnStartup
            prefs[Keys.SHOW_HOME_GREETING] = updated.showHomeGreeting
            prefs[Keys.SHOW_HOME_SHORTCUTS] = updated.showHomeShortcuts
            prefs[Keys.SHOW_HOME_FREQ] = updated.showHomeFreqVisited
            prefs[Keys.SHOW_HOME_RECENT] = updated.showHomeRecentActivity
            prefs[Keys.SHOW_HOME_QUICK_TOOLS] = updated.showHomeQuickTools
            prefs[Keys.SHOW_AMBIENT_BG] = updated.showAmbientHomeBackground
            prefs[Keys.GLOBAL_TEXT_ZOOM] = updated.globalTextZoom.coerceIn(50, 250)
            prefs[Keys.COMPACT_TOOLBAR] = updated.compactBottomToolbar
            prefs[Keys.REDUCED_MOTION] = updated.reducedMotion
            prefs[Keys.PREFERRED_LANGUAGE] = updated.preferredLanguage.ifBlank { "System Default" }
            prefs[Keys.SEARCH_ENGINE] = updated.searchEngine.name
            prefs[Keys.SEARCH_SUGGESTIONS] = updated.searchSuggestionsEnabled
            prefs[Keys.CLOUD_SYNC_ENABLED] = updated.cloudSyncEnabled
            prefs[Keys.JAVASCRIPT_ENABLED] = updated.javascriptEnabled
            prefs[Keys.COOKIES_ENABLED] = updated.cookiesEnabled
            prefs[Keys.DO_NOT_TRACK_ENABLED] = updated.doNotTrackEnabled
            prefs[Keys.CLIPBOARD_ACCESS_ENABLED] = updated.clipboardAccessEnabled
            prefs[Keys.DEFAULT_LOCATION_PERMISSION] = updated.defaultLocationPermission
            prefs[Keys.DEFAULT_CAMERA_PERMISSION] = updated.defaultCameraPermission
            prefs[Keys.DEFAULT_MIC_PERMISSION] = updated.defaultMicPermission
            prefs[Keys.DEFAULT_NOTIFICATION_PERMISSION] = updated.defaultNotificationPermission
            prefs[Keys.AD_BLOCKING] = updated.adBlockingEnabled
            prefs[Keys.POPUP_BLOCKING] = updated.popupBlockingEnabled
            prefs[Keys.REDIRECT_BLOCKING] = updated.redirectBlockingEnabled
            prefs[Keys.SHOW_BLOCKED_MSG] = updated.showBlockedMessages
            prefs[Keys.HTTPS_ONLY] = updated.httpsOnlyMode
            prefs[Keys.BLOCK_3P_COOKIES] = updated.blockThirdPartyCookies
            prefs[Keys.INCOGNITO_BLOCK_SCREENSHOTS] = updated.incognitoBlockScreenshots
            prefs[Keys.PRIVATE_TABS_LOCK] = updated.privateTabsLockEnabled
            prefs[Keys.PRIVATE_TABS_PIN] = updated.privateTabsPinHash
            prefs[Keys.ADS_BLOCKED_COUNT] = updated.totalAdsBlockedCount
            prefs[Keys.POPUPS_BLOCKED_COUNT] = updated.totalPopupsBlockedCount
            prefs[Keys.REDIRECTS_BLOCKED_COUNT] = updated.totalRedirectsBlockedCount
            prefs[Keys.LAST_FILTER_UPDATE] = updated.lastFilterUpdateMillis
            prefs[Keys.FILTER_RULE_COUNT] = updated.filterRuleCount
            prefs[Keys.CONFIRM_RISKY_FILES] = updated.downloadConfirmRiskyFiles
            prefs[Keys.DOWNLOAD_NOTIFICATIONS] = updated.downloadNotificationsEnabled
            prefs[Keys.EDGE_SWIPE] = updated.edgeSwipeBackForward
            prefs[Keys.PULL_TO_REFRESH] = updated.pullToRefreshEnabled
            prefs[Keys.FULLSCREEN_ORIENTATION] = updated.fullscreenOrientationMode.name
            prefs[Keys.VIDEO_ASPECT_MODE] = updated.defaultVideoAspectMode.name
            prefs[Keys.PIP_ENABLED] = updated.pipEnabled
            prefs[Keys.KEEP_SCREEN_ON] = updated.keepScreenOnWhilePlaying
            prefs[Keys.BG_AUDIO] = updated.backgroundAudioEnabled
            prefs[Keys.DEFAULT_DESKTOP] = updated.defaultDesktopSite
            prefs[Keys.DATA_SAVER] = updated.dataSaverBlockImages
            prefs[Keys.FORCE_DARK_WEB] = updated.forceDarkWebPages
            prefs[Keys.HIDE_COOKIE_BANNERS] = updated.hideCookieConsentBanners
            prefs[Keys.CONNECT_BACKEND_ENDPOINT] = updated.connectBackendEndpoint.ifBlank {
                com.example.domain.connect.NyxoraConnectSecurity.configuredPublicWssEndpoint()
            }
        }
    }

    suspend fun resetToDefaults() {
        updateSettings { NyxoraSettingsState() }
    }

    suspend fun incrementBlockedMetric(ads: Int = 0, popups: Int = 0, redirects: Int = 0) {
        context.nyxoraDataStore.edit { prefs ->
            if (ads > 0) prefs[Keys.ADS_BLOCKED_COUNT] = (prefs[Keys.ADS_BLOCKED_COUNT] ?: 0L) + ads
            if (popups > 0) prefs[Keys.POPUPS_BLOCKED_COUNT] = (prefs[Keys.POPUPS_BLOCKED_COUNT] ?: 0L) + popups
            if (redirects > 0) prefs[Keys.REDIRECTS_BLOCKED_COUNT] = (prefs[Keys.REDIRECTS_BLOCKED_COUNT] ?: 0L) + redirects
        }
    }
}
