package com.example.domain.browser

data class CapabilityEntry(
    val feature: String,
    val supportLevel: String, // "Full", "Partial", "Not Supported"
    val engineMechanism: String,
    val limitationOrAlternative: String
)

object EngineCapabilityMatrix {
    const val ENGINE_NAME = "Android System WebView (Blink / Chromium Engine)"
    const val DECISION_REASON =
        "Selected Android System WebView because project repositories in settings.gradle.kts are scoped strictly to google() and mavenCentral() (Mozilla GeckoView requires maven.mozilla.org and adds ~85 MB of native binaries per ABI). System WebView shares the OS Chromium update stream, starts in <80 ms, and exposes real hooks for subresource interception, gesture-flagged navigation, custom fullscreen video views, and scoped file choosers."

    val capabilities: List<CapabilityEntry> = listOf(
        CapabilityEntry(
            feature = "Subresource Ad & Tracker Blocking",
            supportLevel = "Full",
            engineMechanism = "WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)",
            limitationOrAlternative = "Intercepts network requests before fetch and returns an empty 204/text response for matched ad/tracker hosts and URL patterns. Cannot inspect WebSocket frame payloads."
        ),
        CapabilityEntry(
            feature = "Gesture-Verified Pop-up Blocking",
            supportLevel = "Full",
            engineMechanism = "WebChromeClient.onCreateWindow(..., isUserGesture) + WebSettings.setJavaScriptCanOpenWindowsAutomatically(false)",
            limitationOrAlternative = "Blocks window.open() and target='_blank' requests when isUserGesture is false; offers 'Allow once' snackbar."
        ),
        CapabilityEntry(
            feature = "Auto-Redirect & External App Launch Blocking",
            supportLevel = "Partial",
            engineMechanism = "WebViewClient.shouldOverrideUrlLoading(WebView, WebResourceRequest) using request.hasGesture() and request.isRedirect",
            limitationOrAlternative = "Blocks script/timer top-level navigations and intent:// schemes lacking a recent user gesture while allowing server-side HTTP 3xx redirects on user-initiated navigations and OAuth/login flows."
        ),
        CapabilityEntry(
            feature = "Cosmetic Cookie-Banner Hiding & Force Dark Web",
            supportLevel = "Full",
            engineMechanism = "DOM CSS Style Injection via evaluateJavascript in onPageStarted / onPageFinished",
            limitationOrAlternative = "Injects high-specificity CSS rules hiding common CMP selectors (OneTrust, Cookiebot, Quantcast, TrustArc) and optional dark-mode inversion filter."
        ),
        CapabilityEntry(
            feature = "Incognito / Private Session Isolation",
            supportLevel = "Partial",
            engineMechanism = "Dedicated Private Tab Pool with zero Room history writes, disabled cache/DOM persistence where possible, and explicit CookieManager + WebStorage wipe on exit",
            limitationOrAlternative = "Android WebView shares the process-level CookieManager singleton across instances; NYXORA isolates private tabs in memory and flushes session cookies, cache, and DOM storage immediately when private tabs close."
        ),
        CapabilityEntry(
            feature = "Portrait & Landscape Fullscreen Video + PiP",
            supportLevel = "Full",
            engineMechanism = "WebChromeClient.onShowCustomView / onHideCustomView + JS video.videoWidth/videoHeight inspection + Activity.enterPictureInPictureMode",
            limitationOrAlternative = "Detects vertical video (height > width) to stay in portrait fullscreen while rotating wide video to landscape; supports Fit/Fill/Original aspect modes."
        ),
        CapabilityEntry(
            feature = "Per-Site Permissions (Camera, Mic, Location, Notifications)",
            supportLevel = "Full",
            engineMechanism = "WebChromeClient.onPermissionRequest + onGeolocationPermissionsShowPrompt + Android Runtime Permission launcher",
            limitationOrAlternative = "Never grants permissions silently; checks per-site policy (ASK/ALLOW/DENY) before prompting user and requesting Android OS permission."
        ),
        CapabilityEntry(
            feature = "Browser Extensions (WebExtensions .xpi / Chrome .crx)",
            supportLevel = "Not Supported",
            engineMechanism = "Native built-in NYXORA modules (Ad/Tracker Filter Engine, Reader Mode, Cosmetic CSS, Force-Dark Web Page Engine)",
            limitationOrAlternative = "Android System WebView does not support WebExtension APIs; essential extension capabilities are built natively into NYXORA."
        ),
        CapabilityEntry(
            feature = "Background Web Push Notifications",
            supportLevel = "Not Supported",
            engineMechanism = "Foreground HTML5 Notification API & Native Download Status Alerts",
            limitationOrAlternative = "Android System WebView does not support background Service Worker Push API delivery when the browser process is closed."
        ),
        CapabilityEntry(
            feature = "DRM Depth (Widevine L1 / L3 Protected Media)",
            supportLevel = "Partial",
            engineMechanism = "PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in WebChromeClient.onPermissionRequest",
            limitationOrAlternative = "Hardware-backed Widevine L1 depth depends on OEM device provisioning and System WebView implementation; some devices restrict WebView EME streams to Widevine L3 (SD)."
        )
    )
}
