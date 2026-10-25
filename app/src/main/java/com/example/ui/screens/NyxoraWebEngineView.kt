package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.local.FullscreenOrientationMode
import com.example.data.local.NyxoraSettingsState
import com.example.data.local.VideoAspectMode
import com.example.data.model.SiteSettingItem
import com.example.domain.browser.ContentBlockingEngine
import com.example.domain.browser.NavigationDecision
import com.example.domain.browser.ReaderArticle
import com.example.domain.browser.ReaderModeExtractor
import com.example.domain.browser.UrlAndSearchHandler
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.PendingSitePermissionPrompt
import java.io.ByteArrayInputStream
import kotlin.math.abs

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Nyxora/1.0"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Nyxora/1.0"

private const val MAX_LIVE_WEBVIEW_TABS = 3

class WebViewControllerHandle {
    internal var webView: WebView? = null
    internal var customReloadAction: (() -> Unit)? = null
    internal var customStopAction: (() -> Unit)? = null
    internal var customReaderExtractAction: (((ReaderArticle?) -> Unit) -> Unit)? = null

    fun reload() {
        customReloadAction?.invoke() ?: webView?.reload()
    }

    fun stopLoading() {
        customStopAction?.invoke() ?: webView?.stopLoading()
    }

    fun goBack(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun goForward(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoForward()) {
            wv.goForward()
            true
        } else {
            false
        }
    }

    fun findInPage(query: String) {
        val wv = webView ?: return
        if (query.isBlank()) {
            wv.clearMatches()
        } else {
            wv.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) {
        webView?.findNext(forward)
    }

    fun clearFindMatches() {
        webView?.clearMatches()
    }

    fun extractReaderArticle(onResult: (ReaderArticle?) -> Unit) {
        val customExtractor = customReaderExtractAction
        if (customExtractor != null) {
            customExtractor(onResult)
            return
        }
        val wv = webView
        if (wv == null) {
            onResult(null)
            return
        }
        wv.evaluateJavascript(ReaderModeExtractor.EXTRACTION_JS) { raw ->
            onResult(ReaderModeExtractor.parseEvaluateJavascriptResult(raw))
        }
    }
}

/**
 * Native Android System WebView container with:
 * - Multi-tab live WebView pool (up to 5 active tabs kept alive in memory so switching tabs never reloads pages)
 * - Pull-to-refresh gesture at top of page
 * - Subresource ad & tracker filter blocking (shouldInterceptRequest)
 * - Gesture-verified pop-up blocking (onCreateWindow) & auto-redirect blocking (shouldOverrideUrlLoading)
 * - Fullscreen video custom view with portrait/landscape orientation detection & restoration
 * - File upload chooser & site permission prompts (Camera, Microphone, Location)
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun NyxoraWebEngineView(
    tab: BrowserTab,
    settings: NyxoraSettingsState,
    siteSettings: List<SiteSettingItem>,
    allowOnceUrls: Set<String>,
    controllerHandle: WebViewControllerHandle,
    onPageStarted: (tabId: String, url: String) -> Unit,
    onPageFinished: (tabId: String, url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgressChanged: (tabId: String, progress: Int) -> Unit,
    onPageError: (tabId: String, failingUrl: String, description: String) -> Unit,
    onSslError: (tabId: String, failingUrl: String, errorName: String, onDecision: (Boolean) -> Unit) -> Unit,
    onAdBlocked: () -> Unit,
    onPopupBlocked: (String) -> Unit,
    onRedirectBlocked: (String) -> Unit,
    onOpenNewTab: (url: String, inPrivate: Boolean, inBackground: Boolean) -> Unit,
    onPromptExternalScheme: (url: String, scheme: String) -> Unit,
    onRequestDownload: (url: String, fileName: String?, mimeType: String?) -> Unit,
    onPromptSitePermission: (PendingSitePermissionPrompt) -> Unit,
    onFindMatchesUpdated: (activeOrdinal: Int, totalMatches: Int) -> Unit,
    onNavigateHome: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val currentTab by rememberUpdatedState(tab)
    val currentSettings by rememberUpdatedState(settings)
    val currentSiteSettings by rememberUpdatedState(siteSettings)
    val currentAllowOnce by rememberUpdatedState(allowOnceUrls)

    var lastUserTouchElapsedRealtime by remember { mutableLongStateOf(0L) }
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingOsPermissionAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    // Pull-to-refresh state
    var isPullRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Fullscreen video state
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var savedOrientationBeforeFullscreen by remember {
        mutableStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }
    var currentVideoAspect by remember(settings.defaultVideoAspectMode) {
        mutableStateOf(settings.defaultVideoAspectMode)
    }

    // LRU pool of live WebViews keyed by tabId so tab switching never reloads pages
    val webViewPool = remember {
        LinkedHashMap<String, WebView>(MAX_LIVE_WEBVIEW_TABS + 1, 0.75f, true)
    }
    var useSoftwareLayerFallback by remember { mutableStateOf(false) }
    var renderRecoveryEpoch by remember { mutableIntStateOf(0) }
    var lastRendererCrashRealtime by remember { mutableLongStateOf(0L) }

    // Clear pull-to-refresh spinner when page finishes loading or errors
    LaunchedEffect(tab.isLoading, tab.progress, tab.pageErrorDescription) {
        if (!tab.isLoading || tab.progress >= 100 || tab.pageErrorDescription != null) {
            isPullRefreshing = false
        }
    }

    val triggerPullToRefresh: () -> Unit = {
        if (currentSettings.pullToRefreshEnabled && !currentTab.isHomePage) {
            isPullRefreshing = true
            controllerHandle.reload()
        }
    }

    // File Chooser launcher for <input type="file">
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (uris.isNotEmpty()) {
            callback?.onReceiveValue(uris.toTypedArray())
        } else {
            callback?.onReceiveValue(null)
        }
    }

    // OS Runtime Permission Launcher for Camera, Microphone, Location
    val osPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults: Map<String, Boolean> ->
        val allGranted = grantResults.values.isNotEmpty() && grantResults.values.all { it }
        val action = pendingOsPermissionAction
        pendingOsPermissionAction = null
        action?.invoke(allGranted)
    }

    // Clean up all pooled WebViews when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            for ((_, wv) in webViewPool) {
                runCatching {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.webChromeClient = null
                    wv.destroy()
                }
            }
            webViewPool.clear()
        }
    }

    BackHandler(enabled = fullscreenCustomView != null) {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCustomView = null
        fullscreenCallback = null
        restoreActivityAfterFullscreen(activity, savedOrientationBeforeFullscreen)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("nyxora_web_engine_container")
    ) {
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = triggerPullToRefresh,
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("nyxora_pull_to_refresh_box"),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isPullRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .testTag("nyxora_pull_to_refresh_indicator"),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            if (tab.pageErrorDescription != null) {
                NyxoraErrorStateView(
                    failingUrl = tab.url,
                    errorDescription = tab.pageErrorDescription,
                    isSslError = tab.hasCertificateError,
                    onRetry = {
                        onPageStarted(tab.id, tab.url)
                        controllerHandle.reload()
                    },
                    onGoBackOrHome = {
                        if (!controllerHandle.goBack()) {
                            onNavigateHome()
                        }
                    }
                )
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("nyxora_webview_instance"),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { hostContainer ->
                        // Read renderRecoveryEpoch so Compose re-runs update after renderer recovery
                        val recoveryVersion = renderRecoveryEpoch
                        val activeTabSnapshot = currentTab
                        val tabId = activeTabSnapshot.id

                        val targetWebView = webViewPool.getOrPut(tabId) {
                            createConfiguredTabWebView(
                                ctx = hostContainer.context,
                                initialTabId = tabId,
                                forceSoftwareLayer = useSoftwareLayerFallback || recoveryVersion > 0,
                                currentTabProvider = { currentTab },
                                currentSettingsProvider = { currentSettings },
                                currentSiteSettingsProvider = { currentSiteSettings },
                                currentAllowOnceProvider = { currentAllowOnce },
                                lastTouchTimeProvider = { lastUserTouchElapsedRealtime },
                                onTouchRecorded = { lastUserTouchElapsedRealtime = it },
                                onPullRefreshTriggered = {
                                    if (!isPullRefreshing) {
                                        triggerPullToRefresh()
                                    }
                                },
                                onPageStarted = onPageStarted,
                                onPageFinished = onPageFinished,
                                onProgressChanged = onProgressChanged,
                                onPageError = onPageError,
                                onSslError = onSslError,
                                onAdBlocked = onAdBlocked,
                                onPopupBlocked = onPopupBlocked,
                                onRedirectBlocked = onRedirectBlocked,
                                onOpenNewTab = onOpenNewTab,
                                onPromptExternalScheme = onPromptExternalScheme,
                                onRequestDownload = onRequestDownload,
                                onPromptSitePermission = onPromptSitePermission,
                                onFindMatchesUpdated = onFindMatchesUpdated,
                                onToast = onToast,
                                onShowFileChooser = { callback, mimeType ->
                                    fileUploadCallback?.onReceiveValue(null)
                                    fileUploadCallback = callback
                                    runCatching { fileChooserLauncher.launch(mimeType) }
                                        .onFailure {
                                            fileUploadCallback?.onReceiveValue(null)
                                            fileUploadCallback = null
                                        }
                                },
                                onRequestOsPermissions = { perms, resultCallback ->
                                    pendingOsPermissionAction = resultCallback
                                    osPermissionLauncher.launch(perms)
                                },
                                onEnterFullscreenVideo = { view, callback ->
                                    fullscreenCustomView = view
                                    fullscreenCallback = callback
                                    savedOrientationBeforeFullscreen =
                                        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    if (currentSettings.keepScreenOnWhilePlaying) {
                                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    }
                                },
                                onExitFullscreenVideo = {
                                    fullscreenCustomView = null
                                    fullscreenCallback = null
                                    restoreActivityAfterFullscreen(activity, savedOrientationBeforeFullscreen)
                                },
                                onRendererGone = { goneTabId, goneView ->
                                    webViewPool.remove(goneTabId)
                                    if (controllerHandle.webView === goneView) {
                                        controllerHandle.webView = null
                                    }
                                    runCatching {
                                        (goneView?.parent as? ViewGroup)?.removeView(goneView)
                                        goneView?.stopLoading()
                                        goneView?.webChromeClient = null
                                        goneView?.destroy()
                                    }
                                    useSoftwareLayerFallback = true
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastRendererCrashRealtime > 4000L) {
                                        lastRendererCrashRealtime = now
                                        renderRecoveryEpoch += 1
                                    } else {
                                        lastRendererCrashRealtime = now
                                        onPageError(
                                            goneTabId,
                                            currentTab.url,
                                            "The web page renderer stopped unexpectedly. Tap Try Again to reload safely."
                                        )
                                    }
                                },
                                activity = activity
                            )
                        }

                        // Evict oldest inactive WebViews if pool exceeds MAX_LIVE_WEBVIEW_TABS
                        while (webViewPool.size > MAX_LIVE_WEBVIEW_TABS) {
                            val oldestEntry = webViewPool.entries.firstOrNull { it.key != tabId } ?: break
                            webViewPool.remove(oldestEntry.key)
                            runCatching {
                                val evicted = oldestEntry.value
                                (evicted.parent as? ViewGroup)?.removeView(evicted)
                                evicted.stopLoading()
                                evicted.webChromeClient = null
                                evicted.destroy()
                            }
                        }

                        // Swap attached WebView in hostContainer without reloading if user switched tabs
                        if (hostContainer.childCount != 1 || hostContainer.getChildAt(0) !== targetWebView) {
                            for (i in 0 until hostContainer.childCount) {
                                val prevChild = hostContainer.getChildAt(i)
                                if (prevChild is WebView && prevChild !== targetWebView) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        runCatching {
                                            prevChild.setRendererPriorityPolicy(
                                                WebView.RENDERER_PRIORITY_WAIVED,
                                                true
                                            )
                                        }
                                    }
                                    if (!currentSettings.backgroundAudioEnabled) {
                                        runCatching { prevChild.onPause() }
                                    }
                                }
                            }
                            hostContainer.removeAllViews()
                            (targetWebView.parent as? ViewGroup)?.removeView(targetWebView)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                runCatching {
                                    targetWebView.setRendererPriorityPolicy(
                                        WebView.RENDERER_PRIORITY_IMPORTANT,
                                        true
                                    )
                                }
                            }
                            runCatching { targetWebView.onResume() }
                            hostContainer.addView(
                                targetWebView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }

                        // Bind controllerHandle to the currently visible tab's WebView
                        controllerHandle.webView = targetWebView
                        controllerHandle.customReloadAction = { targetWebView.reload() }
                        controllerHandle.customStopAction = { targetWebView.stopLoading() }
                        controllerHandle.customReaderExtractAction = null

                        // Apply dynamic per-tab & global settings without reloading unless URL/UA changed
                        applyDynamicWebSettings(targetWebView, activeTabSnapshot, currentSettings)

                        val currentLoadedUrl = targetWebView.url
                        val desiredUrl = activeTabSnapshot.url
                        if (!activeTabSnapshot.isHomePage &&
                            desiredUrl.isNotBlank() &&
                            desiredUrl != "nyxora://home" &&
                            currentLoadedUrl != desiredUrl &&
                            targetWebView.tag != desiredUrl
                        ) {
                            targetWebView.tag = desiredUrl
                            targetWebView.loadUrl(desiredUrl)
                        }
                    }
                )
            }
        }

        // Edge-Swipe Back / Forward gesture zones (left & right 20dp edges)
        if (settings.edgeSwipeBackForward && fullscreenCustomView == null) {
            EdgeSwipeZone(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(20.dp)
                    .fillMaxHeight(),
                onSwipeTriggered = {
                    if (!controllerHandle.goBack()) {
                        onNavigateHome()
                    }
                }
            )
            EdgeSwipeZone(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(20.dp)
                    .fillMaxHeight(),
                onSwipeTriggered = {
                    controllerHandle.goForward()
                }
            )
        }

        // Fullscreen Video Custom View Overlay (Portrait & Landscape Video Support)
        val customVideoView = fullscreenCustomView
        if (customVideoView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("fullscreen_video_overlay")
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = when (currentVideoAspect) {
                                VideoAspectMode.FIT -> 0.dp
                                VideoAspectMode.FILL -> 0.dp
                                VideoAspectMode.ORIGINAL -> 24.dp
                            }
                        ),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            (customVideoView.parent as? ViewGroup)?.removeView(customVideoView)
                            addView(
                                customVideoView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    },
                    update = { container ->
                        customVideoView.scaleX = if (currentVideoAspect == VideoAspectMode.FILL) 1.18f else 1.0f
                        customVideoView.scaleY = if (currentVideoAspect == VideoAspectMode.FILL) 1.18f else 1.0f
                        if (customVideoView.parent != container) {
                            (customVideoView.parent as? ViewGroup)?.removeView(customVideoView)
                            container.removeAllViews()
                            container.addView(customVideoView)
                        }
                    }
                )

                // Floating Fullscreen Video Controls (Rotate, Aspect Mode, PiP, Exit)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            val currentOrient = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            val nextOrient = if (currentOrient == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                            activity?.requestedOrientation = nextOrient
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ScreenRotation,
                            contentDescription = "Toggle Portrait / Landscape Video"
                        )
                    }

                    FilledTonalIconButton(
                        onClick = {
                            val modes = VideoAspectMode.entries
                            val nextIdx = (modes.indexOf(currentVideoAspect) + 1) % modes.size
                            currentVideoAspect = modes[nextIdx]
                            onToast("Video Aspect: ${currentVideoAspect.label}")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AspectRatio,
                            contentDescription = "Cycle Video Aspect Ratio"
                        )
                    }

                    if (settings.pipEnabled) {
                        FilledTonalIconButton(
                            onClick = {
                                runCatching {
                                    activity?.enterPictureInPictureMode(
                                        android.app.PictureInPictureParams.Builder().build()
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PictureInPictureAlt,
                                contentDescription = "Picture in Picture"
                            )
                        }
                    }

                    FilledTonalIconButton(
                        onClick = {
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCustomView = null
                            fullscreenCallback = null
                            restoreActivityAfterFullscreen(activity, savedOrientationBeforeFullscreen)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Exit Fullscreen Video"
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createConfiguredTabWebView(
    ctx: Context,
    initialTabId: String,
    forceSoftwareLayer: Boolean,
    currentTabProvider: () -> BrowserTab,
    currentSettingsProvider: () -> NyxoraSettingsState,
    currentSiteSettingsProvider: () -> List<SiteSettingItem>,
    currentAllowOnceProvider: () -> Set<String>,
    lastTouchTimeProvider: () -> Long,
    onTouchRecorded: (Long) -> Unit,
    onPullRefreshTriggered: () -> Unit,
    onPageStarted: (tabId: String, url: String) -> Unit,
    onPageFinished: (tabId: String, url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgressChanged: (tabId: String, progress: Int) -> Unit,
    onPageError: (tabId: String, failingUrl: String, description: String) -> Unit,
    onSslError: (tabId: String, failingUrl: String, errorName: String, onDecision: (Boolean) -> Unit) -> Unit,
    onAdBlocked: () -> Unit,
    onPopupBlocked: (String) -> Unit,
    onRedirectBlocked: (String) -> Unit,
    onOpenNewTab: (url: String, inPrivate: Boolean, inBackground: Boolean) -> Unit,
    onPromptExternalScheme: (url: String, scheme: String) -> Unit,
    onRequestDownload: (url: String, fileName: String?, mimeType: String?) -> Unit,
    onPromptSitePermission: (PendingSitePermissionPrompt) -> Unit,
    onFindMatchesUpdated: (activeOrdinal: Int, totalMatches: Int) -> Unit,
    onToast: (String) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, String) -> Unit,
    onRequestOsPermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
    onEnterFullscreenVideo: (View, WebChromeClient.CustomViewCallback?) -> Unit,
    onExitFullscreenVideo: () -> Unit,
    onRendererGone: (tabId: String, view: WebView?) -> Unit,
    activity: Activity?
): WebView {
    return WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(android.graphics.Color.parseColor("#101216"))
        if (forceSoftwareLayer) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }

        // Track user touch & top-of-page pull-to-refresh gesture
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        val pullTriggerPx = (ctx.resources.displayMetrics.density * 88f).coerceAtLeast(touchSlop * 3f)
        var pullStartY = 0f
        var pullStartX = 0f
        var startedAtTopOfPage = false
        var pullRefreshDispatchedInGesture = false

        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onTouchRecorded(SystemClock.elapsedRealtime())
                    pullStartY = event.rawY
                    pullStartX = event.rawX
                    startedAtTopOfPage = (view as? WebView)?.scrollY == 0
                    pullRefreshDispatchedInGesture = false
                }
                MotionEvent.ACTION_MOVE -> {
                    onTouchRecorded(SystemClock.elapsedRealtime())
                    val wv = view as? WebView
                    if (startedAtTopOfPage &&
                        wv != null &&
                        wv.scrollY == 0 &&
                        currentSettingsProvider().pullToRefreshEnabled &&
                        !pullRefreshDispatchedInGesture
                    ) {
                        val dy = event.rawY - pullStartY
                        val dx = abs(event.rawX - pullStartX)
                        if (dy > pullTriggerPx && dy > dx * 1.5f) {
                            pullRefreshDispatchedInGesture = true
                            onPullRefreshTriggered()
                        }
                    } else if ((wv?.scrollY ?: 0) > 0) {
                        startedAtTopOfPage = false
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    onTouchRecorded(SystemClock.elapsedRealtime())
                    startedAtTopOfPage = false
                    pullRefreshDispatchedInGesture = false
                }
            }
            false
        }

        // Long-press context menu for links and images
        setOnLongClickListener {
            val hit = hitTestResult
            val extra = hit.extra
            if (!extra.isNullOrBlank()) {
                when (hit.type) {
                    WebView.HitTestResult.SRC_ANCHOR_TYPE,
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                        onOpenNewTab(extra, currentTabProvider().isPrivate, true)
                        true
                    }
                    WebView.HitTestResult.IMAGE_TYPE -> {
                        onRequestDownload(extra, URLUtil.guessFileName(extra, null, "image/*"), "image/*")
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        // Configure WebSettings
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowContentAccess = false
            allowFileAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { safeBrowsingEnabled = false }
            }
        }

        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            onFindMatchesUpdated(activeMatchOrdinal, numberOfMatches)
        }

        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            onRequestDownload(url, guessedName, mimeType)
        }

        webViewClient = object : WebViewClient() {

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // Always return true so aw_browser_terminator never terminates the host app process
                onRendererGone(initialTabId, view)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val req = request ?: return null
                // Never block main-frame document navigations in subresource filter
                if (req.isForMainFrame) return null

                val reqUrl = req.url?.toString() ?: return null
                val tabSnapshot = currentTabProvider()
                val pageUrl = tabSnapshot.url
                val pageHost = UrlAndSearchHandler.extractHost(pageUrl)
                val siteOverride = currentSiteSettingsProvider().firstOrNull {
                    it.host.equals(pageHost, ignoreCase = true)
                }

                val shouldBlock = ContentBlockingEngine.shouldBlockSubresource(
                    requestUrl = reqUrl,
                    pageUrl = pageUrl,
                    globalAdBlockingEnabled = currentSettingsProvider().adBlockingEnabled,
                    siteSetting = siteOverride
                )
                if (shouldBlock) {
                    onAdBlocked()
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        204,
                        "Blocked by NYXORA",
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val req = request ?: return false
                if (!req.isForMainFrame) return false

                val targetUrl = req.url?.toString() ?: return false
                val tabSnapshot = currentTabProvider()
                val pageUrl = view?.url ?: tabSnapshot.url
                val pageHost = UrlAndSearchHandler.extractHost(pageUrl)
                val siteOverride = currentSiteSettingsProvider().firstOrNull {
                    it.host.equals(pageHost, ignoreCase = true)
                }
                val elapsedSinceTouch = SystemClock.elapsedRealtime() - lastTouchTimeProvider()

                // Allow user-picked local files explicitly registered via the system picker
                if (targetUrl.startsWith("file://", ignoreCase = true)) {
                    return if (UrlAndSearchHandler.isUserPickedFileUrl(targetUrl)) {
                        view?.settings?.allowFileAccess = true
                        false
                    } else {
                        onToast("Blocked unauthorized file:// access")
                        true
                    }
                }

                val decision = ContentBlockingEngine.evaluateTopLevelNavigation(
                    requestUrl = targetUrl,
                    currentPageUrl = pageUrl,
                    hasUserGesture = req.hasGesture(),
                    isServerRedirect = req.isRedirect,
                    timeSinceLastUserInteractionMs = elapsedSinceTouch,
                    globalRedirectBlockingEnabled = currentSettingsProvider().redirectBlockingEnabled,
                    httpsOnlyMode = currentSettingsProvider().httpsOnlyMode,
                    siteSetting = siteOverride,
                    allowOnceUrls = currentAllowOnceProvider()
                )

                return when (decision) {
                    NavigationDecision.ALLOW -> false
                    NavigationDecision.BLOCK_DANGEROUS_SCHEME -> {
                        onToast("Blocked unsafe navigation scheme")
                        true
                    }
                    NavigationDecision.BLOCK_AUTO_REDIRECT -> {
                        onRedirectBlocked(targetUrl)
                        true
                    }
                    NavigationDecision.PROMPT_EXTERNAL_APP_LAUNCH -> {
                        val scheme = targetUrl.substringBefore(":", "app")
                        onPromptExternalScheme(targetUrl, scheme)
                        true
                    }
                    NavigationDecision.PROMPT_HTTP_ON_HTTPS_ONLY -> {
                        val upgraded = "https://" + targetUrl.removePrefix("http://").removePrefix("HTTP://")
                        view?.loadUrl(upgraded)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val safeUrl = url ?: return
                if (!safeUrl.startsWith("file://", ignoreCase = true)) {
                    view?.settings?.allowFileAccess = false
                }
                onPageStarted(initialTabId, safeUrl)

                val cosmeticJs = ContentBlockingEngine.buildCosmeticAndDarkModeScript(
                    hideCookieBanners = currentSettingsProvider().hideCookieConsentBanners,
                    forceDarkWebPages = currentSettingsProvider().forceDarkWebPages
                )
                if (cosmeticJs.isNotEmpty()) {
                    view?.evaluateJavascript(cosmeticJs, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val safeUrl = url ?: return
                val cosmeticJs = ContentBlockingEngine.buildCosmeticAndDarkModeScript(
                    hideCookieBanners = currentSettingsProvider().hideCookieConsentBanners,
                    forceDarkWebPages = currentSettingsProvider().forceDarkWebPages
                )
                if (cosmeticJs.isNotEmpty()) {
                    view?.evaluateJavascript(cosmeticJs, null)
                }
                onPageFinished(
                    initialTabId,
                    safeUrl,
                    view?.title,
                    view?.canGoBack() ?: false,
                    view?.canGoForward() ?: false
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString() ?: "Unable to load webpage"
                    val failingUrl = request.url?.toString() ?: currentTabProvider().url
                    onPageError(initialTabId, failingUrl, desc)
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Never auto-proceed on certificate errors
                val errName = when (error?.primaryError) {
                    SslError.SSL_EXPIRED -> "Certificate Expired"
                    SslError.SSL_IDMISMATCH -> "Hostname Mismatch"
                    SslError.SSL_NOTYETVALID -> "Certificate Not Yet Valid"
                    SslError.SSL_UNTRUSTED -> "Untrusted Certificate Authority"
                    else -> "Invalid SSL Certificate"
                }
                val failingUrl = error?.url ?: currentTabProvider().url
                onSslError(initialTabId, failingUrl, errName) { proceed ->
                    if (proceed) handler?.proceed() else handler?.cancel()
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(initialTabId, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentUrl = view?.url ?: return
                if (!title.isNullOrBlank()) {
                    onPageFinished(
                        initialTabId,
                        currentUrl,
                        title,
                        view.canGoBack(),
                        view.canGoForward()
                    )
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hostView = view ?: return false
                val hitUrl = hostView.hitTestResult.extra
                val pageUrl = hostView.url ?: currentTabProvider().url
                val pageHost = UrlAndSearchHandler.extractHost(pageUrl)
                val siteOverride = currentSiteSettingsProvider().firstOrNull {
                    it.host.equals(pageHost, ignoreCase = true)
                }

                val decision = ContentBlockingEngine.evaluatePopupRequest(
                    targetUrl = hitUrl,
                    pageUrl = pageUrl,
                    hasUserGesture = isUserGesture,
                    globalPopupBlockingEnabled = currentSettingsProvider().popupBlockingEnabled,
                    siteSetting = siteOverride,
                    allowOnceUrls = currentAllowOnceProvider()
                )

                if (!decision.allowed) {
                    onPopupBlocked(hitUrl ?: pageUrl)
                    return false
                }

                if (!hitUrl.isNullOrBlank() && (hitUrl.startsWith("http://") || hitUrl.startsWith("https://"))) {
                    onOpenNewTab(hitUrl, currentTabProvider().isPrivate, false)
                    return false
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(hostView.context)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(
                        v: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        v?.post { runCatching { v.destroy() } }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        req: WebResourceRequest?
                    ): Boolean {
                        val popupUrl = req?.url?.toString()
                        if (!popupUrl.isNullOrBlank()) {
                            onOpenNewTab(popupUrl, currentTabProvider().isPrivate, false)
                        }
                        v?.post { runCatching { v.destroy() } }
                        return true
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                onEnterFullscreenVideo(view, callback)

                val mode = currentSettingsProvider().fullscreenOrientationMode
                when (mode) {
                    FullscreenOrientationMode.ALWAYS_LANDSCAPE -> {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    FullscreenOrientationMode.KEEP_CURRENT -> Unit
                    FullscreenOrientationMode.AUTO -> {
                        evaluateJavascript(
                            """
                            (function() {
                                var v = document.querySelector('video');
                                if (!v || !v.videoWidth || !v.videoHeight) return 'LANDSCAPE';
                                return (v.videoHeight > v.videoWidth) ? 'PORTRAIT' : 'LANDSCAPE';
                            })();
                            """.trimIndent()
                        ) { res ->
                            val isPortraitVideo = res?.contains("PORTRAIT", ignoreCase = true) == true
                            activity?.requestedOrientation = if (isPortraitVideo) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    }
                }
            }

            override fun onHideCustomView() {
                onExitFullscreenVideo()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.firstOrNull { it.isNotBlank() }
                    ?: "*/*"
                val mimeType = if (acceptTypes.contains("/")) acceptTypes else "*/*"
                onShowFileChooser(filePathCallback, mimeType)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val originHost = UrlAndSearchHandler.extractHost(req.origin?.toString().orEmpty())
                val siteRule = currentSiteSettingsProvider().firstOrNull {
                    it.host.equals(originHost, ignoreCase = true)
                }

                val requestedTypes = mutableListOf<String>()
                val osPermissionsNeeded = mutableListOf<String>()
                for (res in req.resources) {
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            requestedTypes.add("CAMERA")
                            osPermissionsNeeded.add(Manifest.permission.CAMERA)
                        }
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            requestedTypes.add("MICROPHONE")
                            osPermissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                if (requestedTypes.isEmpty()) {
                    req.deny()
                    return
                }

                val cameraBlocked = "CAMERA" in requestedTypes && siteRule?.cameraPermission == "DENY"
                val micBlocked = "MICROPHONE" in requestedTypes && siteRule?.micPermission == "DENY"
                if (cameraBlocked || micBlocked) {
                    req.deny()
                    return
                }

                val proceedWithPromptOrGrant: () -> Unit = {
                    val cameraPreAllowed = "CAMERA" !in requestedTypes || siteRule?.cameraPermission == "ALLOW"
                    val micPreAllowed = "MICROPHONE" !in requestedTypes || siteRule?.micPermission == "ALLOW"
                    if (cameraPreAllowed && micPreAllowed) {
                        req.grant(req.resources)
                    } else {
                        onPromptSitePermission(
                            PendingSitePermissionPrompt(
                                host = originHost.ifBlank { "This website" },
                                permissionTypes = requestedTypes,
                                webPermissionRequest = req
                            )
                        )
                    }
                }

                val missingOsPerms = osPermissionsNeeded.filter { perm ->
                    ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED
                }
                if (missingOsPerms.isNotEmpty()) {
                    onRequestOsPermissions(missingOsPerms.toTypedArray()) { granted ->
                        if (granted) proceedWithPromptOrGrant() else req.deny()
                    }
                } else {
                    proceedWithPromptOrGrant()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val cb = callback ?: return
                val originHost = UrlAndSearchHandler.extractHost(origin.orEmpty())
                val siteRule = currentSiteSettingsProvider().firstOrNull {
                    it.host.equals(originHost, ignoreCase = true)
                }

                if (siteRule?.locationPermission == "DENY") {
                    cb.invoke(origin, false, false)
                    return
                }

                val hasFine = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val showSiteLocationDialog: () -> Unit = {
                    if (siteRule?.locationPermission == "ALLOW") {
                        cb.invoke(origin, true, true)
                    } else {
                        onPromptSitePermission(
                            PendingSitePermissionPrompt(
                                host = originHost.ifBlank { "This website" },
                                permissionTypes = listOf("LOCATION"),
                                geolocationCallback = cb,
                                geolocationOrigin = origin
                            )
                        )
                    }
                }

                if (!hasFine && !hasCoarse) {
                    onRequestOsPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    ) { granted ->
                        if (granted) showSiteLocationDialog() else cb.invoke(origin, false, false)
                    }
                } else {
                    showSiteLocationDialog()
                }
            }
        }
    }
}

private fun applyDynamicWebSettings(
    webView: WebView,
    tab: BrowserTab,
    settings: NyxoraSettingsState
) {
    val targetUa = if (tab.desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
    if (webView.settings.userAgentString != targetUa) {
        webView.settings.userAgentString = targetUa
    }
    if (webView.settings.textZoom != tab.textZoom) {
        webView.settings.textZoom = tab.textZoom
    }
    if (webView.settings.blockNetworkImage != settings.dataSaverBlockImages) {
        webView.settings.blockNetworkImage = settings.dataSaverBlockImages
    }
    val desiredCacheMode = if (tab.isPrivate) {
        WebSettings.LOAD_NO_CACHE
    } else {
        WebSettings.LOAD_DEFAULT
    }
    if (webView.settings.cacheMode != desiredCacheMode) {
        webView.settings.cacheMode = desiredCacheMode
    }
    val allowFile = tab.url.startsWith("file://", ignoreCase = true) &&
        UrlAndSearchHandler.isUserPickedFileUrl(tab.url)
    if (webView.settings.allowFileAccess != allowFile) {
        webView.settings.allowFileAccess = allowFile
    }
    runCatching {
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            !settings.blockThirdPartyCookies && !tab.isPrivate
        )
    }
}

@Composable
private fun EdgeSwipeZone(
    modifier: Modifier = Modifier,
    onSwipeTriggered: () -> Unit
) {
    var cumulativeDrag by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { cumulativeDrag = 0f },
                onHorizontalDrag = { _, dragAmount ->
                    cumulativeDrag += dragAmount
                },
                onDragEnd = {
                    if (abs(cumulativeDrag) > 72f) {
                        onSwipeTriggered()
                    }
                    cumulativeDrag = 0f
                }
            )
        }
    )
}

@Composable
private fun NyxoraErrorStateView(
    failingUrl: String,
    errorDescription: String,
    isSslError: Boolean,
    onRetry: () -> Unit,
    onGoBackOrHome: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nyxora_error_page"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = "Page Error",
                tint = if (isSslError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isSslError) "Untrusted Security Certificate" else "Unable to Reach Site",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = failingUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onGoBackOrHome,
                    modifier = Modifier.testTag("error_page_back_button")
                ) {
                    Text("Go Back")
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("error_page_retry_button")
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Try Again")
                }
            }
        }
    }
}

private fun restoreActivityAfterFullscreen(activity: Activity?, savedOrientation: Int) {
    activity ?: return
    activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    activity.requestedOrientation = savedOrientation
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
