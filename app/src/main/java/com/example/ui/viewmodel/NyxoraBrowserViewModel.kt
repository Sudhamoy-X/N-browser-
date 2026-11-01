package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.local.BookmarkDao
import com.example.data.local.DownloadDao
import com.example.data.local.FilterRuleDao
import com.example.data.local.HistoryDao
import com.example.data.local.NyxoraLocalDatabase
import com.example.data.local.NyxoraSettingsDataStore
import com.example.data.local.NyxoraSettingsState
import com.example.data.local.SavedTabDao
import com.example.data.local.SiteSettingDao
import com.example.data.model.BookmarkItem
import com.example.data.model.CustomFilterRule
import com.example.data.model.DownloadRecord
import com.example.data.model.ExportedBookmark
import com.example.data.model.ExportedSiteSetting
import com.example.data.model.HistoryItem
import com.example.data.model.NyxoraBackupPayload
import com.example.data.model.SiteSettingItem
import com.example.data.model.SyncedTabItem
import com.example.domain.browser.ContentBlockingEngine
import com.example.domain.browser.DownloadEngine
import com.example.domain.browser.EncryptedBackupManager
import com.example.domain.browser.InputClassification
import com.example.domain.browser.ReaderArticle
import com.example.domain.browser.UrlAndSearchHandler
import com.example.domain.connect.IncomingRemoteSyncCommand
import com.example.domain.connect.NyxoraConnectSecurity
import com.example.domain.connect.NyxoraConnectSessionManager
import com.example.domain.connect.RealtimeEventType
import com.example.domain.connect.VideoCompatibilityState
import com.example.domain.update.ApkUpdateUiState
import com.example.domain.update.GitHubReleaseApkInfo
import com.example.domain.update.InstallHandoffResult
import com.example.domain.update.NyxoraApkUpdateManager
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "nyxora://home",
    val isPrivate: Boolean = false,
    val groupName: String = "Default",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val backStack: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    val desktopMode: Boolean = false,
    val textZoom: Int = 100,
    val hasCertificateError: Boolean = false,
    val pageErrorDescription: String? = null,
    val pendingHttpFallbackUrl: String? = null,
    val isSuspended: Boolean = false,
    val lastActiveAtMillis: Long = System.currentTimeMillis(),
    val parentTabId: String? = null,
    val reloadTriggerTick: Int = 0
) {
    val isHomePage: Boolean
        get() = url.equals("nyxora://home", ignoreCase = true) || url.isBlank()

    val isSecureConnection: Boolean
        get() = UrlAndSearchHandler.isSecureUrl(url, hasCertificateError) && pageErrorDescription == null

    val host: String
        get() = UrlAndSearchHandler.extractHost(url)

    val safeDisplayHost: String
        get() = UrlAndSearchHandler.formatSafeDisplayHost(url)
}

/**
 * Immutable snapshot of the browser's multi-tab state across normal and private (incognito) modes.
 */
data class MultiTabState(
    val normalTabs: List<BrowserTab> = listOf(BrowserTab(id = "tab_initial", title = "Home", url = "nyxora://home")),
    val privateTabs: List<BrowserTab> = emptyList(),
    val activeNormalTabId: String = "tab_initial",
    val activePrivateTabId: String? = null,
    val isPrivateMode: Boolean = false,
    val recentlyClosedTabs: List<BrowserTab> = emptyList()
) {
    /** Tabs belonging to the currently active browsing mode. */
    val currentModeTabs: List<BrowserTab>
        get() = if (isPrivateMode) privateTabs else normalTabs

    /** Currently active tab in the active browsing mode. */
    val activeTab: BrowserTab
        get() = if (isPrivateMode) {
            privateTabs.find { it.id == activePrivateTabId }
                ?: privateTabs.firstOrNull()
                ?: BrowserTab(id = "priv_empty", title = "Private Home", url = "nyxora://home", isPrivate = true)
        } else {
            normalTabs.find { it.id == activeNormalTabId }
                ?: normalTabs.firstOrNull()
                ?: BrowserTab(id = "tab_initial", title = "Home", url = "nyxora://home", isPrivate = false)
        }

    /** ID of the currently active tab. */
    val activeTabId: String
        get() = activeTab.id

    /** Zero-based index of the active tab within [currentModeTabs], or 0 when empty. */
    val activeTabIndex: Int
        get() = currentModeTabs.indexOfFirst { it.id == activeTab.id }.coerceAtLeast(0)

    /** Number of open tabs in the currently active browsing mode. */
    val tabCount: Int
        get() = currentModeTabs.size

    /** Total number of open tabs across both normal and private modes. */
    val totalTabCount: Int
        get() = normalTabs.size + privateTabs.size

    fun findTabById(tabId: String): BrowserTab? =
        normalTabs.find { it.id == tabId } ?: privateTabs.find { it.id == tabId }

    fun getTabsByGroup(groupName: String): List<BrowserTab> {
        if (groupName.isBlank() || groupName.equals("All", ignoreCase = true)) {
            return currentModeTabs
        }
        return currentModeTabs.filter { it.groupName.equals(groupName, ignoreCase = true) }
    }
}

data class BlockedEventSnackbar(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val blockedUrl: String
)

data class PendingRiskyDownload(
    val url: String,
    val suggestedFileName: String?,
    val mimeType: String?
)

data class PendingExternalSchemePrompt(
    val url: String,
    val scheme: String
)

data class PendingSslErrorPrompt(
    val tabId: String,
    val failingUrl: String,
    val primaryErrorName: String,
    val onDecision: (Boolean) -> Unit
)

data class PendingSitePermissionPrompt(
    val host: String,
    val permissionTypes: List<String>, // CAMERA, MICROPHONE, LOCATION, NOTIFICATIONS
    val webPermissionRequest: PermissionRequest? = null,
    val geolocationCallback: GeolocationPermissions.Callback? = null,
    val geolocationOrigin: String? = null
)

enum class OverlaySheet {
    NONE,
    TAB_SWITCHER,
    BOOKMARKS,
    HISTORY,
    DOWNLOADS,
    SITE_SETTINGS,
    SETTINGS,
    READER_MODE,
    NYXORA_CONNECT
}

class NyxoraBrowserViewModel(
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    private val savedTabDao: SavedTabDao,
    private val siteSettingDao: SiteSettingDao,
    private val downloadDao: DownloadDao,
    private val filterRuleDao: FilterRuleDao,
    private val settingsDataStore: NyxoraSettingsDataStore,
    val downloadEngine: DownloadEngine,
    initialApkUpdateManager: NyxoraApkUpdateManager? = null
) : ViewModel() {

    var apkUpdateManager: NyxoraApkUpdateManager? = initialApkUpdateManager
        internal set

    val settings: StateFlow<NyxoraSettingsState> = settingsDataStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = NyxoraSettingsState()
        )

    val connectManager: NyxoraConnectSessionManager = NyxoraConnectSessionManager(
        scope = viewModelScope,
        configuredEndpointProvider = {
            settings.value.connectBackendEndpoint.ifBlank {
                NyxoraConnectSecurity.configuredPublicWssEndpoint()
            }
        }
    )

    @Volatile
    private var isApplyingRemoteSyncNavigation: Boolean = false

    @Volatile
    private var lastRemoteSyncedUrl: String = ""

    @Volatile
    var hasInitializedWebViewEngine: Boolean = false
        private set

    @Volatile
    private var hasOpenedPrivateWebPage: Boolean = false

    val currentUserId: String = "local_user"

    // Local-first Room flows — work 100% offline with zero account required
    val bookmarks: StateFlow<List<BookmarkItem>> = bookmarkDao.observeAllBookmarks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val history: StateFlow<List<HistoryItem>> = historyDao.observeHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val siteSettings: StateFlow<List<SiteSettingItem>> = siteSettingDao.observeAllSiteSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val downloads: StateFlow<List<DownloadRecord>> = downloadDao.observeAllDownloads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val customFilterRules: StateFlow<List<CustomFilterRule>> = filterRuleDao.observeAllRules()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private val _normalTabs = MutableStateFlow(listOf(BrowserTab(id = "tab_initial", title = "Home", url = "nyxora://home")))
    val normalTabs: StateFlow<List<BrowserTab>> = _normalTabs.asStateFlow()

    private val _privateTabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val privateTabs: StateFlow<List<BrowserTab>> = _privateTabs.asStateFlow()

    private val _isPrivateMode = MutableStateFlow(false)
    val isPrivateMode: StateFlow<Boolean> = _isPrivateMode.asStateFlow()

    private val _isPrivateUnlocked = MutableStateFlow(true)
    val isPrivateUnlocked: StateFlow<Boolean> = _isPrivateUnlocked.asStateFlow()

    private val _activeNormalTabId = MutableStateFlow("tab_initial")
    val activeNormalTabId: StateFlow<String> = _activeNormalTabId.asStateFlow()

    private val _activePrivateTabId = MutableStateFlow<String?>(null)
    val activePrivateTabId: StateFlow<String?> = _activePrivateTabId.asStateFlow()

    private val _recentlyClosedTabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val recentlyClosedTabs: StateFlow<List<BrowserTab>> = _recentlyClosedTabs.asStateFlow()

    // Tier 3 Signature Feature: Split View secondary tab ID
    private val _splitSecondaryTabId = MutableStateFlow<String?>(null)
    val splitSecondaryTabId: StateFlow<String?> = _splitSecondaryTabId.asStateFlow()

    // Tier 3 Signature Feature: Reader Mode article
    private val _activeReaderArticle = MutableStateFlow<ReaderArticle?>(null)
    val activeReaderArticle: StateFlow<ReaderArticle?> = _activeReaderArticle.asStateFlow()

    private val _activeOverlay = MutableStateFlow(OverlaySheet.NONE)
    val activeOverlay: StateFlow<OverlaySheet> = _activeOverlay.asStateFlow()

    private val _findInPageVisible = MutableStateFlow(false)
    val findInPageVisible: StateFlow<Boolean> = _findInPageVisible.asStateFlow()

    private val _findInPageQuery = MutableStateFlow("")
    val findInPageQuery: StateFlow<String> = _findInPageQuery.asStateFlow()

    private val _findInPageMatches = MutableStateFlow(0 to 0) // activeOrdinal to totalMatches
    val findInPageMatches: StateFlow<Pair<Int, Int>> = _findInPageMatches.asStateFlow()

    private val _allowOnceUrls = MutableStateFlow<Set<String>>(emptySet())
    val allowOnceUrls: StateFlow<Set<String>> = _allowOnceUrls.asStateFlow()

    private val _pendingRedirectWarning = MutableStateFlow<BlockedEventSnackbar?>(null)
    val pendingRedirectWarning: StateFlow<BlockedEventSnackbar?> = _pendingRedirectWarning.asStateFlow()

    private val localSiteSettingCache = java.util.concurrent.ConcurrentHashMap<String, SiteSettingItem>()

    private val _blockedSnackbar = MutableSharedFlow<BlockedEventSnackbar>(extraBufferCapacity = 4)
    val blockedSnackbar = _blockedSnackbar.asSharedFlow()

    private val _statusToast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val statusToast = _statusToast.asSharedFlow()

    private val _pendingRiskyDownload = MutableStateFlow<PendingRiskyDownload?>(null)
    val pendingRiskyDownload: StateFlow<PendingRiskyDownload?> = _pendingRiskyDownload.asStateFlow()

    private val _pendingExternalSchemePrompt = MutableStateFlow<PendingExternalSchemePrompt?>(null)
    val pendingExternalSchemePrompt: StateFlow<PendingExternalSchemePrompt?> = _pendingExternalSchemePrompt.asStateFlow()

    private val _pendingSslErrorPrompt = MutableStateFlow<PendingSslErrorPrompt?>(null)
    val pendingSslErrorPrompt: StateFlow<PendingSslErrorPrompt?> = _pendingSslErrorPrompt.asStateFlow()

    private val _pendingSitePermissionPrompt = MutableStateFlow<PendingSitePermissionPrompt?>(null)
    val pendingSitePermissionPrompt: StateFlow<PendingSitePermissionPrompt?> = _pendingSitePermissionPrompt.asStateFlow()

    private val _multiTabState = MutableStateFlow(MultiTabState())
    val multiTabState: StateFlow<MultiTabState> = _multiTabState.asStateFlow()

    private val _activeTab = MutableStateFlow(_multiTabState.value.activeTab)
    val activeTab: StateFlow<BrowserTab> = _activeTab.asStateFlow()

    private fun syncMultiTabState() {
        val nextState = MultiTabState(
            normalTabs = _normalTabs.value,
            privateTabs = _privateTabs.value,
            activeNormalTabId = _activeNormalTabId.value,
            activePrivateTabId = _activePrivateTabId.value,
            isPrivateMode = _isPrivateMode.value,
            recentlyClosedTabs = _recentlyClosedTabs.value
        )
        _multiTabState.value = nextState
        _activeTab.value = nextState.activeTab
    }

    init {
        // Keep ContentBlockingEngine updated with custom rules on a background thread
        viewModelScope.launch(Dispatchers.Default) {
            customFilterRules.collect { rules ->
                ContentBlockingEngine.updateCustomRules(rules)
            }
        }

        // Restore normal tabs on Dispatchers.IO when restoreTabsOnStartup is enabled (no fake shortcuts seeded)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val initialSettings = runCatching { settingsDataStore.settingsFlow.first() }
                    .getOrDefault(NyxoraSettingsState())
                val defaultEndpoint = NyxoraConnectSecurity.configuredPublicWssEndpoint()
                if (initialSettings.connectBackendEndpoint.isBlank() ||
                    initialSettings.connectBackendEndpoint.equals(NyxoraConnectSecurity.LEGACY_PRODUCTION_WSS_ENDPOINT, ignoreCase = true)
                ) {
                    runCatching {
                        settingsDataStore.updateSettings {
                            it.copy(connectBackendEndpoint = defaultEndpoint)
                        }
                    }
                }
                if (!initialSettings.restoreTabsOnStartup) {
                    savedTabDao.clearAllSavedTabs()
                    return@launch
                }
                val localTabs = savedTabDao.getAllSavedTabs()
                if (localTabs.isNotEmpty()) {
                    val restored = localTabs
                        .map { item ->
                            BrowserTab(
                                id = item.id.ifBlank { UUID.randomUUID().toString() },
                                title = item.title,
                                url = item.url,
                                isPrivate = false,
                                groupName = item.groupName.ifBlank { "Default" },
                                desktopMode = item.desktopMode,
                                textZoom = item.textZoom.coerceIn(50, 250)
                            )
                        }
                        .distinctBy { it.id }

                    val homeTab = restored.firstOrNull { it.isHomePage }
                    if (homeTab != null) {
                        _normalTabs.value = restored
                        _activeNormalTabId.value = homeTab.id
                    } else {
                        val homeId = if (restored.any { it.id == "tab_initial" }) {
                            "tab_home_${UUID.randomUUID()}"
                        } else {
                            "tab_initial"
                        }
                        val freshHome = BrowserTab(
                            id = homeId,
                            title = "Home",
                            url = "nyxora://home",
                            isPrivate = false
                        )
                        _normalTabs.value = (listOf(freshHome) + restored).distinctBy { it.id }
                        _activeNormalTabId.value = freshHome.id
                    }
                    syncMultiTabState()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring local tabs", e)
            }
        }

        // Automatic background check for new stable GitHub APK releases on browser startup
        apkUpdateManager?.checkForUpdates(force = false)
    }

    fun checkForApkUpdates(force: Boolean = true) {
        apkUpdateManager?.checkForUpdates(force = force)
    }

    fun downloadApkUpdateNow(
        explicitRelease: GitHubReleaseApkInfo? = null,
        autoTriggerInstall: Boolean = true
    ) {
        apkUpdateManager?.downloadUpdateNow(
            explicitRelease = explicitRelease,
            autoTriggerInstall = autoTriggerInstall
        )
    }

    fun dismissApkUpdateForLater(release: GitHubReleaseApkInfo? = null) {
        apkUpdateManager?.dismissUpdatePromptForLater(release)
    }

    fun cancelApkUpdateDownload() {
        apkUpdateManager?.cancelActiveDownload()
    }

    fun refreshApkInstallPermission(context: Context): Boolean {
        return apkUpdateManager?.refreshInstallPermissionState(context) ?: false
    }

    fun installDownloadedApkUpdate(context: Context): InstallHandoffResult {
        val manager = apkUpdateManager
            ?: return InstallHandoffResult.Failed("Update manager is not initialized.")
        val result = manager.initiateInstallOrRequestPermission(context)
        when (result) {
            is InstallHandoffResult.PermissionSettingsLaunched -> {
                emitToast("Grant 'Install unknown apps' for NYXORA Browser, then tap Install Update")
            }
            is InstallHandoffResult.Failed -> {
                emitToast(result.errorMessage)
            }
            is InstallHandoffResult.InstallerLaunched -> Unit
        }
        return result
    }

    // --- NAVIGATION & ADDRESS BAR ---

    fun navigateInActiveTab(rawInput: String) {
        val trimmedInput = rawInput.trim()
        if (NyxoraConnectSecurity.isNyxoraConnectInvitationUrl(trimmedInput)) {
            _activeOverlay.value = OverlaySheet.NYXORA_CONNECT
            connectManager.joinRoom(trimmedInput)
            return
        }

        val currentSettings = settings.value
        val target = UrlAndSearchHandler.normalizeUserInput(
            rawInput = rawInput,
            searchEngine = currentSettings.searchEngine,
            httpsOnlyMode = currentSettings.httpsOnlyMode
        )

        when (target.classification) {
            InputClassification.BLOCKED_DANGEROUS_SCHEME -> {
                emitToast("Blocked unsafe URL scheme for security.")
                return
            }
            InputClassification.EXTERNAL_INTENT_OR_CUSTOM_SCHEME -> {
                _pendingExternalSchemePrompt.value = PendingExternalSchemePrompt(
                    url = target.finalUrl,
                    scheme = target.host
                )
                return
            }
            InputClassification.INTERNAL_NYXORA,
            InputClassification.DIRECT_URL,
            InputClassification.SEARCH_QUERY -> {
                val currentActive = activeTab.value
                val siteOverride = getSiteSettingForHost(target.host)
                val effectiveDesktop = siteOverride?.desktopMode ?: currentActive.desktopMode
                val effectiveZoom = siteOverride?.textZoom ?: currentSettings.globalTextZoom
                val isHome = target.finalUrl.equals("nyxora://home", ignoreCase = true)
                val finalNavUrl = if (!isHome && effectiveDesktop) {
                    rewriteMobileSubdomainToDesktopUrl(target.finalUrl)
                } else {
                    target.finalUrl
                }
                val newTitle = if (isHome) "Home" else target.safeDisplayHost.ifBlank { finalNavUrl }

                updateTab(currentActive.id) { tab ->
                    val nextBackStack = if (tab.url.isNotBlank() &&
                        !tab.url.equals(finalNavUrl, ignoreCase = true) &&
                        !tab.backStack.lastOrNull().equals(tab.url, ignoreCase = true)
                    ) {
                        (tab.backStack + tab.url).takeLast(50)
                    } else {
                        tab.backStack
                    }
                    tab.copy(
                        url = finalNavUrl,
                        title = newTitle,
                        desktopMode = effectiveDesktop,
                        textZoom = effectiveZoom,
                        isLoading = !isHome,
                        progress = if (isHome) 0 else 8,
                        canGoBack = nextBackStack.isNotEmpty() || !isHome || tab.parentTabId != null,
                        canGoForward = false,
                        backStack = nextBackStack,
                        forwardStack = emptyList(),
                        hasCertificateError = false,
                        pageErrorDescription = null,
                        pendingHttpFallbackUrl = target.fallbackHttpUrl,
                        isSuspended = false,
                        lastActiveAtMillis = System.currentTimeMillis()
                    )
                }

                if (!isApplyingRemoteSyncNavigation && !currentActive.isPrivate && !_isPrivateMode.value) {
                    connectManager.publishUrlNavigation(
                        url = target.finalUrl,
                        title = newTitle,
                        isPrivateTab = false,
                        isPrivateMode = false,
                        eventType = RealtimeEventType.NAVIGATE_URL
                    )
                }
            }
        }
    }

    fun applyRemoteSyncUrlNavigation(url: String, title: String) {
        // Never apply synchronized navigation into a Private/Incognito tab
        if (_isPrivateMode.value) return
        val targetNormalId = _activeNormalTabId.value
        val host = UrlAndSearchHandler.extractHost(url)
        val siteOverride = getSiteSettingForHost(host)
        val currentSettings = settings.value
        val isHome = url.equals("nyxora://home", ignoreCase = true)

        isApplyingRemoteSyncNavigation = true
        lastRemoteSyncedUrl = url
        try {
            updateTab(targetNormalId) { tab ->
                if (tab.url == url) {
                    tab
                } else {
                    tab.copy(
                        url = url,
                        title = title.ifBlank { if (isHome) "Home" else host.ifBlank { url } },
                        desktopMode = siteOverride?.desktopMode ?: currentSettings.defaultDesktopSite,
                        textZoom = siteOverride?.textZoom ?: currentSettings.globalTextZoom,
                        isLoading = !isHome,
                        progress = if (isHome) 0 else 10,
                        hasCertificateError = false,
                        pageErrorDescription = null,
                        isSuspended = false,
                        lastActiveAtMillis = System.currentTimeMillis()
                    )
                }
            }
        } finally {
            isApplyingRemoteSyncNavigation = false
        }
    }

    fun onParticipantRoomJoinConfirmed(cmd: IncomingRemoteSyncCommand.ParticipantJoinConfirmed) {
        if (_activeOverlay.value == OverlaySheet.NYXORA_CONNECT) {
            _activeOverlay.value = OverlaySheet.NONE
        }
        if (_isPrivateMode.value) return
        isApplyingRemoteSyncNavigation = true
        try {
            navigateHome()
            val pb = cmd.playbackState
            if (pb != null) {
                connectManager.updateVideoCompatibility(VideoCompatibilityState.COMPATIBLE_HTML5)
                if (pb.pageUrl.isNotBlank()) {
                    lastRemoteSyncedUrl = pb.pageUrl
                }
            } else {
                connectManager.updateVideoCompatibility(VideoCompatibilityState.NO_VIDEO_DETECTED)
            }
        } finally {
            isApplyingRemoteSyncNavigation = false
        }
    }

    fun openNewTabFromRemoteSync(url: String, title: String) {
        if (_isPrivateMode.value) return
        isApplyingRemoteSyncNavigation = true
        try {
            val id = createNewTab(url = url, inPrivateMode = false, inBackground = false)
            if (title.isNotBlank()) {
                updateTab(id) { it.copy(title = title) }
            }
        } finally {
            isApplyingRemoteSyncNavigation = false
        }
    }

    fun notifyHostBackNavigation() {
        val currentActive = activeTab.value
        connectManager.publishBackForwardAction(
            isBack = true,
            isPrivateTab = currentActive.isPrivate,
            isPrivateMode = _isPrivateMode.value
        )
    }

    fun notifyHostForwardNavigation() {
        val currentActive = activeTab.value
        connectManager.publishBackForwardAction(
            isBack = false,
            isPrivateTab = currentActive.isPrivate,
            isPrivateMode = _isPrivateMode.value
        )
    }

    fun onWebPageStarted(tabId: String, url: String) {
        hasInitializedWebViewEngine = true
        updateTab(tabId) { tab ->
            if (tab.isPrivate) {
                hasOpenedPrivateWebPage = true
            }
            tab.copy(
                url = url,
                isLoading = true,
                pageErrorDescription = null,
                lastActiveAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun onWebPageFinished(
        tabId: String,
        url: String,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: UrlAndSearchHandler.extractHost(url).ifBlank { "Web Page" }

        var updatedTabRef: BrowserTab? = null
        updateTab(tabId) { tab ->
            val isHome = url.equals("nyxora://home", ignoreCase = true)
            val next = tab.copy(
                url = url,
                title = if (isHome) "Home" else cleanTitle,
                isLoading = false,
                progress = 100,
                pendingHttpFallbackUrl = null,
                canGoBack = canGoBack || tab.backStack.isNotEmpty() || !isHome || !tab.parentTabId.isNullOrBlank(),
                canGoForward = canGoForward || tab.forwardStack.isNotEmpty()
            )
            updatedTabRef = next
            next
        }

        val tab = updatedTabRef ?: return
        val alreadyMatchesSynced = url.equals(lastRemoteSyncedUrl, ignoreCase = true) ||
            url.equals(connectManager.roomState.value?.currentSyncedUrl, ignoreCase = true)
        if (!tab.isPrivate &&
            !_isPrivateMode.value &&
            tab.id == _activeNormalTabId.value &&
            !isApplyingRemoteSyncNavigation &&
            !alreadyMatchesSynced
        ) {
            connectManager.publishUrlNavigation(
                url = url,
                title = cleanTitle,
                isPrivateTab = false,
                isPrivateMode = false,
                eventType = RealtimeEventType.NAVIGATE_URL
            )
        }
        if (!tab.isPrivate && !tab.isHomePage && url.startsWith("http")) {
            viewModelScope.launch(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                historyDao.insertHistory(
                    HistoryItem(
                        id = UUID.randomUUID().toString(),
                        userId = currentUserId,
                        title = cleanTitle,
                        url = url,
                        visitedAt = now
                    )
                )
                savedTabDao.upsertSavedTab(
                    SyncedTabItem(
                        id = tab.id,
                        userId = currentUserId,
                        title = cleanTitle,
                        url = url,
                        groupName = tab.groupName,
                        isActive = tab.id == _activeNormalTabId.value,
                        desktopMode = tab.desktopMode,
                        textZoom = tab.textZoom,
                        updatedAtMillis = now
                    )
                )
            }
        }
    }

    fun onWebProgressChanged(tabId: String, progress: Int) {
        val coerced = progress.coerceIn(0, 100)
        val existing = _normalTabs.value.firstOrNull { it.id == tabId }
            ?: _privateTabs.value.firstOrNull { it.id == tabId }
        val nextLoading = coerced in 1..99 && existing?.pageErrorDescription == null
        if (existing != null && existing.progress == coerced && existing.isLoading == nextLoading) {
            return
        }
        updateTab(tabId) { tab ->
            tab.copy(
                progress = coerced,
                isLoading = coerced in 1..99 && tab.pageErrorDescription == null
            )
        }
    }

    fun onWebHistoryUpdated(tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) { tab ->
            val nextUrl = if (url.isNotBlank() && url != "about:blank") url else tab.url
            val isHome = nextUrl.equals("nyxora://home", ignoreCase = true)
            tab.copy(
                url = nextUrl,
                canGoBack = canGoBack || tab.backStack.isNotEmpty() || !isHome || !tab.parentTabId.isNullOrBlank(),
                canGoForward = canGoForward || tab.forwardStack.isNotEmpty()
            )
        }
    }

    fun onWebPageError(tabId: String, failingUrl: String, description: String) {
        val currentSettings = settings.value
        updateTab(tabId) { tab ->
            val fallbackHttp = tab.pendingHttpFallbackUrl
            // HTTPS-first safe fallback to HTTP when initial implicit HTTPS fails to connect
            if (fallbackHttp != null &&
                !currentSettings.httpsOnlyMode &&
                failingUrl.startsWith("https://", ignoreCase = true)
            ) {
                tab.copy(
                    url = fallbackHttp,
                    pendingHttpFallbackUrl = null,
                    hasCertificateError = false,
                    pageErrorDescription = null,
                    isLoading = true,
                    progress = 10
                )
            } else {
                tab.copy(
                    url = failingUrl,
                    pendingHttpFallbackUrl = null,
                    isLoading = false,
                    progress = 0,
                    pageErrorDescription = description
                )
            }
        }
    }

    fun onSslCertificateError(tabId: String, failingUrl: String, errorName: String, onDecision: (Boolean) -> Unit) {
        // Never auto-proceed on certificate errors: abort the untrusted connection immediately
        onDecision(false)
        updateTab(tabId) { tab ->
            tab.copy(
                url = failingUrl,
                pendingHttpFallbackUrl = null,
                hasCertificateError = true,
                isLoading = false,
                progress = 0,
                pageErrorDescription = "SSL Certificate Error ($errorName): The security certificate for this site is not trusted. Connection blocked for your safety."
            )
        }
        _pendingSslErrorPrompt.value = PendingSslErrorPrompt(
            tabId = tabId,
            failingUrl = failingUrl,
            primaryErrorName = errorName,
            onDecision = onDecision
        )
    }

    fun resolveSslErrorPrompt(proceed: Boolean) {
        val prompt = _pendingSslErrorPrompt.value ?: return
        _pendingSslErrorPrompt.value = null
        if (!proceed) {
            updateTab(prompt.tabId) { tab ->
                tab.copy(
                    hasCertificateError = true,
                    isLoading = false,
                    progress = 0,
                    pageErrorDescription = "SSL Certificate Error (${prompt.primaryErrorName}). Connection aborted for your safety."
                )
            }
        }
    }

    fun navigateHome() {
        val configuredHome = settings.value.homepageUrl.trim()
        if (configuredHome.isNotEmpty() && !configuredHome.equals("nyxora://home", ignoreCase = true)) {
            navigateInActiveTab(configuredHome)
            return
        }
        updateTab(activeTab.value.id) { tab ->
            val nextForward = if (!tab.isHomePage && tab.url.isNotBlank()) {
                (listOf(tab.url) + tab.forwardStack).distinct().take(50)
            } else {
                tab.forwardStack
            }
            val nextBack = if (tab.backStack.lastOrNull()?.equals("nyxora://home", ignoreCase = true) == true) {
                tab.backStack.dropLast(1)
            } else {
                tab.backStack
            }
            tab.copy(
                url = "nyxora://home",
                title = if (tab.isPrivate) "Private Home" else "Home",
                isLoading = false,
                progress = 0,
                canGoBack = nextBack.isNotEmpty(),
                canGoForward = nextForward.isNotEmpty(),
                backStack = nextBack,
                forwardStack = nextForward,
                pageErrorDescription = null,
                hasCertificateError = false
            )
        }
    }

    fun navigateBackInActiveTab(webViewWentBack: Boolean = false): Boolean {
        val currentActive = activeTab.value
        if (webViewWentBack) {
            updateTab(currentActive.id) { tab ->
                // Only pop the top of backStack if it is NOT "nyxora://home" while WebView is still on a web page
                val topBack = tab.backStack.lastOrNull()
                val shouldPopBackStack = topBack != null && !topBack.equals("nyxora://home", ignoreCase = true)
                val remainingBack = if (shouldPopBackStack) tab.backStack.dropLast(1) else tab.backStack
                val nextForward = if (tab.url.isNotBlank() && !tab.isHomePage) {
                    (listOf(tab.url) + tab.forwardStack).distinct().take(50)
                } else {
                    tab.forwardStack
                }
                tab.copy(
                    backStack = remainingBack,
                    forwardStack = nextForward,
                    canGoBack = true,
                    canGoForward = true
                )
            }
            return true
        }

        // Deduplicate any trailing entries in backStack that match the current URL (e.g., redirect loops)
        val cleanedBackStack = currentActive.backStack.dropLastWhile {
            it.equals(currentActive.url, ignoreCase = true)
        }

        if (cleanedBackStack.isNotEmpty()) {
            var navigatedUrl: String? = null
            updateTab(currentActive.id) { tab ->
                val cleanStack = tab.backStack.dropLastWhile { it.equals(tab.url, ignoreCase = true) }
                val targetUrl = cleanStack.lastOrNull() ?: return@updateTab tab
                navigatedUrl = targetUrl
                val remainingBack = cleanStack.dropLast(1)
                val nextForward = if (tab.url.isNotBlank() && !tab.url.equals(targetUrl, ignoreCase = true)) {
                    (listOf(tab.url) + tab.forwardStack).distinct().take(50)
                } else {
                    tab.forwardStack
                }
                val isHome = targetUrl.equals("nyxora://home", ignoreCase = true)
                val nextTitle = if (isHome) {
                    if (tab.isPrivate) "Private Home" else "Home"
                } else {
                    UrlAndSearchHandler.formatSafeDisplayHost(targetUrl).ifBlank { targetUrl }
                }
                tab.copy(
                    url = targetUrl,
                    title = nextTitle,
                    isLoading = !isHome,
                    progress = if (isHome) 0 else 8,
                    canGoBack = remainingBack.isNotEmpty() || !isHome || tab.parentTabId != null,
                    canGoForward = nextForward.isNotEmpty(),
                    backStack = remainingBack,
                    forwardStack = nextForward,
                    hasCertificateError = false,
                    pageErrorDescription = null,
                    lastActiveAtMillis = System.currentTimeMillis()
                )
            }
            return navigatedUrl != null
        }

        // If this tab was opened from a previous tab (e.g. popup or target="_blank" link), return to that tab
        val parentId = currentActive.parentTabId
        if (!parentId.isNullOrBlank()) {
            val modeTabs = if (_isPrivateMode.value) _privateTabs.value else _normalTabs.value
            if (modeTabs.any { it.id == parentId && it.id != currentActive.id }) {
                closeTab(currentActive.id)
                selectTab(parentId, _isPrivateMode.value)
                return true
            }
        }

        // Otherwise, if not already on Homepage, return to Homepage instead of exiting the app
        if (!currentActive.isHomePage) {
            navigateHome()
            return true
        }
        return false
    }

    /**
     * Checks whether the browser has any active state that should intercept Android System Back
     * instead of allowing the Activity to finish.
     */
    fun canInterceptSystemBack(
        isFullscreenVideo: Boolean = false,
        isEditingAddress: Boolean = false,
        isOverflowMenuOpen: Boolean = false,
        webViewCanGoBack: Boolean = false
    ): Boolean {
        if (isFullscreenVideo) return true
        if (_pendingRedirectWarning.value != null ||
            _pendingExternalSchemePrompt.value != null ||
            _pendingRiskyDownload.value != null ||
            _pendingSslErrorPrompt.value != null ||
            _pendingSitePermissionPrompt.value != null ||
            isOverflowMenuOpen ||
            _activeOverlay.value != OverlaySheet.NONE ||
            _findInPageVisible.value ||
            isEditingAddress
        ) {
            return true
        }
        val tab = activeTab.value
        val hasParentTab = !tab.parentTabId.isNullOrBlank() &&
            (if (_isPrivateMode.value) _privateTabs.value else _normalTabs.value)
                .any { it.id == tab.parentTabId && it.id != tab.id }
        return webViewCanGoBack ||
            tab.canGoBack ||
            tab.backStack.isNotEmpty() ||
            hasParentTab ||
            !tab.isHomePage
    }

    /**
     * Executes the strict 5-step Back Button priority chain:
     * 1. If a video is in fullscreen, exit fullscreen first.
     * 2. If a redirect warning or temporary overlay is open, dismiss it first.
     * 3. If the current webpage has browser history, navigate Back within that page.
     * 4. Otherwise, return to the previous browser tab or homepage according to navigation design.
     * 5. Return false only when already at the root homepage with nothing to dismiss, allowing app exit.
     */
    fun handleSystemBack(
        isFullscreenVideo: Boolean = false,
        onExitFullscreen: () -> Unit = {},
        isEditingAddress: Boolean = false,
        onDismissAddressEdit: () -> Unit = {},
        isOverflowMenuOpen: Boolean = false,
        onDismissOverflowMenu: () -> Unit = {},
        onExecuteWebViewBack: () -> Boolean = { false }
    ): Boolean {
        // Priority 1: Exit fullscreen video first
        if (isFullscreenVideo) {
            onExitFullscreen()
            return true
        }

        // Priority 2: Dismiss redirect warning, security prompt, or temporary overlay first
        if (_pendingRedirectWarning.value != null) {
            _pendingRedirectWarning.value = null
            return true
        }
        if (_pendingExternalSchemePrompt.value != null) {
            _pendingExternalSchemePrompt.value = null
            return true
        }
        if (_pendingRiskyDownload.value != null) {
            _pendingRiskyDownload.value = null
            return true
        }
        if (_pendingSslErrorPrompt.value != null) {
            resolveSslErrorPrompt(false)
            return true
        }
        if (_pendingSitePermissionPrompt.value != null) {
            resolveSitePermissionPrompt(allow = false, rememberForSite = false)
            return true
        }
        if (isOverflowMenuOpen) {
            onDismissOverflowMenu()
            return true
        }
        if (_activeOverlay.value != OverlaySheet.NONE) {
            if (_activeOverlay.value == OverlaySheet.READER_MODE) {
                showReaderArticle(null)
            }
            _activeOverlay.value = OverlaySheet.NONE
            return true
        }
        if (_findInPageVisible.value) {
            setFindInPageVisible(false)
            return true
        }
        if (isEditingAddress) {
            onDismissAddressEdit()
            return true
        }

        // Priority 3 & 4: Webpage history -> Previous tab -> Homepage
        val currentActive = activeTab.value
        val wvWentBack = !currentActive.isHomePage && onExecuteWebViewBack()
        if (wvWentBack) {
            notifyHostBackNavigation()
            navigateBackInActiveTab(webViewWentBack = true)
            return true
        }
        if (currentActive.backStack.isNotEmpty() || !currentActive.isHomePage || !currentActive.parentTabId.isNullOrBlank()) {
            notifyHostBackNavigation()
            return navigateBackInActiveTab(webViewWentBack = false)
        }

        // Priority 5: Allow normal app exit when already at root homepage
        return false
    }

    fun navigateForwardInActiveTab(webViewWentForward: Boolean = false): Boolean {
        val currentActive = activeTab.value
        if (webViewWentForward) {
            if (currentActive.forwardStack.isNotEmpty()) {
                updateTab(currentActive.id) { tab ->
                    val remainingForward = tab.forwardStack.drop(1)
                    val nextBack = (tab.backStack + tab.url).takeLast(50)
                    tab.copy(
                        backStack = nextBack,
                        forwardStack = remainingForward,
                        canGoBack = true,
                        canGoForward = remainingForward.isNotEmpty()
                    )
                }
            }
            return true
        }

        if (currentActive.forwardStack.isNotEmpty()) {
            var navigatedUrl: String? = null
            updateTab(currentActive.id) { tab ->
                val targetUrl = tab.forwardStack.firstOrNull() ?: return@updateTab tab
                navigatedUrl = targetUrl
                val remainingForward = tab.forwardStack.drop(1)
                val nextBack = if (tab.url.isNotBlank() && !tab.url.equals(targetUrl, ignoreCase = true)) {
                    (tab.backStack + tab.url).takeLast(50)
                } else {
                    tab.backStack
                }
                val isHome = targetUrl.equals("nyxora://home", ignoreCase = true)
                val nextTitle = if (isHome) {
                    if (tab.isPrivate) "Private Home" else "Home"
                } else {
                    UrlAndSearchHandler.formatSafeDisplayHost(targetUrl).ifBlank { targetUrl }
                }
                tab.copy(
                    url = targetUrl,
                    title = nextTitle,
                    isLoading = !isHome,
                    progress = if (isHome) 0 else 8,
                    canGoBack = nextBack.isNotEmpty() || !isHome || tab.parentTabId != null,
                    canGoForward = remainingForward.isNotEmpty(),
                    backStack = nextBack,
                    forwardStack = remainingForward,
                    hasCertificateError = false,
                    pageErrorDescription = null,
                    lastActiveAtMillis = System.currentTimeMillis()
                )
            }
            return navigatedUrl != null
        }
        return false
    }

    // --- TABS & SESSION MANAGEMENT ---

    fun createNewTab(
        url: String = "nyxora://home",
        inPrivateMode: Boolean = _isPrivateMode.value,
        inBackground: Boolean = false,
        groupName: String = "Default",
        parentTabId: String? = null
    ): String {
        val currentSettings = settings.value
        val trimmedUrl = url.trim().ifBlank { "nyxora://home" }
        val resolvedUrl = if (
            trimmedUrl.equals("nyxora://home", ignoreCase = true) ||
            trimmedUrl.equals("about:blank", ignoreCase = true) ||
            trimmedUrl.startsWith("http://", ignoreCase = true) ||
            trimmedUrl.startsWith("https://", ignoreCase = true) ||
            trimmedUrl.startsWith("file://", ignoreCase = true) ||
            trimmedUrl.startsWith("nyxora://", ignoreCase = true)
        ) {
            trimmedUrl
        } else {
            UrlAndSearchHandler.normalizeUserInput(
                rawInput = trimmedUrl,
                searchEngine = currentSettings.searchEngine,
                httpsOnlyMode = currentSettings.httpsOnlyMode
            ).finalUrl
        }
        val isHome = resolvedUrl.equals("nyxora://home", ignoreCase = true)
        val host = UrlAndSearchHandler.extractHost(resolvedUrl)
        val siteOverride = getSiteSettingForHost(host)
        val cleanGroup = groupName.trim().ifBlank { "Default" }
        val newTab = BrowserTab(
            id = UUID.randomUUID().toString(),
            title = if (isHome) {
                if (inPrivateMode) "Private Home" else "Home"
            } else {
                UrlAndSearchHandler.formatSafeDisplayHost(resolvedUrl).ifBlank { host.ifBlank { "New Tab" } }
            },
            url = resolvedUrl,
            isPrivate = inPrivateMode,
            groupName = cleanGroup,
            isLoading = !isHome,
            progress = if (isHome) 0 else 8,
            canGoBack = !isHome || !parentTabId.isNullOrBlank(),
            desktopMode = siteOverride?.desktopMode ?: currentSettings.defaultDesktopSite,
            textZoom = siteOverride?.textZoom ?: currentSettings.globalTextZoom,
            lastActiveAtMillis = System.currentTimeMillis(),
            parentTabId = parentTabId
        )

        if (inPrivateMode) {
            _privateTabs.update { it + newTab }
            if (!inBackground || _activePrivateTabId.value == null) {
                _activePrivateTabId.value = newTab.id
                _isPrivateMode.value = true
            }
        } else {
            _normalTabs.update { it + newTab }
            if (!inBackground || _activeNormalTabId.value.isBlank()) {
                _activeNormalTabId.value = newTab.id
                _isPrivateMode.value = false
            }
            persistNormalTabAsync(newTab, isActive = !inBackground)
        }
        syncMultiTabState()
        suspendExcessInactiveTabs()
        if (inBackground) {
            emitToast("Opened in background ${if (inPrivateMode) "private " else ""}tab")
        }
        if (!inPrivateMode && !isApplyingRemoteSyncNavigation && !isHome) {
            connectManager.publishUrlNavigation(
                url = newTab.url,
                title = newTab.title,
                isPrivateTab = false,
                isPrivateMode = false,
                eventType = RealtimeEventType.NEW_PAGE_OPENED
            )
        }
        return newTab.id
    }

    fun selectTab(
        tabId: String,
        isPrivate: Boolean = _privateTabs.value.any { it.id == tabId } ||
            (_isPrivateMode.value && _normalTabs.value.none { it.id == tabId })
    ) {
        val existsInTargetMode = if (isPrivate) {
            _privateTabs.value.any { it.id == tabId }
        } else {
            _normalTabs.value.any { it.id == tabId }
        }
        if (!existsInTargetMode) {
            // Check if the tab exists in the opposite mode
            val existsInPrivate = _privateTabs.value.any { it.id == tabId }
            val existsInNormal = _normalTabs.value.any { it.id == tabId }
            if (!existsInPrivate && !existsInNormal) return
            return selectTab(tabId, isPrivate = existsInPrivate)
        }

        _isPrivateMode.value = isPrivate
        if (isPrivate) {
            _activePrivateTabId.value = tabId
        } else {
            _activeNormalTabId.value = tabId
        }
        val now = System.currentTimeMillis()
        updateTab(tabId) { it.copy(isSuspended = false, lastActiveAtMillis = now) }
        syncMultiTabState()
        suspendExcessInactiveTabs()
        if (!isPrivate) {
            val selectedTab = _normalTabs.value.firstOrNull { it.id == tabId }
            if (selectedTab != null) {
                persistNormalTabAsync(selectedTab, isActive = true)
                if (!isApplyingRemoteSyncNavigation) {
                    connectManager.publishUrlNavigation(
                        url = selectedTab.url,
                        title = selectedTab.title,
                        isPrivateTab = false,
                        isPrivateMode = false,
                        eventType = RealtimeEventType.SELECT_TAB_NAVIGATION
                    )
                }
            }
        }
    }

    /**
     * Switches to the open tab matching [tabId] across normal or private tabs.
     * Returns true if the tab was found and activated, false otherwise.
     */
    fun switchToTab(tabId: String): Boolean {
        val inPrivate = _privateTabs.value.any { it.id == tabId }
        val inNormal = _normalTabs.value.any { it.id == tabId }
        if (!inPrivate && !inNormal) return false
        selectTab(tabId = tabId, isPrivate = inPrivate)
        return true
    }

    /**
     * Switches to the tab at [index] in the currently active browsing mode's tab list.
     * Returns true if [index] is within bounds and the tab was selected.
     */
    fun switchToTabAtIndex(index: Int): Boolean {
        val tabs = if (_isPrivateMode.value) _privateTabs.value else _normalTabs.value
        val target = tabs.getOrNull(index) ?: return false
        selectTab(target.id, _isPrivateMode.value)
        return true
    }

    /**
     * Switches to the next tab in the current browsing mode (wrapping around to the first tab).
     * Returns the newly active tab's ID, or null if no tabs exist in the current mode.
     */
    fun switchToNextTab(): String? {
        val tabs = if (_isPrivateMode.value) _privateTabs.value else _normalTabs.value
        if (tabs.isEmpty()) return null
        if (tabs.size == 1) {
            val only = tabs.first()
            selectTab(only.id, _isPrivateMode.value)
            return only.id
        }
        val currentId = if (_isPrivateMode.value) _activePrivateTabId.value else _activeNormalTabId.value
        val currentIdx = tabs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val nextTab = tabs[(currentIdx + 1) % tabs.size]
        selectTab(nextTab.id, _isPrivateMode.value)
        return nextTab.id
    }

    /**
     * Switches to the previous tab in the current browsing mode (wrapping around to the last tab).
     * Returns the newly active tab's ID, or null if no tabs exist in the current mode.
     */
    fun switchToPreviousTab(): String? {
        val tabs = if (_isPrivateMode.value) _privateTabs.value else _normalTabs.value
        if (tabs.isEmpty()) return null
        if (tabs.size == 1) {
            val only = tabs.first()
            selectTab(only.id, _isPrivateMode.value)
            return only.id
        }
        val currentId = if (_isPrivateMode.value) _activePrivateTabId.value else _activeNormalTabId.value
        val currentIdx = tabs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val prevIdx = (currentIdx - 1 + tabs.size) % tabs.size
        val prevTab = tabs[prevIdx]
        selectTab(prevTab.id, _isPrivateMode.value)
        return prevTab.id
    }

    /**
     * Reorders an open tab identified by [tabId] to [targetIndex] within its tab list.
     * Returns true if the tab was moved, false if not found or index unchanged.
     */
    fun reorderTab(tabId: String, targetIndex: Int): Boolean {
        val privIdx = _privateTabs.value.indexOfFirst { it.id == tabId }
        if (privIdx != -1) {
            val list = _privateTabs.value.toMutableList()
            val coerced = targetIndex.coerceIn(0, list.lastIndex)
            if (privIdx == coerced) return false
            val item = list.removeAt(privIdx)
            list.add(coerced, item)
            _privateTabs.value = list
            syncMultiTabState()
            return true
        }
        val normIdx = _normalTabs.value.indexOfFirst { it.id == tabId }
        if (normIdx != -1) {
            val list = _normalTabs.value.toMutableList()
            val coerced = targetIndex.coerceIn(0, list.lastIndex)
            if (normIdx == coerced) return false
            val item = list.removeAt(normIdx)
            list.add(coerced, item)
            _normalTabs.value = list
            syncMultiTabState()
            return true
        }
        return false
    }

    fun duplicateTab(tabId: String): String? {
        val source = (_normalTabs.value + _privateTabs.value).find { it.id == tabId } ?: return null
        val newId = createNewTab(
            url = source.url,
            inPrivateMode = source.isPrivate,
            inBackground = false,
            groupName = source.groupName
        )
        updateTab(newId) {
            it.copy(
                title = source.title,
                desktopMode = source.desktopMode,
                textZoom = source.textZoom,
                backStack = source.backStack,
                forwardStack = source.forwardStack,
                canGoBack = source.canGoBack,
                canGoForward = source.canGoForward
            )
        }
        return newId
    }

    fun closeTab(tabId: String): Boolean {
        val privList = _privateTabs.value
        val privIdx = privList.indexOfFirst { it.id == tabId }
        if (privIdx != -1) {
            val remaining = privList.filterNot { it.id == tabId }
            _privateTabs.value = remaining
            if (_activePrivateTabId.value == tabId) {
                val nextActive = if (remaining.isNotEmpty()) {
                    remaining[privIdx.coerceAtMost(remaining.lastIndex)]
                } else {
                    null
                }
                _activePrivateTabId.value = nextActive?.id
                if (nextActive != null) {
                    val now = System.currentTimeMillis()
                    _privateTabs.update { list ->
                        list.map { if (it.id == nextActive.id) it.copy(isSuspended = false, lastActiveAtMillis = now) else it }
                    }
                }
            }
            if (remaining.isEmpty()) {
                wipePrivateSessionData()
                _isPrivateMode.value = false
            }
            if (_splitSecondaryTabId.value == tabId) {
                _splitSecondaryTabId.value = null
            }
            syncMultiTabState()
            return true
        }

        val normList = _normalTabs.value
        val normIdx = normList.indexOfFirst { it.id == tabId }
        if (normIdx == -1) return false
        val normMatch = normList[normIdx]
        _recentlyClosedTabs.update { (listOf(normMatch) + it).take(15) }
        val remaining = normList.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val fallback = BrowserTab(id = UUID.randomUUID().toString(), title = "Home", url = "nyxora://home")
            _normalTabs.value = listOf(fallback)
            _activeNormalTabId.value = fallback.id
            persistNormalTabAsync(fallback, isActive = true)
        } else {
            val wasActive = _activeNormalTabId.value == tabId
            val nextActiveId = if (wasActive) {
                remaining[normIdx.coerceAtMost(remaining.lastIndex)].id
            } else {
                _activeNormalTabId.value
            }
            val now = System.currentTimeMillis()
            _normalTabs.value = remaining.map { tab ->
                if (tab.id == nextActiveId) tab.copy(isSuspended = false, lastActiveAtMillis = now) else tab
            }
            _activeNormalTabId.value = nextActiveId
        }
        if (_splitSecondaryTabId.value == tabId) {
            _splitSecondaryTabId.value = null
        }
        syncMultiTabState()
        viewModelScope.launch {
            savedTabDao.deleteSavedTab(tabId)
        }
        return true
    }

    fun closeAllTabs(inPrivateMode: Boolean = _isPrivateMode.value) {
        if (inPrivateMode) {
            _privateTabs.value = emptyList()
            _activePrivateTabId.value = null
            wipePrivateSessionData()
            _isPrivateMode.value = false
            syncMultiTabState()
            emitToast("Private session wiped")
        } else {
            val oldTabs = _normalTabs.value
            _recentlyClosedTabs.update { (oldTabs + it).take(15) }
            val fresh = BrowserTab(id = UUID.randomUUID().toString(), title = "Home", url = "nyxora://home")
            _normalTabs.value = listOf(fresh)
            _activeNormalTabId.value = fresh.id
            _splitSecondaryTabId.value = null
            syncMultiTabState()
            viewModelScope.launch {
                savedTabDao.clearAllSavedTabs()
                savedTabDao.upsertSavedTab(
                    SyncedTabItem(
                        id = fresh.id,
                        userId = currentUserId,
                        title = fresh.title,
                        url = fresh.url,
                        isActive = true
                    )
                )
            }
            emitToast("Closed all normal tabs")
        }
    }

    fun reopenRecentlyClosedTab(): String? {
        val latest = _recentlyClosedTabs.value.firstOrNull() ?: return null
        _recentlyClosedTabs.update { it.drop(1) }
        val newId = createNewTab(
            url = latest.url,
            inPrivateMode = false,
            inBackground = false,
            groupName = latest.groupName
        )
        updateTab(newId) {
            it.copy(
                title = latest.title,
                desktopMode = latest.desktopMode,
                textZoom = latest.textZoom,
                backStack = latest.backStack,
                forwardStack = latest.forwardStack,
                canGoBack = latest.canGoBack,
                canGoForward = latest.canGoForward
            )
        }
        return newId
    }

    fun switchBrowsingMode(privateMode: Boolean) {
        _isPrivateMode.value = privateMode
        if (privateMode) {
            if (settings.value.privateTabsLockEnabled && settings.value.privateTabsPinHash.isNotEmpty()) {
                _isPrivateUnlocked.value = false
            }
            if (_privateTabs.value.isEmpty()) {
                createNewTab(url = "nyxora://home", inPrivateMode = true, inBackground = false)
                return
            } else if (_activePrivateTabId.value == null || _privateTabs.value.none { it.id == _activePrivateTabId.value }) {
                _activePrivateTabId.value = _privateTabs.value.last().id
            }
        } else if (_normalTabs.value.none { it.id == _activeNormalTabId.value }) {
            _activeNormalTabId.value = _normalTabs.value.lastOrNull()?.id ?: "tab_initial"
        }
        syncMultiTabState()
    }

    fun unlockPrivateTabsWithPin(pin: String): Boolean {
        val expected = settings.value.privateTabsPinHash
        if (expected.isEmpty() || hashPin(pin) == expected) {
            _isPrivateUnlocked.value = true
            return true
        }
        return false
    }

    fun setPrivateTabsPin(pin: String) {
        val hashed = hashPin(pin)
        viewModelScope.launch {
            settingsDataStore.updateSettings {
                it.copy(privateTabsLockEnabled = pin.isNotBlank(), privateTabsPinHash = hashed)
            }
            emitToast(if (pin.isNotBlank()) "Private tabs lock enabled" else "Private tabs lock removed")
        }
    }

    private fun hashPin(pin: String): String {
        if (pin.isBlank()) return ""
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun wipePrivateSessionData() {
        if (!hasOpenedPrivateWebPage) return
        hasOpenedPrivateWebPage = false
        if (!hasInitializedWebViewEngine) return
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeSessionCookies(null)
            cookieManager.flush()
        }
    }

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookiesAndStorage: Boolean
    ) {
        viewModelScope.launch {
            if (clearHistory) {
                historyDao.clearAllHistory()
            }
            if (clearCookiesAndStorage && hasInitializedWebViewEngine) {
                runCatching {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    WebStorage.getInstance().deleteAllData()
                }
            }
            emitToast("Selected browsing data cleared")
        }
    }

    /**
     * Suspends older inactive tabs when more than 5 tabs are open or on system memory pressure.
     */
    fun suspendExcessInactiveTabs(forceAggressive: Boolean = false) {
        val maxKeepAlive = if (forceAggressive) 1 else 5
        val currentActiveNormalId = _activeNormalTabId.value
        val currentActivePrivateId = _activePrivateTabId.value
        val splitId = _splitSecondaryTabId.value
        _normalTabs.update { list ->
            val sortedByRecent = list.sortedByDescending { it.lastActiveAtMillis }
            val keepIds = sortedByRecent.take(maxKeepAlive).map { it.id }.toSet() + setOfNotNull(currentActiveNormalId, splitId)
            list.map { tab ->
                if (tab.id in keepIds) tab else tab.copy(isSuspended = true, isLoading = false)
            }
        }
        _privateTabs.update { list ->
            if (list.isEmpty()) list
            else {
                val sortedByRecent = list.sortedByDescending { it.lastActiveAtMillis }
                val keepIds = sortedByRecent.take(maxKeepAlive).map { it.id }.toSet() + setOfNotNull(currentActivePrivateId, splitId)
                list.map { tab ->
                    if (tab.id in keepIds) tab else tab.copy(isSuspended = true, isLoading = false)
                }
            }
        }
        val totalSuspended = _normalTabs.value.count { it.isSuspended } + _privateTabs.value.count { it.isSuspended }
        com.example.domain.browser.BrowserDiagnosticsEngine.updateSuspendedTabCount(totalSuspended)
        syncMultiTabState()
    }

    fun updateTabGroup(tabId: String, groupName: String) {
        updateTab(tabId) { it.copy(groupName = groupName) }
    }

    fun toggleSplitView(secondaryTabId: String? = null) {
        if (_splitSecondaryTabId.value != null && secondaryTabId == null) {
            _splitSecondaryTabId.value = null
            emitToast("Closed Split View")
            return
        }
        val targetId = secondaryTabId ?: _normalTabs.value.firstOrNull { it.id != activeTab.value.id }?.id
            ?: createNewTab(url = "https://www.wikipedia.org", inPrivateMode = false, inBackground = true)
        _splitSecondaryTabId.value = targetId
    }

    // --- PER-TAB / PER-SITE CONTROLS ---

    fun toggleDesktopSiteForActiveTab() {
        val current = activeTab.value
        val nextDesktop = !current.desktopMode
        val host = current.host
        if (host.isNotBlank() && host != "nyxora.home") {
            val cleanHost = normalizeSiteHostKey(host)
            val existing = getSiteSettingForHost(cleanHost) ?: SiteSettingItem(userId = currentUserId, host = cleanHost)
            saveSiteSetting(existing.copy(desktopMode = nextDesktop))
        }
        updateTab(current.id) { tab ->
            val updatedUrl = if (!tab.isHomePage && nextDesktop) {
                rewriteMobileSubdomainToDesktopUrl(tab.url)
            } else {
                tab.url
            }
            tab.copy(
                url = updatedUrl,
                desktopMode = nextDesktop,
                isLoading = !tab.isHomePage,
                progress = if (tab.isHomePage) 0 else 12,
                reloadTriggerTick = tab.reloadTriggerTick + 1
            )
        }
        emitToast(if (nextDesktop) "Desktop site enabled" else "Mobile site enabled")
    }

    private fun rewriteMobileSubdomainToDesktopUrl(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            val rawHost = uri.host ?: return url
            val lowerHost = rawHost.lowercase()
            val desktopHost = when {
                lowerHost.startsWith("m.") && lowerHost.length > 2 -> rawHost.substring(2)
                lowerHost.startsWith("mobile.") && lowerHost.length > 7 -> rawHost.substring(7)
                else -> return url
            }
            uri.buildUpon().authority(
                if (uri.port != -1) "$desktopHost:${uri.port}" else desktopHost
            ).build().toString()
        }.getOrDefault(url)
    }

    private fun normalizeSiteHostKey(host: String): String {
        return host.trim().lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("mobile.")
    }

    fun updateTextZoomForActiveTab(newZoom: Int) {
        val clamped = newZoom.coerceIn(50, 250)
        val current = activeTab.value
        updateTab(current.id) { it.copy(textZoom = clamped) }
        val host = normalizeSiteHostKey(current.host)
        if (host.isNotBlank() && host != "nyxora.home") {
            val existing = getSiteSettingForHost(host) ?: SiteSettingItem(userId = currentUserId, host = host)
            saveSiteSetting(existing.copy(textZoom = clamped))
        }
    }

    fun getSiteSettingForHost(host: String): SiteSettingItem? {
        val clean = normalizeSiteHostKey(host)
        if (clean.isEmpty()) return null
        localSiteSettingCache[clean]?.let { return it }
        return siteSettings.value.firstOrNull { normalizeSiteHostKey(it.host) == clean }
    }

    fun saveSiteSetting(setting: SiteSettingItem) {
        val cleanHost = normalizeSiteHostKey(setting.host)
        if (cleanHost.isEmpty()) return
        val normalized = setting.copy(
            id = setting.id.ifBlank { cleanHost },
            userId = currentUserId,
            host = cleanHost
        )
        localSiteSettingCache[cleanHost] = normalized
        viewModelScope.launch {
            siteSettingDao.upsertSiteSetting(normalized)
        }
    }

    fun deleteSiteSetting(host: String) {
        val cleanHost = normalizeSiteHostKey(host)
        localSiteSettingCache.remove(cleanHost)
        viewModelScope.launch {
            siteSettingDao.deleteSiteSettingByHost(cleanHost)
        }
    }

    fun clearAllSiteData() {
        viewModelScope.launch {
            if (hasInitializedWebViewEngine) {
                runCatching {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    WebStorage.getInstance().deleteAllData()
                }
            }
            emitToast("Cleared site cookies and local storage")
        }
    }

    fun resetAllSitePermissions() {
        localSiteSettingCache.clear()
        viewModelScope.launch {
            siteSettingDao.clearAllSiteSettings()
            settingsDataStore.updateSettings {
                it.copy(
                    defaultLocationPermission = "ASK",
                    defaultCameraPermission = "ASK",
                    defaultMicPermission = "ASK",
                    defaultNotificationPermission = "ASK"
                )
            }
            emitToast("Reset all site permissions")
        }
    }

    fun resetBrowserSettings() {
        localSiteSettingCache.clear()
        viewModelScope.launch {
            settingsDataStore.resetToDefaults()
            siteSettingDao.clearAllSiteSettings()
            emitToast("Browser settings restored to defaults")
        }
    }

    // --- BOOKMARKS & SHORTCUTS ---

    fun toggleBookmarkForActiveTab() {
        val tab = activeTab.value
        if (tab.isHomePage) {
            _activeOverlay.value = OverlaySheet.BOOKMARKS
            return
        }
        val existing = bookmarks.value.firstOrNull { it.url.equals(tab.url, ignoreCase = true) }
        viewModelScope.launch {
            if (existing != null) {
                bookmarkDao.deleteBookmarkById(existing.id)
                emitToast("Removed bookmark")
            } else {
                val item = BookmarkItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    title = tab.title,
                    url = tab.url,
                    folder = "General",
                    isShortcut = false,
                    createdAtMillis = System.currentTimeMillis()
                )
                bookmarkDao.upsertBookmark(item)
                emitToast("Bookmarked ${tab.title}")
            }
        }
    }

    fun addBookmarkOrShortcut(title: String, url: String, folder: String, isShortcut: Boolean) {
        val normalized = UrlAndSearchHandler.normalizeUserInput(url, settings.value.searchEngine, false).finalUrl
        val cleanTitle = title.ifBlank { UrlAndSearchHandler.extractHost(normalized).ifBlank { normalized } }
        val cleanFolder = folder.ifBlank { "General" }
        viewModelScope.launch {
            val item = BookmarkItem(
                id = UUID.randomUUID().toString(),
                userId = currentUserId,
                title = cleanTitle,
                url = normalized,
                folder = cleanFolder,
                isShortcut = isShortcut,
                createdAtMillis = System.currentTimeMillis()
            )
            bookmarkDao.upsertBookmark(item)
            emitToast(if (isShortcut) "Shortcut added to Homepage" else "Bookmark saved")
        }
    }

    fun moveShortcut(shortcutId: String, moveEarlier: Boolean) {
        viewModelScope.launch {
            val allBookmarks = bookmarkDao.getAllBookmarks()
            val shortcuts = allBookmarks.filter { it.isShortcut }
            val index = shortcuts.indexOfFirst { it.id == shortcutId }
            if (index == -1) return@launch
            val swapIndex = if (moveEarlier) index - 1 else index + 1
            if (swapIndex !in shortcuts.indices) return@launch

            val current = shortcuts[index]
            val neighbor = shortcuts[swapIndex]
            val currentTs = current.createdAtMillis
            val neighborTs = if (neighbor.createdAtMillis == currentTs) {
                if (moveEarlier) currentTs + 1L else currentTs - 1L
            } else {
                neighbor.createdAtMillis
            }
            bookmarkDao.upsertBookmarks(
                listOf(
                    current.copy(createdAtMillis = neighborTs),
                    neighbor.copy(createdAtMillis = currentTs)
                )
            )
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            bookmarkDao.deleteBookmarkById(bookmarkId)
        }
    }

    fun deleteHistoryItem(historyId: String) {
        viewModelScope.launch {
            historyDao.deleteHistoryById(historyId)
        }
    }

    // --- BLOCKING EVENTS & ALLOW ONCE ---

    fun recordAdBlocked() {
        viewModelScope.launch {
            settingsDataStore.incrementBlockedMetric(ads = 1)
        }
    }

    fun onPopupBlocked(blockedUrl: String) {
        if (settings.value.showBlockedMessages) {
            _blockedSnackbar.tryEmit(
                BlockedEventSnackbar(
                    message = "Pop-up blocked",
                    blockedUrl = blockedUrl
                )
            )
        }
        viewModelScope.launch {
            settingsDataStore.incrementBlockedMetric(popups = 1)
        }
    }

    fun onAutoRedirectBlocked(blockedUrl: String) {
        val event = BlockedEventSnackbar(
            message = "Auto-redirect blocked",
            blockedUrl = blockedUrl
        )
        _pendingRedirectWarning.value = event
        if (settings.value.showBlockedMessages) {
            _blockedSnackbar.tryEmit(event)
        }
        viewModelScope.launch {
            settingsDataStore.incrementBlockedMetric(redirects = 1)
        }
    }

    fun dismissRedirectWarning() {
        _pendingRedirectWarning.value = null
    }

    fun allowUrlOnce(url: String) {
        if (url.isBlank()) return
        _pendingRedirectWarning.value = null
        val trimmed = url.trim()
        _allowOnceUrls.update { it + trimmed }
        navigateInActiveTab(trimmed)
    }

    fun refreshRemoteFilterList() {
        viewModelScope.launch {
            emitToast("Updating filter rules…")
            val result = ContentBlockingEngine.fetchRemoteFilterListUpdate()
            result.onSuccess { newRules ->
                filterRuleDao.deleteBySourceList("Remote Update")
                filterRuleDao.insertRules(newRules)
                val totalCount = ContentBlockingEngine.getTotalActiveRuleCount() + newRules.size
                settingsDataStore.updateSettings {
                    it.copy(
                        lastFilterUpdateMillis = System.currentTimeMillis(),
                        filterRuleCount = totalCount
                    )
                }
                emitToast("Filter list updated ($totalCount active rules)")
            }.onFailure { err ->
                val builtInCount = ContentBlockingEngine.getTotalActiveRuleCount()
                settingsDataStore.updateSettings {
                    it.copy(
                        lastFilterUpdateMillis = System.currentTimeMillis(),
                        filterRuleCount = builtInCount
                    )
                }
                emitToast("Using verified built-in rules ($builtInCount active) — remote list unreachable: ${err.message ?: "offline"}")
            }
        }
    }

    fun addCustomFilterRule(pattern: String, isAllowRule: Boolean) {
        val clean = pattern.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            filterRuleDao.insertRule(
                CustomFilterRule(
                    pattern = clean,
                    isAllowRule = isAllowRule,
                    sourceList = "User Custom"
                )
            )
            emitToast("Added filter rule: $clean")
        }
    }

    fun removeCustomFilterRule(pattern: String) {
        viewModelScope.launch {
            filterRuleDao.deleteRule(pattern)
        }
    }

    // --- DOWNLOADS & FILES ---

    fun requestDownload(url: String, suggestedFileName: String?, mimeType: String?) {
        val isRisky = UrlAndSearchHandler.isRiskyDownloadFile(suggestedFileName ?: url, mimeType)
        if (isRisky && settings.value.downloadConfirmRiskyFiles) {
            _pendingRiskyDownload.value = PendingRiskyDownload(url, suggestedFileName, mimeType)
        } else {
            downloadEngine.enqueueDownload(
                url = url,
                suggestedFileName = suggestedFileName,
                mimeType = mimeType,
                notificationsEnabled = settings.value.downloadNotificationsEnabled
            )
            emitToast("Download started: ${suggestedFileName ?: UrlAndSearchHandler.extractHost(url)}")
        }
    }

    fun confirmRiskyDownload(proceed: Boolean) {
        val pending = _pendingRiskyDownload.value ?: return
        _pendingRiskyDownload.value = null
        if (proceed) {
            downloadEngine.enqueueDownload(
                url = pending.url,
                suggestedFileName = pending.suggestedFileName,
                mimeType = pending.mimeType,
                notificationsEnabled = settings.value.downloadNotificationsEnabled
            )
            emitToast("Download started")
        }
    }

    fun dismissExternalSchemePrompt() {
        _pendingExternalSchemePrompt.value = null
    }

    fun openLocalUriInBrowser(context: Context, uri: Uri, mimeType: String?) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val type = mimeType ?: appContext.contentResolver.getType(uri) ?: "text/plain"
                val ext = when {
                    type.contains("html", ignoreCase = true) -> ".html"
                    type.contains("xml", ignoreCase = true) -> ".xml"
                    type.contains("json", ignoreCase = true) -> ".json"
                    else -> ".txt"
                }
                val pickerDir = java.io.File(appContext.cacheDir, "picker_files").apply { mkdirs() }
                val destFile = java.io.File(pickerDir, "picked_${System.currentTimeMillis()}$ext")
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: if (uri.scheme.equals("file", ignoreCase = true)) {
                        uri.path?.let { java.io.File(it).inputStream() }
                    } else {
                        null
                    }
                if (inputStream != null) {
                    inputStream.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val canonicalFileUrl = "file://${destFile.canonicalPath}"
                    UrlAndSearchHandler.registerUserPickedFileUrl(canonicalFileUrl)
                    createNewTab(
                        url = canonicalFileUrl,
                        inPrivateMode = _isPrivateMode.value,
                        inBackground = false
                    )
                } else {
                    emitToast("Could not open selected file")
                }
            } catch (e: Exception) {
                emitToast("Could not read local file: ${e.localizedMessage}")
            }
        }
    }

    // --- SITE PERMISSIONS ---

    fun promptSitePermission(prompt: PendingSitePermissionPrompt) {
        _pendingSitePermissionPrompt.value = prompt
    }

    fun resolveSitePermissionPrompt(allow: Boolean, rememberForSite: Boolean) {
        val prompt = _pendingSitePermissionPrompt.value ?: return
        _pendingSitePermissionPrompt.value = null

        if (rememberForSite && prompt.host.isNotBlank()) {
            val current = getSiteSettingForHost(prompt.host) ?: SiteSettingItem(userId = currentUserId, host = prompt.host)
            val newState = if (allow) "ALLOW" else "DENY"
            var updated = current
            for (perm in prompt.permissionTypes) {
                updated = when (perm) {
                    "CAMERA" -> updated.copy(cameraPermission = newState)
                    "MICROPHONE" -> updated.copy(micPermission = newState)
                    "LOCATION" -> updated.copy(locationPermission = newState)
                    "NOTIFICATIONS" -> updated.copy(notificationPermission = newState)
                    else -> updated
                }
            }
            saveSiteSetting(updated)
        }

        if (allow) {
            prompt.webPermissionRequest?.grant(prompt.webPermissionRequest.resources)
            prompt.geolocationCallback?.invoke(prompt.geolocationOrigin, true, rememberForSite)
        } else {
            prompt.webPermissionRequest?.deny()
            prompt.geolocationCallback?.invoke(prompt.geolocationOrigin, false, false)
        }
    }

    // --- FIND IN PAGE & READER MODE ---

    fun setFindInPageVisible(visible: Boolean) {
        _findInPageVisible.value = visible
        if (!visible) {
            _findInPageQuery.value = ""
            _findInPageMatches.value = 0 to 0
        }
    }

    fun updateFindInPageQuery(query: String) {
        _findInPageQuery.value = query
    }

    fun updateFindInPageMatches(activeOrdinal: Int, numberOfMatches: Int) {
        _findInPageMatches.value = (if (numberOfMatches > 0) activeOrdinal + 1 else 0) to numberOfMatches
    }

    fun showReaderArticle(article: ReaderArticle?) {
        _activeReaderArticle.value = article
        if (article != null) {
            _activeOverlay.value = OverlaySheet.READER_MODE
        }
    }

    fun setOverlaySheet(sheet: OverlaySheet) {
        _activeOverlay.value = sheet
    }

    // --- SETTINGS & ENCRYPTED BACKUP ---

    fun updateSettings(transform: (NyxoraSettingsState) -> NyxoraSettingsState) {
        val before = settings.value
        val after = transform(before)
        if (after.globalTextZoom != before.globalTextZoom || after.defaultDesktopSite != before.defaultDesktopSite) {
            val propagateToTab: (BrowserTab) -> BrowserTab = { tab ->
                val siteOverride = getSiteSettingForHost(tab.host)
                tab.copy(
                    textZoom = siteOverride?.textZoom ?: after.globalTextZoom,
                    desktopMode = siteOverride?.desktopMode ?: after.defaultDesktopSite
                )
            }
            _normalTabs.update { list -> list.map(propagateToTab) }
            _privateTabs.update { list -> list.map(propagateToTab) }
            syncMultiTabState()
        }
        viewModelScope.launch {
            settingsDataStore.updateSettings(transform)
        }
    }

    fun exportEncryptedBackup(passphrase: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val s = settings.value
            val bms = bookmarks.value.map {
                ExportedBookmark(title = it.title, url = it.url, folder = it.folder, isShortcut = it.isShortcut)
            }
            val sites = siteSettings.value.map {
                ExportedSiteSetting(
                    host = it.host,
                    allowAds = it.allowAds,
                    allowPopups = it.allowPopups,
                    allowRedirects = it.allowRedirects,
                    desktopMode = it.desktopMode,
                    textZoom = it.textZoom,
                    cameraPermission = it.cameraPermission,
                    micPermission = it.micPermission,
                    locationPermission = it.locationPermission,
                    notificationPermission = it.notificationPermission
                )
            }
            val payload = NyxoraBackupPayload(
                exportedAtMillis = System.currentTimeMillis(),
                defaultSearchEngine = s.searchEngine.name,
                themeMode = s.themeMode.name,
                adBlockingEnabled = s.adBlockingEnabled,
                popupBlockingEnabled = s.popupBlockingEnabled,
                redirectBlockingEnabled = s.redirectBlockingEnabled,
                httpsOnlyMode = s.httpsOnlyMode,
                dataSaverEnabled = s.dataSaverBlockImages,
                forceDarkWeb = s.forceDarkWebPages,
                hideCookieBanners = s.hideCookieConsentBanners,
                bookmarks = bms,
                siteSettings = sites
            )
            val encrypted = EncryptedBackupManager.encryptBackup(payload, passphrase)
            onResult(encrypted)
        }
    }

    fun importEncryptedBackup(encryptedText: String, passphrase: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val decrypted = EncryptedBackupManager.decryptBackup(encryptedText, passphrase)
            decrypted.onSuccess { payload ->
                settingsDataStore.updateSettings { current ->
                    current.copy(
                        adBlockingEnabled = payload.adBlockingEnabled,
                        popupBlockingEnabled = payload.popupBlockingEnabled,
                        redirectBlockingEnabled = payload.redirectBlockingEnabled,
                        httpsOnlyMode = payload.httpsOnlyMode,
                        dataSaverBlockImages = payload.dataSaverEnabled,
                        forceDarkWebPages = payload.forceDarkWeb,
                        hideCookieConsentBanners = payload.hideCookieBanners
                    )
                }
                val now = System.currentTimeMillis()
                for ((idx, bm) in payload.bookmarks.withIndex()) {
                    bookmarkDao.upsertBookmark(
                        BookmarkItem(
                            id = UUID.randomUUID().toString(),
                            userId = currentUserId,
                            title = bm.title,
                            url = bm.url,
                            folder = bm.folder,
                            isShortcut = bm.isShortcut,
                            createdAtMillis = now - idx
                        )
                    )
                }
                for (site in payload.siteSettings) {
                    val cleanHost = normalizeSiteHostKey(site.host)
                    if (cleanHost.isEmpty()) continue
                    val siteEntity = SiteSettingItem(
                        id = cleanHost,
                        userId = currentUserId,
                        host = cleanHost,
                        allowAds = site.allowAds,
                        allowPopups = site.allowPopups,
                        allowRedirects = site.allowRedirects,
                        desktopMode = site.desktopMode,
                        textZoom = site.textZoom,
                        cameraPermission = site.cameraPermission,
                        micPermission = site.micPermission,
                        locationPermission = site.locationPermission,
                        notificationPermission = site.notificationPermission
                    )
                    localSiteSettingCache[cleanHost] = siteEntity
                    siteSettingDao.upsertSiteSetting(siteEntity)
                }
                onResult(Result.success(payload.bookmarks.size + payload.siteSettings.size))
            }.onFailure { err ->
                onResult(Result.failure(err))
            }
        }
    }

    fun emitToast(message: String) {
        _statusToast.tryEmit(message)
    }

    private fun persistNormalTabAsync(tab: BrowserTab, isActive: Boolean = tab.id == _activeNormalTabId.value) {
        if (tab.isPrivate) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching {
                savedTabDao.upsertSavedTab(
                    SyncedTabItem(
                        id = tab.id,
                        userId = currentUserId,
                        title = tab.title,
                        url = tab.url,
                        groupName = tab.groupName,
                        isActive = isActive,
                        desktopMode = tab.desktopMode,
                        textZoom = tab.textZoom,
                        updatedAtMillis = now
                    )
                )
            }
        }
    }

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        var updatedNormalTab: BrowserTab? = null
        var previousNormalTab: BrowserTab? = null
        _normalTabs.update { list ->
            list.map {
                if (it.id == tabId) {
                    previousNormalTab = it
                    val next = transform(it)
                    updatedNormalTab = next
                    next
                } else {
                    it
                }
            }
        }
        _privateTabs.update { list ->
            list.map { if (it.id == tabId) transform(it) else it }
        }
        syncMultiTabState()
        val nextNorm = updatedNormalTab
        val prevNorm = previousNormalTab
        if (nextNorm != null && prevNorm != null && (
                nextNorm.url != prevNorm.url ||
                    nextNorm.title != prevNorm.title ||
                    nextNorm.groupName != prevNorm.groupName ||
                    nextNorm.desktopMode != prevNorm.desktopMode ||
                    nextNorm.textZoom != prevNorm.textZoom
                )
        ) {
            persistNormalTabAsync(nextNorm)
        }
    }

    override fun onCleared() {
        connectManager.leaveOrCloseRoom(notifyServer = true)
        super.onCleared()
    }

    companion object {
        private const val TAG = "NyxoraBrowserVM"

        fun provideFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val localDb = NyxoraLocalDatabase.getInstance(app)
                val settingsStore = NyxoraSettingsDataStore(app)
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
                val dlEngine = DownloadEngine(app, localDb.downloadDao(), scope)
                val updateMgr = NyxoraApkUpdateManager(context = app, scope = scope)

                NyxoraBrowserViewModel(
                    bookmarkDao = localDb.bookmarkDao(),
                    historyDao = localDb.historyDao(),
                    savedTabDao = localDb.savedTabDao(),
                    siteSettingDao = localDb.siteSettingDao(),
                    downloadDao = localDb.downloadDao(),
                    filterRuleDao = localDb.filterRuleDao(),
                    settingsDataStore = settingsStore,
                    downloadEngine = dlEngine,
                    initialApkUpdateManager = updateMgr
                )
            }
        }
    }
}
