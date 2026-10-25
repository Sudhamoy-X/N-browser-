package com.example.domain.browser

import com.example.data.model.CustomFilterRule
import com.example.data.model.SiteSettingItem
import java.net.HttpURLConnection
import java.net.URL
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

    // Core high-confidence ad & tracker domains (EasyList / EasyPrivacy subset)
    private val BUILT_IN_BLOCKED_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
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
        "zedo.com"
    )

    private val BUILT_IN_BLOCKED_PATH_TOKENS = listOf(
        "/pagead/",
        "/adserver/",
        "/ads/banner",
        "/adframe.",
        "/doubleclick/",
        "/collect?v=1",
        "/pixel/track"
    )

    // Essential login, OAuth, SSO, and payment hosts that legitimately perform multi-step redirects
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

    @Volatile
    private var dynamicBlockedDomains: Set<String> = emptySet()

    @Volatile
    private var dynamicAllowDomains: Set<String> = emptySet()

    fun updateCustomRules(rules: List<CustomFilterRule>) {
        val blocked = mutableSetOf<String>()
        val allowed = mutableSetOf<String>()
        for (rule in rules) {
            val clean = rule.pattern.trim().lowercase()
                .removePrefix("||")
                .removePrefix("@@||")
                .substringBefore('^')
                .substringBefore('/')
            if (clean.isNotBlank()) {
                if (rule.isAllowRule || rule.pattern.startsWith("@@")) {
                    allowed.add(clean)
                } else {
                    blocked.add(clean)
                }
            }
        }
        dynamicBlockedDomains = blocked
        dynamicAllowDomains = allowed
    }

    fun getTotalActiveRuleCount(): Int {
        return BUILT_IN_BLOCKED_DOMAINS.size + BUILT_IN_BLOCKED_PATH_TOKENS.size + dynamicBlockedDomains.size
    }

    /**
     * Checks whether a subresource request should be blocked by the filter engine.
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

        // Do not block first-party same-domain essential assets unless explicitly an ad path token
        val pageHost = UrlAndSearchHandler.extractHost(pageUrl)

        // Check custom allowlist rules first
        if (matchesDomainSet(reqHost, dynamicAllowDomains)) return false

        // Check built-in and dynamic blocked domains
        if (matchesDomainSet(reqHost, BUILT_IN_BLOCKED_DOMAINS) || matchesDomainSet(reqHost, dynamicBlockedDomains)) {
            return true
        }

        // Check URL path tokens if third-party or explicit ad path
        val lowerUrl = requestUrl.lowercase()
        if (reqHost != pageHost || lowerUrl.contains("/pagead/") || lowerUrl.contains("/adserver/")) {
            for (token in BUILT_IN_BLOCKED_PATH_TOKENS) {
                if (lowerUrl.contains(token)) return true
            }
        }
        return false
    }

    private fun matchesDomainSet(host: String, domainSet: Set<String>): Boolean {
        if (host in domainSet) return true
        var current = host
        while (current.contains('.')) {
            current = current.substringAfter('.')
            if (current in domainSet) return true
        }
        return false
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
        if (targetUrl != null && targetUrl in allowOnceUrls) {
            return PopupDecision(allowed = true, reason = "Allowed once by user")
        }
        if (!globalPopupBlockingEnabled || siteSetting?.allowPopups == true) {
            return PopupDecision(allowed = true, reason = "Pop-up blocking disabled for site")
        }
        if (hasUserGesture) {
            return PopupDecision(allowed = true, reason = "Triggered by real user gesture")
        }
        return PopupDecision(allowed = false, reason = "Blocked automatic pop-up window")
    }

    /**
     * Evaluates top-level navigation requests in shouldOverrideUrlLoading.
     * Distinguishes normal user-initiated navigations, HTTP server redirects, OAuth/payment flows,
     * and non-gesture script/timer auto-redirects or external app launches.
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
        allowOnceUrls: Set<String>
    ): NavigationDecision {
        val trimmed = requestUrl.trim()
        if (trimmed in allowOnceUrls) return NavigationDecision.ALLOW

        val lower = trimmed.lowercase()
        // Block dangerous schemes from web pages
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:")) {
            return NavigationDecision.BLOCK_DANGEROUS_SCHEME
        }

        // External app schemes (intent://, market://, tel:, mailto:, etc.)
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("nyxora://") && !lower.startsWith("about:")) {
            val redirectBlockActive = globalRedirectBlockingEnabled && (siteSetting?.allowRedirects != true)
            val recentGesture = hasUserGesture || timeSinceLastUserInteractionMs < 1500L
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

        // Same-origin navigations and essential OAuth/SSO/payment flows are always allowed
        if (targetHost.isNotEmpty() && (targetHost == currentHost || matchesDomainSet(targetHost, ESSENTIAL_AUTH_AND_PAYMENT_HOSTS))) {
            return NavigationDecision.ALLOW
        }

        // Server-side 3xx redirects following a recent user navigation are normal web behavior
        val recentUserTouch = hasUserGesture || timeSinceLastUserInteractionMs < 2800L
        if (isServerRedirect && recentUserTouch) {
            return NavigationDecision.ALLOW
        }

        // Cross-origin navigation without user gesture or recent touch (e.g., timer/script/ad redirect)
        if (!recentUserTouch && currentHost.isNotEmpty() && currentHost != "nyxora.home") {
            return NavigationDecision.BLOCK_AUTO_REDIRECT
        }

        return NavigationDecision.ALLOW
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
                    seq.take(500).toList()
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
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            if (line.startsWith("@@||") && line.contains('^')) {
                val domain = line.removePrefix("@@||").substringBefore('^').substringBefore('/').trim()
                if (domain.contains('.') && !domain.contains('*')) {
                    result.add(CustomFilterRule(pattern = domain, isAllowRule = true, sourceList = sourceName, addedAtMillis = now))
                }
            } else if (line.startsWith("||") && line.contains('^')) {
                val domain = line.removePrefix("||").substringBefore('^').substringBefore('/').trim()
                if (domain.contains('.') && !domain.contains('*')) {
                    result.add(CustomFilterRule(pattern = domain, isAllowRule = false, sourceList = sourceName, addedAtMillis = now))
                }
            } else if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                val domain = line.substringAfter(' ').trim().substringBefore('#').trim()
                if (domain.contains('.') && domain != "localhost") {
                    result.add(CustomFilterRule(pattern = domain, isAllowRule = false, sourceList = sourceName, addedAtMillis = now))
                }
            }
        }
        return result.distinctBy { it.pattern }.take(250)
    }

    /**
     * Cosmetic CSS injection script for cookie-consent banner hiding and optional Force-Dark web pages.
     */
    fun buildCosmeticAndDarkModeScript(
        hideCookieBanners: Boolean,
        forceDarkWebPages: Boolean
    ): String {
        if (!hideCookieBanners && !forceDarkWebPages) return ""
        val cssParts = StringBuilder()
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
