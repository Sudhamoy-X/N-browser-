package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.viewmodel.NyxoraBrowserViewModel
import com.example.ui.viewmodel.OverlaySheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main Jetpack Compose screen for the NYXORA Browser with a minimalist 3-region layout:
 * 1. Top Bar: URL/search input field and thin real-time progress indicator (`NyxoraTopAddressBar`).
 * 2. Central Container: Browser viewport hosting the start view or active web engine (`nyxora_central_browser_container`).
 * 3. Bottom Bar: Essential navigation controls — Back, Forward, Home, Tabs, Menu (`NyxoraBottomTabManagementBar`).
 */
@Composable
fun NyxoraMainBrowserScreen(
    viewModel: NyxoraBrowserViewModel
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val addressFocusRequester = remember { FocusRequester() }

    // Core lightweight states required for the main frame
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val normalTabs by viewModel.normalTabs.collectAsStateWithLifecycle()
    val privateTabs by viewModel.privateTabs.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val isPrivateMode by viewModel.isPrivateMode.collectAsStateWithLifecycle()
    val isPrivateUnlocked by viewModel.isPrivateUnlocked.collectAsStateWithLifecycle()
    val activeOverlay by viewModel.activeOverlay.collectAsStateWithLifecycle()
    val findInPageVisible by viewModel.findInPageVisible.collectAsStateWithLifecycle()
    val findInPageQuery by viewModel.findInPageQuery.collectAsStateWithLifecycle()
    val findInPageMatches by viewModel.findInPageMatches.collectAsStateWithLifecycle()
    val splitSecondTabId by viewModel.splitSecondaryTabId.collectAsStateWithLifecycle()
    val sitePermPrompt by viewModel.pendingSitePermissionPrompt.collectAsStateWithLifecycle()
    val externalSchemePrompt by viewModel.pendingExternalSchemePrompt.collectAsStateWithLifecycle()
    val riskyDownloadPrompt by viewModel.pendingRiskyDownload.collectAsStateWithLifecycle()
    val sslWarningPrompt by viewModel.pendingSslErrorPrompt.collectAsStateWithLifecycle()

    var addressInput by rememberSaveable { mutableStateOf("") }
    var isEditingAddress by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val primaryController = remember { WebViewControllerHandle() }
    val splitController = remember { WebViewControllerHandle() }
    val snackbarHostState = remember { SnackbarHostState() }

    val localFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.openLocalUriInBrowser(context, uri, null)
        }
    }

    val secondarySplitTab = remember(normalTabs, privateTabs, splitSecondTabId) {
        if (splitSecondTabId == null) null
        else (normalTabs + privateTabs).firstOrNull { it.id == splitSecondTabId }
    }

    // Collect toast messages
    LaunchedEffect(Unit) {
        viewModel.statusToast.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Quiet 2-second snackbar for blocked pop-ups and auto-redirects with "Allow once"
    LaunchedEffect(Unit) {
        viewModel.blockedSnackbar.collect { event ->
            val job = launch {
                val result = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = "Allow once",
                    duration = SnackbarDuration.Indefinite
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.allowUrlOnce(event.blockedUrl)
                }
            }
            delay(2000L)
            snackbarHostState.currentSnackbarData?.dismiss()
            job.cancel()
        }
    }

    val currentModeTabs = remember(normalTabs, privateTabs, isPrivateMode) {
        if (isPrivateMode) privateTabs else normalTabs
    }

    val visibleModeTabsCount = remember(currentModeTabs) {
        currentModeTabs.size.coerceAtLeast(1)
    }

    // Back handler: exit editing, find-in-page, or navigate WebView back
    BackHandler(
        enabled = isEditingAddress ||
            findInPageVisible ||
            (!activeTab.isHomePage && activeTab.canGoBack)
    ) {
        when {
            isEditingAddress -> {
                focusManager.clearFocus()
                isEditingAddress = false
            }
            findInPageVisible -> {
                primaryController.clearFindMatches()
                viewModel.setFindInPageVisible(false)
            }
            !activeTab.isHomePage && activeTab.canGoBack -> {
                primaryController.goBack()
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .testTag("nyxora_main_browser_screen"),
        contentWindowInsets = WindowInsets.systemBars,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            // 1. Minimalist Top Bar: URL/Search Input & Real-Time Thin Progress Indicator
            NyxoraTopAddressBar(
                activeTab = activeTab,
                settings = settings,
                isPrivateMode = isPrivateMode,
                addressInput = addressInput,
                isEditingAddress = isEditingAddress,
                findInPageVisible = findInPageVisible,
                findInPageQuery = findInPageQuery,
                findInPageMatches = findInPageMatches,
                currentModeTabs = currentModeTabs,
                addressFocusRequester = addressFocusRequester,
                onAddressInputChange = {
                    if (!isEditingAddress) isEditingAddress = true
                    addressInput = it
                },
                onEditingChanged = { focused ->
                    if (focused && !isEditingAddress) {
                        addressInput = if (activeTab.isHomePage) "" else activeTab.url
                        isEditingAddress = true
                    } else if (!focused && isEditingAddress) {
                        isEditingAddress = false
                    }
                },
                onSubmitAddress = {
                    val submitted = addressInput.trim()
                    isEditingAddress = false
                    focusManager.clearFocus()
                    if (submitted.isNotEmpty()) {
                        viewModel.navigateInActiveTab(submitted)
                    }
                },
                onClearAddress = { addressInput = "" },
                onReloadOrStop = {
                    if (activeTab.isLoading) {
                        primaryController.stopLoading()
                    } else {
                        primaryController.reload()
                    }
                },
                onSelectTab = { tabId ->
                    viewModel.selectTab(tabId, isPrivateMode)
                },
                onFindQueryChange = { q ->
                    viewModel.updateFindInPageQuery(q)
                    primaryController.findInPage(q)
                },
                onFindNext = { forward -> primaryController.findNext(forward) },
                onCloseFindInPage = {
                    primaryController.clearFindMatches()
                    viewModel.setFindInPageVisible(false)
                }
            )
        },
        bottomBar = {
            // 3. Minimalist Bottom Bar: Back, Forward, Home, Tabs (with count), Menu
            NyxoraBottomTabManagementBar(
                activeTab = activeTab,
                visibleModeTabsCount = visibleModeTabsCount,
                isPrivateMode = isPrivateMode,
                compactMode = settings.compactBottomToolbar,
                showOverflowMenu = showOverflowMenu,
                splitSecondTabId = splitSecondTabId,
                onBack = {
                    if (!activeTab.isHomePage) {
                        if (!primaryController.goBack()) {
                            viewModel.navigateHome()
                        }
                    }
                },
                onForward = {
                    if (activeTab.canGoForward) {
                        primaryController.goForward()
                    }
                },
                onHome = { viewModel.navigateHome() },
                onOpenTabSwitcher = {
                    viewModel.setOverlaySheet(OverlaySheet.TAB_SWITCHER)
                },
                onToggleOverflowMenu = { showOverflowMenu = it },
                onMenuNewTab = {
                    viewModel.createNewTab(url = "nyxora://home", inPrivateMode = false, inBackground = false)
                },
                onMenuTogglePrivateMode = {
                    if (isPrivateMode) {
                        viewModel.switchBrowsingMode(false)
                    } else {
                        viewModel.createNewTab(url = "nyxora://home", inPrivateMode = true, inBackground = false)
                    }
                },
                onMenuOpenLocalFile = {
                    localFileLauncher.launch(arrayOf("text/html", "text/plain", "*/*"))
                },
                onMenuToggleBookmark = { viewModel.toggleBookmarkForActiveTab() },
                onMenuToggleDesktop = { viewModel.toggleDesktopSiteForActiveTab() },
                onMenuOpenFindInPage = { viewModel.setFindInPageVisible(true) },
                onMenuOpenReaderMode = {
                    primaryController.extractReaderArticle { article ->
                        if (article != null) {
                            viewModel.showReaderArticle(article)
                        } else {
                            viewModel.emitToast("No readable article detected on this page")
                        }
                    }
                },
                onMenuToggleSplitView = { viewModel.toggleSplitView(null) },
                onMenuSharePage = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, activeTab.title)
                        putExtra(Intent.EXTRA_TEXT, activeTab.url)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Share Page"))
                },
                onMenuCopyLink = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", activeTab.url))
                    viewModel.emitToast("Link copied")
                },
                onMenuOpenOverlay = { sheet -> viewModel.setOverlaySheet(sheet) }
            )
        }
    ) { innerPadding ->
        // 2. Central Container for the Browser View
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("nyxora_central_browser_container")
        ) {
            if (isPrivateMode && settings.privateTabsLockEnabled && settings.privateTabsPinHash.isNotEmpty() && !isPrivateUnlocked) {
                PrivatePinLockView(
                    onVerifyPin = { pin -> viewModel.unlockPrivateTabsWithPin(pin) },
                    onExitPrivate = { viewModel.switchBrowsingMode(false) }
                )
            } else if (activeTab.isHomePage) {
                NyxoraHomePage(
                    settings = settings,
                    isPrivateMode = isPrivateMode,
                    bookmarksFlow = viewModel.bookmarks,
                    historyFlow = viewModel.history,
                    onNavigateToUrl = { input ->
                        isEditingAddress = false
                        focusManager.clearFocus()
                        viewModel.navigateInActiveTab(input)
                    },
                    onAddShortcut = { title, url ->
                        viewModel.addBookmarkOrShortcut(title, url, "Shortcuts", isShortcut = true)
                    },
                    onDeleteBookmark = { id -> viewModel.deleteBookmark(id) },
                    onOpenHistory = { viewModel.setOverlaySheet(OverlaySheet.HISTORY) }
                )
            } else {
                // Lazy-collect site settings & allowOnceUrls only when rendering a web page
                val siteSettings by viewModel.siteSettings.collectAsStateWithLifecycle()
                val allowOnceUrls by viewModel.allowOnceUrls.collectAsStateWithLifecycle()

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideScreen = maxWidth >= 600.dp
                    if (secondarySplitTab != null && secondarySplitTab.id != activeTab.id) {
                        if (isWideScreen) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    NyxoraWebEngineView(
                                        tab = activeTab,
                                        settings = settings,
                                        siteSettings = siteSettings,
                                        allowOnceUrls = allowOnceUrls,
                                        controllerHandle = primaryController,
                                        onPageStarted = viewModel::onWebPageStarted,
                                        onPageFinished = viewModel::onWebPageFinished,
                                        onProgressChanged = viewModel::onWebProgressChanged,
                                        onPageError = viewModel::onWebPageError,
                                        onSslError = viewModel::onSslCertificateError,
                                        onAdBlocked = viewModel::recordAdBlocked,
                                        onPopupBlocked = viewModel::onPopupBlocked,
                                        onRedirectBlocked = viewModel::onAutoRedirectBlocked,
                                        onOpenNewTab = { url, priv, bg ->
                                            viewModel.createNewTab(url = url, inPrivateMode = priv, inBackground = bg)
                                        },
                                        onPromptExternalScheme = { url, _ ->
                                            viewModel.navigateInActiveTab(url)
                                        },
                                        onRequestDownload = viewModel::requestDownload,
                                        onPromptSitePermission = viewModel::promptSitePermission,
                                        onFindMatchesUpdated = viewModel::updateFindInPageMatches,
                                        onNavigateHome = viewModel::navigateHome,
                                        onToast = viewModel::emitToast
                                    )
                                }
                                VerticalDivider(
                                    thickness = 2.dp,
                                    color = NyxoraElectricBlue.copy(alpha = 0.6f)
                                )
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    NyxoraWebEngineView(
                                        tab = secondarySplitTab,
                                        settings = settings,
                                        siteSettings = siteSettings,
                                        allowOnceUrls = allowOnceUrls,
                                        controllerHandle = splitController,
                                        onPageStarted = viewModel::onWebPageStarted,
                                        onPageFinished = viewModel::onWebPageFinished,
                                        onProgressChanged = viewModel::onWebProgressChanged,
                                        onPageError = viewModel::onWebPageError,
                                        onSslError = viewModel::onSslCertificateError,
                                        onAdBlocked = viewModel::recordAdBlocked,
                                        onPopupBlocked = viewModel::onPopupBlocked,
                                        onRedirectBlocked = viewModel::onAutoRedirectBlocked,
                                        onOpenNewTab = { url, priv, bg ->
                                            viewModel.createNewTab(url = url, inPrivateMode = priv, inBackground = bg)
                                        },
                                        onPromptExternalScheme = { url, _ ->
                                            viewModel.navigateInActiveTab(url)
                                        },
                                        onRequestDownload = viewModel::requestDownload,
                                        onPromptSitePermission = viewModel::promptSitePermission,
                                        onFindMatchesUpdated = { _, _ -> },
                                        onNavigateHome = { viewModel.toggleSplitView(null) },
                                        onToast = viewModel::emitToast
                                    )
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    NyxoraWebEngineView(
                                        tab = activeTab,
                                        settings = settings,
                                        siteSettings = siteSettings,
                                        allowOnceUrls = allowOnceUrls,
                                        controllerHandle = primaryController,
                                        onPageStarted = viewModel::onWebPageStarted,
                                        onPageFinished = viewModel::onWebPageFinished,
                                        onProgressChanged = viewModel::onWebProgressChanged,
                                        onPageError = viewModel::onWebPageError,
                                        onSslError = viewModel::onSslCertificateError,
                                        onAdBlocked = viewModel::recordAdBlocked,
                                        onPopupBlocked = viewModel::onPopupBlocked,
                                        onRedirectBlocked = viewModel::onAutoRedirectBlocked,
                                        onOpenNewTab = { url, priv, bg ->
                                            viewModel.createNewTab(url = url, inPrivateMode = priv, inBackground = bg)
                                        },
                                        onPromptExternalScheme = { url, _ ->
                                            viewModel.navigateInActiveTab(url)
                                        },
                                        onRequestDownload = viewModel::requestDownload,
                                        onPromptSitePermission = viewModel::promptSitePermission,
                                        onFindMatchesUpdated = viewModel::updateFindInPageMatches,
                                        onNavigateHome = viewModel::navigateHome,
                                        onToast = viewModel::emitToast
                                    )
                                }
                                HorizontalDivider(
                                    thickness = 2.dp,
                                    color = NyxoraElectricBlue.copy(alpha = 0.6f)
                                )
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    NyxoraWebEngineView(
                                        tab = secondarySplitTab,
                                        settings = settings,
                                        siteSettings = siteSettings,
                                        allowOnceUrls = allowOnceUrls,
                                        controllerHandle = splitController,
                                        onPageStarted = viewModel::onWebPageStarted,
                                        onPageFinished = viewModel::onWebPageFinished,
                                        onProgressChanged = viewModel::onWebProgressChanged,
                                        onPageError = viewModel::onWebPageError,
                                        onSslError = viewModel::onSslCertificateError,
                                        onAdBlocked = viewModel::recordAdBlocked,
                                        onPopupBlocked = viewModel::onPopupBlocked,
                                        onRedirectBlocked = viewModel::onAutoRedirectBlocked,
                                        onOpenNewTab = { url, priv, bg ->
                                            viewModel.createNewTab(url = url, inPrivateMode = priv, inBackground = bg)
                                        },
                                        onPromptExternalScheme = { url, _ ->
                                            viewModel.navigateInActiveTab(url)
                                        },
                                        onRequestDownload = viewModel::requestDownload,
                                        onPromptSitePermission = viewModel::promptSitePermission,
                                        onFindMatchesUpdated = { _, _ -> },
                                        onNavigateHome = { viewModel.toggleSplitView(null) },
                                        onToast = viewModel::emitToast
                                    )
                                }
                            }
                        }
                    } else {
                        NyxoraWebEngineView(
                            tab = activeTab,
                            settings = settings,
                            siteSettings = siteSettings,
                            allowOnceUrls = allowOnceUrls,
                            controllerHandle = primaryController,
                            onPageStarted = viewModel::onWebPageStarted,
                            onPageFinished = viewModel::onWebPageFinished,
                            onProgressChanged = viewModel::onWebProgressChanged,
                            onPageError = viewModel::onWebPageError,
                            onSslError = viewModel::onSslCertificateError,
                            onAdBlocked = viewModel::recordAdBlocked,
                            onPopupBlocked = viewModel::onPopupBlocked,
                            onRedirectBlocked = viewModel::onAutoRedirectBlocked,
                            onOpenNewTab = { url, priv, bg ->
                                viewModel.createNewTab(url = url, inPrivateMode = priv, inBackground = bg)
                            },
                            onPromptExternalScheme = { url, _ ->
                                viewModel.navigateInActiveTab(url)
                            },
                            onRequestDownload = viewModel::requestDownload,
                            onPromptSitePermission = viewModel::promptSitePermission,
                            onFindMatchesUpdated = viewModel::updateFindInPageMatches,
                            onNavigateHome = viewModel::navigateHome,
                            onToast = viewModel::emitToast
                        )
                    }
                }
            }

            // Autocomplete Suggestions Dropdown Overlay when editing top address bar
            if (isEditingAddress && addressInput.isNotBlank() && settings.searchSuggestionsEnabled) {
                val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
                val history by viewModel.history.collectAsStateWithLifecycle()
                val suggestions = remember(addressInput, bookmarks, history) {
                    val q = addressInput.trim().lowercase()
                    val bmMatches = bookmarks.filter {
                        it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
                    }.map { Triple(it.title, it.url, true) }
                    val histMatches = history.filter {
                        it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
                    }.map { Triple(it.title, it.url, false) }
                    (bmMatches + histMatches).distinctBy { it.second }.take(6)
                }

                if (suggestions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp
                    ) {
                        LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                            items(suggestions, key = { it.second }) { (title, url, isBookmark) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isEditingAddress = false
                                            focusManager.clearFocus()
                                            viewModel.navigateInActiveTab(url)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isBookmark) Icons.Default.Bookmark else Icons.Default.History,
                                        contentDescription = null,
                                        tint = if (isBookmark) NyxoraElectricBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = url,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Lazy-loaded Full-screen Overlay Sheets — only compose & collect data when opened
    when (activeOverlay) {
        OverlaySheet.TAB_SWITCHER -> {
            val activeNormalTabId by viewModel.activeNormalTabId.collectAsStateWithLifecycle()
            val activePrivateTabId by viewModel.activePrivateTabId.collectAsStateWithLifecycle()
            val recentlyClosedTabs by viewModel.recentlyClosedTabs.collectAsStateWithLifecycle()
            NyxoraTabSwitcherSheet(
                normalTabs = normalTabs,
                privateTabs = privateTabs,
                activeNormalTabId = activeNormalTabId,
                activePrivateTabId = activePrivateTabId,
                isPrivateMode = isPrivateMode,
                isPrivateUnlocked = isPrivateUnlocked || !settings.privateTabsLockEnabled || settings.privateTabsPinHash.isEmpty(),
                hasRecentlyClosed = recentlyClosedTabs.isNotEmpty(),
                onSwitchMode = { priv -> viewModel.switchBrowsingMode(priv) },
                onUnlockPrivateWithPin = { pin -> viewModel.unlockPrivateTabsWithPin(pin) },
                onSelectTab = { tabId, priv -> viewModel.selectTab(tabId, priv) },
                onCreateNewTab = { priv, group ->
                    viewModel.createNewTab(url = "nyxora://home", inPrivateMode = priv, inBackground = false, groupName = group)
                },
                onDuplicateTab = { id -> viewModel.duplicateTab(id) },
                onCloseTab = { id -> viewModel.closeTab(id) },
                onCloseAllTabs = { priv -> viewModel.closeAllTabs(priv) },
                onReopenClosedTab = { viewModel.reopenRecentlyClosedTab() },
                onAssignTabGroup = { tabId, group -> viewModel.updateTabGroup(tabId, group) },
                onOpenInSplitView = { tabId ->
                    viewModel.toggleSplitView(tabId)
                    viewModel.setOverlaySheet(OverlaySheet.NONE)
                },
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
        }
        OverlaySheet.BOOKMARKS -> {
            val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
            NyxoraBookmarksSheet(
                bookmarks = bookmarks,
                onSelectUrl = { url -> viewModel.navigateInActiveTab(url) },
                onAddBookmark = { title, url, folder, isShortcut ->
                    viewModel.addBookmarkOrShortcut(title, url, folder, isShortcut)
                },
                onDeleteBookmark = { id -> viewModel.deleteBookmark(id) },
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
        }
        OverlaySheet.HISTORY -> {
            val history by viewModel.history.collectAsStateWithLifecycle()
            NyxoraHistorySheet(
                history = history,
                onSelectUrl = { url -> viewModel.navigateInActiveTab(url) },
                onDeleteHistoryItem = { id -> viewModel.deleteHistoryItem(id) },
                onClearBrowsingData = { clearHist, clearCookies ->
                    viewModel.clearBrowsingData(clearHist, clearCookies)
                },
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
        }
        OverlaySheet.DOWNLOADS -> {
            val downloads by viewModel.downloads.collectAsStateWithLifecycle()
            NyxoraDownloadsSheet(
                downloads = downloads,
                downloadEngine = viewModel.downloadEngine,
                onToast = viewModel::emitToast,
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
        }
        OverlaySheet.SITE_SETTINGS -> {
            val siteSettings by viewModel.siteSettings.collectAsStateWithLifecycle()
            NyxoraSiteSettingsSheet(
                currentHost = activeTab.host,
                siteSettings = siteSettings,
                currentUserId = viewModel.currentUserId,
                onSaveSiteSetting = { setting ->
                    viewModel.saveSiteSetting(setting)
                    primaryController.reload()
                },
                onDeleteSiteSetting = { host ->
                    viewModel.deleteSiteSetting(host)
                    primaryController.reload()
                },
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
        }
        OverlaySheet.SETTINGS -> {
            val customFilterRules by viewModel.customFilterRules.collectAsStateWithLifecycle()
            NyxoraSettingsScreen(
                settings = settings,
                customFilterRules = customFilterRules,
                onUpdateSettings = viewModel::updateSettings,
                onRefreshFilterList = viewModel::refreshRemoteFilterList,
                onAddCustomFilterRule = viewModel::addCustomFilterRule,
                onRemoveCustomFilterRule = viewModel::removeCustomFilterRule,
                onOpenSiteSettings = { viewModel.setOverlaySheet(OverlaySheet.SITE_SETTINGS) },
                onClearBrowsingData = { clearHist, clearCookies ->
                    viewModel.clearBrowsingData(clearHist, clearCookies)
                },
                onSetPrivatePin = viewModel::setPrivateTabsPin,
                onExportEncryptedBackup = viewModel::exportEncryptedBackup,
                onImportEncryptedBackup = viewModel::importEncryptedBackup,
                onToast = viewModel::emitToast,
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
        }
        OverlaySheet.READER_MODE -> {
            val readerArticle by viewModel.activeReaderArticle.collectAsStateWithLifecycle()
            val article = readerArticle
            if (article != null) {
                NyxoraReaderModeSheet(
                    article = article,
                    onDismiss = {
                        viewModel.showReaderArticle(null)
                        viewModel.setOverlaySheet(OverlaySheet.NONE)
                    }
                )
            } else {
                viewModel.setOverlaySheet(OverlaySheet.NONE)
            }
        }
        OverlaySheet.NONE -> Unit
    }

    // Security & Site Permission Dialogs
    NyxoraSecurityAndPermissionDialogs(
        sitePermPrompt = sitePermPrompt,
        externalSchemePrompt = externalSchemePrompt,
        riskyDownloadPrompt = riskyDownloadPrompt,
        sslWarningPrompt = sslWarningPrompt,
        onResolveSitePermission = viewModel::resolveSitePermissionPrompt,
        onDismissExternalScheme = viewModel::dismissExternalSchemePrompt,
        onConfirmRiskyDownload = viewModel::confirmRiskyDownload,
        onResolveSslError = viewModel::resolveSslErrorPrompt,
        onToast = viewModel::emitToast
    )
}
