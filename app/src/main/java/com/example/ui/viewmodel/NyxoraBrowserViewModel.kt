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
    val desktopMode: Boolean = false,
    val textZoom: Int = 100,
    val hasCertificateError: Boolean = false,
    val pageErrorDescription: String? = null,
    val pendingHttpFallbackUrl: String? = null,
    val isSuspended: Boolean = false,
    val lastActiveAtMillis: Long = System.currentTimeMillis()
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
    READER_MODE
}

class NyxoraBrowserViewModel(
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    private val savedTabDao: SavedTabDao,
    private val siteSettingDao: SiteSettingDao,
    private val downloadDao: DownloadDao,
    private val filterRuleDao: FilterRuleDao,
    private val settingsDataStore: NyxoraSettingsDataStore,
    val downloadEngine: DownloadEngine
) : ViewModel() {

    @Volatile
    var hasInitializedWebViewEngine: Boolean = false
        private set

    @Volatile
    private var hasOpenedPrivateWebPage: Boolean = false

    val currentUserId: String = "local_user"

    val settings: StateFlow<NyxoraSettingsState> = settingsDataStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = NyxoraSettingsState()
        )

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

    // Active tab derived flow
    val activeTab: StateFlow<BrowserTab> = combine(
        _isPrivateMode,
        _normalTabs,
        _privateTabs,
        _activeNormalTabId,
        _activePrivateTabId
    ) { isPriv, normList, privList, normId, privId ->
        if (isPriv) {
            privList.find { it.id == privId } ?: privList.firstOrNull()
                ?: BrowserTab(id = "priv_empty", title = "Private Home", url = "nyxora://home", isPrivate = true)
        } else {
            normList.find { it.id == normId } ?: normList.firstOrNull()
                ?: BrowserTab(id = "tab_initial", title = "Home", url = "nyxora://home", isPrivate = false)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = BrowserTab(id = "tab_initial", title = "Home", url = "nyxora://home")
    )

    init {
        // Keep ContentBlockingEngine updated with custom rules on a background thread
        viewModelScope.launch(Dispatchers.Default) {
            customFilterRules.collect { rules ->
                ContentBlockingEngine.updateCustomRules(rules)
            }
        }

        // Restore normal tabs, seed default homepage shortcuts, and warm up top-visited host on Dispatchers.IO
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localTabs = savedTabDao.getAllSavedTabs()
                if (localTabs.isNotEmpty()) {
                    val restored = localTabs.map { item ->
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
                    val homeTab = restored.firstOrNull { it.isHomePage }
                    if (homeTab != null) {
                        _normalTabs.value = restored
                        _activeNormalTabId.value = homeTab.id
                    } else {
                        val freshHome = BrowserTab(
                            id = "tab_initial",
                            title = "Home",
                            url = "nyxora://home",
                            isPrivate = false
                        )
                        _normalTabs.value = listOf(freshHome) + restored
                        _activeNormalTabId.value = freshHome.id
                    }
                }
                seedDefaultShortcutsIfEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring local tabs", e)
            }
        }
    }

    private suspend fun seedDefaultShortcutsIfEmpty() {
        val currentBookmarks = bookmarkDao.getAllBookmarks()
        if (currentBookmarks.isEmpty()) {
            val now = System.currentTimeMillis()
            val defaults = listOf(
                Triple("Wikipedia", "https://www.wikipedia.org", "Reference"),
                Triple("Hacker News", "https://news.ycombinator.com", "Tech"),
                Triple("GitHub", "https://github.com", "Dev"),
                Triple("MDN Web Docs", "https://developer.mozilla.org", "Dev"),
                Triple("DuckDuckGo", "https://duckduckgo.com", "Search"),
                Triple("Android Developers", "https://developer.android.com", "Dev")
            )
            val entities = defaults.mapIndexed { idx, (title, url, folder) ->
                BookmarkItem(
                    id = "default_shortcut_$idx",
                    userId = "local_user",
                    title = title,
                    url = url,
                    folder = folder,
                    isShortcut = true,
                    createdAtMillis = now - idx
                )
            }
            bookmarkDao.upsertBookmarks(entities)
        }
    }

    // --- NAVIGATION & ADDRESS BAR ---

    fun navigateInActiveTab(rawInput: String) {
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
                val siteOverride = getSiteSettingForHost(target.host)
                val effectiveDesktop = siteOverride?.desktopMode ?: currentSettings.defaultDesktopSite
                val effectiveZoom = siteOverride?.textZoom ?: currentSettings.globalTextZoom
                val isHome = target.finalUrl.equals("nyxora://home", ignoreCase = true)

                updateTab(activeTab.value.id) { tab ->
                    tab.copy(
                        url = target.finalUrl,
                        title = if (isHome) "Home" else target.safeDisplayHost.ifBlank { target.finalUrl },
                        desktopMode = effectiveDesktop,
                        textZoom = effectiveZoom,
                        isLoading = !isHome,
                        progress = if (isHome) 0 else 8,
                        hasCertificateError = false,
                        pageErrorDescription = null,
                        pendingHttpFallbackUrl = target.fallbackHttpUrl,
                        isSuspended = false,
                        lastActiveAtMillis = System.currentTimeMillis()
                    )
                }
            }
        }
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
            val next = tab.copy(
                url = url,
                title = if (url == "nyxora://home") "Home" else cleanTitle,
                isLoading = false,
                progress = 100,
                pendingHttpFallbackUrl = null,
                canGoBack = canGoBack,
                canGoForward = canGoForward
            )
            updatedTabRef = next
            next
        }

        val tab = updatedTabRef ?: return
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
        updateTab(tabId) { tab ->
            tab.copy(
                progress = progress.coerceIn(0, 100),
                isLoading = progress in 1..99 && tab.pageErrorDescription == null
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
        updateTab(activeTab.value.id) { tab ->
            tab.copy(
                url = "nyxora://home",
                title = if (tab.isPrivate) "Private Home" else "Home",
                isLoading = false,
                progress = 0,
                pageErrorDescription = null,
                hasCertificateError = false
            )
        }
    }

    // --- TABS & SESSION MANAGEMENT ---

    fun createNewTab(
        url: String = "nyxora://home",
        inPrivateMode: Boolean = _isPrivateMode.value,
        inBackground: Boolean = false,
        groupName: String = "Default"
    ): String {
        val currentSettings = settings.value
        val host = UrlAndSearchHandler.extractHost(url)
        val siteOverride = getSiteSettingForHost(host)
        val newTab = BrowserTab(
            id = UUID.randomUUID().toString(),
            title = if (url == "nyxora://home") (if (inPrivateMode) "Private Home" else "Home") else host.ifBlank { "New Tab" },
            url = url,
            isPrivate = inPrivateMode,
            groupName = groupName,
            desktopMode = siteOverride?.desktopMode ?: currentSettings.defaultDesktopSite,
            textZoom = siteOverride?.textZoom ?: currentSettings.globalTextZoom
        )

        if (inPrivateMode) {
            _privateTabs.update { it + newTab }
            if (!inBackground || _activePrivateTabId.value == null) {
                _activePrivateTabId.value = newTab.id
                _isPrivateMode.value = true
            }
        } else {
            _normalTabs.update { it + newTab }
            if (!inBackground) {
                _activeNormalTabId.value = newTab.id
                _isPrivateMode.value = false
            }
            viewModelScope.launch {
                savedTabDao.upsertSavedTab(
                    SyncedTabItem(
                        id = newTab.id,
                        userId = currentUserId,
                        title = newTab.title,
                        url = newTab.url,
                        groupName = newTab.groupName,
                        isActive = !inBackground,
                        desktopMode = newTab.desktopMode,
                        textZoom = newTab.textZoom,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
        suspendExcessInactiveTabs()
        if (inBackground) {
            emitToast("Opened in background ${if (inPrivateMode) "private " else ""}tab")
        }
        return newTab.id
    }

    fun selectTab(tabId: String, isPrivate: Boolean) {
        _isPrivateMode.value = isPrivate
        if (isPrivate) {
            _activePrivateTabId.value = tabId
        } else {
            _activeNormalTabId.value = tabId
        }
        updateTab(tabId) { it.copy(isSuspended = false, lastActiveAtMillis = System.currentTimeMillis()) }
        suspendExcessInactiveTabs()
    }

    fun duplicateTab(tabId: String) {
        val source = (_normalTabs.value + _privateTabs.value).find { it.id == tabId } ?: return
        createNewTab(
            url = source.url,
            inPrivateMode = source.isPrivate,
            inBackground = false,
            groupName = source.groupName
        )
    }

    fun closeTab(tabId: String) {
        val privMatch = _privateTabs.value.find { it.id == tabId }
        if (privMatch != null) {
            val remaining = _privateTabs.value.filterNot { it.id == tabId }
            _privateTabs.value = remaining
            if (_activePrivateTabId.value == tabId) {
                _activePrivateTabId.value = remaining.lastOrNull()?.id
            }
            if (remaining.isEmpty()) {
                wipePrivateSessionData()
            }
            return
        }

        val normMatch = _normalTabs.value.find { it.id == tabId } ?: return
        _recentlyClosedTabs.update { (listOf(normMatch) + it).take(15) }
        val remaining = _normalTabs.value.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val fallback = BrowserTab(id = UUID.randomUUID().toString(), title = "Home", url = "nyxora://home")
            _normalTabs.value = listOf(fallback)
            _activeNormalTabId.value = fallback.id
        } else {
            _normalTabs.value = remaining
            if (_activeNormalTabId.value == tabId) {
                _activeNormalTabId.value = remaining.last().id
            }
        }
        if (_splitSecondaryTabId.value == tabId) {
            _splitSecondaryTabId.value = null
        }
        viewModelScope.launch {
            savedTabDao.deleteSavedTab(tabId)
        }
    }

    fun closeAllTabs(inPrivateMode: Boolean) {
        if (inPrivateMode) {
            _privateTabs.value = emptyList()
            _activePrivateTabId.value = null
            wipePrivateSessionData()
            _isPrivateMode.value = false
            emitToast("Private session wiped")
        } else {
            val oldTabs = _normalTabs.value
            _recentlyClosedTabs.update { (oldTabs + it).take(15) }
            val fresh = BrowserTab(id = UUID.randomUUID().toString(), title = "Home", url = "nyxora://home")
            _normalTabs.value = listOf(fresh)
            _activeNormalTabId.value = fresh.id
            _splitSecondaryTabId.value = null
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

    fun reopenRecentlyClosedTab() {
        val latest = _recentlyClosedTabs.value.firstOrNull() ?: return
        _recentlyClosedTabs.update { it.drop(1) }
        createNewTab(
            url = latest.url,
            inPrivateMode = false,
            inBackground = false,
            groupName = latest.groupName
        )
    }

    fun switchBrowsingMode(privateMode: Boolean) {
        _isPrivateMode.value = privateMode
        if (privateMode) {
            if (settings.value.privateTabsLockEnabled && settings.value.privateTabsPinHash.isNotEmpty()) {
                _isPrivateUnlocked.value = false
            }
            if (_privateTabs.value.isEmpty()) {
                createNewTab(url = "nyxora://home", inPrivateMode = true, inBackground = false)
            }
        }
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
        val currentActiveId = activeTab.value.id
        val splitId = _splitSecondaryTabId.value
        _normalTabs.update { list ->
            val sortedByRecent = list.sortedByDescending { it.lastActiveAtMillis }
            val keepIds = sortedByRecent.take(maxKeepAlive).map { it.id }.toSet() + setOfNotNull(currentActiveId, splitId)
            list.map { tab ->
                if (tab.id in keepIds) tab else tab.copy(isSuspended = true)
            }
        }
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
        updateTab(current.id) { it.copy(desktopMode = nextDesktop) }
        val host = current.host
        if (host.isNotBlank() && host != "nyxora.home") {
            val existing = getSiteSettingForHost(host) ?: SiteSettingItem(userId = currentUserId, host = host)
            saveSiteSetting(existing.copy(desktopMode = nextDesktop))
        }
    }

    fun updateTextZoomForActiveTab(newZoom: Int) {
        val clamped = newZoom.coerceIn(50, 250)
        val current = activeTab.value
        updateTab(current.id) { it.copy(textZoom = clamped) }
        val host = current.host
        if (host.isNotBlank() && host != "nyxora.home") {
            val existing = getSiteSettingForHost(host) ?: SiteSettingItem(userId = currentUserId, host = host)
            saveSiteSetting(existing.copy(textZoom = clamped))
        }
    }

    fun getSiteSettingForHost(host: String): SiteSettingItem? {
        val clean = host.trim().lowercase().removePrefix("www.")
        if (clean.isEmpty()) return null
        return siteSettings.value.firstOrNull { it.host.equals(clean, ignoreCase = true) }
    }

    fun saveSiteSetting(setting: SiteSettingItem) {
        val cleanHost = setting.host.trim().lowercase().removePrefix("www.")
        if (cleanHost.isEmpty()) return
        val normalized = setting.copy(
            id = setting.id.ifBlank { cleanHost },
            userId = currentUserId,
            host = cleanHost
        )
        viewModelScope.launch {
            siteSettingDao.upsertSiteSetting(normalized)
        }
    }

    fun deleteSiteSetting(host: String) {
        val cleanHost = host.trim().lowercase().removePrefix("www.")
        viewModelScope.launch {
            siteSettingDao.deleteSiteSettingByHost(cleanHost)
        }
    }

    // --- BOOKMARKS & SHORTCUTS ---

    fun toggleBookmarkForActiveTab() {
        val tab = activeTab.value
        if (tab.isHomePage) return
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
            emitToast(if (isShortcut) "Shortcut pinned to Homepage" else "Bookmark saved")
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
        viewModelScope.launch {
            settingsDataStore.incrementBlockedMetric(popups = 1)
            if (settings.value.showBlockedMessages) {
                _blockedSnackbar.tryEmit(
                    BlockedEventSnackbar(
                        message = "Pop-up blocked",
                        blockedUrl = blockedUrl
                    )
                )
            }
        }
    }

    fun onAutoRedirectBlocked(blockedUrl: String) {
        viewModelScope.launch {
            settingsDataStore.incrementBlockedMetric(redirects = 1)
            if (settings.value.showBlockedMessages) {
                _blockedSnackbar.tryEmit(
                    BlockedEventSnackbar(
                        message = "Auto-redirect blocked",
                        blockedUrl = blockedUrl
                    )
                )
            }
        }
    }

    fun allowUrlOnce(url: String) {
        if (url.isBlank()) return
        _allowOnceUrls.update { it + url }
        navigateInActiveTab(url)
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
            downloadEngine.enqueueDownload(url, suggestedFileName, mimeType)
            emitToast("Download started: ${suggestedFileName ?: UrlAndSearchHandler.extractHost(url)}")
        }
    }

    fun confirmRiskyDownload(proceed: Boolean) {
        val pending = _pendingRiskyDownload.value ?: return
        _pendingRiskyDownload.value = null
        if (proceed) {
            downloadEngine.enqueueDownload(pending.url, pending.suggestedFileName, pending.mimeType)
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
                    val cleanHost = site.host.trim().lowercase().removePrefix("www.")
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

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _normalTabs.update { list ->
            list.map { if (it.id == tabId) transform(it) else it }
        }
        _privateTabs.update { list ->
            list.map { if (it.id == tabId) transform(it) else it }
        }
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

                NyxoraBrowserViewModel(
                    bookmarkDao = localDb.bookmarkDao(),
                    historyDao = localDb.historyDao(),
                    savedTabDao = localDb.savedTabDao(),
                    siteSettingDao = localDb.siteSettingDao(),
                    downloadDao = localDb.downloadDao(),
                    filterRuleDao = localDb.filterRuleDao(),
                    settingsDataStore = settingsStore,
                    downloadEngine = dlEngine
                )
            }
        }
    }
}
