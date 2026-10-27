package com.example.domain.browser

import android.util.Base64
import android.webkit.WebResourceResponse
import com.example.data.model.CustomFilterRule
import com.example.data.model.SiteSettingItem
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NavigationDecision {
    ALLOW,
    BLOCK_AUTO_REDIRECT,
    BLOCK_DANGEROUS_SCHEME,
    PROMPT_EXTERNAL_APP_LAUNCH,
    PROMPT_HTTP_ON_HTTPS_ONLY
}

data class PopupDecision(
    val allowed: Boolean,
    val reason: String
)

object ContentBlockingEngine {

    // Core high-confidence ad, tracker, & pop-under domains (EasyList / EasyPrivacy subset)
    private val BUILT_IN_BLOCKED_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "quantserve.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "moatads.com",
        "amazon-adsystem.com",
        "adform.net",
        "smartadserver.com",
        "2mdn.net",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "inmobi.com",
        "adjust.com",
        "appsflyer.com",
        "branch.io",
        "mixpanel.com",
        "hotjar.com",
        "fullstory.com",
        "clarity.ms",
        "mouseflow.com",
        "crazyegg.com",
        "amplitude.com",
        "segment.io",
        "newrelic.com",
        "nr-data.net",
        "bugsnag.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "zedo.com",
        "mgid.com",
        "revcontent.com",
        "teads.tv",
        "bidswitch.net",
        "casalemedia.com",
        "media.net",
        "medianet.com",
        "indexww.com",
        "3lift.com",
        "triplelift.com",
        "gumgum.com",
        "sovrn.com",
        "lijit.com",
        "onclickads.net",
        "hilltopads.net",
        "a-ads.com",
        "tsyndicate.com",
        "trafficstars.com",
        "bidgear.com",
        "adroll.com",
        "smartlook.com",
        "bat.bing.com",
        "static.ads-twitter.com",
        "ads-api.twitter.com",
        "analytics.tiktok.com",
        "mc.yandex.ru"
    )

    private val BUILT_IN_BLOCKED_PATH_TOKENS = listOf(
        "/pagead/",
        "/adserver/",
        "/ads/banner",
        "/adframe.",
        "/doubleclick/",
        "/collect?v=1",
        "/pixel/track",
        "/adsbygoogle",
        "/gpt/pubads",
        "/adunit/",
        "/popunder",
        "/banner_ad",
        "/ad_iframe"
    )

    // Essential video streaming, CDN, login, OAuth, SSO, and payment hosts that must never be broken
    private val ESSENTIAL_AUTH_AND_PAYMENT_HOSTS = setOf(
        "accounts.google.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "login.live.com",
        "github.com",
        "auth0.com",
        "okta.com",
        "checkout.stripe.com",
        "hooks.stripe.com",
        "paypal.com",
        "www.paypal.com"
    )

    private val SAFE_VIDEO_AND_CDN_HOSTS = setOf(
        "googlevideo.com",
        "ytimg.com",
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "vimeo.com",
        "vimeocdn.com",
        "jwpcdn.com",
        "jwplayer.com",
        "gstatic.com",
        "googleapis.com",
        "cloudflare.com",
        "jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com"
    )

    private val TRANSPARENT_1X1_GIF_BYTES: ByteArray = Base64.decode(
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
        Base64.DEFAULT
    )

    @Volatile
    private var dynamicBlockedDomains: Set<String> = emptySet()

    @Volatile
    private var dynamicAllowDomains: Set<String> = emptySet()

    @Volatile
    private var dynamicBlockedPathTokens: List<String> = emptyList()

    private data class RedirectTrackerState(
        val recentCrossOriginTimestamps: MutableList<Long> = mutableListOf(),
        val recentTargets: MutableList<String> = mutableListOf()
    )

    private val redirectLoopTrackers = ConcurrentHashMap<String, RedirectTrackerState>()

    fun resetRedirectLoopTracking() {
        redirectLoopTrackers.clear()
    }

    fun updateCustomRules(rules: List<CustomFilterRule>) {
        val blockedDomains = mutableSetOf<String>()
        val allowedDomains = mutableSetOf<String>()
        val blockedPaths = mutableListOf<String>()

        for (rule in rules) {
            val raw = rule.pattern.trim().lowercase()
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("[")) continue

            val isAllow = rule.isAllowRule || raw.startsWith("@@")
            val stripped = raw
                .removePrefix("@@||")
                .removePrefix("||")
                .removePrefix("@@")
                .substringBefore('$')
                .trim()

            if (stripped.startsWith("/") && stripped.length >= 4) {
                if (!isAllow) {
                    blockedPaths.add(stripped)
                }
                continue
            }

            val hostPart = stripped
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('^')
                .substringBefore('/')
                .substringBefore('*')
                .trim()

            if (hostPart.isNotBlank() && hostPart.contains('.')) {
                if (isAllow) {
                    allowedDomains.add(hostPart)
                } else {
                    blockedDomains.add(hostPart)
                }
            }
        }
        dynamicBlockedDomains = blockedDomains
        dynamicAllowDomains = allowedDomains
        dynamicBlockedPathTokens = blockedPaths
    }

    fun getTotalActiveRuleCount(): Int {
        return BUILT_IN_BLOCKED_DOMAINS.size +
            BUILT_IN_BLOCKED_PATH_TOKENS.size +
            dynamicBlockedDomains.size +
            dynamicBlockedPathTokens.size
    }

    /**
     * Checks whether a subresource request should be blocked by the filter engine.
     * Avoids breaking first-party content, login flows, or video playback streams.
     */
    fun shouldBlockSubresource(
        requestUrl: String,
        pageUrl: String,
        globalAdBlockingEnabled: Boolean,
        siteSetting: SiteSettingItem?
    ): Boolean {
        if (!globalAdBlockingEnabled) return false
        if (siteSetting?.allowAds == true) return false

        val reqHost = UrlAndSearchHandler.extractHost(requestUrl)
        if (reqHost.isEmpty()) return false

        val pageHost = UrlAndSearchHandler.extractHost(pageUrl)

        // Check custom allowlist rules for either the page host or the request host
        if (matchesDomainSet(reqHost, dynamicAllowDomains) ||
            (pageHost.isNotEmpty() && matchesDomainSet(pageHost, dynamicAllowDomains))
        ) {
            return false
        }

        // Never block essential login/payment flows or direct video stream CDNs unless it's an explicit /pagead/ path
        val lowerUrl = requestUrl.lowercase()
        val isExplicitAdPath = lowerUrl.contains("/pagead/") ||
            lowerUrl.contains("/adserver/") ||
            lowerUrl.contains("/adsbygoogle") ||
            lowerUrl.contains("/gpt/pubads")

        if (!isExplicitAdPath &&
            (matchesDomainSet(reqHost, ESSENTIAL_AUTH_AND_PAYMENT_HOSTS) ||
                matchesDomainSet(reqHost, SAFE_VIDEO_AND_CDN_HOSTS))
        ) {
            return false
        }

        // Check built-in and dynamic blocked domains
        if (matchesDomainSet(reqHost, BUILT_IN_BLOCKED_DOMAINS) ||
            matchesDomainSet(reqHost, dynamicBlockedDomains)
        ) {
            return true
        }

        // Check URL path tokens if third-party or explicit ad path
        val isThirdParty = !isSameRegistrableDomain(reqHost, pageHost)
        if (isThirdParty || isExplicitAdPath) {
            for (token in BUILT_IN_BLOCKED_PATH_TOKENS) {
                if (lowerUrl.contains(token)) return true
            }
            for (customToken in dynamicBlockedPathTokens) {
                if (lowerUrl.contains(customToken)) return true
            }
        }
        return false
    }

    /**
     * Builds a safe synthetic WebResourceResponse for blocked subresources so web pages
     * don't suffer broken script onload handlers or layout shifts.
     */
    fun createBlockedSubresourceResponse(requestUrl: String): WebResourceResponse {
        val cleanPath = requestUrl.substringBefore('?').substringBefore('#').lowercase()
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store"
        )
        return when {
            cleanPath.endsWith(".js") || cleanPath.contains("/js/") -> WebResourceResponse(
                "application/javascript",
                "utf-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream("/* Blocked by NYXORA */".toByteArray(Charsets.UTF_8))
            )
            cleanPath.endsWith(".css") -> WebResourceResponse(
                "text/css",
                "utf-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream("".toByteArray(Charsets.UTF_8))
            )
            cleanPath.endsWith(".gif") || cleanPath.endsWith(".png") ||
                cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") ||
                cleanPath.endsWith(".webp") -> WebResourceResponse(
                "image/gif",
                null,
                200,
                "OK",
                headers,
                ByteArrayInputStream(TRANSPARENT_1X1_GIF_BYTES)
            )
            else -> WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "Blocked by NYXORA",
                headers,
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    internal fun matchesDomainSet(host: String, domainSet: Set<String>): Boolean {
        val clean = host.lowercase().trimEnd('.')
        if (clean in domainSet) return true
        var current = clean
        while (current.contains('.')) {
            current = current.substringAfter('.')
            if (current in domainSet) return true
        }
        return false
    }

    internal fun isSameRegistrableDomain(hostA: String, hostB: String): Boolean {
        if (hostA.isEmpty() || hostB.isEmpty()) return false
        val cleanA = hostA.lowercase().removePrefix("www.").removePrefix("m.")
        val cleanB = hostB.lowercase().removePrefix("www.").removePrefix("m.")
        if (cleanA == cleanB) return true
        val partsA = cleanA.split('.')
        val partsB = cleanB.split('.')
        if (partsA.size >= 2 && partsB.size >= 2) {
            val tailA = partsA.takeLast(2).joinToString(".")
            val tailB = partsB.takeLast(2).joinToString(".")
            if (tailA == tailB) return true
        }
        return false
    }

    internal fun isUrlAllowedOnce(targetUrl: String, allowOnceUrls: Set<String>): Boolean {
        if (allowOnceUrls.isEmpty()) return false
        val normalizedTarget = normalizeUrlForAllowOnce(targetUrl)
        val targetHost = UrlAndSearchHandler.extractHost(targetUrl)
        return allowOnceUrls.any { allowed ->
            val normAllowed = normalizeUrlForAllowOnce(allowed)
            normAllowed == normalizedTarget ||
                (targetHost.isNotEmpty() && UrlAndSearchHandler.extractHost(allowed) == targetHost)
        }
    }

    private fun normalizeUrlForAllowOnce(url: String): String {
        return url.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
            .lowercase()
    }

    /**
     * Evaluates whether a pop-up / new window request (window.open or target="_blank") should be allowed.
     */
    fun evaluatePopupRequest(
        targetUrl: String?,
        pageUrl: String,
        hasUserGesture: Boolean,
        globalPopupBlockingEnabled: Boolean,
        siteSetting: SiteSettingItem?,
        allowOnceUrls: Set<String>
    ): PopupDecision {
        if (targetUrl != null && isUrlAllowedOnce(targetUrl, allowOnceUrls)) {
            return PopupDecision(allowed = true, reason = "Allowed once by user")
        }
        if (!globalPopupBlockingEnabled || siteSetting?.allowPopups == true) {
            return PopupDecision(allowed = true, reason = "Pop-up blocking disabled for site")
        }
        if (!targetUrl.isNullOrBlank()) {
            val targetHost = UrlAndSearchHandler.extractHost(targetUrl)
            if (targetHost.isNotEmpty() &&
                (matchesDomainSet(targetHost, BUILT_IN_BLOCKED_DOMAINS) ||
                    matchesDomainSet(targetHost, dynamicBlockedDomains))
            ) {
                return PopupDecision(allowed = false, reason = "Blocked known ad/pop-up network")
            }
        }
        if (hasUserGesture) {
            return PopupDecision(allowed = true, reason = "Triggered by real user gesture")
        }
        return PopupDecision(allowed = false, reason = "Blocked automatic pop-up window")
    }

    /**
     * Evaluates top-level navigation requests in shouldOverrideUrlLoading.
     * Distinguishes normal user-initiated navigations, HTTP server redirects, OAuth/payment flows,
     * redirect loops, ad-network redirects, and non-gesture script/timer auto-redirects.
     */
    fun evaluateTopLevelNavigation(
        requestUrl: String,
        currentPageUrl: String,
        hasUserGesture: Boolean,
        isServerRedirect: Boolean,
        timeSinceLastUserInteractionMs: Long,
        globalRedirectBlockingEnabled: Boolean,
        httpsOnlyMode: Boolean,
        siteSetting: SiteSettingItem?,
        allowOnceUrls: Set<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): NavigationDecision {
        val trimmed = requestUrl.trim()
        if (isUrlAllowedOnce(trimmed, allowOnceUrls)) return NavigationDecision.ALLOW

        val lower = trimmed.lowercase()
        // Block dangerous schemes from web pages
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:")) {
            return NavigationDecision.BLOCK_DANGEROUS_SCHEME
        }

        // External app schemes (intent://, market://, tel:, mailto:, etc.)
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("nyxora://") && !lower.startsWith("about:")) {
            val redirectBlockActive = globalRedirectBlockingEnabled && (siteSetting?.allowRedirects != true)
            val recentGesture = hasUserGesture || timeSinceLastUserInteractionMs in 0L..1500L
            return if (redirectBlockActive && !recentGesture) {
                NavigationDecision.BLOCK_AUTO_REDIRECT
            } else {
                NavigationDecision.PROMPT_EXTERNAL_APP_LAUNCH
            }
        }

        // HTTPS-Only check for plain http://
        if (httpsOnlyMode && lower.startsWith("http://")) {
            return NavigationDecision.PROMPT_HTTP_ON_HTTPS_ONLY
        }

        val redirectBlockActive = globalRedirectBlockingEnabled && (siteSetting?.allowRedirects != true)
        if (!redirectBlockActive) return NavigationDecision.ALLOW

        val targetHost = UrlAndSearchHandler.extractHost(trimmed)
        val currentHost = UrlAndSearchHandler.extractHost(currentPageUrl)

        // Block automatic redirects to known ad / pop-under / malvertising domains even on click/redirect
        if (targetHost.isNotEmpty() &&
            targetHost != currentHost &&
            (matchesDomainSet(targetHost, BUILT_IN_BLOCKED_DOMAINS) ||
                matchesDomainSet(targetHost, dynamicBlockedDomains)) &&
            !matchesDomainSet(targetHost, dynamicAllowDomains)
        ) {
            return NavigationDecision.BLOCK_AUTO_REDIRECT
        }

        // Same-origin / same-registrable-domain navigations and essential OAuth/SSO/payment flows are allowed
        if (targetHost.isNotEmpty() &&
            (isSameRegistrableDomain(targetHost, currentHost) ||
                matchesDomainSet(targetHost, ESSENTIAL_AUTH_AND_PAYMENT_HOSTS))
        ) {
            return NavigationDecision.ALLOW
        }

        // Check for rapid cross-origin redirect loops (e.g. A -> B -> C -> A without fresh gesture)
        if (currentHost.isNotEmpty() && currentHost != "nyxora.home" && targetHost.isNotEmpty()) {
            if (isRedirectLoopDetected(currentHost, targetHost, trimmed, hasUserGesture, nowMillis)) {
                return NavigationDecision.BLOCK_AUTO_REDIRECT
            }
        }

        val recentUserTouch = hasUserGesture || timeSinceLastUserInteractionMs in 0L..1800L

        // Intentional tap by the user on a link or button
        if (hasUserGesture && recentUserTouch) {
            return NavigationDecision.ALLOW
        }

        // Server-side 3xx redirects immediately following a user navigation are normal web behavior
        if (isServerRedirect && recentUserTouch) {
            return NavigationDecision.ALLOW
        }

        // Cross-origin navigation without user gesture or recent touch (e.g., timer/script/meta-refresh redirect)
        if (!recentUserTouch && currentHost.isNotEmpty() && currentHost != "nyxora.home") {
            return NavigationDecision.BLOCK_AUTO_REDIRECT
        }

        // Script-driven cross-origin redirect without a real WebView gesture (e.g. window.location hijack during scroll)
        if (!hasUserGesture && !isServerRedirect && currentHost.isNotEmpty() && currentHost != "nyxora.home") {
            return NavigationDecision.BLOCK_AUTO_REDIRECT
        }

        return NavigationDecision.ALLOW
    }

    internal fun isRedirectLoopDetected(
        sourceHost: String,
        targetHost: String,
        targetUrl: String,
        hasUserGesture: Boolean,
        nowMillis: Long
    ): Boolean {
        val state = redirectLoopTrackers.getOrPut(sourceHost) { RedirectTrackerState() }
        synchronized(state) {
            state.recentCrossOriginTimestamps.removeAll { nowMillis - it > 4000L }
            if (state.recentCrossOriginTimestamps.isEmpty()) {
                state.recentTargets.clear()
            }
            if (hasUserGesture && state.recentCrossOriginTimestamps.isEmpty()) {
                state.recentCrossOriginTimestamps.add(nowMillis)
                state.recentTargets.add(targetUrl)
                return false
            }
            val duplicateCount = state.recentTargets.count {
                it.equals(targetUrl, ignoreCase = true) || UrlAndSearchHandler.extractHost(it) == targetHost
            }
            state.recentCrossOriginTimestamps.add(nowMillis)
            state.recentTargets.add(targetUrl)
            // Block if the same cross-origin target is redirected to repeatedly (>= 2 times in 4s) or > 3 rapid hops
            return duplicateCount >= 2 || (!hasUserGesture && state.recentCrossOriginTimestamps.size >= 3)
        }
    }

    /**
     * Fetches updated filter rules from a remote filter list URL with clean fallback error handling.
     */
    suspend fun fetchRemoteFilterListUpdate(
        listUrl: String = "https://easylist.to/easylist/easylist.txt"
    ): Result<List<CustomFilterRule>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(listUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code fetching filter list")
                }
                val lines = conn.inputStream.bufferedReader().useLines { seq ->
                    seq.take(1200).toList()
                }
                parseFilterListLines(lines, "Remote Update")
            } finally {
                conn.disconnect()
            }
        }
    }

    fun parseFilterListLines(lines: List<String>, sourceName: String = "Custom"): List<CustomFilterRule> {
        val result = mutableListOf<CustomFilterRule>()
        val now = System.currentTimeMillis()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.contains("##") || line.contains("#@#")) continue
            val strippedOptions = line.substringBefore('$').trim()
            if (strippedOptions.startsWith("@@||")) {
                val domain = strippedOptions.removePrefix("@@||").substringBefore('^').substringBefore('/').trim()
                if (domain.contains('.') && !domain.contains('*')) {
                    result.add(CustomFilterRule(pattern = domain, isAllowRule = true, sourceList = sourceName, addedAtMillis = now))
                }
            } else if (strippedOptions.startsWith("||")) {
                val domain = strippedOptions.removePrefix("||").substringBefore('^').substringBefore('/').trim()
                if (domain.contains('.') && !domain.contains('*')) {
                    result.add(CustomFilterRule(pattern = domain, isAllowRule = false, sourceList = sourceName, addedAtMillis = now))
                }
            } else if (strippedOptions.startsWith("0.0.0.0 ") || strippedOptions.startsWith("127.0.0.1 ")) {
                val domain = strippedOptions.substringAfter(' ').trim().substringBefore('#').trim()
                if (domain.contains('.') && domain != "localhost") {
                    result.add(CustomFilterRule(pattern = domain, isAllowRule = false, sourceList = sourceName, addedAtMillis = now))
                }
            } else if (!strippedOptions.contains('/') && !strippedOptions.contains('*') && strippedOptions.contains('.')) {
                result.add(CustomFilterRule(pattern = strippedOptions.lowercase(), isAllowRule = false, sourceList = sourceName, addedAtMillis = now))
            }
        }
        return result.distinctBy { "${it.isAllowRule}:${it.pattern}" }.take(500)
    }

    /**
     * Cosmetic CSS injection script for ad-container hiding, cookie-consent banner hiding, and optional Force-Dark web pages.
     * Never hides `<video>`, `<audio>`, or video player containers.
     */
    fun buildCosmeticAndDarkModeScript(
        hideCookieBanners: Boolean,
        forceDarkWebPages: Boolean,
        adBlockingEnabled: Boolean = true
    ): String {
        if (!adBlockingEnabled && !hideCookieBanners && !forceDarkWebPages) return ""
        val cssParts = StringBuilder()
        if (adBlockingEnabled) {
            cssParts.append(
                """
                ins.adsbygoogle, .adsbygoogle,
                [id^="div-gpt-ad"], [id^="google_ads_iframe"],
                iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"],
                iframe[src*="adnxs.com"], iframe[src*="taboola.com"],
                .taboola-container, [id^="taboola-"],
                .OUTBRAIN, [data-widget-id^="outbrain"],
                .ad-banner:not(:has(video)), .ad-slot:not(:has(video)),
                [id*="ad-container"]:not(:has(video)) {
                    display: none !important;
                    visibility: hidden !important;
                    height: 0 !important;
                    min-height: 0 !important;
                    max-height: 0 !important;
                    overflow: hidden !important;
                    pointer-events: none !important;
                }
                """.trimIndent()
            )
        }
        if (hideCookieBanners) {
            cssParts.append(
                """
                #onetrust-consent-sdk, #onetrust-banner-sdk,
                #CybotCookiebotDialog, #CybotCookiebotDialogBodyUnderlay,
                .qc-cmp2-container, .qc-cmp-cleanslate,
                #truste-consent-track, .trustarc-banner,
                [id*="cookie-banner"], [class*="cookie-banner"],
                [id*="cookie-consent"], [class*="cookie-consent"],
                [aria-label*="cookie" i][role="dialog"],
                .cc-window, .cc-banner, #gdpr-cookie-message {
                    display: none !important;
                    visibility: hidden !important;
                    opacity: 0 !important;
                    pointer-events: none !important;
                }
                """.trimIndent()
            )
        }
        if (forceDarkWebPages) {
            cssParts.append(
                """
                html {
                    background-color: #0D1017 !important;
                    color: #E2E8F0 !important;
                }
                body {
                    background-color: #0D1017 !important;
                    color: #E2E8F0 !important;
                }
                a { color: #60A5FA !important; }
                """.trimIndent()
            )
        }
        val escapedCss = cssParts.toString().replace("\n", " ").replace("'", "\\'")
        return """
            (function() {
                var styleId = 'nyxora-cosmetic-style';
                var existing = document.getElementById(styleId);
                if (existing) existing.remove();
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = '$escapedCss';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }
}
