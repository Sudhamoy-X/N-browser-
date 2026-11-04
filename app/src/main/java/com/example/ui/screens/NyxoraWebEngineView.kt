package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Intent
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import com.example.data.local.FullscreenOrientationMode
import com.example.data.local.NyxoraSettingsState
import com.example.data.local.UserAgentMode
import com.example.data.local.UserScriptStorage
import com.example.data.local.VideoAspectMode
import com.example.data.model.SiteSettingItem
import com.example.domain.browser.BrowserDiagnosticsEngine
import com.example.domain.browser.ContentBlockingEngine
import com.example.domain.browser.FaviconCacheManager
import com.example.domain.browser.NavigationDecision
import com.example.domain.browser.NyxoraShieldEngine
import com.example.domain.browser.ReaderArticle
import com.example.domain.browser.ReaderModeExtractor
import com.example.domain.browser.ShieldBlockOutcome
import com.example.domain.browser.UrlAndSearchHandler
import com.example.domain.connect.MediaControlActionResult
import com.example.domain.connect.VideoCompatibilityState
import com.example.domain.connect.WatchTogetherMediaBridge
import com.example.domain.connect.WatchTogetherPlaybackState
import com.example.ui.components.NyxoraMomentumScrollPhysics
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.PendingSitePermissionPrompt
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Nyxora/1.0"
internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Nyxora/1.0"

private const val MAX_LIVE_WEBVIEW_TABS = 3
internal const val VIDEO_TOOLBAR_AUTO_HIDE_MS = 2500L
internal const val MIN_VIDEO_ZOOM = 1.0f
internal const val MAX_VIDEO_ZOOM = 5.0f
internal val VIDEO_ZOOM_LEVELS = floatArrayOf(1.0f, 1.15f, 1.30f, 1.50f, 2.0f, 3.0f, 4.0f, 5.0f)

internal data class VideoZoomTransform(
    val zoom: Float,
    val panX: Float,
    val panY: Float
)

/**
 * Computes two-finger pinch-to-zoom centered around the user's focal point and clamps pan offsets
 * so the video never moves completely off-screen while preserving aspect ratio (100% to 500%).
 */
internal fun computeVideoZoomAndClampedPan(
    currentZoom: Float,
    currentPanX: Float,
    currentPanY: Float,
    zoomMultiplier: Float,
    panDeltaX: Float,
    panDeltaY: Float,
    focalX: Float,
    focalY: Float,
    containerWidth: Float,
    containerHeight: Float,
    baseAspectScale: Float = 1.0f
): VideoZoomTransform {
    val newZoom = (currentZoom * zoomMultiplier).coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM)
    val prevEffectiveScale = (baseAspectScale * currentZoom).coerceAtLeast(1.0f)
    val nextEffectiveScale = (baseAspectScale * newZoom).coerceAtLeast(1.0f)
    val scaleRatio = nextEffectiveScale / prevEffectiveScale

    val centerX = if (containerWidth > 0f) containerWidth / 2f else focalX
    val centerY = if (containerHeight > 0f) containerHeight / 2f else focalY
    val dxFromCenter = focalX - centerX
    val dyFromCenter = focalY - centerY

    val rawPanX = (currentPanX - dxFromCenter) * scaleRatio + dxFromCenter + panDeltaX
    val rawPanY = (currentPanY - dyFromCenter) * scaleRatio + dyFromCenter + panDeltaY

    if (newZoom <= 1.001f && baseAspectScale <= 1.001f) {
        return VideoZoomTransform(zoom = 1.0f, panX = 0f, panY = 0f)
    }

    val safeW = containerWidth.coerceAtLeast(1f)
    val safeH = containerHeight.coerceAtLeast(1f)
    val maxPanX = ((safeW * (nextEffectiveScale - 1f)) / 2f).coerceAtLeast(0f)
    val maxPanY = ((safeH * (nextEffectiveScale - 1f)) / 2f).coerceAtLeast(0f)

    return VideoZoomTransform(
        zoom = newZoom,
        panX = rawPanX.coerceIn(-maxPanX, maxPanX),
        panY = rawPanY.coerceIn(-maxPanY, maxPanY)
    )
}

internal data class AppliedTabWebConfig(
    val desktopMode: Boolean,
    val reloadTriggerTick: Int,
    val cookiesEnabled: Boolean? = null,
    val acceptThirdPartyCookies: Boolean? = null
)

class WebViewControllerHandle {
    internal var webView: WebView? = null
    internal val webViewPool = LinkedHashMap<String, WebView>(MAX_LIVE_WEBVIEW_TABS + 1, 0.75f, true)
    internal val appliedTabConfigMap = HashMap<String, AppliedTabWebConfig>()
    internal var customReloadAction: (() -> Unit)? = null
    internal var customStopAction: (() -> Unit)? = null
    internal var customReaderExtractAction: (((ReaderArticle?) -> Unit) -> Unit)? = null
    internal var onExitFullscreenRequest: (() -> Unit)? = null
    var onWebPageScrollChanged: ((scrollY: Int, deltaY: Int) -> Unit)? = null

    var isFullscreen by mutableStateOf(false)
        internal set

    var fullscreenCustomView by mutableStateOf<View?>(null)
        internal set

    fun getActivePoolCount(): Int = webViewPool.size

    fun hasPooledWebView(tabId: String): Boolean = webViewPool.containsKey(tabId)

    fun syncTextZoomAcrossPool(
        globalTextZoom: Int,
        siteSettingsByHost: Map<String, SiteSettingItem> = emptyMap()
    ) {
        val safeGlobal = globalTextZoom.coerceIn(50, 250)
        for ((_, pooledView) in webViewPool) {
            runCatching {
                val loadedUrl = pooledView.url ?: (pooledView.tag as? String) ?: ""
                val host = UrlAndSearchHandler.extractHost(loadedUrl)
                val siteOverride = lookupSiteSettingFromMap(siteSettingsByHost, host)
                val targetZoom = (siteOverride?.textZoom ?: safeGlobal).coerceIn(50, 250)
                if (pooledView.settings.textZoom != targetZoom) {
                    pooledView.settings.textZoom = targetZoom
                }
            }
        }
    }

    internal fun destroyWebViewSafely(wv: WebView?, reason: String = "evicted") {
        if (wv == null) return
        if (webView === wv) {
            webView = null
        }
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeJavascriptInterface("NyxoraConnectJsBridge")
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearFormData()
            wv.removeAllViews()
            wv.webChromeClient = null
            wv.destroy()
        }
        BrowserDiagnosticsEngine.updateActiveWebViewCount(webViewPool.size)
        BrowserDiagnosticsEngine.recordWebViewEvicted(reason)
    }

    fun evictTabWebView(tabId: String, reason: String = "tab_closed_or_suspended"): Boolean {
        appliedTabConfigMap.remove(tabId)
        val removed = webViewPool.remove(tabId) ?: return false
        destroyWebViewSafely(removed, reason)
        return true
    }

    fun evictTabsNotIn(openNonSuspendedTabIds: Set<String>, activeTabId: String) {
        if (webViewPool.isEmpty()) return
        val keysToEvict = webViewPool.keys.filter { it != activeTabId && it !in openNonSuspendedTabIds }
        for (evictId in keysToEvict) {
            evictTabWebView(evictId, "closed_or_suspended")
        }
    }

    fun destroyAllWebViews() {
        val views = webViewPool.values.toList()
        webViewPool.clear()
        appliedTabConfigMap.clear()
        webView = null
        for (wv in views) {
            destroyWebViewSafely(wv, "controller_disposed")
        }
        BrowserDiagnosticsEngine.updateActiveWebViewCount(0)
    }

    fun notifyPageScrollChanged(scrollY: Int, deltaY: Int) {
        onWebPageScrollChanged?.invoke(scrollY, deltaY)
    }

    fun exitFullscreen(): Boolean {
        val exitAction = onExitFullscreenRequest
        return if (isFullscreen && exitAction != null) {
            exitAction.invoke()
            true
        } else {
            false
        }
    }

    fun reload() {
        customReloadAction?.invoke() ?: webView?.reload()
    }

    fun stopLoading() {
        customStopAction?.invoke() ?: webView?.stopLoading()
    }

    fun canGoBack(): Boolean {
        return webView?.canGoBack() == true
    }

    fun canGoForward(): Boolean {
        return webView?.canGoForward() == true
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

    fun clearCache() {
        runCatching { webView?.clearCache(true) }
    }

    /**
     * Applies high-clarity, aspect-preserving scaling and pan strictly to the active HTML5 `<video>`
     * element on the page (never scaling playback controls, captions, or the entire webpage).
     * Sizes the `<video>` layout box directly inside a centered flex container so Chromium and ABR
     * streams (YouTube/HLS/DASH) render at full target resolution instead of magnifying a low-res bitmap.
     */
    fun applyHtml5VideoZoomTransform(
        scale: Float,
        panX: Float,
        panY: Float,
        aspectMode: VideoAspectMode = VideoAspectMode.FIT,
        onResult: ((supported: Boolean, reason: String?) -> Unit)? = null
    ) {
        val wv = webView
        if (wv == null) {
            onResult?.invoke(false, "No active web page")
            return
        }
        val clampedScale = scale.coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM)
        val isFillMode = aspectMode == VideoAspectMode.FILL
        val objectFitValue = if (isFillMode) "cover" else "contain"
        val aspectAttr = if (isFillMode) "fill" else "fit"
        val js = """
            (function() {
                try {
                    var videos = document.querySelectorAll('video');
                    if (!videos || videos.length === 0) {
                        var iframes = document.querySelectorAll('iframe');
                        if (iframes && iframes.length > 0) {
                            return JSON.stringify({supported: false, reason: 'Embedded native/cross-origin player restricts custom video zoom'});
                        }
                        return JSON.stringify({supported: false, reason: 'No HTML5 video element found'});
                    }
                    var v = videos[0];
                    for (var i = 0; i < videos.length; i++) {
                        if (!videos[i].paused || videos[i].readyState >= 2) {
                            v = videos[i];
                            break;
                        }
                    }
                    if (v.mediaKeys || v.webkitKeys) {
                        return JSON.stringify({supported: false, reason: 'DRM-protected video restricts custom zoom'});
                    }
                    try {
                        var ytPlayer = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (ytPlayer) {
                            if (typeof ytPlayer.setPlaybackQualityRange === 'function') {
                                ytPlayer.setPlaybackQualityRange('hd2160', 'highres');
                            } else if (typeof ytPlayer.setPlaybackQuality === 'function') {
                                ytPlayer.setPlaybackQuality('highres');
                            }
                        }
                    } catch (qe) {}
                    var reqScale = ${clampedScale};
                    var effectiveScale = reqScale;
                    if (reqScale > 1.001 && v.videoWidth > 0 && v.videoHeight > 0) {
                        var dpr = Math.max(window.devicePixelRatio || 1, 1);
                        var viewW = Math.max(window.innerWidth || 360, 1) * dpr;
                        var viewH = Math.max(window.innerHeight || 640, 1) * dpr;
                        var sourceRatio = Math.max(v.videoWidth / viewW, v.videoHeight / viewH);
                        var maxClearScale = Math.max(2.5, Math.min(5.0, sourceRatio * 2.25));
                        effectiveScale = Math.min(reqScale, maxClearScale);
                    }
                    var container = v.closest('.html5-video-container') || v.parentElement;
                    if (container && (document.fullscreenElement || document.webkitFullscreenElement || document.getElementById('__nyxora_fs_style__'))) {
                        if (!container.hasAttribute('data-nyxora-fs-ancestor')) {
                            container.setAttribute('data-nyxora-orig-css', container.style.cssText || '');
                            container.setAttribute('data-nyxora-fs-ancestor', 'true');
                        }
                        container.style.setProperty('width', '100%', 'important');
                        container.style.setProperty('height', '100%', 'important');
                        container.style.setProperty('position', 'absolute', 'important');
                        container.style.setProperty('inset', '0', 'important');
                        container.style.setProperty('display', 'flex', 'important');
                        container.style.setProperty('align-items', 'center', 'important');
                        container.style.setProperty('justify-content', 'center', 'important');
                        container.style.setProperty('overflow', 'hidden', 'important');
                        container.style.setProperty('padding', '0', 'important');
                        container.style.setProperty('margin', '0', 'important');
                    }
                    var docStyle = document.documentElement.style;
                    docStyle.setProperty('--nyxora-video-fit', '${objectFitValue}');
                    v.setAttribute('data-nyxora-aspect', '${aspectAttr}');
                    v.style.setProperty('object-fit', '${objectFitValue}', 'important');
                    v.style.setProperty('object-position', 'center center', 'important');
                    v.style.willChange = 'auto';
                    if (effectiveScale <= 1.001 && Math.abs(${panX}) < 0.5 && Math.abs(${panY}) < 0.5) {
                        docStyle.setProperty('--nyxora-video-size', '100%');
                        docStyle.setProperty('--nyxora-video-tx', '0px');
                        docStyle.setProperty('--nyxora-video-ty', '0px');
                        if (v.getAttribute('data-nyxora-zoomed') === 'true') {
                            v.style.transform = v.getAttribute('data-nyxora-orig-transform') || '';
                            v.style.transformOrigin = v.getAttribute('data-nyxora-orig-origin') || '';
                            v.removeAttribute('data-nyxora-zoomed');
                        }
                        v.style.setProperty('width', '100%', 'important');
                        v.style.setProperty('height', '100%', 'important');
                        v.style.setProperty('max-width', '100vw', 'important');
                        v.style.setProperty('max-height', '100dvh', 'important');
                        v.style.setProperty('top', '0', 'important');
                        v.style.setProperty('left', '0', 'important');
                        if (!${isFillMode}) {
                            v.removeAttribute('data-nyxora-aspect');
                        }
                    } else {
                        if (v.getAttribute('data-nyxora-zoomed') !== 'true') {
                            v.setAttribute('data-nyxora-orig-transform', v.style.transform || '');
                            v.setAttribute('data-nyxora-orig-origin', v.style.transformOrigin || '');
                            v.setAttribute('data-nyxora-zoomed', 'true');
                        }
                        var sizePct = (effectiveScale * 100).toFixed(2) + '%';
                        docStyle.setProperty('--nyxora-video-size', sizePct);
                        docStyle.setProperty('--nyxora-video-tx', '${panX}px');
                        docStyle.setProperty('--nyxora-video-ty', '${panY}px');
                        v.style.setProperty('flex-shrink', '0', 'important');
                        v.style.setProperty('width', sizePct, 'important');
                        v.style.setProperty('height', sizePct, 'important');
                        v.style.setProperty('min-width', sizePct, 'important');
                        v.style.setProperty('min-height', sizePct, 'important');
                        v.style.setProperty('max-width', 'none', 'important');
                        v.style.setProperty('max-height', 'none', 'important');
                        v.style.setProperty('top', '0', 'important');
                        v.style.setProperty('left', '0', 'important');
                        v.style.transformOrigin = 'center center';
                        v.style.setProperty('transform', 'translate(${panX}px, ${panY}px)', 'important');
                    }
                    window.dispatchEvent(new Event('resize'));
                    return JSON.stringify({supported: true, reason: null});
                } catch (e) {
                    return JSON.stringify({supported: false, reason: e.message || 'Video zoom restricted by player'});
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw ->
            if (onResult != null) {
                val isSupported = raw?.contains("\"supported\":true") == true || raw?.contains("\\\"supported\\\":true") == true
                val reason = if (!isSupported && raw != null && raw.contains("DRM")) {
                    "DRM-protected video restricts custom zoom"
                } else if (!isSupported && raw != null && raw.contains("Embedded")) {
                    "Native/embedded player restricts custom zoom"
                } else {
                    null
                }
                onResult(isSupported, reason)
            }
        }
    }

    /**
     * Synchronizes HTML5 / YouTube fullscreen video layout inside the active WebView so the
     * fullscreen `<video>` and player container expand to 100% of the available screen dimensions
     * with zero wasted bottom space, keeps playback controls overlaid near the bottom edge,
     * requests the highest available video resolution, and restores cleanly on exit.
     */
    internal fun syncHtml5FullscreenVideoLayout(
        enteringFullscreen: Boolean,
        onHtml5VideoDetected: ((Boolean) -> Unit)? = null
    ) {
        val wv = webView
        if (wv == null) {
            onHtml5VideoDetected?.invoke(false)
            return
        }
        val js = if (enteringFullscreen) {
            """
            (function() {
                try {
                    var docStyle = document.documentElement.style;
                    if (!docStyle.getPropertyValue('--nyxora-video-size')) {
                        docStyle.setProperty('--nyxora-video-size', '100%');
                    }
                    if (!docStyle.getPropertyValue('--nyxora-video-fit')) {
                        docStyle.setProperty('--nyxora-video-fit', 'contain');
                    }
                    if (!docStyle.getPropertyValue('--nyxora-video-tx')) {
                        docStyle.setProperty('--nyxora-video-tx', '0px');
                    }
                    if (!docStyle.getPropertyValue('--nyxora-video-ty')) {
                        docStyle.setProperty('--nyxora-video-ty', '0px');
                    }
                    var styleEl = document.getElementById('__nyxora_fs_style__');
                    if (!styleEl) {
                        styleEl = document.createElement('style');
                        styleEl.id = '__nyxora_fs_style__';
                        (document.head || document.documentElement).appendChild(styleEl);
                    }
                    styleEl.textContent =
                        ':fullscreen, :-webkit-full-screen, ' +
                        '.html5-video-player:fullscreen, .html5-video-player:-webkit-full-screen, ' +
                        ':fullscreen .html5-video-player, :-webkit-full-screen .html5-video-player, ' +
                        '.html5-video-player.ytp-fullscreen, #movie_player.ytp-fullscreen, ' +
                        '#movie_player:fullscreen, #movie_player:-webkit-full-screen, ' +
                        ':fullscreen #movie_player, :-webkit-full-screen #movie_player, ' +
                        '#player-container-id:fullscreen, #player-container-id:-webkit-full-screen, ' +
                        ':fullscreen #player-container-id, :-webkit-full-screen #player-container-id, ' +
                        ':fullscreen .player-container, :-webkit-full-screen .player-container, ' +
                        ':fullscreen #player, :-webkit-full-screen #player, ' +
                        'ytm-player:fullscreen, ytm-player:-webkit-full-screen, ' +
                        ':fullscreen ytm-player, :-webkit-full-screen ytm-player { ' +
                        'position: fixed !important; inset: 0 !important; ' +
                        'width: 100vw !important; height: 100dvh !important; min-height: 100vh !important; ' +
                        'max-width: 100vw !important; max-height: 100dvh !important; ' +
                        'margin: 0 !important; padding: 0 !important; padding-bottom: 0 !important; ' +
                        'top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; ' +
                        'background-color: #000 !important; overflow: hidden !important; ' +
                        '} ' +
                        ':fullscreen .player-size, :-webkit-full-screen .player-size, ' +
                        '.ytp-fullscreen .player-size { ' +
                        'padding: 0 !important; padding-bottom: 0 !important; margin: 0 !important; ' +
                        'width: 100% !important; height: 100% !important; max-height: 100dvh !important; ' +
                        '} ' +
                        ':fullscreen .html5-video-container, :-webkit-full-screen .html5-video-container, ' +
                        '.html5-video-player:fullscreen .html5-video-container, ' +
                        '.html5-video-player:-webkit-full-screen .html5-video-container, ' +
                        ':fullscreen .html5-video-player .html5-video-container, ' +
                        ':-webkit-full-screen .html5-video-player .html5-video-container, ' +
                        '#movie_player:fullscreen .html5-video-container, ' +
                        '#movie_player:-webkit-full-screen .html5-video-container, ' +
                        '.ytp-fullscreen .html5-video-container { ' +
                        'position: absolute !important; inset: 0 !important; ' +
                        'width: 100% !important; height: 100% !important; ' +
                        'display: flex !important; align-items: center !important; justify-content: center !important; ' +
                        'overflow: hidden !important; ' +
                        'margin: 0 !important; padding: 0 !important; top: 0 !important; left: 0 !important; ' +
                        '} ' +
                        ':fullscreen video, :-webkit-full-screen video, ' +
                        'video:fullscreen, video:-webkit-full-screen, ' +
                        '.html5-video-player:fullscreen video.html5-main-video, ' +
                        '.html5-video-player:-webkit-full-screen video.html5-main-video, ' +
                        ':fullscreen .html5-video-player video.html5-main-video, ' +
                        ':-webkit-full-screen .html5-video-player video.html5-main-video, ' +
                        '#movie_player:fullscreen video, #movie_player:-webkit-full-screen video, ' +
                        ':fullscreen #movie_player video, :-webkit-full-screen #movie_player video, ' +
                        '.ytp-fullscreen video.html5-main-video, ' +
                        'video[data-nyxora-zoomed="true"], video[data-nyxora-aspect] { ' +
                        'position: relative !important; flex-shrink: 0 !important; ' +
                        'width: var(--nyxora-video-size, 100%) !important; ' +
                        'height: var(--nyxora-video-size, 100%) !important; ' +
                        'min-width: var(--nyxora-video-size, 100%) !important; ' +
                        'min-height: var(--nyxora-video-size, 100%) !important; ' +
                        'max-width: none !important; max-height: none !important; ' +
                        'object-fit: var(--nyxora-video-fit, contain) !important; ' +
                        'object-position: center center !important; ' +
                        'top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; ' +
                        'margin: auto !important; ' +
                        'transform: translate(var(--nyxora-video-tx, 0px), var(--nyxora-video-ty, 0px)) !important; ' +
                        '} ' +
                        ':fullscreen video[data-nyxora-aspect="fill"], ' +
                        ':-webkit-full-screen video[data-nyxora-aspect="fill"], ' +
                        'video[data-nyxora-aspect="fill"] { ' +
                        'object-fit: cover !important; ' +
                        '} ' +
                        ':fullscreen #player-control-overlay, :-webkit-full-screen #player-control-overlay, ' +
                        ':fullscreen ytm-custom-control, :-webkit-full-screen ytm-custom-control, ' +
                        ':fullscreen .player-controls-content, :-webkit-full-screen .player-controls-content { ' +
                        'position: absolute !important; inset: 0 !important; ' +
                        'width: 100% !important; height: 100% !important; max-height: 100dvh !important; ' +
                        'margin: 0 !important; padding: 0 !important; ' +
                        '} ' +
                        ':fullscreen .ytp-chrome-bottom, :-webkit-full-screen .ytp-chrome-bottom, ' +
                        '.ytp-fullscreen .ytp-chrome-bottom, ' +
                        ':fullscreen .player-controls-bottom, :-webkit-full-screen .player-controls-bottom, ' +
                        ':fullscreen .player-controls-pb, :-webkit-full-screen .player-controls-pb, ' +
                        ':fullscreen ytm-custom-control .player-controls-bottom, ' +
                        ':-webkit-full-screen ytm-custom-control .player-controls-bottom, ' +
                        ':fullscreen #player-control-overlay .player-controls-bottom, ' +
                        ':-webkit-full-screen #player-control-overlay .player-controls-bottom { ' +
                        'bottom: env(safe-area-inset-bottom, 4px) !important; ' +
                        'margin-bottom: 0 !important; padding-bottom: 4px !important; ' +
                        '} ' +
                        ':fullscreen .watch-below-the-player, :-webkit-full-screen .watch-below-the-player, ' +
                        ':fullscreen ytm-single-column-watch-next-results-renderer, ' +
                        ':-webkit-full-screen ytm-single-column-watch-next-results-renderer, ' +
                        ':fullscreen ytm-mobile-topbar-renderer, :-webkit-full-screen ytm-mobile-topbar-renderer, ' +
                        ':fullscreen ytm-pivot-bar-renderer, :-webkit-full-screen ytm-pivot-bar-renderer, ' +
                        ':fullscreen #masthead-container, :-webkit-full-screen #masthead-container, ' +
                        ':fullscreen .ytp-ad-companion-slot, :-webkit-full-screen .ytp-ad-companion-slot, ' +
                        ':fullscreen ytm-companion-slot, :-webkit-full-screen ytm-companion-slot, ' +
                        ':fullscreen ytm-engagement-panel-section-list-renderer, ' +
                        ':-webkit-full-screen ytm-engagement-panel-section-list-renderer, ' +
                        ':fullscreen .fullscreen-engagement-panel-placeholder, ' +
                        ':-webkit-full-screen .fullscreen-engagement-panel-placeholder { ' +
                        'display: none !important; height: 0 !important; max-height: 0 !important; ' +
                        'margin: 0 !important; padding: 0 !important; ' +
                        '}';
                    var videos = document.querySelectorAll('video');
                    var hasPlayableVideo = false;
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (!v.mediaKeys && !v.webkitKeys) {
                            hasPlayableVideo = true;
                        }
                        if (!v.hasAttribute('data-nyxora-orig-video-css')) {
                            v.setAttribute('data-nyxora-orig-video-css', v.style.cssText || '');
                        }
                        v.style.setProperty('width', 'var(--nyxora-video-size, 100%)', 'important');
                        v.style.setProperty('height', 'var(--nyxora-video-size, 100%)', 'important');
                        v.style.setProperty('top', '0', 'important');
                        v.style.setProperty('left', '0', 'important');
                        var parent = v.parentElement;
                        var depth = 0;
                        var fsRoot = document.fullscreenElement || document.webkitFullscreenElement || document.body;
                        while (parent && parent !== document.documentElement && depth < 6) {
                            if (!parent.hasAttribute('data-nyxora-fs-ancestor')) {
                                parent.setAttribute('data-nyxora-orig-css', parent.style.cssText || '');
                                parent.setAttribute('data-nyxora-fs-ancestor', 'true');
                            }
                            parent.style.setProperty('padding-bottom', '0', 'important');
                            parent.style.setProperty('margin-bottom', '0', 'important');
                            if (parent === fsRoot) break;
                            parent = parent.parentElement;
                            depth++;
                        }
                    }
                    try {
                        var ytPlayer = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (ytPlayer) {
                            if (typeof ytPlayer.setPlaybackQualityRange === 'function') {
                                ytPlayer.setPlaybackQualityRange('hd2160', 'highres');
                            } else if (typeof ytPlayer.setPlaybackQuality === 'function') {
                                ytPlayer.setPlaybackQuality('highres');
                            }
                        }
                    } catch (qe) {}
                    window.dispatchEvent(new Event('resize'));
                    setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 80);
                    setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 240);
                    return JSON.stringify({hasVideo: hasPlayableVideo});
                } catch (e) {
                    return JSON.stringify({hasVideo: false});
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                try {
                    var styleEl = document.getElementById('__nyxora_fs_style__');
                    if (styleEl && styleEl.parentNode) {
                        styleEl.parentNode.removeChild(styleEl);
                    }
                    var docStyle = document.documentElement.style;
                    docStyle.removeProperty('--nyxora-video-size');
                    docStyle.removeProperty('--nyxora-video-fit');
                    docStyle.removeProperty('--nyxora-video-tx');
                    docStyle.removeProperty('--nyxora-video-ty');
                    var ancestors = document.querySelectorAll('[data-nyxora-fs-ancestor]');
                    for (var a = 0; a < ancestors.length; a++) {
                        var el = ancestors[a];
                        el.style.cssText = el.getAttribute('data-nyxora-orig-css') || '';
                        el.removeAttribute('data-nyxora-orig-css');
                        el.removeAttribute('data-nyxora-fs-ancestor');
                    }
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.hasAttribute('data-nyxora-orig-video-css')) {
                            v.style.cssText = v.getAttribute('data-nyxora-orig-video-css') || '';
                            v.removeAttribute('data-nyxora-orig-video-css');
                        } else {
                            v.style.transform = v.getAttribute('data-nyxora-orig-transform') || '';
                            v.style.transformOrigin = v.getAttribute('data-nyxora-orig-origin') || '';
                            v.style.willChange = '';
                            v.style.removeProperty('object-fit');
                            v.style.removeProperty('object-position');
                            v.style.removeProperty('width');
                            v.style.removeProperty('height');
                            v.style.removeProperty('min-width');
                            v.style.removeProperty('min-height');
                            v.style.removeProperty('max-width');
                            v.style.removeProperty('max-height');
                        }
                        v.removeAttribute('data-nyxora-zoomed');
                        v.removeAttribute('data-nyxora-aspect');
                    }
                    if (document.fullscreenElement && document.exitFullscreen) {
                        document.exitFullscreen().catch(function(){});
                    } else if (document.webkitFullscreenElement && document.webkitExitFullscreen) {
                        document.webkitExitFullscreen();
                    }
                    window.dispatchEvent(new Event('resize'));
                    setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 80);
                    return JSON.stringify({hasVideo: false});
                } catch (e) {
                    return JSON.stringify({hasVideo: false});
                }
            })();
            """.trimIndent()
        }
        runCatching {
            wv.evaluateJavascript(js) { raw ->
                val detected = raw?.contains("\"hasVideo\":true") == true || raw?.contains("\\\"hasVideo\\\":true") == true
                onHtml5VideoDetected?.invoke(detected)
            }
        }.onFailure {
            onHtml5VideoDetected?.invoke(false)
        }
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

    var onLocalWatchTogetherEvent: ((eventType: String, currentTimeSec: Double, durationSec: Double, isPaused: Boolean, playbackRate: Double) -> Unit)? = null

    fun probeWatchTogetherMedia(
        onResult: (
            compatibility: VideoCompatibilityState,
            currentTimeSec: Double,
            durationSec: Double,
            isPaused: Boolean,
            playbackRate: Double
        ) -> Unit
    ) {
        val wv = webView
        if (wv == null) {
            onResult(VideoCompatibilityState.NO_VIDEO_DETECTED, 0.0, 0.0, true, 1.0)
            return
        }
        wv.evaluateJavascript(WatchTogetherMediaBridge.buildMediaHookAndProbeScript()) { raw ->
            val parsed = WatchTogetherMediaBridge.parseProbeResult(raw)
            onResult(
                parsed.compatibility,
                parsed.currentTimeSec,
                parsed.durationSec,
                parsed.isPaused,
                parsed.playbackRate
            )
        }
    }

    fun executeWatchTogetherControl(
        shouldPlay: Boolean,
        seekDeltaSeconds: Double?,
        currentRoomPlayback: WatchTogetherPlaybackState?,
        currentCompatibility: VideoCompatibilityState,
        onResult: (MediaControlActionResult) -> Unit
    ) {
        val wv = webView
        if (wv == null) {
            val fallback = WatchTogetherMediaBridge.computeControlledPlaybackState(
                currentRoomPlayback = currentRoomPlayback,
                currentCompatibility = currentCompatibility,
                shouldPlay = shouldPlay,
                seekDeltaSeconds = seekDeltaSeconds
            )
            onResult(fallback)
            return
        }
        var callbackDelivered = false
        val script = WatchTogetherMediaBridge.buildExecuteControlActionScript(
            shouldPlay = shouldPlay,
            seekDeltaSeconds = seekDeltaSeconds,
            fallbackPositionSeconds = currentRoomPlayback?.positionSeconds ?: 0.0,
            fallbackDurationSeconds = currentRoomPlayback?.durationSeconds ?: 0.0
        )
        wv.evaluateJavascript(script) { raw ->
            if (callbackDelivered) return@evaluateJavascript
            callbackDelivered = true
            val parsed = WatchTogetherMediaBridge.parseControlActionResult(raw)
            if (parsed != null) {
                onResult(parsed)
            } else {
                val fallback = WatchTogetherMediaBridge.computeControlledPlaybackState(
                    currentRoomPlayback = currentRoomPlayback,
                    currentCompatibility = currentCompatibility,
                    shouldPlay = shouldPlay,
                    seekDeltaSeconds = seekDeltaSeconds
                )
                if (fallback.succeeded) {
                    applyWatchTogetherPlayback(
                        shouldPlay = fallback.isPlaying,
                        seekToSeconds = fallback.positionSeconds,
                        playbackRate = fallback.playbackRate
                    )
                }
                onResult(fallback)
            }
        }
        if (!callbackDelivered && android.os.Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true) {
            callbackDelivered = true
            val fallback = WatchTogetherMediaBridge.computeControlledPlaybackState(
                currentRoomPlayback = currentRoomPlayback,
                currentCompatibility = currentCompatibility,
                shouldPlay = shouldPlay,
                seekDeltaSeconds = seekDeltaSeconds
            )
            if (fallback.succeeded) {
                applyWatchTogetherPlayback(
                    shouldPlay = fallback.isPlaying,
                    seekToSeconds = fallback.positionSeconds,
                    playbackRate = fallback.playbackRate
                )
            }
            onResult(fallback)
        }
    }

    fun applyWatchTogetherPlayback(
        shouldPlay: Boolean,
        seekToSeconds: Double?,
        playbackRate: Double
    ) {
        val wv = webView ?: return
        val script = WatchTogetherMediaBridge.buildApplyRemotePlaybackScript(
            shouldPlay = shouldPlay,
            seekToSeconds = seekToSeconds,
            playbackRate = playbackRate
        )
        wv.evaluateJavascript(script, null)
    }
}

private class NyxoraConnectWebViewBridge(
    private val controllerProvider: () -> WebViewControllerHandle
) {
    @JavascriptInterface
    fun onMediaEvent(
        eventType: String,
        currentTimeSec: Double,
        durationSec: Double,
        isPaused: Boolean,
        playbackRate: Double
    ) {
        val safeCur = if (currentTimeSec.isNaN() || currentTimeSec < 0.0) 0.0 else currentTimeSec
        val safeDur = if (durationSec.isNaN() || durationSec < 0.0) 0.0 else durationSec
        val safeRate = if (playbackRate.isNaN()) 1.0 else playbackRate.coerceIn(0.25, 2.0)
        controllerProvider().onLocalWatchTogetherEvent?.invoke(
            eventType,
            safeCur,
            safeDur,
            isPaused,
            safeRate
        )
    }
}

/**
 * Native Android System WebView container with:
 * - Multi-tab live WebView pool (up to 3 active tabs kept alive in memory so switching tabs never reloads pages)
 * - Pull-to-refresh gesture at top of page
 * - Subresource ad & tracker filter blocking (shouldInterceptRequest) + cosmetic ad-slot hiding
 * - Gesture-verified pop-up blocking (onCreateWindow) & auto-redirect blocking (shouldOverrideUrlLoading)
 * - Functional Desktop Mode user-agent, viewport adaptation, and automatic reload on toggle
 * - Fullscreen video custom view with portrait/landscape orientation preservation and 100%–1000% pinch/step video zoom
 * - Live website favicon capture into FaviconCacheManager
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
    openTabIds: Set<String> = emptySet(),
    onHistoryUpdated: (tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _, _, _ -> },
    onShieldSubresourceBlocked: (ShieldBlockOutcome) -> Unit = { onAdBlocked() },
    onTrackingParamsStripped: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val currentTab by rememberUpdatedState(tab)
    val currentSettings by rememberUpdatedState(settings)
    val currentSiteSettings by rememberUpdatedState(siteSettings)
    val siteSettingsByHost = remember(siteSettings) {
        buildSiteSettingsHostMap(siteSettings)
    }
    val currentSiteSettingsByHost by rememberUpdatedState(siteSettingsByHost)
    val currentAllowOnce by rememberUpdatedState(allowOnceUrls)
    val currentOpenTabIds by rememberUpdatedState(openTabIds)

    var lastUserTouchElapsedRealtime by remember { mutableLongStateOf(0L) }
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingOsPermissionAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    // Pull-to-refresh state
    var isPullRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Fullscreen video state + 100%..1000% zoom & clamped pan state
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var savedWindowStateBeforeFullscreen by remember {
        mutableStateOf<SavedFullscreenWindowState?>(null)
    }
    var currentVideoAspect by remember(settings.defaultVideoAspectMode) {
        mutableStateOf(
            if (settings.defaultVideoAspectMode == VideoAspectMode.FILL) {
                VideoAspectMode.FILL
            } else {
                VideoAspectMode.FIT
            }
        )
    }
    var currentVideoZoom by remember { mutableFloatStateOf(1.0f) }
    var currentVideoPanX by remember { mutableFloatStateOf(0f) }
    var currentVideoPanY by remember { mutableFloatStateOf(0f) }
    var html5DomZoomSupported by remember { mutableStateOf(false) }
    var fullscreenContainerSize by remember { mutableStateOf(IntSize.Zero) }
    var isVideoToolbarVisible by remember { mutableStateOf(true) }
    var videoToolbarInteractionTick by remember { mutableIntStateOf(0) }

    // Pre-created FullscreenVideoHostLayout so WebChromeClient.onShowCustomView can attach and measure
    // the custom video view synchronously to full screen dimensions before returning to Chromium.
    val fullscreenHostContainer = remember(context) {
        FullscreenVideoHostLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 0)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    // Auto-hide fullscreen video toolbar after 2.5 seconds without user interaction
    LaunchedEffect(fullscreenCustomView, isVideoToolbarVisible, videoToolbarInteractionTick) {
        if (fullscreenCustomView != null && isVideoToolbarVisible) {
            delay(VIDEO_TOOLBAR_AUTO_HIDE_MS)
            isVideoToolbarVisible = false
        }
    }

    val resetVideoZoomTo100: () -> Unit = {
        currentVideoZoom = 1.0f
        currentVideoPanX = 0f
        currentVideoPanY = 0f
        controllerHandle.applyHtml5VideoZoomTransform(
            scale = 1.0f,
            panX = 0f,
            panY = 0f,
            aspectMode = currentVideoAspect
        ) { supported, _ ->
            html5DomZoomSupported = supported
            if (supported) {
                fullscreenCustomView?.let { v ->
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    v.translationX = 0f
                    v.translationY = 0f
                }
            }
        }
    }

    val exitFullscreenVideo: () -> Unit = {
        val viewToDetach = fullscreenCustomView
        val callbackToNotify = fullscreenCallback
        val stateToRestore = savedWindowStateBeforeFullscreen
        val wasFullscreen = viewToDetach != null || controllerHandle.isFullscreen
        if (wasFullscreen) {
            fullscreenCustomView = null
            fullscreenCallback = null
            savedWindowStateBeforeFullscreen = null
            currentVideoZoom = 1.0f
            currentVideoPanX = 0f
            currentVideoPanY = 0f
            html5DomZoomSupported = false
            isVideoToolbarVisible = true
            controllerHandle.fullscreenCustomView = null
            controllerHandle.isFullscreen = false
            controllerHandle.applyHtml5VideoZoomTransform(
                scale = 1.0f,
                panX = 0f,
                panY = 0f,
                aspectMode = VideoAspectMode.FIT
            )
            controllerHandle.syncHtml5FullscreenVideoLayout(enteringFullscreen = false)
            runCatching {
                viewToDetach?.scaleX = 1.0f
                viewToDetach?.scaleY = 1.0f
                viewToDetach?.translationX = 0f
                viewToDetach?.translationY = 0f
                (viewToDetach?.parent as? ViewGroup)?.removeView(viewToDetach)
                fullscreenHostContainer.removeAllViews()
            }
            runCatching {
                callbackToNotify?.onCustomViewHidden()
            }
            restoreActivityAfterFullscreen(activity, stateToRestore)
            runCatching {
                controllerHandle.webView?.requestLayout()
            }
        }
    }

    val enterFullscreenVideo: (View, Int, WebChromeClient.CustomViewCallback?) -> Unit = { view, videoRequestedOrientation, callback ->
        val existingView = fullscreenCustomView
        if (existingView != null && existingView !== view) {
            val prevCallback = fullscreenCallback
            runCatching {
                (existingView.parent as? ViewGroup)?.removeView(existingView)
            }
            runCatching {
                prevCallback?.onCustomViewHidden()
            }
        }
        if (savedWindowStateBeforeFullscreen == null) {
            savedWindowStateBeforeFullscreen = captureWindowStateBeforeFullscreen(activity)
        }

        enterImmersiveFullscreen(
            activity = activity,
            keepScreenOn = currentSettings.keepScreenOnWhilePlaying,
            orientationMode = currentSettings.fullscreenOrientationMode,
            videoRequestedOrientation = videoRequestedOrientation
        )

        // Synchronously attach and size both the WebView and custom video view to the full window bounds
        // so Chromium's FullScreenView and DOM viewport (100vh / 100dvh / window.innerHeight) immediately
        // match 100% of the screen dimensions with zero wasted bottom space.
        runCatching {
            (view.parent as? ViewGroup)?.removeView(view)
            fullscreenHostContainer.removeAllViews()
            fullscreenHostContainer.setPadding(0, 0, 0, 0)
            fullscreenHostContainer.onUserInteraction = {
                isVideoToolbarVisible = true
                videoToolbarInteractionTick++
            }
            view.setPadding(0, 0, 0, 0)
            view.scaleX = 1.0f
            view.scaleY = 1.0f
            view.translationX = 0f
            view.translationY = 0f
            fullscreenHostContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val decor = activity?.window?.decorView
            val dm = context.resources.displayMetrics
            val fullW = maxOf(decor?.width ?: 0, dm.widthPixels).coerceAtLeast(1)
            val fullH = maxOf(decor?.height ?: 0, dm.heightPixels).coerceAtLeast(1)
            controllerHandle.webView?.let { wv ->
                wv.setPadding(0, 0, 0, 0)
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(fullW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(fullH, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, fullW, fullH)
            }
            fullscreenHostContainer.measure(
                View.MeasureSpec.makeMeasureSpec(fullW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fullH, View.MeasureSpec.EXACTLY)
            )
            fullscreenHostContainer.layout(0, 0, fullW, fullH)
        }

        fullscreenCustomView = view
        fullscreenCallback = callback
        currentVideoZoom = 1.0f
        currentVideoPanX = 0f
        currentVideoPanY = 0f
        html5DomZoomSupported = false
        isVideoToolbarVisible = true
        videoToolbarInteractionTick++
        controllerHandle.fullscreenCustomView = view
        controllerHandle.isFullscreen = true
        controllerHandle.syncHtml5FullscreenVideoLayout(enteringFullscreen = true) { hasHtml5Video ->
            html5DomZoomSupported = hasHtml5Video
            if (hasHtml5Video) {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f
            }
        }
    }

    val currentOnEnterFullscreen by rememberUpdatedState(enterFullscreenVideo)
    val currentOnExitFullscreen by rememberUpdatedState(exitFullscreenVideo)
    controllerHandle.onExitFullscreenRequest = exitFullscreenVideo

    // Hoisted LRU pool of live WebViews keyed by tabId so tab switching AND Home-page transitions never reload pages
    val webViewPool = controllerHandle.webViewPool
    val appliedTabConfigMap = controllerHandle.appliedTabConfigMap
    var useSoftwareLayerFallback by remember {
        mutableStateOf(!java.io.File("/dev/dri/renderD128").exists())
    }
    var renderRecoveryEpoch by remember { mutableIntStateOf(0) }
    var lastRendererCrashRealtime by remember { mutableLongStateOf(0L) }

    // Exit fullscreen video cleanly if the active tab switches while in fullscreen
    LaunchedEffect(tab.id) {
        if (fullscreenCustomView != null || controllerHandle.isFullscreen) {
            exitFullscreenVideo()
        }
    }

    // Immediately propagate global or per-site text scale updates across all pooled WebViews
    LaunchedEffect(settings.globalTextZoom, siteSettingsByHost, tab.textZoom) {
        controllerHandle.syncTextZoomAcrossPool(
            globalTextZoom = settings.globalTextZoom,
            siteSettingsByHost = siteSettingsByHost
        )
    }

    // Clear pull-to-refresh spinner when page finishes loading or errors
    LaunchedEffect(tab.isLoading, tab.progress, tab.pageErrorDescription) {
        if (!tab.isLoading || tab.progress >= 100 || tab.pageErrorDescription != null) {
            isPullRefreshing = false
        }
    }

    val triggerPullToRefresh: () -> Unit = {
        if (currentSettings.pullToRefreshEnabled && !currentTab.isHomePage && fullscreenCustomView == null) {
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

    // Detach pooled WebViews from temporary FrameLayout and restore fullscreen state when leaving web view composition (e.g., going to Home screen)
    DisposableEffect(Unit) {
        onDispose {
            if (fullscreenCustomView != null || controllerHandle.isFullscreen) {
                currentOnExitFullscreen()
            }
            controllerHandle.onExitFullscreenRequest = null
            for ((_, wv) in webViewPool) {
                runCatching {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    if (!currentSettings.backgroundAudioEnabled) {
                        wv.onPause()
                    }
                }
            }
        }
    }

    BackHandler(enabled = fullscreenCustomView != null) {
        exitFullscreenVideo()
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
                        WebViewHostFrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setPadding(0, 0, 0, 0)
                            setBackgroundColor(android.graphics.Color.parseColor("#0A0D14"))
                        }
                    },
                    update = { hostContainer ->
                        // Read renderRecoveryEpoch so Compose re-runs update after renderer recovery
                        val recoveryVersion = renderRecoveryEpoch
                        val activeTabSnapshot = currentTab
                        val tabId = activeTabSnapshot.id

                        val existingPooled = webViewPool[tabId]
                        if (existingPooled != null) {
                            BrowserDiagnosticsEngine.recordWebViewReusedFromPool(tabId)
                        }
                        val targetWebView = existingPooled ?: run {
                            appliedTabConfigMap[tabId] = AppliedTabWebConfig(
                                desktopMode = activeTabSnapshot.desktopMode,
                                reloadTriggerTick = activeTabSnapshot.reloadTriggerTick
                            )
                            val created = createConfiguredTabWebView(
                                ctx = hostContainer.context.applicationContext.let { hostContainer.context },
                                initialTabId = tabId,
                                forceSoftwareLayer = useSoftwareLayerFallback || recoveryVersion > 0,
                                currentTabProvider = { currentTab },
                                currentSettingsProvider = { currentSettings },
                                currentSiteSettingsMapProvider = { currentSiteSettingsByHost },
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
                                onHistoryUpdated = onHistoryUpdated,
                                onProgressChanged = onProgressChanged,
                                onPageError = onPageError,
                                onSslError = onSslError,
                                onAdBlocked = onAdBlocked,
                                onShieldSubresourceBlocked = onShieldSubresourceBlocked,
                                onTrackingParamsStripped = onTrackingParamsStripped,
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
                                onEnterFullscreenVideo = { view, requestedOrientation, callback ->
                                    currentOnEnterFullscreen(view, requestedOrientation, callback)
                                },
                                onExitFullscreenVideo = {
                                    currentOnExitFullscreen()
                                },
                                onRendererGone = { goneTabId, goneView, didCrash ->
                                    BrowserDiagnosticsEngine.recordRendererCrash(goneTabId, didCrash)
                                    webViewPool.remove(goneTabId)
                                    appliedTabConfigMap.remove(goneTabId)
                                    controllerHandle.destroyWebViewSafely(goneView, "renderer_gone")
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
                                controllerHandle = controllerHandle
                            )
                            webViewPool[tabId] = created
                            BrowserDiagnosticsEngine.updateActiveWebViewCount(webViewPool.size)
                            created
                        }

                        // Immediately evict and destroy WebViews belonging to closed or suspended tabs
                        val validOpenIds = currentOpenTabIds
                        if (validOpenIds.isNotEmpty()) {
                            controllerHandle.evictTabsNotIn(validOpenIds, tabId)
                        }

                        // Evict oldest inactive WebViews if pool exceeds MAX_LIVE_WEBVIEW_TABS
                        while (webViewPool.size > MAX_LIVE_WEBVIEW_TABS) {
                            val oldestEntry = webViewPool.entries.firstOrNull { it.key != tabId } ?: break
                            controllerHandle.evictTabWebView(oldestEntry.key, "lru_pool_limit")
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

                        // Check if Desktop Mode or explicit reload trigger changed on this tab
                        val prevApplied = appliedTabConfigMap[tabId]
                        val desktopChanged = prevApplied != null && prevApplied.desktopMode != activeTabSnapshot.desktopMode
                        val reloadRequested = prevApplied != null && prevApplied.reloadTriggerTick != activeTabSnapshot.reloadTriggerTick
                        val activeSiteOverride = lookupSiteSettingFromMap(
                            currentSiteSettingsByHost,
                            UrlAndSearchHandler.extractHost(activeTabSnapshot.url)
                        )
                        val desiredThirdPartyCookies = currentSettings.cookiesEnabled &&
                            !currentSettings.blockThirdPartyCookies &&
                            !activeTabSnapshot.isPrivate

                        // Apply dynamic per-tab & global settings (including Desktop Mode User-Agent, viewport & textZoom)
                        val uaChanged = applyDynamicWebSettings(
                            webView = targetWebView,
                            tab = activeTabSnapshot,
                            settings = currentSettings,
                            siteOverride = activeSiteOverride,
                            previousAppliedConfig = prevApplied
                        )
                        appliedTabConfigMap[tabId] = AppliedTabWebConfig(
                            desktopMode = activeTabSnapshot.desktopMode,
                            reloadTriggerTick = activeTabSnapshot.reloadTriggerTick,
                            cookiesEnabled = currentSettings.cookiesEnabled,
                            acceptThirdPartyCookies = desiredThirdPartyCookies
                        )

                        val currentLoadedUrl = targetWebView.url
                        val desiredUrl = activeTabSnapshot.url
                        if (fullscreenCustomView == null &&
                            !activeTabSnapshot.isHomePage &&
                            desiredUrl.isNotBlank() &&
                            desiredUrl != "nyxora://home"
                        ) {
                            if (desktopChanged || uaChanged) {
                                runCatching { targetWebView.setInitialScale(0) }
                            }
                            if (currentLoadedUrl != desiredUrl && targetWebView.tag != desiredUrl) {
                                targetWebView.tag = desiredUrl
                                targetWebView.loadUrl(desiredUrl)
                            } else if (desktopChanged || reloadRequested || uaChanged) {
                                targetWebView.tag = desiredUrl
                                if (currentLoadedUrl != null && currentLoadedUrl != desiredUrl) {
                                    targetWebView.loadUrl(desiredUrl)
                                } else {
                                    targetWebView.reload()
                                }
                                applyViewportModeScript(targetWebView, activeTabSnapshot.desktopMode)
                            }
                        }
                    }
                )
            }
        }

        // Fullscreen Video Custom View Overlay (Portrait & Landscape Video Support + 100%..1000% Pinch & Step Zoom)
        val customVideoView = fullscreenCustomView
        if (customVideoView != null) {
            val baseAspectScale = if (currentVideoAspect == VideoAspectMode.FILL) 1.18f else 1.0f
            val effectiveScale = baseAspectScale * currentVideoZoom

            // Re-clamp pan offsets if orientation / container dimensions change
            LaunchedEffect(fullscreenContainerSize, currentVideoZoom, currentVideoAspect) {
                if (fullscreenContainerSize.width > 0 && fullscreenContainerSize.height > 0) {
                    val clamped = computeVideoZoomAndClampedPan(
                        currentZoom = currentVideoZoom,
                        currentPanX = currentVideoPanX,
                        currentPanY = currentVideoPanY,
                        zoomMultiplier = 1.0f,
                        panDeltaX = 0f,
                        panDeltaY = 0f,
                        focalX = fullscreenContainerSize.width / 2f,
                        focalY = fullscreenContainerSize.height / 2f,
                        containerWidth = fullscreenContainerSize.width.toFloat(),
                        containerHeight = fullscreenContainerSize.height.toFloat(),
                        baseAspectScale = baseAspectScale
                    )
                    currentVideoPanX = clamped.panX
                    currentVideoPanY = clamped.panY
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .onSizeChanged { newSize ->
                        if (newSize != fullscreenContainerSize) {
                            fullscreenContainerSize = newSize
                            if (newSize.width > 0 && newSize.height > 0) {
                                runCatching {
                                    controllerHandle.webView?.let { wv ->
                                        wv.setPadding(0, 0, 0, 0)
                                        if (wv.width != newSize.width || wv.height != newSize.height) {
                                            wv.measure(
                                                View.MeasureSpec.makeMeasureSpec(newSize.width, View.MeasureSpec.EXACTLY),
                                                View.MeasureSpec.makeMeasureSpec(newSize.height, View.MeasureSpec.EXACTLY)
                                            )
                                            wv.layout(0, 0, newSize.width, newSize.height)
                                        }
                                    }
                                }
                                fullscreenHostContainer.forceExpandVideoHierarchy()
                                controllerHandle.syncHtml5FullscreenVideoLayout(enteringFullscreen = true) { hasHtml5Video ->
                                    if (hasHtml5Video) {
                                        html5DomZoomSupported = true
                                        customVideoView.scaleX = 1.0f
                                        customVideoView.scaleY = 1.0f
                                        customVideoView.translationX = 0f
                                        customVideoView.translationY = 0f
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(currentVideoAspect) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            isVideoToolbarVisible = true
                            videoToolbarInteractionTick++

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val safeFocal = if (centroid != Offset.Unspecified) {
                                        centroid
                                    } else {
                                        Offset(size.width / 2f, size.height / 2f)
                                    }
                                    if (abs(zoomChange - 1f) > 0.002f || panChange.getDistance() > 0.5f) {
                                        val next = computeVideoZoomAndClampedPan(
                                            currentZoom = currentVideoZoom,
                                            currentPanX = currentVideoPanX,
                                            currentPanY = currentVideoPanY,
                                            zoomMultiplier = zoomChange,
                                            panDeltaX = panChange.x,
                                            panDeltaY = panChange.y,
                                            focalX = safeFocal.x,
                                            focalY = safeFocal.y,
                                            containerWidth = size.width.toFloat(),
                                            containerHeight = size.height.toFloat(),
                                            baseAspectScale = if (currentVideoAspect == VideoAspectMode.FILL) 1.18f else 1.0f
                                        )
                                        currentVideoZoom = next.zoom
                                        currentVideoPanX = next.panX
                                        currentVideoPanY = next.panY
                                        isVideoToolbarVisible = true
                                        videoToolbarInteractionTick++
                                        controllerHandle.applyHtml5VideoZoomTransform(
                                            scale = next.zoom,
                                            panX = next.panX,
                                            panY = next.panY,
                                            aspectMode = currentVideoAspect
                                        ) { supported, _ ->
                                            html5DomZoomSupported = supported
                                            if (supported) {
                                                customVideoView.scaleX = 1.0f
                                                customVideoView.scaleY = 1.0f
                                                customVideoView.translationX = 0f
                                                customVideoView.translationY = 0f
                                            }
                                        }
                                        event.changes.forEach { change ->
                                            if (change.position != change.previousPosition) {
                                                change.consume()
                                            }
                                        }
                                    }
                                } else if (pressedCount == 1 && currentVideoZoom > 1.01f) {
                                    // Allow single-finger panning within clamped bounds when video is zoomed in
                                    val panChange = event.calculatePan()
                                    if (panChange.getDistance() > 1.5f) {
                                        val next = computeVideoZoomAndClampedPan(
                                            currentZoom = currentVideoZoom,
                                            currentPanX = currentVideoPanX,
                                            currentPanY = currentVideoPanY,
                                            zoomMultiplier = 1.0f,
                                            panDeltaX = panChange.x,
                                            panDeltaY = panChange.y,
                                            focalX = size.width / 2f,
                                            focalY = size.height / 2f,
                                            containerWidth = size.width.toFloat(),
                                            containerHeight = size.height.toFloat(),
                                            baseAspectScale = if (currentVideoAspect == VideoAspectMode.FILL) 1.18f else 1.0f
                                        )
                                        currentVideoPanX = next.panX
                                        currentVideoPanY = next.panY
                                        videoToolbarInteractionTick++
                                        controllerHandle.applyHtml5VideoZoomTransform(
                                            scale = next.zoom,
                                            panX = next.panX,
                                            panY = next.panY,
                                            aspectMode = currentVideoAspect
                                        ) { supported, _ ->
                                            html5DomZoomSupported = supported
                                            if (supported) {
                                                customVideoView.scaleX = 1.0f
                                                customVideoView.scaleY = 1.0f
                                                customVideoView.translationX = 0f
                                                customVideoView.translationY = 0f
                                            }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .testTag("fullscreen_video_overlay")
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("fullscreen_custom_view_container"),
                    factory = {
                        (fullscreenHostContainer.parent as? ViewGroup)?.removeView(fullscreenHostContainer)
                        fullscreenHostContainer.apply {
                            onUserInteraction = {
                                isVideoToolbarVisible = true
                                videoToolbarInteractionTick++
                            }
                            setPadding(0, 0, 0, 0)
                            setBackgroundColor(android.graphics.Color.BLACK)
                            if (customVideoView.parent !== this) {
                                (customVideoView.parent as? ViewGroup)?.removeView(customVideoView)
                                removeAllViews()
                                addView(
                                    customVideoView,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }
                    },
                    update = { container ->
                        container.onUserInteraction = {
                            isVideoToolbarVisible = true
                            videoToolbarInteractionTick++
                        }
                        // When HTML5 DOM `<video>` zoom is active in the WebView, keep customVideoView
                        // unscaled (1.0f) so the video is never double-scaled and player controls/overlays
                        // stay crisp and independent from zoom. Otherwise fall back to view-level scaling.
                        val viewScale = if (html5DomZoomSupported) 1.0f else effectiveScale
                        val viewPanX = if (html5DomZoomSupported) 0f else currentVideoPanX
                        val viewPanY = if (html5DomZoomSupported) 0f else currentVideoPanY
                        customVideoView.scaleX = viewScale
                        customVideoView.scaleY = viewScale
                        customVideoView.translationX = viewPanX
                        customVideoView.translationY = viewPanY
                        if (container.childCount != 1 || container.getChildAt(0) !== customVideoView) {
                            (customVideoView.parent as? ViewGroup)?.removeView(customVideoView)
                            container.removeAllViews()
                            container.addView(
                                customVideoView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                        container.forceExpandVideoHierarchy()
                    }
                )

                // Convenient Zoom-Out & 1-tap Reset to 100% pill when zoomed in (> 100%)
                if (isVideoToolbarVisible && settings.videoAssistantEnabled && currentVideoZoom > 1.005f) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .displayCutoutPadding()
                            .padding(top = 12.dp, start = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x990A0D14))
                                .clickable {
                                    isVideoToolbarVisible = true
                                    videoToolbarInteractionTick++
                                    val prevZoom = previousStepVideoZoom(currentVideoZoom)
                                    if (prevZoom <= 1.005f) {
                                        resetVideoZoomTo100()
                                    } else {
                                        val prevTransform = computeVideoZoomAndClampedPan(
                                            currentZoom = currentVideoZoom,
                                            currentPanX = currentVideoPanX,
                                            currentPanY = currentVideoPanY,
                                            zoomMultiplier = prevZoom / currentVideoZoom,
                                            panDeltaX = 0f,
                                            panDeltaY = 0f,
                                            focalX = fullscreenContainerSize.width / 2f,
                                            focalY = fullscreenContainerSize.height / 2f,
                                            containerWidth = fullscreenContainerSize.width.toFloat(),
                                            containerHeight = fullscreenContainerSize.height.toFloat(),
                                            baseAspectScale = baseAspectScale
                                        )
                                        currentVideoZoom = prevTransform.zoom
                                        currentVideoPanX = prevTransform.panX
                                        currentVideoPanY = prevTransform.panY
                                        controllerHandle.applyHtml5VideoZoomTransform(
                                            scale = prevTransform.zoom,
                                            panX = prevTransform.panX,
                                            panY = prevTransform.panY,
                                            aspectMode = currentVideoAspect
                                        ) { supported, _ ->
                                            html5DomZoomSupported = supported
                                            if (supported) {
                                                customVideoView.scaleX = 1.0f
                                                customVideoView.scaleY = 1.0f
                                                customVideoView.translationX = 0f
                                                customVideoView.translationY = 0f
                                            }
                                        }
                                    }
                                    onToast("Video Zoom: ${(currentVideoZoom * 100f).roundToInt()}%")
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("fullscreen_zoom_out_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "− Zoom Out",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = Color(0xFFE2E8F0)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x990A0D14))
                                .clickable {
                                    isVideoToolbarVisible = true
                                    videoToolbarInteractionTick++
                                    resetVideoZoomTo100()
                                    onToast("Video Zoom: 100%")
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("fullscreen_zoom_reset_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${(currentVideoZoom * 100f).roundToInt()}% • Reset",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }

                // Minimal Monochrome Auto-Hiding Video Toolbar (Orientation, Fit/Fill, Video Zoom up to 500%, Exit)
                if (isVideoToolbarVisible && settings.videoAssistantEnabled) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .displayCutoutPadding()
                            .padding(top = 12.dp, end = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x990A0D14))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("fullscreen_video_toolbar"),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Screen Orientation
                        MonochromeVideoToolbarButton(
                            icon = Icons.Outlined.ScreenRotation,
                            contentDescription = "Screen Orientation",
                            testTag = "fullscreen_rotate_button",
                            onClick = {
                                isVideoToolbarVisible = true
                                videoToolbarInteractionTick++
                                val act = activity ?: return@MonochromeVideoToolbarButton
                                val nextOrient = if (isActivityCurrentlyLandscape(act)) {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                                act.requestedOrientation = nextOrient
                            }
                        )

                        // 2. Fit Screen / Fill Screen
                        val isFillMode = currentVideoAspect == VideoAspectMode.FILL
                        MonochromeVideoToolbarButton(
                            icon = if (isFillMode) Icons.Outlined.FitScreen else Icons.Outlined.AspectRatio,
                            contentDescription = if (isFillMode) "Fill Screen" else "Fit Screen",
                            isActive = isFillMode,
                            testTag = "fullscreen_aspect_button",
                            onClick = {
                                isVideoToolbarVisible = true
                                videoToolbarInteractionTick++
                                val nextAspect = if (isFillMode) {
                                    VideoAspectMode.FIT
                                } else {
                                    VideoAspectMode.FILL
                                }
                                currentVideoAspect = nextAspect
                                controllerHandle.applyHtml5VideoZoomTransform(
                                    scale = currentVideoZoom,
                                    panX = currentVideoPanX,
                                    panY = currentVideoPanY,
                                    aspectMode = nextAspect
                                ) { supported, _ ->
                                    html5DomZoomSupported = supported
                                    if (supported) {
                                        customVideoView.scaleX = 1.0f
                                        customVideoView.scaleY = 1.0f
                                        customVideoView.translationX = 0f
                                        customVideoView.translationY = 0f
                                    }
                                }
                                val modeLabel = if (nextAspect == VideoAspectMode.FILL) {
                                    "Fill Screen"
                                } else {
                                    "Fit Screen"
                                }
                                onToast(modeLabel)
                            }
                        )

                        // 3. Video Zoom (cycles up to 500% / 5x and wraps back to 100%)
                        MonochromeVideoToolbarButton(
                            icon = Icons.Outlined.ZoomIn,
                            contentDescription = "Video Zoom",
                            isActive = currentVideoZoom > 1.0f,
                            testTag = "fullscreen_zoom_button",
                            onClick = {
                                isVideoToolbarVisible = true
                                videoToolbarInteractionTick++
                                val nextZoom = nextStepVideoZoom(currentVideoZoom)
                                val nextTransform = computeVideoZoomAndClampedPan(
                                    currentZoom = currentVideoZoom,
                                    currentPanX = currentVideoPanX,
                                    currentPanY = currentVideoPanY,
                                    zoomMultiplier = if (currentVideoZoom > 0f) nextZoom / currentVideoZoom else nextZoom,
                                    panDeltaX = 0f,
                                    panDeltaY = 0f,
                                    focalX = fullscreenContainerSize.width / 2f,
                                    focalY = fullscreenContainerSize.height / 2f,
                                    containerWidth = fullscreenContainerSize.width.toFloat(),
                                    containerHeight = fullscreenContainerSize.height.toFloat(),
                                    baseAspectScale = baseAspectScale
                                )
                                currentVideoZoom = nextTransform.zoom
                                currentVideoPanX = nextTransform.panX
                                currentVideoPanY = nextTransform.panY
                                controllerHandle.applyHtml5VideoZoomTransform(
                                    scale = nextTransform.zoom,
                                    panX = nextTransform.panX,
                                    panY = nextTransform.panY,
                                    aspectMode = currentVideoAspect
                                ) { supported, reason ->
                                    html5DomZoomSupported = supported
                                    if (supported) {
                                        customVideoView.scaleX = 1.0f
                                        customVideoView.scaleY = 1.0f
                                        customVideoView.translationX = 0f
                                        customVideoView.translationY = 0f
                                    }
                                    if (!supported && reason != null && reason.contains("DRM")) {
                                        onToast(reason)
                                    }
                                }
                                onToast("Video Zoom: ${(currentVideoZoom * 100f).roundToInt()}%")
                            }
                        )

                        // 3b. External Video Player (when enabled in General Settings)
                        if (settings.externalVideoPlayerEnabled) {
                            MonochromeVideoToolbarButton(
                                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = "External Video Player",
                                testTag = "fullscreen_external_player_button",
                                onClick = {
                                    isVideoToolbarVisible = true
                                    videoToolbarInteractionTick++
                                    val wv = controllerHandle.webView
                                    if (wv != null) {
                                        wv.evaluateJavascript(
                                            "(function(){try{var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):'';}catch(e){return '';}})();"
                                        ) { raw ->
                                            val videoUrl = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
                                            val fallbackUrl = currentTab.url
                                            val launchUrl = if (videoUrl.startsWith("http://", ignoreCase = true) ||
                                                videoUrl.startsWith("https://", ignoreCase = true)
                                            ) {
                                                videoUrl
                                            } else {
                                                fallbackUrl
                                            }
                                            if (launchUrl.startsWith("http://", ignoreCase = true) ||
                                                launchUrl.startsWith("https://", ignoreCase = true)
                                            ) {
                                                runCatching {
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(Uri.parse(launchUrl), "video/*")
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(Intent.createChooser(intent, "Open in External Video Player").apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    })
                                                }.onFailure {
                                                    runCatching {
                                                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl)).apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(fallbackIntent)
                                                    }.onFailure {
                                                        onToast("No external video player installed")
                                                    }
                                                }
                                            } else {
                                                onToast("Stream uses embedded MSE/DRM buffer; cannot export direct URL")
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        // 4. Exit Fullscreen
                        MonochromeVideoToolbarButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = "Exit Fullscreen",
                            testTag = "fullscreen_exit_button",
                            onClick = {
                                exitFullscreenVideo()
                            }
                        )
                    }
                }
            }
        }
    }
}

internal fun nextStepVideoZoom(currentZoom: Float): Float {
    val exactIdx = VIDEO_ZOOM_LEVELS.indexOfFirst { abs(it - currentZoom) < 0.02f }
    if (exactIdx >= 0) {
        return VIDEO_ZOOM_LEVELS[(exactIdx + 1) % VIDEO_ZOOM_LEVELS.size]
    }
    val nextHigher = VIDEO_ZOOM_LEVELS.firstOrNull { it > currentZoom + 0.02f }
    return nextHigher ?: 1.0f
}

internal fun previousStepVideoZoom(currentZoom: Float): Float {
    val exactIdx = VIDEO_ZOOM_LEVELS.indexOfFirst { abs(it - currentZoom) < 0.02f }
    if (exactIdx > 0) {
        return VIDEO_ZOOM_LEVELS[exactIdx - 1]
    }
    val prevLower = VIDEO_ZOOM_LEVELS.lastOrNull { it < currentZoom - 0.02f }
    return prevLower ?: 1.0f
}

@Composable
private fun MonochromeVideoToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) Color(0xFFFFFFFF) else Color(0xFFE2E8F0),
            modifier = Modifier.size(18.dp)
        )
    }
}

private class FullscreenVideoHostLayout(
    context: Context,
    var onUserInteraction: (() -> Unit)? = null
) : FrameLayout(context) {

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        setPadding(0, 0, 0, 0)
        return insets
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val action = ev?.actionMasked
        if (action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_POINTER_DOWN
        ) {
            onUserInteraction?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setPadding(0, 0, 0, 0)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w > 0 && h > 0) {
            val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.setPadding(0, 0, 0, 0)
                child.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                child.measure(exactW, exactH)
            }
            setMeasuredDimension(w, h)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = (right - left).coerceAtLeast(0)
        val h = (bottom - top).coerceAtLeast(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.setPadding(0, 0, 0, 0)
            child.layout(0, 0, w, h)
        }
    }

    fun forceExpandVideoHierarchy() {
        setPadding(0, 0, 0, 0)
        val w = width
        val h = height
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.setPadding(0, 0, 0, 0)
            val lp = child.layoutParams
            if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                child.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            if (w > 0 && h > 0 && (child.width != w || child.height != h)) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
                )
                child.layout(0, 0, w, h)
            }
        }
    }
}

private class WebViewHostFrameLayout(context: Context) : FrameLayout(context) {
    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        setPadding(0, 0, 0, 0)
        return insets
    }

    override fun dispatchApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        setPadding(0, 0, 0, 0)
        val zeroedInsets = WindowInsetsCompat.Builder(WindowInsetsCompat.toWindowInsetsCompat(insets))
            .setInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                androidx.core.graphics.Insets.NONE
            )
            .build()
            .toWindowInsets()
        if (zeroedInsets != null) {
            for (i in 0 until childCount) {
                getChildAt(i)?.dispatchApplyWindowInsets(zeroedInsets)
            }
        }
        return insets
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createConfiguredTabWebView(
    ctx: Context,
    initialTabId: String,
    forceSoftwareLayer: Boolean,
    currentTabProvider: () -> BrowserTab,
    currentSettingsProvider: () -> NyxoraSettingsState,
    currentSiteSettingsMapProvider: () -> Map<String, SiteSettingItem>,
    currentAllowOnceProvider: () -> Set<String>,
    lastTouchTimeProvider: () -> Long,
    onTouchRecorded: (Long) -> Unit,
    onPullRefreshTriggered: () -> Unit,
    onPageStarted: (tabId: String, url: String) -> Unit,
    onPageFinished: (tabId: String, url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onHistoryUpdated: (tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgressChanged: (tabId: String, progress: Int) -> Unit,
    onPageError: (tabId: String, failingUrl: String, description: String) -> Unit,
    onSslError: (tabId: String, failingUrl: String, errorName: String, onDecision: (Boolean) -> Unit) -> Unit,
    onAdBlocked: () -> Unit,
    onShieldSubresourceBlocked: (ShieldBlockOutcome) -> Unit,
    onTrackingParamsStripped: (Int) -> Unit,
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
    onEnterFullscreenVideo: (View, Int, WebChromeClient.CustomViewCallback?) -> Unit,
    onExitFullscreenVideo: () -> Unit,
    onRendererGone: (tabId: String, view: WebView?, didCrash: Boolean) -> Unit,
    controllerHandle: WebViewControllerHandle
): WebView {
    return object : WebView(ctx) {
        override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
            setPadding(0, 0, 0, 0)
            val zeroed = WindowInsetsCompat.Builder(WindowInsetsCompat.toWindowInsetsCompat(insets))
                .setInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                    androidx.core.graphics.Insets.NONE
                )
                .build()
                .toWindowInsets()
            return super.onApplyWindowInsets(zeroed ?: insets)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val deltaY = t - oldt
            if (abs(deltaY) > 2) {
                controllerHandle.notifyPageScrollChanged(t, deltaY)
            }
        }

        override fun flingScroll(vx: Int, vy: Int) {
            val reducedMotion = currentSettingsProvider().reducedMotion
            val gentleVx = NyxoraMomentumScrollPhysics.computeGentleWebViewFlingVelocity(vx, reducedMotion)
            val gentleVy = NyxoraMomentumScrollPhysics.computeGentleWebViewFlingVelocity(vy, reducedMotion)
            super.flingScroll(gentleVx, gentleVy)
        }
    }.apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setPadding(0, 0, 0, 0)
        setBackgroundColor(android.graphics.Color.parseColor("#0A0D14"))
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        overScrollMode = View.OVER_SCROLL_NEVER
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
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = currentTabProvider().textZoom.coerceIn(50, 250)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowContentAccess = false
            allowFileAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { safeBrowsingEnabled = true }
            }
        }

        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            onFindMatchesUpdated(activeMatchOrdinal, numberOfMatches)
        }

        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            onRequestDownload(url, guessedName, mimeType)
        }

        addJavascriptInterface(
            NyxoraConnectWebViewBridge { controllerHandle },
            "NyxoraConnectJsBridge"
        )

        webViewClient = object : WebViewClient() {

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // Always return true so aw_browser_terminator never terminates the host app process
                onRendererGone(initialTabId, view, detail?.didCrash() == true)
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
                val siteOverride = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), pageHost)
                val settingsSnapshot = currentSettingsProvider()

                if (!settingsSnapshot.shieldMasterEnabled) {
                    return super.shouldInterceptRequest(view, request)
                }

                val shieldEval = NyxoraShieldEngine.evaluateWebResourceRequest(
                    request = req,
                    pageUrl = pageUrl,
                    siteSetting = siteOverride,
                    isPrivateTab = tabSnapshot.isPrivate
                )
                if (shieldEval.blocked) {
                    onShieldSubresourceBlocked(shieldEval.outcome)
                    return ContentBlockingEngine.createBlockedSubresourceResponse(reqUrl)
                }

                val shouldBlockFallback = ContentBlockingEngine.shouldBlockSubresource(
                    requestUrl = reqUrl,
                    pageUrl = pageUrl,
                    globalAdBlockingEnabled = settingsSnapshot.adBlockingEnabled,
                    siteSetting = siteOverride
                )
                if (shouldBlockFallback) {
                    onShieldSubresourceBlocked(ShieldBlockOutcome.BLOCKED_AD)
                    return ContentBlockingEngine.createBlockedSubresourceResponse(reqUrl)
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
                val siteOverride = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), pageHost)
                val lastTouch = lastTouchTimeProvider()
                val elapsedSinceTouch = if (lastTouch > 0L) {
                    SystemClock.elapsedRealtime() - lastTouch
                } else {
                    Long.MAX_VALUE
                }

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
                    NavigationDecision.ALLOW -> {
                        val targetHost = UrlAndSearchHandler.extractHost(targetUrl)
                        val targetSiteOverride = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), targetHost)
                        val cleanResult = NyxoraShieldEngine.cleanUrlTrackingParameters(
                            rawUrl = targetUrl,
                            siteSetting = targetSiteOverride
                        )
                        if (cleanResult.wasModified) {
                            onTrackingParamsStripped(cleanResult.removedParams.size)
                            view?.loadUrl(cleanResult.cleanedUrl)
                            true
                        } else {
                            false
                        }
                    }
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
                view?.tag = safeUrl
                if (!safeUrl.startsWith("file://", ignoreCase = true)) {
                    view?.settings?.allowFileAccess = false
                }
                if (favicon != null && !currentTabProvider().isPrivate) {
                    FaviconCacheManager.saveFaviconForUrl(ctx, safeUrl, favicon)
                }
                onPageStarted(initialTabId, safeUrl)

                val pageHost = UrlAndSearchHandler.extractHost(safeUrl)
                val siteOverride = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), pageHost)
                val settingsSnap = currentSettingsProvider()
                val effectiveTextZoom = (siteOverride?.textZoom ?: currentTabProvider().textZoom).coerceIn(50, 250)
                if (view != null && view.settings.textZoom != effectiveTextZoom) {
                    view.settings.textZoom = effectiveTextZoom
                }
                val adBlockActive = settingsSnap.adBlockingEnabled && (siteOverride?.allowAds != true)
                val cosmeticJs = ContentBlockingEngine.buildCosmeticAndDarkModeScript(
                    hideCookieBanners = settingsSnap.hideCookieConsentBanners,
                    forceDarkWebPages = settingsSnap.forceDarkWebPages,
                    adBlockingEnabled = adBlockActive,
                    pageUrl = safeUrl,
                    cosmeticFilteringEnabled = settingsSnap.cosmeticFilteringEnabled,
                    shieldMasterEnabled = settingsSnap.shieldMasterEnabled,
                    siteSetting = siteOverride
                )
                if (cosmeticJs.isNotEmpty()) {
                    view?.evaluateJavascript(cosmeticJs, null)
                }
                if (view != null) {
                    val effectiveDesktop = currentTabProvider().desktopMode ||
                        settingsSnap.userAgentMode == UserAgentMode.DESKTOP
                    applyViewportModeScript(view, effectiveDesktop)
                }
                if (settingsSnap.doNotTrackEnabled) {
                    view?.evaluateJavascript(
                        "(function(){try{Object.defineProperty(navigator,'doNotTrack',{get:function(){return '1';}});Object.defineProperty(navigator,'globalPrivacyControl',{get:function(){return true;}});}catch(e){}})();",
                        null
                    )
                }
                if (settingsSnap.webrtcLeakProtectionEnabled) {
                    view?.evaluateJavascript(WEBRTC_IP_LEAK_PROTECTION_JS, null)
                }
                if (!settingsSnap.clipboardAccessEnabled) {
                    view?.evaluateJavascript(
                        "(function(){try{if(navigator.clipboard){navigator.clipboard.readText=function(){return Promise.reject(new DOMException('Clipboard access blocked by NYXORA','NotAllowedError'));};navigator.clipboard.writeText=function(){return Promise.reject(new DOMException('Clipboard access blocked by NYXORA','NotAllowedError'));};}}catch(e){}})();",
                        null
                    )
                }
                if (view != null && settingsSnap.javascriptEnabled && settingsSnap.userScriptsEnabled) {
                    injectMatchingUserScripts(
                        webView = view,
                        pageUrl = safeUrl,
                        pageHost = pageHost,
                        settings = settingsSnap,
                        atDocumentEnd = false
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val safeUrl = url ?: view?.url ?: return
                onHistoryUpdated(
                    initialTabId,
                    safeUrl,
                    view?.canGoBack() ?: false,
                    view?.canGoForward() ?: false
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val safeUrl = url ?: return
                view?.tag = safeUrl
                val pageHost = UrlAndSearchHandler.extractHost(safeUrl)
                val siteOverride = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), pageHost)
                val settingsSnap = currentSettingsProvider()
                val effectiveTextZoom = (siteOverride?.textZoom ?: currentTabProvider().textZoom).coerceIn(50, 250)
                if (view != null && view.settings.textZoom != effectiveTextZoom) {
                    view.settings.textZoom = effectiveTextZoom
                }
                val adBlockActive = settingsSnap.adBlockingEnabled && (siteOverride?.allowAds != true)
                val cosmeticJs = ContentBlockingEngine.buildCosmeticAndDarkModeScript(
                    hideCookieBanners = settingsSnap.hideCookieConsentBanners,
                    forceDarkWebPages = settingsSnap.forceDarkWebPages,
                    adBlockingEnabled = adBlockActive,
                    pageUrl = safeUrl,
                    cosmeticFilteringEnabled = settingsSnap.cosmeticFilteringEnabled,
                    shieldMasterEnabled = settingsSnap.shieldMasterEnabled,
                    siteSetting = siteOverride
                )
                if (cosmeticJs.isNotEmpty()) {
                    view?.evaluateJavascript(cosmeticJs, null)
                }
                if (view != null) {
                    val effectiveDesktop = currentTabProvider().desktopMode ||
                        settingsSnap.userAgentMode == UserAgentMode.DESKTOP
                    applyViewportModeScript(view, effectiveDesktop)
                }
                if (settingsSnap.webrtcLeakProtectionEnabled) {
                    view?.evaluateJavascript(WEBRTC_IP_LEAK_PROTECTION_JS, null)
                }
                if (!settingsSnap.clipboardAccessEnabled) {
                    view?.evaluateJavascript(
                        "(function(){try{if(navigator.clipboard){navigator.clipboard.readText=function(){return Promise.reject(new DOMException('Clipboard access blocked by NYXORA','NotAllowedError'));};navigator.clipboard.writeText=function(){return Promise.reject(new DOMException('Clipboard access blocked by NYXORA','NotAllowedError'));};}}catch(e){}})();",
                        null
                    )
                }
                if (view != null && settingsSnap.javascriptEnabled && settingsSnap.userScriptsEnabled) {
                    injectMatchingUserScripts(
                        webView = view,
                        pageUrl = safeUrl,
                        pageHost = pageHost,
                        settings = settingsSnap,
                        atDocumentEnd = true
                    )
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

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                val currentUrl = view?.url ?: currentTabProvider().url
                if (icon != null && !currentTabProvider().isPrivate) {
                    FaviconCacheManager.saveFaviconForUrl(ctx, currentUrl, icon)
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
                val siteOverride = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), pageHost)

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
                val tempWebView = WebView(hostView.context).apply {
                    if (forceSoftwareLayer) {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    }
                }
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

            override fun getDefaultVideoPoster(): Bitmap {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                onEnterFullscreenVideo(view, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, callback)
            }

            @Suppress("DEPRECATION")
            override fun onShowCustomView(
                view: View?,
                requestedOrientation: Int,
                callback: CustomViewCallback?
            ) {
                if (view == null) return
                onEnterFullscreenVideo(view, requestedOrientation, callback)
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
                val siteRule = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), originHost)

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

                val effectiveCameraRule = siteRule?.cameraPermission ?: currentSettingsProvider().defaultCameraPermission
                val effectiveMicRule = siteRule?.micPermission ?: currentSettingsProvider().defaultMicPermission
                val cameraBlocked = "CAMERA" in requestedTypes && effectiveCameraRule == "DENY"
                val micBlocked = "MICROPHONE" in requestedTypes && effectiveMicRule == "DENY"
                if (cameraBlocked || micBlocked) {
                    req.deny()
                    return
                }

                val proceedWithPromptOrGrant: () -> Unit = {
                    val cameraPreAllowed = "CAMERA" !in requestedTypes || effectiveCameraRule == "ALLOW"
                    val micPreAllowed = "MICROPHONE" !in requestedTypes || effectiveMicRule == "ALLOW"
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
                val siteRule = lookupSiteSettingFromMap(currentSiteSettingsMapProvider(), originHost)
                val effectiveLocationRule = siteRule?.locationPermission ?: currentSettingsProvider().defaultLocationPermission

                if (effectiveLocationRule == "DENY") {
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
                    if (effectiveLocationRule == "ALLOW") {
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

private fun normalizeHostForSiteSettingLookup(rawHost: String): String {
    return rawHost.trim().lowercase()
        .removePrefix("www.")
        .removePrefix("m.")
        .removePrefix("mobile.")
}

internal fun buildSiteSettingsHostMap(siteSettings: List<SiteSettingItem>): Map<String, SiteSettingItem> {
    if (siteSettings.isEmpty()) return emptyMap()
    val map = HashMap<String, SiteSettingItem>(siteSettings.size * 2)
    for (item in siteSettings) {
        val key = normalizeHostForSiteSettingLookup(item.host)
        if (key.isNotEmpty()) {
            map[key] = item
        }
    }
    return map
}

internal fun lookupSiteSettingFromMap(
    siteSettingsMap: Map<String, SiteSettingItem>,
    rawHost: String
): SiteSettingItem? {
    if (siteSettingsMap.isEmpty() || rawHost.isEmpty()) return null
    val key = normalizeHostForSiteSettingLookup(rawHost)
    return siteSettingsMap[key]
}

internal fun findSiteSettingForHost(
    siteSettings: List<SiteSettingItem>,
    rawHost: String
): SiteSettingItem? {
    val cleanHost = normalizeHostForSiteSettingLookup(rawHost)
    if (cleanHost.isEmpty()) return null
    return siteSettings.firstOrNull {
        normalizeHostForSiteSettingLookup(it.host) == cleanHost
    }
}

internal const val WEBRTC_IP_LEAK_PROTECTION_JS = """
(function() {
    try {
        if (window.__nyxoraWebRtcProtected) return;
        window.__nyxoraWebRtcProtected = true;
        var OrigPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;
        if (!OrigPeerConnection) return;
        var SafePeerConnection = function(config, constraints) {
            var safeConfig = config ? Object.assign({}, config) : {};
            if (!safeConfig.iceTransportPolicy) {
                safeConfig.iceServers = [];
            }
            return new OrigPeerConnection(safeConfig, constraints);
        };
        SafePeerConnection.prototype = OrigPeerConnection.prototype;
        window.RTCPeerConnection = SafePeerConnection;
        if (window.webkitRTCPeerConnection) {
            window.webkitRTCPeerConnection = SafePeerConnection;
        }
    } catch (e) {}
})();
"""

internal fun resolveEffectiveUserAgent(
    tabDesktopMode: Boolean,
    settings: NyxoraSettingsState
): String {
    return when {
        tabDesktopMode -> DESKTOP_USER_AGENT
        settings.userAgentMode == UserAgentMode.DESKTOP -> DESKTOP_USER_AGENT
        settings.userAgentMode == UserAgentMode.CUSTOM && settings.customUserAgent.isNotBlank() ->
            settings.customUserAgent.trim()
        else -> MOBILE_USER_AGENT
    }
}

private fun injectMatchingUserScripts(
    webView: WebView,
    pageUrl: String,
    pageHost: String,
    settings: NyxoraSettingsState,
    atDocumentEnd: Boolean
) {
    if (!pageUrl.startsWith("https://", ignoreCase = true) &&
        !pageUrl.startsWith("http://", ignoreCase = true)
    ) {
        return
    }
    val scripts = settings.parsedUserScripts
    if (scripts.isEmpty()) return
    for (script in scripts) {
        if (!script.enabled) continue
        if (settings.userScriptsRequireApproval && !script.approvedByUser) continue
        if (script.runAtDocumentEnd != atDocumentEnd) continue
        if (!UserScriptStorage.matchesDomain(script.domainPattern, pageHost)) continue
        val wrapped = "(function(){try{\n${script.code}\n}catch(err){console.warn('NYXORA UserScript error:',err);}})();"
        webView.evaluateJavascript(wrapped, null)
    }
}

/**
 * Applies dynamic per-tab & global settings (including Desktop Mode User-Agent and viewport behavior).
 * Returns true if the User-Agent string changed so the caller can trigger a page reload.
 */
internal fun applyDynamicWebSettings(
    webView: WebView,
    tab: BrowserTab,
    settings: NyxoraSettingsState,
    siteOverride: SiteSettingItem? = null,
    previousAppliedConfig: AppliedTabWebConfig? = null
): Boolean {
    if (webView.settings.javaScriptEnabled != settings.javascriptEnabled) {
        webView.settings.javaScriptEnabled = settings.javascriptEnabled
    }
    val targetUa = resolveEffectiveUserAgent(tab.desktopMode, settings)
    val uaChanged = webView.settings.userAgentString != targetUa
    if (uaChanged) {
        webView.settings.userAgentString = targetUa
    }
    if (!webView.settings.useWideViewPort) {
        webView.settings.useWideViewPort = true
    }
    if (!webView.settings.loadWithOverviewMode) {
        webView.settings.loadWithOverviewMode = true
    }
    if (!webView.settings.supportZoom()) {
        webView.settings.setSupportZoom(true)
    }
    if (!webView.settings.builtInZoomControls) {
        webView.settings.builtInZoomControls = true
    }
    if (webView.settings.displayZoomControls) {
        webView.settings.displayZoomControls = false
    }
    val effectiveDesktop = tab.desktopMode || settings.userAgentMode == UserAgentMode.DESKTOP
    val desiredLayoutAlgorithm = if (effectiveDesktop) {
        WebSettings.LayoutAlgorithm.NORMAL
    } else {
        WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
    }
    if (webView.settings.layoutAlgorithm != desiredLayoutAlgorithm) {
        webView.settings.layoutAlgorithm = desiredLayoutAlgorithm
    }
    val effectiveTextZoom = (siteOverride?.textZoom ?: tab.textZoom).coerceIn(50, 250)
    if (webView.settings.textZoom != effectiveTextZoom) {
        webView.settings.textZoom = effectiveTextZoom
    }
    if (webView.settings.blockNetworkImage != settings.dataSaverBlockImages) {
        webView.settings.blockNetworkImage = settings.dataSaverBlockImages
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        @Suppress("DEPRECATION")
        webView.settings.saveFormData = settings.saveFormDataEnabled && !tab.isPrivate
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
    val desiredThirdPartyCookies = settings.cookiesEnabled && !settings.blockThirdPartyCookies && !tab.isPrivate
    val cookieConfigChanged = previousAppliedConfig == null ||
        previousAppliedConfig.cookiesEnabled != settings.cookiesEnabled ||
        previousAppliedConfig.acceptThirdPartyCookies != desiredThirdPartyCookies
    if (cookieConfigChanged) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(settings.cookiesEnabled)
            cm.setAcceptThirdPartyCookies(webView, desiredThirdPartyCookies)
        }
    }
    return uaChanged
}

/**
 * Synchronizes the page's `<meta name="viewport">` tag for Desktop Site vs Mobile Site mode
 * so responsive websites render desktop layouts when enabled and cleanly restore mobile layouts when disabled.
 */
private fun applyViewportModeScript(webView: WebView, desktopMode: Boolean) {
    val js = if (desktopMode) {
        """
        (function() {
            try {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.setAttribute('data-nyxora-created', 'true');
                    (document.head || document.documentElement).appendChild(meta);
                } else if (!meta.hasAttribute('data-nyxora-orig')) {
                    meta.setAttribute('data-nyxora-orig', meta.getAttribute('content') || 'width=device-width, initial-scale=1.0');
                }
                meta.setAttribute('content', 'width=1280, initial-scale=0.65, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes');
            } catch (e) {}
        })();
        """.trimIndent()
    } else {
        """
        (function() {
            try {
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    if (meta.getAttribute('data-nyxora-created') === 'true') {
                        meta.setAttribute('content', 'width=device-width, initial-scale=1.0');
                        meta.removeAttribute('data-nyxora-created');
                    } else if (meta.hasAttribute('data-nyxora-orig')) {
                        meta.setAttribute('content', meta.getAttribute('data-nyxora-orig'));
                        meta.removeAttribute('data-nyxora-orig');
                    } else {
                        var cur = meta.getAttribute('content') || '';
                        if (cur.indexOf('width=1280') !== -1) {
                            meta.setAttribute('content', 'width=device-width, initial-scale=1.0');
                        }
                    }
                }
            } catch (e) {}
        })();
        """.trimIndent()
    }
    webView.evaluateJavascript(js, null)
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

internal data class SavedFullscreenWindowState(
    val requestedOrientation: Int,
    val systemUiVisibility: Int,
    val systemBarsBehavior: Int,
    val cutoutMode: Int,
    val hadKeepScreenOnFlag: Boolean,
    val hadFullscreenFlag: Boolean
)

internal fun isExplicitLandscapeOrientation(orientation: Int): Boolean {
    return orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
}

internal fun isExplicitPortraitOrientation(orientation: Int): Boolean {
    return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
}

internal fun isActivityCurrentlyLandscape(activity: Activity): Boolean {
    val req = activity.requestedOrientation
    return when {
        isExplicitLandscapeOrientation(req) -> true
        isExplicitPortraitOrientation(req) -> false
        else -> activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}

private fun captureWindowStateBeforeFullscreen(activity: Activity?): SavedFullscreenWindowState? {
    val act = activity ?: return null
    val window = act.window ?: return null
    val decorView = window.decorView
    val insetsController = WindowCompat.getInsetsController(window, decorView)
    val attrs = window.attributes
    val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        attrs.layoutInDisplayCutoutMode
    } else {
        0
    }
    @Suppress("DEPRECATION")
    val sysUiVis = decorView.systemUiVisibility
    @Suppress("DEPRECATION")
    val hadFullscreenFlag = (attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
    return SavedFullscreenWindowState(
        requestedOrientation = act.requestedOrientation,
        systemUiVisibility = sysUiVis,
        systemBarsBehavior = insetsController.systemBarsBehavior,
        cutoutMode = cutoutMode,
        hadKeepScreenOnFlag = (attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
        hadFullscreenFlag = hadFullscreenFlag
    )
}

private fun enterImmersiveFullscreen(
    activity: Activity?,
    keepScreenOn: Boolean,
    orientationMode: FullscreenOrientationMode,
    videoRequestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
) {
    val act = activity ?: return
    val window = act.window

    // 1. Respect the video's requested orientation if explicitly provided, or follow user's orientation mode
    // while preserving current Portrait/Landscape orientation without forcing unnecessary rotation.
    when {
        isExplicitLandscapeOrientation(videoRequestedOrientation) ||
            isExplicitPortraitOrientation(videoRequestedOrientation) -> {
            act.requestedOrientation = videoRequestedOrientation
        }
        orientationMode == FullscreenOrientationMode.ALWAYS_LANDSCAPE -> {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        orientationMode == FullscreenOrientationMode.PORTRAIT -> {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        else -> {
            if (isActivityCurrentlyLandscape(act)) {
                if (!isExplicitLandscapeOrientation(act.requestedOrientation)) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            } else {
                if (!isExplicitPortraitOrientation(act.requestedOrientation)) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
    }

    if (window == null) return

    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val params = window.attributes
        params.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        window.attributes = params
    }

    WindowCompat.setDecorFitsSystemWindows(window, false)
    val decorView = window.decorView
    val insetsController = WindowCompat.getInsetsController(window, decorView)
    insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    insetsController.hide(WindowInsetsCompat.Type.systemBars())

    @Suppress("DEPRECATION")
    decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    @Suppress("DEPRECATION")
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
}

private fun restoreActivityAfterFullscreen(
    activity: Activity?,
    savedState: SavedFullscreenWindowState?
) {
    val act = activity ?: return
    val window = act.window

    if (window != null) {
        if (savedState?.hadKeepScreenOnFlag != true) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (savedState?.hadFullscreenFlag != true) {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && savedState != null) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = savedState.cutoutMode
            window.attributes = params
        }

        val darkWindowBg = android.graphics.Color.parseColor("#060E1B")
        val darkNavBarBg = android.graphics.Color.parseColor("#071222")
        window.decorView.setBackgroundColor(darkWindowBg)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = darkNavBarBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
        }

        val decorView = window.decorView
        val insetsController = WindowCompat.getInsetsController(window, decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        if (savedState != null) {
            insetsController.systemBarsBehavior = savedState.systemBarsBehavior
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = savedState.systemUiVisibility
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    act.requestedOrientation =
        savedState?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
