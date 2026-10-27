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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.connect.IncomingRemoteSyncCommand
import com.example.domain.connect.PlaybackCorrectionDecision
import com.example.domain.connect.RoomSyncMode
import com.example.domain.connect.WatchTogetherMediaBridge
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.theme.NyxoraIncognitoPurple
import com.example.ui.viewmodel.NyxoraBrowserViewModel
import com.example.ui.viewmodel.OverlaySheet
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class EdgeSwipeSide {
    LEFT_BACK,
    RIGHT_FORWARD
}

private val EDGE_SWIPE_ACTIVATION_WIDTH = 28.dp
private val EDGE_SWIPE_COMMIT_THRESHOLD = 72.dp

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
    val pendingRedirectWarning by viewModel.pendingRedirectWarning.collectAsStateWithLifecycle()

    // NYXORA Connect Realtime Room Sync States
    val connectRoomState by viewModel.connectManager.roomState.collectAsStateWithLifecycle()
    val connectStatus by viewModel.connectManager.connectionStatus.collectAsStateWithLifecycle()
    val isFollowingPaused by viewModel.connectManager.isFollowingPaused.collectAsStateWithLifecycle()
    val videoCompatibility by viewModel.connectManager.videoCompatibility.collectAsStateWithLifecycle()
    val lastSyncError by viewModel.connectManager.lastSyncError.collectAsStateWithLifecycle()
    val deviceDisplayName by viewModel.connectManager.deviceDisplayName.collectAsStateWithLifecycle()

    var addressInput by rememberSaveable { mutableStateOf("") }
    var isEditingAddress by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var lastAutoSeekMillis by remember { mutableLongStateOf(0L) }

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

    // Bind local HTML5 video events from WebView to NYXORA Connect when in Watch Together mode
    LaunchedEffect(primaryController, activeTab.url, isPrivateMode) {
        primaryController.onLocalWatchTogetherEvent = { _, curTime, dur, isPaused, rate ->
            if (!isPrivateMode && !activeTab.isPrivate && !activeTab.isHomePage) {
                viewModel.connectManager.publishPlaybackEvent(
                    pageUrl = activeTab.url,
                    isPlaying = !isPaused,
                    positionSeconds = curTime,
                    durationSeconds = dur,
                    playbackRate = rate,
                    isBeacon = false,
                    isPrivateTab = activeTab.isPrivate,
                    isPrivateMode = isPrivateMode
                )
            }
        }
    }

    // Periodic Watch Together media compatibility probe & host playback beacon
    LaunchedEffect(connectRoomState?.roomId, connectRoomState?.mode, activeTab.url, isPrivateMode) {
        val room = connectRoomState
        if (room != null && room.mode == RoomSyncMode.WATCH_TOGETHER && !isPrivateMode && !activeTab.isHomePage) {
            while (isActive) {
                primaryController.probeWatchTogetherMedia { compat, curTime, dur, isPaused, rate ->
                    viewModel.connectManager.updateVideoCompatibility(compat)
                    if (compat.supportsDirectSync &&
                        room.hostDeviceId == viewModel.connectManager.localDeviceId &&
                        room.isSyncActive
                    ) {
                        viewModel.connectManager.publishPlaybackEvent(
                            pageUrl = activeTab.url,
                            isPlaying = !isPaused,
                            positionSeconds = curTime,
                            durationSeconds = dur,
                            playbackRate = rate,
                            isBeacon = true,
                            isPrivateTab = activeTab.isPrivate,
                            isPrivateMode = isPrivateMode
                        )
                    }
                }
                delay(2500L)
            }
        }
    }

    // Collect incoming remote synchronization commands from NYXORA Connect
    LaunchedEffect(Unit) {
        viewModel.connectManager.remoteSyncCommands.collect { cmd ->
            if (isPrivateMode) return@collect
            when (cmd) {
                is IncomingRemoteSyncCommand.NavigateToUrl -> {
                    viewModel.applyRemoteSyncUrlNavigation(cmd.url, cmd.title)
                }
                IncomingRemoteSyncCommand.NavigateBack -> {
                    val wvWentBack = !activeTab.isHomePage && primaryController.goBack()
                    viewModel.navigateBackInActiveTab(webViewWentBack = wvWentBack)
                }
                IncomingRemoteSyncCommand.NavigateForward -> {
                    if (activeTab.canGoForward || activeTab.forwardStack.isNotEmpty()) {
                        val wvWentForward = !activeTab.isHomePage && primaryController.goForward()
                        viewModel.navigateForwardInActiveTab(webViewWentForward = wvWentForward)
                    }
                }
                is IncomingRemoteSyncCommand.OpenNewSyncedTab -> {
                    viewModel.openNewTabFromRemoteSync(cmd.url, cmd.title)
                }
                is IncomingRemoteSyncCommand.ApplyPlaybackState -> {
                    val state = cmd.state
                    if (state.pageUrl.isNotBlank() && state.pageUrl != activeTab.url) {
                        viewModel.applyRemoteSyncUrlNavigation(state.pageUrl, "")
                    }
                    primaryController.probeWatchTogetherMedia { compat, localPos, _, localPaused, _ ->
                        viewModel.connectManager.updateVideoCompatibility(compat)
                        if (compat.supportsDirectSync) {
                            val now = System.currentTimeMillis()
                            val adj = WatchTogetherMediaBridge.calculatePlaybackAdjustment(
                                hostState = state,
                                localPositionSeconds = localPos,
                                localIsPlaying = !localPaused,
                                nowMillis = now,
                                lastSeekAtMillis = lastAutoSeekMillis,
                                forceManualResync = cmd.forceManualResync
                            )
                            when (adj.decision) {
                                PlaybackCorrectionDecision.DIRECT_SEEK -> {
                                    lastAutoSeekMillis = now
                                    primaryController.applyWatchTogetherPlayback(
                                        shouldPlay = adj.targetPlayState,
                                        seekToSeconds = adj.targetPositionSeconds,
                                        playbackRate = adj.targetPlaybackRate
                                    )
                                }
                                PlaybackCorrectionDecision.GENTLE_RATE_ADJUST -> {
                                    primaryController.applyWatchTogetherPlayback(
                                        shouldPlay = adj.targetPlayState,
                                        seekToSeconds = null,
                                        playbackRate = adj.targetPlaybackRate
                                    )
                                }
                                PlaybackCorrectionDecision.NONE_IN_SYNC -> {
                                    primaryController.applyWatchTogetherPlayback(
                                        shouldPlay = adj.targetPlayState,
                                        seekToSeconds = null,
                                        playbackRate = state.playbackRate
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
            delay(2500L)
            snackbarHostState.currentSnackbarData?.dismiss()
            viewModel.dismissRedirectWarning()
            job.cancel()
        }
    }

    LaunchedEffect(pendingRedirectWarning) {
        if (pendingRedirectWarning == null) {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    val currentModeTabs = remember(normalTabs, privateTabs, isPrivateMode) {
        if (isPrivateMode) privateTabs else normalTabs
    }

    val visibleModeTabsCount = remember(currentModeTabs) {
        currentModeTabs.size.coerceAtLeast(1)
    }

    val isFullscreenVideo = primaryController.isFullscreen || splitController.isFullscreen

    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val edgeActivationWidthPx = remember(density) { with(density) { EDGE_SWIPE_ACTIVATION_WIDTH.toPx() } }
    val edgeCommitThresholdPx = remember(density) { with(density) { EDGE_SWIPE_COMMIT_THRESHOLD.toPx() } }

    val hasParentTab = remember(activeTab.parentTabId, currentModeTabs, activeTab.id) {
        !activeTab.parentTabId.isNullOrBlank() &&
            currentModeTabs.any { it.id == activeTab.parentTabId && it.id != activeTab.id }
    }
    val canNavigateBack = !activeTab.isHomePage || activeTab.canGoBack || activeTab.backStack.isNotEmpty() || hasParentTab
    val canNavigateForward = activeTab.canGoForward || activeTab.forwardStack.isNotEmpty()

    val triggerBrowserBackNavigation: () -> Boolean = {
        if (canNavigateBack) {
            viewModel.notifyHostBackNavigation()
            val wvWentBack = !activeTab.isHomePage && primaryController.goBack()
            viewModel.navigateBackInActiveTab(webViewWentBack = wvWentBack)
            true
        } else {
            false
        }
    }

    val triggerBrowserForwardNavigation: () -> Boolean = {
        if (activeTab.canGoForward || activeTab.forwardStack.isNotEmpty()) {
            viewModel.notifyHostForwardNavigation()
            val wvWentForward = !activeTab.isHomePage && primaryController.goForward()
            viewModel.navigateForwardInActiveTab(webViewWentForward = wvWentForward)
            true
        } else {
            false
        }
    }

    val currentOnSwipeBack by rememberUpdatedState(triggerBrowserBackNavigation)
    val currentOnSwipeForward by rememberUpdatedState(triggerBrowserForwardNavigation)
    val currentCanSwipeBack by rememberUpdatedState(canNavigateBack)
    val currentCanSwipeForward by rememberUpdatedState(canNavigateForward)
    val currentEdgeSwipeEnabled by rememberUpdatedState(settings.edgeSwipeBackForward && !isFullscreenVideo)

    var activeEdgeSwipeSide by remember { mutableStateOf<EdgeSwipeSide?>(null) }
    var edgeSwipeDragPx by remember { mutableFloatStateOf(0f) }

    val shouldInterceptSystemBack = isFullscreenVideo ||
        pendingRedirectWarning != null ||
        externalSchemePrompt != null ||
        riskyDownloadPrompt != null ||
        sslWarningPrompt != null ||
        sitePermPrompt != null ||
        showOverflowMenu ||
        activeOverlay != OverlaySheet.NONE ||
        findInPageVisible ||
        isEditingAddress ||
        canNavigateBack

    // Strict 5-step System Back priority:
    // 1. Fullscreen video -> 2. Redirect warning / Overlays / Menus -> 3. Webpage history -> 4. Previous tab / Homepage -> 5. App exit
    BackHandler(enabled = shouldInterceptSystemBack) {
        if (findInPageVisible &&
            !isFullscreenVideo &&
            pendingRedirectWarning == null &&
            externalSchemePrompt == null &&
            riskyDownloadPrompt == null &&
            sslWarningPrompt == null &&
            sitePermPrompt == null &&
            !showOverflowMenu &&
            activeOverlay == OverlaySheet.NONE
        ) {
            primaryController.clearFindMatches()
        }
        viewModel.handleSystemBack(
            isFullscreenVideo = isFullscreenVideo,
            onExitFullscreen = {
                if (!primaryController.exitFullscreen()) {
                    splitController.exitFullscreen()
                }
            },
            isEditingAddress = isEditingAddress,
            onDismissAddressEdit = {
                focusManager.clearFocus()
                isEditingAddress = false
            },
            isOverflowMenuOpen = showOverflowMenu,
            onDismissOverflowMenu = {
                showOverflowMenu = false
            },
            onExecuteWebViewBack = {
                !activeTab.isHomePage && primaryController.goBack()
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isFullscreenVideo) Modifier else Modifier.imePadding())
            .testTag("nyxora_main_browser_screen"),
        contentWindowInsets = if (isFullscreenVideo) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBars,
        snackbarHost = {
            if (!isFullscreenVideo) {
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        topBar = {
            if (!isFullscreenVideo) {
                Column(modifier = Modifier.fillMaxWidth()) {
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

                    val activeRoom = connectRoomState
                    if (activeRoom != null) {
                        NyxoraConnectStatusBanner(
                            roomState = activeRoom,
                            localDeviceId = viewModel.connectManager.localDeviceId,
                            connectionStatus = connectStatus,
                            isFollowingPaused = isFollowingPaused,
                            isPrivateMode = isPrivateMode,
                            onOpenConnectSheet = {
                                viewModel.setOverlaySheet(OverlaySheet.NYXORA_CONNECT)
                            },
                            onManualResync = {
                                viewModel.connectManager.requestManualResync()
                                viewModel.emitToast("Resynchronizing with host…")
                            },
                            onTogglePauseOrSync = {
                                viewModel.connectManager.updateRoomConfiguration(
                                    isSyncActive = !activeRoom.isSyncActive
                                )
                            },
                            onStopOrLeaveImmediate = {
                                viewModel.connectManager.leaveOrCloseRoom(notifyServer = true)
                                viewModel.emitToast("Disconnected from NYXORA Connect room")
                            }
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!isFullscreenVideo) {
                // 3. Minimalist Bottom Bar: Back, Forward, Home, Tabs (with count), Menu
                NyxoraBottomTabManagementBar(
                    activeTab = activeTab,
                    visibleModeTabsCount = visibleModeTabsCount,
                    isPrivateMode = isPrivateMode,
                    compactMode = settings.compactBottomToolbar,
                    showOverflowMenu = showOverflowMenu,
                    onBack = { triggerBrowserBackNavigation() },
                    onForward = { triggerBrowserForwardNavigation() },
                    onHome = { viewModel.navigateHome() },
                    onOpenTabSwitcher = {
                        viewModel.setOverlaySheet(OverlaySheet.TAB_SWITCHER)
                    },
                    onToggleOverflowMenu = { showOverflowMenu = it }
                )
            }
        }
    ) { innerPadding ->
        // 2. Central Container for the Browser View (with Horizontal Edge-Swipe Back/Forward Navigation)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreenVideo) PaddingValues(0.dp) else innerPadding)
                .pointerInput(edgeActivationWidthPx, edgeCommitThresholdPx) {
                    val slopPx = viewConfiguration.touchSlop.coerceAtMost(14f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!currentEdgeSwipeEnabled) return@awaitEachGesture

                        val containerWidth = size.width.toFloat()
                        val candidateSide = when {
                            down.position.x <= edgeActivationWidthPx -> EdgeSwipeSide.LEFT_BACK
                            containerWidth > 0f && down.position.x >= containerWidth - edgeActivationWidthPx -> EdgeSwipeSide.RIGHT_FORWARD
                            else -> null
                        } ?: return@awaitEachGesture

                        val pointerId = down.id
                        val startX = down.position.x
                        val startY = down.position.y
                        var lockedHorizontal = false
                        var crossedThresholdHapticFired = false
                        var currentDrag = 0f

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (!change.pressed) {
                                    if (lockedHorizontal) {
                                        change.consume()
                                    }
                                    break
                                }

                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                val inwardDrag = when (candidateSide) {
                                    EdgeSwipeSide.LEFT_BACK -> dx
                                    EdgeSwipeSide.RIGHT_FORWARD -> -dx
                                }

                                if (!lockedHorizontal) {
                                    if (abs(dy) > slopPx && abs(dy) > abs(dx)) {
                                        break
                                    }
                                    if (inwardDrag > slopPx && abs(dx) >= abs(dy)) {
                                        lockedHorizontal = true
                                        activeEdgeSwipeSide = candidateSide
                                    } else if (inwardDrag < -slopPx) {
                                        break
                                    }
                                }

                                if (lockedHorizontal) {
                                    change.consume()
                                    currentDrag = inwardDrag.coerceAtLeast(0f)
                                    edgeSwipeDragPx = currentDrag

                                    val isAvailable = when (candidateSide) {
                                        EdgeSwipeSide.LEFT_BACK -> currentCanSwipeBack
                                        EdgeSwipeSide.RIGHT_FORWARD -> currentCanSwipeForward
                                    }
                                    if (isAvailable && currentDrag >= edgeCommitThresholdPx && !crossedThresholdHapticFired) {
                                        crossedThresholdHapticFired = true
                                        runCatching {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    } else if (currentDrag < edgeCommitThresholdPx) {
                                        crossedThresholdHapticFired = false
                                    }
                                }
                            }

                            if (lockedHorizontal && currentDrag >= edgeCommitThresholdPx) {
                                when (candidateSide) {
                                    EdgeSwipeSide.LEFT_BACK -> currentOnSwipeBack()
                                    EdgeSwipeSide.RIGHT_FORWARD -> currentOnSwipeForward()
                                }
                            }
                        } finally {
                            activeEdgeSwipeSide = null
                            edgeSwipeDragPx = 0f
                        }
                    }
                }
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
                    onMoveShortcut = viewModel::moveShortcut,
                    onOpenHistory = { viewModel.setOverlaySheet(OverlaySheet.HISTORY) }
                )
            } else {
                // Lazy-collect site settings & allowOnceUrls only when rendering a web page
                val siteSettings by viewModel.siteSettings.collectAsStateWithLifecycle()
                val allowOnceUrls by viewModel.allowOnceUrls.collectAsStateWithLifecycle()
                val openTabIds = remember(normalTabs, privateTabs) {
                    (normalTabs + privateTabs).map { it.id }.toSet()
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideScreen = maxWidth >= 600.dp
                    if (secondarySplitTab != null && secondarySplitTab.id != activeTab.id) {
                        if (isWideScreen) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (!splitController.isFullscreen) {
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
                                                viewModel.createNewTab(
                                                    url = url,
                                                    inPrivateMode = priv,
                                                    inBackground = bg,
                                                    parentTabId = activeTab.id
                                                )
                                            },
                                            onPromptExternalScheme = { url, _ ->
                                                viewModel.navigateInActiveTab(url)
                                            },
                                            onRequestDownload = viewModel::requestDownload,
                                            onPromptSitePermission = viewModel::promptSitePermission,
                                            onFindMatchesUpdated = viewModel::updateFindInPageMatches,
                                            onNavigateHome = viewModel::navigateHome,
                                            onToast = viewModel::emitToast,
                                            openTabIds = openTabIds,
                                            onHistoryUpdated = viewModel::onWebHistoryUpdated
                                        )
                                    }
                                }
                                if (!isFullscreenVideo) {
                                    VerticalDivider(
                                        thickness = 2.dp,
                                        color = NyxoraElectricBlue.copy(alpha = 0.6f)
                                    )
                                }
                                if (!primaryController.isFullscreen) {
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
                                                viewModel.createNewTab(
                                                    url = url,
                                                    inPrivateMode = priv,
                                                    inBackground = bg,
                                                    parentTabId = secondarySplitTab.id
                                                )
                                            },
                                            onPromptExternalScheme = { url, _ ->
                                                viewModel.navigateInActiveTab(url)
                                            },
                                            onRequestDownload = viewModel::requestDownload,
                                            onPromptSitePermission = viewModel::promptSitePermission,
                                            onFindMatchesUpdated = { _, _ -> },
                                            onNavigateHome = { viewModel.toggleSplitView(null) },
                                            onToast = viewModel::emitToast,
                                            openTabIds = openTabIds,
                                            onHistoryUpdated = viewModel::onWebHistoryUpdated
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (!splitController.isFullscreen) {
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
                                                viewModel.createNewTab(
                                                    url = url,
                                                    inPrivateMode = priv,
                                                    inBackground = bg,
                                                    parentTabId = activeTab.id
                                                )
                                            },
                                            onPromptExternalScheme = { url, _ ->
                                                viewModel.navigateInActiveTab(url)
                                            },
                                            onRequestDownload = viewModel::requestDownload,
                                            onPromptSitePermission = viewModel::promptSitePermission,
                                            onFindMatchesUpdated = viewModel::updateFindInPageMatches,
                                            onNavigateHome = viewModel::navigateHome,
                                            onToast = viewModel::emitToast,
                                            openTabIds = openTabIds,
                                            onHistoryUpdated = viewModel::onWebHistoryUpdated
                                        )
                                    }
                                }
                                if (!isFullscreenVideo) {
                                    HorizontalDivider(
                                        thickness = 2.dp,
                                        color = NyxoraElectricBlue.copy(alpha = 0.6f)
                                    )
                                }
                                if (!primaryController.isFullscreen) {
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
                                                viewModel.createNewTab(
                                                    url = url,
                                                    inPrivateMode = priv,
                                                    inBackground = bg,
                                                    parentTabId = secondarySplitTab.id
                                                )
                                            },
                                            onPromptExternalScheme = { url, _ ->
                                                viewModel.navigateInActiveTab(url)
                                            },
                                            onRequestDownload = viewModel::requestDownload,
                                            onPromptSitePermission = viewModel::promptSitePermission,
                                            onFindMatchesUpdated = { _, _ -> },
                                            onNavigateHome = { viewModel.toggleSplitView(null) },
                                            onToast = viewModel::emitToast,
                                            openTabIds = openTabIds,
                                            onHistoryUpdated = viewModel::onWebHistoryUpdated
                                        )
                                    }
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
                                viewModel.createNewTab(
                                    url = url,
                                    inPrivateMode = priv,
                                    inBackground = bg,
                                    parentTabId = activeTab.id
                                )
                            },
                            onPromptExternalScheme = { url, _ ->
                                viewModel.navigateInActiveTab(url)
                            },
                            onRequestDownload = viewModel::requestDownload,
                            onPromptSitePermission = viewModel::promptSitePermission,
                            onFindMatchesUpdated = viewModel::updateFindInPageMatches,
                            onNavigateHome = viewModel::navigateHome,
                            onToast = viewModel::emitToast,
                            openTabIds = openTabIds,
                            onHistoryUpdated = viewModel::onWebHistoryUpdated
                        )
                    }
                }
            }

            // Semantic Left & Right Edge-Swipe Zones + Visual Edge-Pull Navigation Indicator
            if (settings.edgeSwipeBackForward && !isFullscreenVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(EDGE_SWIPE_ACTIVATION_WIDTH)
                        .fillMaxHeight()
                        .testTag("edge_swipe_left_zone")
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(EDGE_SWIPE_ACTIVATION_WIDTH)
                        .fillMaxHeight()
                        .testTag("edge_swipe_right_zone")
                )

                val swipeSide = activeEdgeSwipeSide
                if (swipeSide != null && edgeSwipeDragPx > 4f) {
                    val isActionAvailable = when (swipeSide) {
                        EdgeSwipeSide.LEFT_BACK -> canNavigateBack
                        EdgeSwipeSide.RIGHT_FORWARD -> canNavigateForward
                    }
                    NyxoraEdgeSwipeIndicator(
                        side = swipeSide,
                        dragPx = edgeSwipeDragPx,
                        commitThresholdPx = edgeCommitThresholdPx,
                        isActionAvailable = isActionAvailable,
                        isPrivateMode = isPrivateMode,
                        modifier = Modifier.align(
                            if (swipeSide == EdgeSwipeSide.LEFT_BACK) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            }
                        )
                    )
                }
            }

            // Autocomplete Suggestions Dropdown Overlay when editing top address bar
            if (!isFullscreenVideo && isEditingAddress && addressInput.isNotBlank() && settings.searchSuggestionsEnabled) {
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

            // Compact 8-Action Bottom Menu Sheet Overlay
            val bookmarksForMenu by viewModel.bookmarks.collectAsStateWithLifecycle()
            val isActivePageBookmarked = remember(bookmarksForMenu, activeTab.url) {
                !activeTab.isHomePage && bookmarksForMenu.any { it.url == activeTab.url }
            }
            NyxoraBottomMenuSheet(
                visible = !isFullscreenVideo && showOverflowMenu,
                reducedMotion = settings.reducedMotion,
                isPrivateMode = isPrivateMode,
                isDesktopMode = activeTab.desktopMode,
                isCurrentPageBookmarked = isActivePageBookmarked,
                onDismiss = { showOverflowMenu = false },
                onOpenBookmarks = { viewModel.setOverlaySheet(OverlaySheet.BOOKMARKS) },
                onOpenHistory = { viewModel.setOverlaySheet(OverlaySheet.HISTORY) },
                onOpenDownloads = { viewModel.setOverlaySheet(OverlaySheet.DOWNLOADS) },
                onToggleIncognito = {
                    if (isPrivateMode) {
                        viewModel.switchBrowsingMode(false)
                    } else {
                        viewModel.createNewTab(url = "nyxora://home", inPrivateMode = true, inBackground = false)
                    }
                },
                onToggleAddBookmark = { viewModel.toggleBookmarkForActiveTab() },
                onToggleDesktopSite = { viewModel.toggleDesktopSiteForActiveTab() },
                onSharePage = {
                    val shareText = if (activeTab.isHomePage) "https://nyxora.browser" else activeTab.url
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, activeTab.title)
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(sendIntent, "Share Page"))
                    }.onFailure {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", shareText))
                        viewModel.emitToast("Link copied to clipboard")
                    }
                },
                onOpenSettings = { viewModel.setOverlaySheet(OverlaySheet.SETTINGS) }
            )
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
                settings = settings,
                onUpdateSettings = viewModel::updateSettings,
                onSaveSiteSetting = { setting ->
                    viewModel.saveSiteSetting(setting)
                    primaryController.reload()
                },
                onDeleteSiteSetting = { host ->
                    viewModel.deleteSiteSetting(host)
                    primaryController.reload()
                },
                onClearAllSiteData = {
                    viewModel.clearAllSiteData()
                    primaryController.clearCache()
                    primaryController.reload()
                },
                onResetSitePermissions = {
                    viewModel.resetAllSitePermissions()
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
                onOpenDownloads = { viewModel.setOverlaySheet(OverlaySheet.DOWNLOADS) },
                onOpenNyxoraConnect = { viewModel.setOverlaySheet(OverlaySheet.NYXORA_CONNECT) },
                onClearBrowsingData = { clearHist, clearCookies ->
                    viewModel.clearBrowsingData(clearHist, clearCookies)
                },
                onClearCache = {
                    primaryController.clearCache()
                    splitController.clearCache()
                },
                onResetBrowserSettings = viewModel::resetBrowserSettings,
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
        OverlaySheet.NYXORA_CONNECT -> {
            NyxoraConnectSheet(
                roomState = connectRoomState,
                localDeviceId = viewModel.connectManager.localDeviceId,
                deviceDisplayName = deviceDisplayName,
                connectionStatus = connectStatus,
                isFollowingPaused = isFollowingPaused,
                videoCompatibility = videoCompatibility,
                lastSyncError = lastSyncError,
                isPrivateMode = isPrivateMode,
                activePageUrl = activeTab.url,
                onUpdateDeviceName = viewModel.connectManager::updateDeviceDisplayName,
                onClearError = viewModel.connectManager::clearSyncError,
                onCreateRoom = { roomName, mode, perms, customEndpoint ->
                    val created = viewModel.connectManager.createRoom(
                        roomName = roomName,
                        mode = mode,
                        permissions = perms,
                        initialUrl = activeTab.url,
                        initialTitle = activeTab.title,
                        isPrivateMode = isPrivateMode || activeTab.isPrivate,
                        customRelayEndpoint = customEndpoint
                    )
                    viewModel.emitToast("Created room ${created.roomId}")
                },
                onJoinRoom = { roomOrInvite, key, endpoint ->
                    val ok = viewModel.connectManager.joinRoom(
                        rawRoomOrInvite = roomOrInvite,
                        explicitAccessKey = key,
                        explicitEndpointUrl = endpoint
                    )
                    if (ok) {
                        viewModel.emitToast("Connecting to room…")
                    }
                },
                onLeaveOrCloseRoom = {
                    val wasHost = viewModel.connectManager.isCurrentDeviceHost
                    viewModel.connectManager.leaveOrCloseRoom(notifyServer = true)
                    viewModel.emitToast(if (wasHost) "Closed room for all participants" else "Left room")
                },
                onUpdateRoomConfig = { name, mode, active, perms ->
                    viewModel.connectManager.updateRoomConfiguration(
                        roomName = name,
                        mode = mode,
                        isSyncActive = active,
                        permissions = perms
                    )
                },
                onSetFollowingPaused = { paused ->
                    viewModel.connectManager.setParticipantFollowingPaused(paused)
                },
                onManualResync = {
                    viewModel.connectManager.requestManualResync()
                    viewModel.emitToast("Requested manual resync from host")
                },
                onBroadcastCurrentUrlNow = {
                    val sent = viewModel.connectManager.publishUrlNavigation(
                        url = activeTab.url,
                        title = activeTab.title,
                        isPrivateTab = activeTab.isPrivate,
                        isPrivateMode = isPrivateMode
                    )
                    if (sent) {
                        viewModel.emitToast("Synchronized current URL to room")
                    } else {
                        viewModel.emitToast("Cannot sync private or non-web pages")
                    }
                },
                onHostPlaybackControl = { shouldPlay, seekDelta ->
                    primaryController.probeWatchTogetherMedia { _, curTime, dur, _, rate ->
                        val nextPos = if (seekDelta != null) {
                            (curTime + seekDelta).coerceAtLeast(0.0)
                        } else {
                            curTime
                        }
                        primaryController.applyWatchTogetherPlayback(
                            shouldPlay = shouldPlay,
                            seekToSeconds = if (seekDelta != null) nextPos else null,
                            playbackRate = rate
                        )
                        viewModel.connectManager.publishPlaybackEvent(
                            pageUrl = activeTab.url,
                            isPlaying = shouldPlay,
                            positionSeconds = nextPos,
                            durationSeconds = dur,
                            playbackRate = rate,
                            isBeacon = false,
                            isPrivateTab = activeTab.isPrivate,
                            isPrivateMode = isPrivateMode
                        )
                    }
                },
                onRemoveParticipant = { targetId ->
                    viewModel.connectManager.removeParticipant(targetId)
                    viewModel.emitToast("Removed participant")
                },
                onTransferHost = { targetId ->
                    viewModel.connectManager.transferHostRole(targetId)
                    viewModel.emitToast("Transferred host role")
                },
                onSimulateNetworkDrop = {
                    viewModel.connectManager.simulateNetworkInterruptionForReconnect()
                    viewModel.emitToast("Simulated network drop — reconnecting…")
                },
                onToast = viewModel::emitToast,
                onDismiss = { viewModel.setOverlaySheet(OverlaySheet.NONE) }
            )
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

    // Top-priority BackHandler mounted dynamically when Fullscreen Video (Priority 1)
    // or Redirect Warning (Priority 2a) becomes active so it registers on top of OnBackPressedDispatcher.
    if (isFullscreenVideo || pendingRedirectWarning != null) {
        BackHandler(enabled = true) {
            viewModel.handleSystemBack(
                isFullscreenVideo = isFullscreenVideo,
                onExitFullscreen = {
                    if (!primaryController.exitFullscreen()) {
                        splitController.exitFullscreen()
                    }
                }
            )
        }
    }
}

@Composable
private fun NyxoraEdgeSwipeIndicator(
    side: EdgeSwipeSide,
    dragPx: Float,
    commitThresholdPx: Float,
    isActionAvailable: Boolean,
    isPrivateMode: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = (dragPx / commitThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1.35f)
    val isArmed = isActionAvailable && progress >= 1f
    val maxTravelPx = commitThresholdPx * 0.65f
    val signedOffsetX = when (side) {
        EdgeSwipeSide.LEFT_BACK -> (progress.coerceAtMost(1.15f) * maxTravelPx).roundToInt()
        EdgeSwipeSide.RIGHT_FORWARD -> -(progress.coerceAtMost(1.15f) * maxTravelPx).roundToInt()
    }
    val bubbleScale = (0.78f + (progress.coerceAtMost(1f) * 0.26f)).coerceIn(0.78f, 1.08f)
    val bubbleAlpha = (0.35f + (progress.coerceAtMost(1f) * 0.65f)).coerceIn(0.35f, 1f)

    val activeAccent = if (isPrivateMode) NyxoraIncognitoPurple else NyxoraElectricBlue
    val containerColor = when {
        isArmed -> activeAccent
        isActionAvailable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val iconTint = when {
        isArmed -> Color.White
        isActionAvailable -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = when {
        isArmed -> Color.White.copy(alpha = 0.28f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .offset { IntOffset(x = signedOffsetX, y = 0) }
            .scale(bubbleScale)
            .alpha(bubbleAlpha)
            .size(44.dp)
            .background(color = containerColor, shape = CircleShape)
            .border(width = 1.dp, color = borderColor, shape = CircleShape)
            .testTag("nyxora_edge_swipe_indicator"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (side == EdgeSwipeSide.LEFT_BACK) {
                Icons.AutoMirrored.Outlined.ArrowBack
            } else {
                Icons.AutoMirrored.Outlined.ArrowForward
            },
            contentDescription = if (side == EdgeSwipeSide.LEFT_BACK) {
                "Swipe Back"
            } else {
                "Swipe Forward"
            },
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
    }
}

