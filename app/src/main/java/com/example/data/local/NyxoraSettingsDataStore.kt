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
    BING(
        "Bing",
        "https://www.bing.com/search?q=%s",
        "https://api.bing.com/osjson.aspx?query=%s"
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

object UniversalTextScalePresets {
    const val DEFAULT_SCALE = 100
    val PRESETS: List<Int> = listOf(75, 85, 90, 100, 110, 115, 120, 125, 130, 150, 175, 200)

    fun normalizeScale(value: Int): Int = value.coerceIn(50, 250)
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

enum class UserAgentMode(val label: String) {
    DEFAULT("Default (Mobile)"),
    MOBILE("Mobile User Agent"),
    DESKTOP("Desktop User Agent"),
    CUSTOM("Custom User Agent")
}

enum class BrowserOrientationMode(val label: String) {
    SYSTEM("System Default"),
    PORTRAIT("Portrait Only"),
    LANDSCAPE("Landscape Only"),
    SENSOR("Auto-Rotate Sensor")
}

data class UserScriptItem(
    val id: String,
    val name: String,
    val domainPattern: String = "*",
    val code: String,
    val enabled: Boolean = true,
    val runAtDocumentEnd: Boolean = true,
    val approvedByUser: Boolean = true
)

object UserScriptStorage {

    fun parseScripts(rawJson: String): List<UserScriptItem> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(trimmed)
            val result = mutableListOf<UserScriptItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim().ifEmpty { "script_$i" }
                val name = obj.optString("name").trim().ifEmpty { "User Script ${i + 1}" }
                val domain = obj.optString("domainPattern", "*").trim().ifEmpty { "*" }
                val code = obj.optString("code", "").trim()
                if (code.isEmpty()) continue
                val enabled = obj.optBoolean("enabled", true)
                val runAtEnd = obj.optBoolean("runAtDocumentEnd", true)
                val approved = obj.optBoolean("approvedByUser", true)
                result.add(
                    UserScriptItem(
                        id = id,
                        name = name,
                        domainPattern = domain,
                        code = code,
                        enabled = enabled,
                        runAtDocumentEnd = runAtEnd,
                        approvedByUser = approved
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    /**
     * Parses external JSON script bundles safely.
     * Imported scripts NEVER auto-execute without explicit user approval:
     * unless `userExplicitlyApproved` is true, imported scripts default to `enabled = false` and `approvedByUser = false`.
     */
    fun parseImportedScripts(rawJson: String, userExplicitlyApproved: Boolean = false): List<UserScriptItem> {
        val parsed = parseScripts(rawJson)
        return parsed.map { item ->
            item.copy(
                enabled = if (userExplicitlyApproved) item.enabled else false,
                approvedByUser = userExplicitlyApproved
            )
        }
    }

    fun serializeScripts(scripts: List<UserScriptItem>): String {
        val arr = org.json.JSONArray()
        for (item in scripts) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("domainPattern", item.domainPattern.ifBlank { "*" })
                put("code", item.code)
                put("enabled", item.enabled)
                put("runAtDocumentEnd", item.runAtDocumentEnd)
                put("approvedByUser", item.approvedByUser)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun matchesDomain(domainPattern: String, pageHost: String): Boolean {
        val cleanHost = pageHost.trim().lowercase().removePrefix("www.")
        if (cleanHost.isEmpty()) return false
        val patterns = domainPattern.split(',')
            .map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.") }
            .filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return false
        for (pat in patterns) {
            if (pat == "*" || pat == "*.*") return true
            val stripped = pat.removePrefix("*.")
            if (cleanHost == stripped || cleanHost.endsWith(".$stripped")) {
                return true
            }
        }
        return false
    }
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
    val homeWallpaperStyle: String = "AURORA_BLUE",
    val showHomeNewsFeed: Boolean = true,
    val showHomePromotionalCards: Boolean = false,
    val launchAnimationEnabled: Boolean = true,
    val homeLogoAnimationEnabled: Boolean = true,
    val globalTextZoom: Int = 100,
    val compactBottomToolbar: Boolean = false,
    val addressBarShowReloadButton: Boolean = true,
    val addressBarShowSecurityIcon: Boolean = true,
    val addressBarSwipeToSwitchTabs: Boolean = true,
    val reducedMotion: Boolean = false,
    val preferredLanguage: String = "System Default",
    val browserOrientationMode: BrowserOrientationMode = BrowserOrientationMode.SYSTEM,
    // User Agent
    val userAgentMode: UserAgentMode = UserAgentMode.DEFAULT,
    val customUserAgent: String = "",
    // Search (Google is the default search engine; other engines stay selectable in Settings)
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val searchSuggestionsEnabled: Boolean = true,
    val searchSuggestionProvider: String = "FOLLOW_DEFAULT",
    val privateModeSearchSuggestionsEnabled: Boolean = false,
    val recentSearchQueriesJson: String = "[]",
    // Optional Account & Sync (OFF by default — never required or prompted at launch)
    val cloudSyncEnabled: Boolean = false,
    // Site Content & Permissions Defaults
    val javascriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val doNotTrackEnabled: Boolean = true,
    val webrtcLeakProtectionEnabled: Boolean = true,
    val saveFormDataEnabled: Boolean = true,
    val clearDataOnExit: Boolean = false,
    val clipboardAccessEnabled: Boolean = true,
    val defaultLocationPermission: String = "ASK",
    val defaultCameraPermission: String = "ASK",
    val defaultMicPermission: String = "ASK",
    val defaultNotificationPermission: String = "ASK",
    // Privacy, Security & NYXORA Shield
    val shieldMasterEnabled: Boolean = true,
    val adBlockingEnabled: Boolean = true,
    val trackerProtectionEnabled: Boolean = true,
    val popupBlockingEnabled: Boolean = true,
    val redirectBlockingEnabled: Boolean = true,
    val cosmeticFilteringEnabled: Boolean = true,
    val urlTrackingProtectionEnabled: Boolean = true,
    val dnsProtectionEnabled: Boolean = true,
    val enabledFilterLists: Set<String> = com.example.domain.browser.ShieldFilterListId.defaultEnabledIds(),
    val whitelistedWebsites: Set<String> = emptySet(),
    val showBlockedMessages: Boolean = true,
    val httpsOnlyMode: Boolean = false,
    val blockThirdPartyCookies: Boolean = true,
    val incognitoBlockScreenshots: Boolean = true,
    val privateTabsLockEnabled: Boolean = false,
    val privateTabsPinHash: String = "",
    // Blocking Metrics
    val totalAdsBlockedCount: Long = 0L,
    val totalTrackersBlockedCount: Long = 0L,
    val totalPopupsBlockedCount: Long = 0L,
    val totalRedirectsBlockedCount: Long = 0L,
    val totalTrackingParamsStrippedCount: Long = 0L,
    val totalDnsThreatsBlockedCount: Long = 0L,
    val lastFilterUpdateMillis: Long = 0L,
    val lastFilterUpdateStatus: String = "Active (Built-in + Cached Rules)",
    val filterRuleCount: Int = com.example.domain.browser.NyxoraShieldEngine.getTotalActiveRuleCount(),
    // Downloads
    val downloadConfirmRiskyFiles: Boolean = true,
    val downloadNotificationsEnabled: Boolean = true,
    // Gestures
    val edgeSwipeBackForward: Boolean = true,
    val pullToRefreshEnabled: Boolean = true,
    // Video Assistant & Playback
    val videoAssistantEnabled: Boolean = true,
    val externalVideoPlayerEnabled: Boolean = false,
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
    // User Scripts (Category E: SCRIPTS)
    val userScriptsEnabled: Boolean = true,
    val userScriptsRequireApproval: Boolean = true,
    val userScriptsJson: String = "[]",
    // NYXORA Connect Permanent Backend Configuration
    val connectBackendEndpoint: String = com.example.domain.connect.NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT
) {
    val parsedUserScripts: List<UserScriptItem>
        get() = UserScriptStorage.parseScripts(userScriptsJson)

    val resolvedSuggestionEngine: SearchEngine
        get() = if (searchSuggestionProvider.isBlank() || searchSuggestionProvider == "FOLLOW_DEFAULT") {
            searchEngine
        } else {
            runCatching { SearchEngine.valueOf(searchSuggestionProvider) }.getOrDefault(searchEngine)
        }

    val suggestionProviderLabel: String
        get() = if (searchSuggestionProvider.isBlank() || searchSuggestionProvider == "FOLLOW_DEFAULT") {
            "Follow Default (${searchEngine.label})"
        } else {
            resolvedSuggestionEngine.label
        }
}

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
        val HOME_WALLPAPER_STYLE = stringPreferencesKey("home_wallpaper_style")
        val SHOW_HOME_NEWS_FEED = booleanPreferencesKey("show_home_news_feed")
        val SHOW_HOME_PROMO_CARDS = booleanPreferencesKey("show_home_promo_cards")
        val LAUNCH_ANIMATION_ENABLED = booleanPreferencesKey("launch_animation_enabled")
        val HOME_LOGO_ANIMATION_ENABLED = booleanPreferencesKey("home_logo_animation_enabled")
        val GLOBAL_TEXT_ZOOM = intPreferencesKey("global_text_zoom")
        val COMPACT_TOOLBAR = booleanPreferencesKey("compact_toolbar")
        val ADDRESS_BAR_SHOW_RELOAD = booleanPreferencesKey("address_bar_show_reload")
        val ADDRESS_BAR_SHOW_SECURITY_ICON = booleanPreferencesKey("address_bar_show_security_icon")
        val ADDRESS_BAR_SWIPE_TABS = booleanPreferencesKey("address_bar_swipe_tabs")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val BROWSER_ORIENTATION_MODE = stringPreferencesKey("browser_orientation_mode")

        val USER_AGENT_MODE = stringPreferencesKey("user_agent_mode")
        val CUSTOM_USER_AGENT = stringPreferencesKey("custom_user_agent")

        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val SEARCH_SUGGESTIONS = booleanPreferencesKey("search_suggestions")
        val SEARCH_SUGGESTION_PROVIDER = stringPreferencesKey("search_suggestion_provider")
        val PRIVATE_MODE_SEARCH_SUGGESTIONS = booleanPreferencesKey("private_mode_search_suggestions")
        val RECENT_SEARCH_QUERIES_JSON = stringPreferencesKey("recent_search_queries_json")

        val CLOUD_SYNC_ENABLED = booleanPreferencesKey("cloud_sync_enabled")

        val JAVASCRIPT_ENABLED = booleanPreferencesKey("javascript_enabled")
        val COOKIES_ENABLED = booleanPreferencesKey("cookies_enabled")
        val DO_NOT_TRACK_ENABLED = booleanPreferencesKey("do_not_track_enabled")
        val WEBRTC_LEAK_PROTECTION = booleanPreferencesKey("webrtc_leak_protection")
        val SAVE_FORM_DATA_ENABLED = booleanPreferencesKey("save_form_data_enabled")
        val CLEAR_DATA_ON_EXIT = booleanPreferencesKey("clear_data_on_exit")
        val CLIPBOARD_ACCESS_ENABLED = booleanPreferencesKey("clipboard_access_enabled")
        val DEFAULT_LOCATION_PERMISSION = stringPreferencesKey("default_location_permission")
        val DEFAULT_CAMERA_PERMISSION = stringPreferencesKey("default_camera_permission")
        val DEFAULT_MIC_PERMISSION = stringPreferencesKey("default_mic_permission")
        val DEFAULT_NOTIFICATION_PERMISSION = stringPreferencesKey("default_notification_permission")

        val SHIELD_MASTER_ENABLED = booleanPreferencesKey("shield_master_enabled")
        val AD_BLOCKING = booleanPreferencesKey("ad_blocking")
        val TRACKER_PROTECTION = booleanPreferencesKey("tracker_protection")
        val POPUP_BLOCKING = booleanPreferencesKey("popup_blocking")
        val REDIRECT_BLOCKING = booleanPreferencesKey("redirect_blocking")
        val COSMETIC_FILTERING = booleanPreferencesKey("cosmetic_filtering")
        val URL_TRACKING_PROTECTION = booleanPreferencesKey("url_tracking_protection")
        val DNS_PROTECTION = booleanPreferencesKey("dns_protection")
        val SHIELD_ENABLED_FILTER_LISTS = stringPreferencesKey("shield_enabled_filter_lists")
        val SHIELD_WHITELISTED_WEBSITES = stringPreferencesKey("shield_whitelisted_websites")
        val SHOW_BLOCKED_MSG = booleanPreferencesKey("show_blocked_msg")
        val HTTPS_ONLY = booleanPreferencesKey("https_only")
        val BLOCK_3P_COOKIES = booleanPreferencesKey("block_3p_cookies")
        val INCOGNITO_BLOCK_SCREENSHOTS = booleanPreferencesKey("incognito_block_screenshots")
        val PRIVATE_TABS_LOCK = booleanPreferencesKey("private_tabs_lock")
        val PRIVATE_TABS_PIN = stringPreferencesKey("private_tabs_pin")

        val ADS_BLOCKED_COUNT = longPreferencesKey("ads_blocked_count")
        val TRACKERS_BLOCKED_COUNT = longPreferencesKey("trackers_blocked_count")
        val POPUPS_BLOCKED_COUNT = longPreferencesKey("popups_blocked_count")
        val REDIRECTS_BLOCKED_COUNT = longPreferencesKey("redirects_blocked_count")
        val TRACKING_PARAMS_STRIPPED_COUNT = longPreferencesKey("tracking_params_stripped_count")
        val DNS_THREATS_BLOCKED_COUNT = longPreferencesKey("dns_threats_blocked_count")
        val LAST_FILTER_UPDATE = longPreferencesKey("last_filter_update")
        val LAST_FILTER_UPDATE_STATUS = stringPreferencesKey("last_filter_update_status")
        val FILTER_RULE_COUNT = intPreferencesKey("filter_rule_count")

        val CONFIRM_RISKY_FILES = booleanPreferencesKey("confirm_risky_files")
        val DOWNLOAD_NOTIFICATIONS = booleanPreferencesKey("download_notifications")

        val EDGE_SWIPE = booleanPreferencesKey("edge_swipe")
        val PULL_TO_REFRESH = booleanPreferencesKey("pull_to_refresh")

        val VIDEO_ASSISTANT_ENABLED = booleanPreferencesKey("video_assistant_enabled")
        val EXTERNAL_VIDEO_PLAYER_ENABLED = booleanPreferencesKey("external_video_player_enabled")
        val FULLSCREEN_ORIENTATION = stringPreferencesKey("fullscreen_orientation")
        val VIDEO_ASPECT_MODE = stringPreferencesKey("video_aspect_mode")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BG_AUDIO = booleanPreferencesKey("bg_audio")

        val DEFAULT_DESKTOP = booleanPreferencesKey("default_desktop")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val FORCE_DARK_WEB = booleanPreferencesKey("force_dark_web")
        val HIDE_COOKIE_BANNERS = booleanPreferencesKey("hide_cookie_banners")

        val USER_SCRIPTS_ENABLED = booleanPreferencesKey("user_scripts_enabled")
        val USER_SCRIPTS_REQUIRE_APPROVAL = booleanPreferencesKey("user_scripts_require_approval")
        val USER_SCRIPTS_JSON = stringPreferencesKey("user_scripts_json")

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
        val rawEnabledLists = prefs[Keys.SHIELD_ENABLED_FILTER_LISTS]
        val parsedEnabledLists = when {
            rawEnabledLists == null -> com.example.domain.browser.ShieldFilterListId.defaultEnabledIds()
            rawEnabledLists.isBlank() || rawEnabledLists == "NONE" -> emptySet()
            else -> rawEnabledLists.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        val rawWhitelist = prefs[Keys.SHIELD_WHITELISTED_WEBSITES]
        val parsedWhitelist = if (rawWhitelist.isNullOrBlank()) {
            emptySet()
        } else {
            rawWhitelist.split(',')
                .map { com.example.domain.browser.NyxoraShieldEngine.normalizeDomain(it) }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        val defaultDesktop = prefs[Keys.DEFAULT_DESKTOP] ?: false
        val savedUaMode = prefs[Keys.USER_AGENT_MODE]
        val resolvedUaMode = if (savedUaMode != null) {
            runCatching { UserAgentMode.valueOf(savedUaMode) }.getOrDefault(UserAgentMode.DEFAULT)
        } else if (defaultDesktop) {
            UserAgentMode.DESKTOP
        } else {
            UserAgentMode.DEFAULT
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
            homeWallpaperStyle = (prefs[Keys.HOME_WALLPAPER_STYLE] ?: "AURORA_BLUE").ifBlank { "AURORA_BLUE" },
            showHomeNewsFeed = prefs[Keys.SHOW_HOME_NEWS_FEED] ?: true,
            showHomePromotionalCards = prefs[Keys.SHOW_HOME_PROMO_CARDS] ?: false,
            launchAnimationEnabled = prefs[Keys.LAUNCH_ANIMATION_ENABLED] ?: true,
            homeLogoAnimationEnabled = prefs[Keys.HOME_LOGO_ANIMATION_ENABLED] ?: true,
            globalTextZoom = (prefs[Keys.GLOBAL_TEXT_ZOOM] ?: 100).coerceIn(50, 250),
            compactBottomToolbar = prefs[Keys.COMPACT_TOOLBAR] ?: false,
            addressBarShowReloadButton = prefs[Keys.ADDRESS_BAR_SHOW_RELOAD] ?: true,
            addressBarShowSecurityIcon = prefs[Keys.ADDRESS_BAR_SHOW_SECURITY_ICON] ?: true,
            addressBarSwipeToSwitchTabs = prefs[Keys.ADDRESS_BAR_SWIPE_TABS] ?: true,
            reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
            preferredLanguage = (prefs[Keys.PREFERRED_LANGUAGE] ?: "System Default").ifBlank { "System Default" },
            browserOrientationMode = runCatching {
                BrowserOrientationMode.valueOf(prefs[Keys.BROWSER_ORIENTATION_MODE] ?: BrowserOrientationMode.SYSTEM.name)
            }.getOrDefault(BrowserOrientationMode.SYSTEM),
            userAgentMode = resolvedUaMode,
            customUserAgent = prefs[Keys.CUSTOM_USER_AGENT] ?: "",
            searchEngine = runCatching { SearchEngine.valueOf(prefs[Keys.SEARCH_ENGINE] ?: SearchEngine.GOOGLE.name) }.getOrDefault(SearchEngine.GOOGLE),
            searchSuggestionsEnabled = prefs[Keys.SEARCH_SUGGESTIONS] ?: true,
            searchSuggestionProvider = (prefs[Keys.SEARCH_SUGGESTION_PROVIDER] ?: "FOLLOW_DEFAULT").ifBlank { "FOLLOW_DEFAULT" },
            privateModeSearchSuggestionsEnabled = prefs[Keys.PRIVATE_MODE_SEARCH_SUGGESTIONS] ?: false,
            recentSearchQueriesJson = prefs[Keys.RECENT_SEARCH_QUERIES_JSON] ?: "[]",
            cloudSyncEnabled = prefs[Keys.CLOUD_SYNC_ENABLED] ?: false,
            javascriptEnabled = prefs[Keys.JAVASCRIPT_ENABLED] ?: true,
            cookiesEnabled = prefs[Keys.COOKIES_ENABLED] ?: true,
            doNotTrackEnabled = prefs[Keys.DO_NOT_TRACK_ENABLED] ?: true,
            webrtcLeakProtectionEnabled = prefs[Keys.WEBRTC_LEAK_PROTECTION] ?: true,
            saveFormDataEnabled = prefs[Keys.SAVE_FORM_DATA_ENABLED] ?: true,
            clearDataOnExit = prefs[Keys.CLEAR_DATA_ON_EXIT] ?: false,
            clipboardAccessEnabled = prefs[Keys.CLIPBOARD_ACCESS_ENABLED] ?: true,
            defaultLocationPermission = prefs[Keys.DEFAULT_LOCATION_PERMISSION] ?: "ASK",
            defaultCameraPermission = prefs[Keys.DEFAULT_CAMERA_PERMISSION] ?: "ASK",
            defaultMicPermission = prefs[Keys.DEFAULT_MIC_PERMISSION] ?: "ASK",
            defaultNotificationPermission = prefs[Keys.DEFAULT_NOTIFICATION_PERMISSION] ?: "ASK",
            shieldMasterEnabled = prefs[Keys.SHIELD_MASTER_ENABLED] ?: true,
            adBlockingEnabled = prefs[Keys.AD_BLOCKING] ?: true,
            trackerProtectionEnabled = prefs[Keys.TRACKER_PROTECTION] ?: true,
            popupBlockingEnabled = prefs[Keys.POPUP_BLOCKING] ?: true,
            redirectBlockingEnabled = prefs[Keys.REDIRECT_BLOCKING] ?: true,
            cosmeticFilteringEnabled = prefs[Keys.COSMETIC_FILTERING] ?: true,
            urlTrackingProtectionEnabled = prefs[Keys.URL_TRACKING_PROTECTION] ?: true,
            dnsProtectionEnabled = prefs[Keys.DNS_PROTECTION] ?: true,
            enabledFilterLists = parsedEnabledLists,
            whitelistedWebsites = parsedWhitelist,
            showBlockedMessages = prefs[Keys.SHOW_BLOCKED_MSG] ?: true,
            httpsOnlyMode = prefs[Keys.HTTPS_ONLY] ?: false,
            blockThirdPartyCookies = prefs[Keys.BLOCK_3P_COOKIES] ?: true,
            incognitoBlockScreenshots = prefs[Keys.INCOGNITO_BLOCK_SCREENSHOTS] ?: true,
            privateTabsLockEnabled = prefs[Keys.PRIVATE_TABS_LOCK] ?: false,
            privateTabsPinHash = prefs[Keys.PRIVATE_TABS_PIN] ?: "",
            totalAdsBlockedCount = prefs[Keys.ADS_BLOCKED_COUNT] ?: 0L,
            totalTrackersBlockedCount = prefs[Keys.TRACKERS_BLOCKED_COUNT] ?: 0L,
            totalPopupsBlockedCount = prefs[Keys.POPUPS_BLOCKED_COUNT] ?: 0L,
            totalRedirectsBlockedCount = prefs[Keys.REDIRECTS_BLOCKED_COUNT] ?: 0L,
            totalTrackingParamsStrippedCount = prefs[Keys.TRACKING_PARAMS_STRIPPED_COUNT] ?: 0L,
            totalDnsThreatsBlockedCount = prefs[Keys.DNS_THREATS_BLOCKED_COUNT] ?: 0L,
            lastFilterUpdateMillis = prefs[Keys.LAST_FILTER_UPDATE] ?: 0L,
            lastFilterUpdateStatus = prefs[Keys.LAST_FILTER_UPDATE_STATUS] ?: "Active (Built-in + Cached Rules)",
            filterRuleCount = prefs[Keys.FILTER_RULE_COUNT] ?: com.example.domain.browser.NyxoraShieldEngine.getTotalActiveRuleCount(),
            downloadConfirmRiskyFiles = prefs[Keys.CONFIRM_RISKY_FILES] ?: true,
            downloadNotificationsEnabled = prefs[Keys.DOWNLOAD_NOTIFICATIONS] ?: true,
            edgeSwipeBackForward = prefs[Keys.EDGE_SWIPE] ?: true,
            pullToRefreshEnabled = prefs[Keys.PULL_TO_REFRESH] ?: true,
            videoAssistantEnabled = prefs[Keys.VIDEO_ASSISTANT_ENABLED] ?: true,
            externalVideoPlayerEnabled = prefs[Keys.EXTERNAL_VIDEO_PLAYER_ENABLED] ?: false,
            fullscreenOrientationMode = runCatching {
                FullscreenOrientationMode.valueOf(prefs[Keys.FULLSCREEN_ORIENTATION] ?: FullscreenOrientationMode.KEEP_CURRENT.name)
            }.getOrDefault(FullscreenOrientationMode.KEEP_CURRENT),
            defaultVideoAspectMode = runCatching {
                VideoAspectMode.valueOf(prefs[Keys.VIDEO_ASPECT_MODE] ?: VideoAspectMode.FIT.name)
            }.getOrDefault(VideoAspectMode.FIT),
            pipEnabled = prefs[Keys.PIP_ENABLED] ?: true,
            keepScreenOnWhilePlaying = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            backgroundAudioEnabled = prefs[Keys.BG_AUDIO] ?: false,
            defaultDesktopSite = defaultDesktop,
            dataSaverBlockImages = prefs[Keys.DATA_SAVER] ?: false,
            forceDarkWebPages = prefs[Keys.FORCE_DARK_WEB] ?: false,
            hideCookieConsentBanners = prefs[Keys.HIDE_COOKIE_BANNERS] ?: true,
            userScriptsEnabled = prefs[Keys.USER_SCRIPTS_ENABLED] ?: true,
            userScriptsRequireApproval = prefs[Keys.USER_SCRIPTS_REQUIRE_APPROVAL] ?: true,
            userScriptsJson = prefs[Keys.USER_SCRIPTS_JSON] ?: "[]",
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
            prefs[Keys.HOME_WALLPAPER_STYLE] = updated.homeWallpaperStyle.ifBlank { "AURORA_BLUE" }
            prefs[Keys.SHOW_HOME_NEWS_FEED] = updated.showHomeNewsFeed
            prefs[Keys.SHOW_HOME_PROMO_CARDS] = updated.showHomePromotionalCards
            prefs[Keys.LAUNCH_ANIMATION_ENABLED] = updated.launchAnimationEnabled
            prefs[Keys.HOME_LOGO_ANIMATION_ENABLED] = updated.homeLogoAnimationEnabled
            prefs[Keys.GLOBAL_TEXT_ZOOM] = updated.globalTextZoom.coerceIn(50, 250)
            prefs[Keys.COMPACT_TOOLBAR] = updated.compactBottomToolbar
            prefs[Keys.ADDRESS_BAR_SHOW_RELOAD] = updated.addressBarShowReloadButton
            prefs[Keys.ADDRESS_BAR_SHOW_SECURITY_ICON] = updated.addressBarShowSecurityIcon
            prefs[Keys.ADDRESS_BAR_SWIPE_TABS] = updated.addressBarSwipeToSwitchTabs
            prefs[Keys.REDUCED_MOTION] = updated.reducedMotion
            prefs[Keys.PREFERRED_LANGUAGE] = updated.preferredLanguage.ifBlank { "System Default" }
            prefs[Keys.BROWSER_ORIENTATION_MODE] = updated.browserOrientationMode.name
            prefs[Keys.USER_AGENT_MODE] = updated.userAgentMode.name
            prefs[Keys.CUSTOM_USER_AGENT] = updated.customUserAgent.trim()
            prefs[Keys.SEARCH_ENGINE] = updated.searchEngine.name
            prefs[Keys.SEARCH_SUGGESTIONS] = updated.searchSuggestionsEnabled
            prefs[Keys.SEARCH_SUGGESTION_PROVIDER] = updated.searchSuggestionProvider.ifBlank { "FOLLOW_DEFAULT" }
            prefs[Keys.PRIVATE_MODE_SEARCH_SUGGESTIONS] = updated.privateModeSearchSuggestionsEnabled
            prefs[Keys.RECENT_SEARCH_QUERIES_JSON] = updated.recentSearchQueriesJson.ifBlank { "[]" }
            prefs[Keys.CLOUD_SYNC_ENABLED] = updated.cloudSyncEnabled
            prefs[Keys.JAVASCRIPT_ENABLED] = updated.javascriptEnabled
            prefs[Keys.COOKIES_ENABLED] = updated.cookiesEnabled
            prefs[Keys.DO_NOT_TRACK_ENABLED] = updated.doNotTrackEnabled
            prefs[Keys.WEBRTC_LEAK_PROTECTION] = updated.webrtcLeakProtectionEnabled
            prefs[Keys.SAVE_FORM_DATA_ENABLED] = updated.saveFormDataEnabled
            prefs[Keys.CLEAR_DATA_ON_EXIT] = updated.clearDataOnExit
            prefs[Keys.CLIPBOARD_ACCESS_ENABLED] = updated.clipboardAccessEnabled
            prefs[Keys.DEFAULT_LOCATION_PERMISSION] = updated.defaultLocationPermission
            prefs[Keys.DEFAULT_CAMERA_PERMISSION] = updated.defaultCameraPermission
            prefs[Keys.DEFAULT_MIC_PERMISSION] = updated.defaultMicPermission
            prefs[Keys.DEFAULT_NOTIFICATION_PERMISSION] = updated.defaultNotificationPermission
            prefs[Keys.SHIELD_MASTER_ENABLED] = updated.shieldMasterEnabled
            prefs[Keys.AD_BLOCKING] = updated.adBlockingEnabled
            prefs[Keys.TRACKER_PROTECTION] = updated.trackerProtectionEnabled
            prefs[Keys.POPUP_BLOCKING] = updated.popupBlockingEnabled
            prefs[Keys.REDIRECT_BLOCKING] = updated.redirectBlockingEnabled
            prefs[Keys.COSMETIC_FILTERING] = updated.cosmeticFilteringEnabled
            prefs[Keys.URL_TRACKING_PROTECTION] = updated.urlTrackingProtectionEnabled
            prefs[Keys.DNS_PROTECTION] = updated.dnsProtectionEnabled
            prefs[Keys.SHIELD_ENABLED_FILTER_LISTS] = if (updated.enabledFilterLists.isEmpty()) {
                "NONE"
            } else {
                updated.enabledFilterLists.joinToString(",")
            }
            prefs[Keys.SHIELD_WHITELISTED_WEBSITES] = updated.whitelistedWebsites
                .map { com.example.domain.browser.NyxoraShieldEngine.normalizeDomain(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(",")
            prefs[Keys.SHOW_BLOCKED_MSG] = updated.showBlockedMessages
            prefs[Keys.HTTPS_ONLY] = updated.httpsOnlyMode
            prefs[Keys.BLOCK_3P_COOKIES] = updated.blockThirdPartyCookies
            prefs[Keys.INCOGNITO_BLOCK_SCREENSHOTS] = updated.incognitoBlockScreenshots
            prefs[Keys.PRIVATE_TABS_LOCK] = updated.privateTabsLockEnabled
            prefs[Keys.PRIVATE_TABS_PIN] = updated.privateTabsPinHash
            prefs[Keys.ADS_BLOCKED_COUNT] = updated.totalAdsBlockedCount
            prefs[Keys.TRACKERS_BLOCKED_COUNT] = updated.totalTrackersBlockedCount
            prefs[Keys.POPUPS_BLOCKED_COUNT] = updated.totalPopupsBlockedCount
            prefs[Keys.REDIRECTS_BLOCKED_COUNT] = updated.totalRedirectsBlockedCount
            prefs[Keys.TRACKING_PARAMS_STRIPPED_COUNT] = updated.totalTrackingParamsStrippedCount
            prefs[Keys.DNS_THREATS_BLOCKED_COUNT] = updated.totalDnsThreatsBlockedCount
            prefs[Keys.LAST_FILTER_UPDATE] = updated.lastFilterUpdateMillis
            prefs[Keys.LAST_FILTER_UPDATE_STATUS] = updated.lastFilterUpdateStatus
            prefs[Keys.FILTER_RULE_COUNT] = updated.filterRuleCount
            prefs[Keys.CONFIRM_RISKY_FILES] = updated.downloadConfirmRiskyFiles
            prefs[Keys.DOWNLOAD_NOTIFICATIONS] = updated.downloadNotificationsEnabled
            prefs[Keys.EDGE_SWIPE] = updated.edgeSwipeBackForward
            prefs[Keys.PULL_TO_REFRESH] = updated.pullToRefreshEnabled
            prefs[Keys.VIDEO_ASSISTANT_ENABLED] = updated.videoAssistantEnabled
            prefs[Keys.EXTERNAL_VIDEO_PLAYER_ENABLED] = updated.externalVideoPlayerEnabled
            prefs[Keys.FULLSCREEN_ORIENTATION] = updated.fullscreenOrientationMode.name
            prefs[Keys.VIDEO_ASPECT_MODE] = updated.defaultVideoAspectMode.name
            prefs[Keys.PIP_ENABLED] = updated.pipEnabled
            prefs[Keys.KEEP_SCREEN_ON] = updated.keepScreenOnWhilePlaying
            prefs[Keys.BG_AUDIO] = updated.backgroundAudioEnabled
            prefs[Keys.DEFAULT_DESKTOP] = updated.defaultDesktopSite
            prefs[Keys.DATA_SAVER] = updated.dataSaverBlockImages
            prefs[Keys.FORCE_DARK_WEB] = updated.forceDarkWebPages
            prefs[Keys.HIDE_COOKIE_BANNERS] = updated.hideCookieConsentBanners
            prefs[Keys.USER_SCRIPTS_ENABLED] = updated.userScriptsEnabled
            prefs[Keys.USER_SCRIPTS_REQUIRE_APPROVAL] = updated.userScriptsRequireApproval
            prefs[Keys.USER_SCRIPTS_JSON] = updated.userScriptsJson.ifBlank { "[]" }
            prefs[Keys.CONNECT_BACKEND_ENDPOINT] = updated.connectBackendEndpoint.ifBlank {
                com.example.domain.connect.NyxoraConnectSecurity.configuredPublicWssEndpoint()
            }
        }
    }

    suspend fun resetToDefaults() {
        updateSettings { NyxoraSettingsState() }
    }

    suspend fun incrementBlockedMetric(
        ads: Int = 0,
        popups: Int = 0,
        redirects: Int = 0,
        trackers: Int = 0,
        trackingParams: Int = 0,
        dnsThreats: Int = 0
    ) {
        context.nyxoraDataStore.edit { prefs ->
            if (ads > 0) prefs[Keys.ADS_BLOCKED_COUNT] = (prefs[Keys.ADS_BLOCKED_COUNT] ?: 0L) + ads
            if (trackers > 0) prefs[Keys.TRACKERS_BLOCKED_COUNT] = (prefs[Keys.TRACKERS_BLOCKED_COUNT] ?: 0L) + trackers
            if (popups > 0) prefs[Keys.POPUPS_BLOCKED_COUNT] = (prefs[Keys.POPUPS_BLOCKED_COUNT] ?: 0L) + popups
            if (redirects > 0) prefs[Keys.REDIRECTS_BLOCKED_COUNT] = (prefs[Keys.REDIRECTS_BLOCKED_COUNT] ?: 0L) + redirects
            if (trackingParams > 0) {
                prefs[Keys.TRACKING_PARAMS_STRIPPED_COUNT] =
                    (prefs[Keys.TRACKING_PARAMS_STRIPPED_COUNT] ?: 0L) + trackingParams
            }
            if (dnsThreats > 0) {
                prefs[Keys.DNS_THREATS_BLOCKED_COUNT] =
                    (prefs[Keys.DNS_THREATS_BLOCKED_COUNT] ?: 0L) + dnsThreats
            }
        }
    }
}
