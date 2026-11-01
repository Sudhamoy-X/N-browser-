package com.example.domain.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import com.example.data.model.CustomFilterRule
import com.example.data.model.SiteSettingItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ShieldRuleCategory(val label: String) {
    ADVERTISEMENT("Advertisement"),
    TRACKER("Tracker"),
    MALICIOUS_DNS("Malicious / DNS Guard")
}

enum class ShieldFilterListId(
    val id: String,
    val title: String,
    val description: String,
    val remoteUrl: String,
    val category: ShieldRuleCategory,
    val defaultEnabled: Boolean = true
) {
    ADGUARD_BASE(
        id = "adguard_base",
        title = "AdGuard Base Filter",
        description = "High-precision network and cosmetic rules for banners, video pre-roll slots, and ad frames.",
        remoteUrl = "https://filters.adtidy.org/extension/chromium/filters/2.txt",
        category = ShieldRuleCategory.ADVERTISEMENT,
        defaultEnabled = true
    ),
    EASYLIST(
        id = "easylist",
        title = "EasyList",
        description = "Industry-standard advertising domain, script, and ad-container filter rules.",
        remoteUrl = "https://easylist.to/easylist/easylist.txt",
        category = ShieldRuleCategory.ADVERTISEMENT,
        defaultEnabled = true
    ),
    EASYPRIVACY(
        id = "easyprivacy",
        title = "EasyPrivacy",
        description = "Blocks third-party analytics, behavioral trackers, session replay, and fingerprinting pixels.",
        remoteUrl = "https://easylist.to/easylist/easyprivacy.txt",
        category = ShieldRuleCategory.TRACKER,
        defaultEnabled = true
    ),
    PETER_LOWE(
        id = "peter_lowe",
        title = "Peter Lowe's Ad & Tracker List",
        description = "Reputable host blocklist targeting ad-serving and tracking infrastructure.",
        remoteUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        category = ShieldRuleCategory.ADVERTISEMENT,
        defaultEnabled = true
    ),
    MALVERTISING_GUARD(
        id = "malvertising_guard",
        title = "NYXORA Malvertising & DNS Guard",
        description = "Browser-level hostname protection against malicious ad domains, pop-unders, and phishing redirects.",
        remoteUrl = "https://filters.adtidy.org/extension/chromium/filters/208.txt",
        category = ShieldRuleCategory.MALICIOUS_DNS,
        defaultEnabled = true
    );

    companion object {
        fun defaultEnabledIds(): Set<String> = entries.filter { it.defaultEnabled }.mapTo( LinkedHashSet() ) { it.id }

        fun fromId(id: String): ShieldFilterListId? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}

enum class ShieldResourceType {
    DOCUMENT,
    SCRIPT,
    IMAGE,
    STYLESHEET,
    XMLHTTPREQUEST,
    SUBDOCUMENT,
    MEDIA,
    FONT,
    OTHER
}

enum class ShieldBlockOutcome {
    ALLOWED,
    BLOCKED_AD,
    BLOCKED_TRACKER,
    BLOCKED_MALICIOUS_DNS,
    BLOCKED_POPUP,
    BLOCKED_REDIRECT
}

data class ShieldEvaluationResult(
    val blocked: Boolean,
    val outcome: ShieldBlockOutcome,
    val matchedRule: String? = null,
    val matchedListId: String? = null
)

data class ShieldProtectionConfig(
    val shieldMasterEnabled: Boolean = true,
    val adBlockingEnabled: Boolean = true,
    val trackerProtectionEnabled: Boolean = true,
    val popupProtectionEnabled: Boolean = true,
    val redirectProtectionEnabled: Boolean = true,
    val cosmeticFilteringEnabled: Boolean = true,
    val urlTrackingProtectionEnabled: Boolean = true,
    val dnsProtectionEnabled: Boolean = true,
    val enabledFilterListIds: Set<String> = ShieldFilterListId.defaultEnabledIds(),
    val whitelistedHosts: Set<String> = emptySet()
)

data class ParsedFilterRule(
    val rawRule: String,
    val isAllowRule: Boolean,
    val domainPattern: String? = null,
    val urlTokenPattern: String? = null,
    val category: ShieldRuleCategory = ShieldRuleCategory.ADVERTISEMENT,
    val sourceListId: String = ShieldFilterListId.EASYLIST.id,
    val thirdPartyOnly: Boolean = false,
    val firstPartyOnly: Boolean = false,
    val allowedResourceTypes: Set<ShieldResourceType> = emptySet(),
    val excludedResourceTypes: Set<ShieldResourceType> = emptySet(),
    val includedPageDomains: Set<String> = emptySet(),
    val excludedPageDomains: Set<String> = emptySet()
)

data class CosmeticFilterRule(
    val selector: String,
    val isException: Boolean = false,
    val domains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val sourceListId: String = ShieldFilterListId.ADGUARD_BASE.id
)

data class UrlCleanResult(
    val cleanedUrl: String,
    val removedParams: List<String>,
    val wasModified: Boolean
)

data class FilterListStatusItem(
    val listId: ShieldFilterListId,
    val enabled: Boolean,
    val builtInRuleCount: Int,
    val remoteRuleCount: Int,
    val totalActiveRules: Int
)

data class FilterUpdateSummary(
    val updatedFromRemote: Boolean,
    val retainedLastWorkingDatabase: Boolean,
    val totalActiveRules: Int,
    val statusMessage: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * NYXORA Shield – Native Advanced Browser-Level Advertisement, Tracker, Malvertising,
 * Pop-up, Redirect, Cosmetic, and URL Parameter Protection Engine.
 *
 * Designed for zero-UI-blocking subresource evaluation, strict video & login compatibility,
 * and resilient filter list updates that retain the last working filter database on network errors.
 */
object NyxoraShieldEngine {

    // --- 1. BUILT-IN REPUTABLE FILTER LISTS ---

    private val ADGUARD_BASE_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "googletagservices.com",
        "2mdn.net",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "inmobi.com",
        "mgid.com",
        "revcontent.com",
        "teads.tv",
        "adform.net",
        "smartadserver.com",
        "a-ads.com",
        "bidgear.com",
        "aniview.com",
        "primis.tech",
        "connatix.com"
    )

    private val ADGUARD_BASE_PATH_TOKENS = listOf(
        "/pagead/",
        "/adsbygoogle",
        "/gpt/pubads",
        "/ad_iframe",
        "/adframe.",
        "/ads/banner",
        "/banner_ad",
        "/sponsor-banner",
        "/native-ad-"
    )

    private val EASYLIST_DOMAINS = setOf(
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "moatads.com",
        "amazon-adsystem.com",
        "zedo.com",
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
        "adroll.com",
        "yieldmo.com",
        "sharethrough.com",
        "kargo.com",
        "undertone.com",
        "flashtalking.com",
        "serving-sys.com",
        "sizmek.com"
    )

    private val EASYLIST_PATH_TOKENS = listOf(
        "/adserver/",
        "/doubleclick/",
        "/adunit/",
        "/adslots/",
        "/adbanner/",
        "/dfp/ads"
    )

    private val EASYPRIVACY_DOMAINS = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "scorecardresearch.com",
        "quantserve.com",
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
        "segment.com",
        "newrelic.com",
        "nr-data.net",
        "bugsnag.com",
        "smartlook.com",
        "bat.bing.com",
        "static.ads-twitter.com",
        "ads-api.twitter.com",
        "analytics.tiktok.com",
        "mc.yandex.ru",
        "connect.facebook.net",
        "pixel.facebook.com",
        "tr.snapchat.com",
        "ct.pinterest.com",
        "px.ads.linkedin.com",
        "snap.licdn.com",
        "omtrdc.net",
        "demdex.net",
        "2o7.net",
        "everesttech.net",
        "krxd.net",
        "bluekai.com",
        "bkrtx.com",
        "rlcdn.com",
        "tapad.com",
        "agkn.com",
        "mathtag.com",
        "turn.com",
        "eyeota.net",
        "chartbeat.com",
        "chartbeat.net",
        "parsely.com",
        "permutive.com",
        "kochava.com",
        "singular.net",
        "clevertap.com",
        "pardot.com",
        "mktoresp.com",
        "hs-analytics.net",
        "hs-scripts.com"
    )

    private val EASYPRIVACY_PATH_TOKENS = listOf(
        "/collect?v=1",
        "/g/collect?",
        "/pixel/track",
        "/fbevents.js",
        "/gtm.js",
        "/analytics.js",
        "/uwt.js",
        "/bat.js",
        "/insight.min.js"
    )

    private val PETER_LOWE_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "amazon-adsystem.com",
        "adform.net",
        "smartadserver.com",
        "zedo.com",
        "casalemedia.com",
        "media.net",
        "indexww.com",
        "triplelift.com",
        "sovrn.com",
        "lijit.com",
        "adroll.com",
        "celtra.com",
        "innovid.com",
        "spotxchange.com",
        "spotx.tv",
        "springserve.com",
        "tremorhub.com",
        "lkqd.net"
    )

    private val MALVERTISING_AND_DNS_GUARD_DOMAINS = setOf(
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "onclickads.net",
        "hilltopads.net",
        "tsyndicate.com",
        "trafficstars.com",
        "adcash.com",
        "clickadu.com",
        "adsterra.com",
        "richpush.co",
        "evadav.com",
        "pushground.com",
        "megapush.com",
        "rollerads.com",
        "galaksion.com",
        "zeropark.com",
        "bodis.com",
        "sedoparking.com",
        "trackwilltrk.com",
        "voluumtrk.com",
        "bemobtrk.com",
        "binomtrk.com",
        "redtrack.io",
        "malware-adserver.net",
        "phishing-redirect.example",
        "evil-malvertising.example",
        "scam-prize-winner.top",
        "fake-flash-update.xyz",
        "crypto-drainer-ads.io"
    )

    private val MALVERTISING_PATH_TOKENS = listOf(
        "/popunder",
        "/pu-script.js",
        "/pop-under.js",
        "/malvertising/"
    )

    // --- 2. ESSENTIAL AUTH, PAYMENT, BANKING, API & VIDEO COMPATIBILITY SAFELISTS ---

    internal val ESSENTIAL_AUTH_PAYMENT_BANKING_HOSTS = setOf(
        "accounts.google.com",
        "oauth2.googleapis.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "login.live.com",
        "account.microsoft.com",
        "github.com",
        "api.github.com",
        "auth0.com",
        "okta.com",
        "duosecurity.com",
        "onetap.google.com",
        "stripe.com",
        "js.stripe.com",
        "checkout.stripe.com",
        "hooks.stripe.com",
        "m.stripe.network",
        "paypal.com",
        "www.paypal.com",
        "paypalobjects.com",
        "braintreegateway.com",
        "adyen.com",
        "razorpay.com",
        "checkout.razorpay.com",
        "plaid.com",
        "cdn.plaid.com",
        "chase.com",
        "bankofamerica.com",
        "wellsfargo.com",
        "citibank.com",
        "citi.com",
        "capitalone.com",
        "americanexpress.com",
        "hsbc.com",
        "barclays.co.uk",
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net"
    )

    internal val SAFE_VIDEO_AND_ESSENTIAL_CDN_HOSTS = setOf(
        "googlevideo.com",
        "ytimg.com",
        "i.ytimg.com",
        "s.ytimg.com",
        "ggpht.com",
        "yt3.ggpht.com",
        "youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "vimeo.com",
        "player.vimeo.com",
        "vimeocdn.com",
        "jwpcdn.com",
        "jwplayer.com",
        "brightcove.com",
        "brightcove.net",
        "wistia.com",
        "wistia.net",
        "dailymotion.com",
        "dmcdn.net",
        "twitch.tv",
        "ttvnw.net",
        "jtvnw.net",
        "mux.com",
        "stream.mux.com",
        "cloudflarestream.com",
        "gstatic.com",
        "googleapis.com",
        "cloudflare.com",
        "jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com"
    )

    // Known URL tracking query parameters stripped by URL Tracking Protection
    private val TRACKING_QUERY_PARAMETERS = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_name",
        "utm_reader",
        "utm_viz_id",
        "utm_pubreferrer",
        "utm_swu",
        "fbclid",
        "gclid",
        "dclid",
        "gbraid",
        "wbraid",
        "msclkid",
        "twclid",
        "yclid",
        "ttclid",
        "li_fat_id",
        "epik",
        "mc_cid",
        "mc_eid",
        "igshid",
        "igsh",
        "_hsenc",
        "_hsmi",
        "__hssc",
        "__hstc",
        "__hsfp",
        "hsctatracking",
        "vero_id",
        "vero_conv",
        "oly_enc_id",
        "oly_anon_id",
        "rb_clickid",
        "s_kwcid",
        "wickedid",
        "mkt_tok",
        "_openstat"
    )

    // Sensitive auth/signed query parameters — if present on an auth/payment/signed URL, never mutate query
    private val PROTECTED_AUTH_OR_SIGNATURE_PARAMS = setOf(
        "code",
        "state",
        "id_token",
        "access_token",
        "refresh_token",
        "samlresponse",
        "samlrequest",
        " relaystate",
        "relaystate",
        "x-amz-signature",
        "x-amz-credential",
        "x-goog-signature",
        "sig",
        "signature",
        "oauth_token",
        "oauth_verifier"
    )

    // --- 3. RUNTIME CONFIG & CONCURRENT RULE INDEX ---

    @Volatile
    private var currentConfig: ShieldProtectionConfig = ShieldProtectionConfig()

    @Volatile
    private var customParsedRules: List<ParsedFilterRule> = emptyList()

    @Volatile
    private var customCosmeticRules: List<CosmeticFilterRule> = emptyList()

    @Volatile
    private var remoteRulesByListId: Map<String, List<ParsedFilterRule>> = emptyMap()

    @Volatile
    private var activeAdDomains: Set<String> = ADGUARD_BASE_DOMAINS + EASYLIST_DOMAINS + PETER_LOWE_DOMAINS

    @Volatile
    private var activeTrackerDomains: Set<String> = EASYPRIVACY_DOMAINS

    @Volatile
    private var activeMaliciousDnsDomains: Set<String> = MALVERTISING_AND_DNS_GUARD_DOMAINS

    @Volatile
    private var activeAllowDomains: Set<String> = emptySet()

    @Volatile
    private var activeAdvancedRules: List<ParsedFilterRule> = emptyList()

    private const val MAX_DECISION_CACHE_SIZE = 2048
    private val domainCategoryCache = ConcurrentHashMap<String, Int>(512)

    private const val FLAG_ALLOW_DOMAIN = 1
    private const val FLAG_SAFE_AUTH_OR_PAYMENT = 2
    private const val FLAG_SAFE_VIDEO_OR_CDN = 4
    private const val FLAG_AD_DOMAIN = 8
    private const val FLAG_TRACKER_DOMAIN = 16
    private const val FLAG_MALICIOUS_DNS_DOMAIN = 32

    // Live atomic statistics counters
    private val sessionAdsBlocked = AtomicLong(0L)
    private val sessionTrackersBlocked = AtomicLong(0L)
    private val sessionMaliciousDnsBlocked = AtomicLong(0L)
    private val sessionPopupsBlocked = AtomicLong(0L)
    private val sessionRedirectsBlocked = AtomicLong(0L)
    private val sessionTrackingUrlsCleaned = AtomicLong(0L)

    init {
        rebuildActiveRuleIndex()
    }

    fun getProtectionConfig(): ShieldProtectionConfig = currentConfig

    fun updateProtectionConfig(config: ShieldProtectionConfig) {
        val listChanged = currentConfig.enabledFilterListIds != config.enabledFilterListIds
        currentConfig = config.copy(
            whitelistedHosts = config.whitelistedHosts.mapTo(LinkedHashSet()) { normalizeDomain(it) }
                .filter { it.isNotEmpty() }
                .toSet()
        )
        if (listChanged) {
            rebuildActiveRuleIndex()
        } else {
            domainCategoryCache.clear()
        }
    }

    /**
     * Synchronizes custom & remotely cached rules from Room into NYXORA Shield's in-memory index.
     */
    fun syncCustomAndRemoteRules(rules: List<CustomFilterRule>) {
        val userRules = mutableListOf<ParsedFilterRule>()
        val userCosmetics = mutableListOf<CosmeticFilterRule>()
        val remoteByList = mutableMapOf<String, MutableList<ParsedFilterRule>>()

        for (rule in rules) {
            val raw = rule.pattern.trim()
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("[")) continue

            if (raw.contains("##") || raw.contains("#@#")) {
                parseCosmeticRule(raw, rule.sourceList)?.let { userCosmetics.add(it) }
                continue
            }

            val listId = resolveFilterListIdFromSource(rule.sourceList)
            val defaultCat = when (listId) {
                ShieldFilterListId.EASYPRIVACY.id -> ShieldRuleCategory.TRACKER
                ShieldFilterListId.MALVERTISING_GUARD.id -> ShieldRuleCategory.MALICIOUS_DNS
                else -> ShieldRuleCategory.ADVERTISEMENT
            }

            val parsed = parseNetworkFilterRule(
                rawLine = if (rule.isAllowRule && !raw.startsWith("@@")) "@@||$raw" else raw,
                sourceListId = listId ?: "user_custom",
                defaultCategory = defaultCat
            ) ?: continue

            if (listId != null && rule.sourceList.startsWith("Remote:", ignoreCase = true)) {
                remoteByList.getOrPut(listId) { mutableListOf() }.add(parsed)
            } else {
                userRules.add(parsed)
            }
        }

        customParsedRules = userRules
        customCosmeticRules = userCosmetics
        remoteRulesByListId = remoteByList
        rebuildActiveRuleIndex()
    }

    private fun resolveFilterListIdFromSource(sourceList: String): String? {
        val normalized = sourceList.trim().lowercase().removePrefix("remote:")
        return ShieldFilterListId.entries.firstOrNull {
            it.id == normalized || it.title.lowercase() == normalized
        }?.id
    }

    @Synchronized
    private fun rebuildActiveRuleIndex() {
        val enabledIds = currentConfig.enabledFilterListIds
        val adSet = LinkedHashSet<String>()
        val trackerSet = LinkedHashSet<String>()
        val dnsSet = LinkedHashSet<String>()
        val allowSet = LinkedHashSet<String>()
        val advancedList = mutableListOf<ParsedFilterRule>()

        if (ShieldFilterListId.ADGUARD_BASE.id in enabledIds) {
            adSet.addAll(ADGUARD_BASE_DOMAINS)
        }
        if (ShieldFilterListId.EASYLIST.id in enabledIds) {
            adSet.addAll(EASYLIST_DOMAINS)
        }
        if (ShieldFilterListId.PETER_LOWE.id in enabledIds) {
            adSet.addAll(PETER_LOWE_DOMAINS)
        }
        if (ShieldFilterListId.EASYPRIVACY.id in enabledIds) {
            trackerSet.addAll(EASYPRIVACY_DOMAINS)
        }
        if (ShieldFilterListId.MALVERTISING_GUARD.id in enabledIds) {
            dnsSet.addAll(MALVERTISING_AND_DNS_GUARD_DOMAINS)
        }

        // Include remotely fetched rules for enabled lists
        for ((listId, listRules) in remoteRulesByListId) {
            if (listId !in enabledIds) continue
            for (r in listRules) {
                indexRuleIntoSets(r, adSet, trackerSet, dnsSet, allowSet, advancedList)
            }
        }

        // Always include user custom rules
        for (r in customParsedRules) {
            indexRuleIntoSets(r, adSet, trackerSet, dnsSet, allowSet, advancedList)
        }

        activeAdDomains = adSet
        activeTrackerDomains = trackerSet
        activeMaliciousDnsDomains = dnsSet
        activeAllowDomains = allowSet
        activeAdvancedRules = advancedList
        domainCategoryCache.clear()
    }

    private fun indexRuleIntoSets(
        rule: ParsedFilterRule,
        adSet: MutableSet<String>,
        trackerSet: MutableSet<String>,
        dnsSet: MutableSet<String>,
        allowSet: MutableSet<String>,
        advancedList: MutableList<ParsedFilterRule>
    ) {
        val hasModifiers = rule.thirdPartyOnly ||
            rule.firstPartyOnly ||
            rule.allowedResourceTypes.isNotEmpty() ||
            rule.excludedResourceTypes.isNotEmpty() ||
            rule.includedPageDomains.isNotEmpty() ||
            rule.excludedPageDomains.isNotEmpty() ||
            !rule.urlTokenPattern.isNullOrBlank()

        if (!hasModifiers && !rule.domainPattern.isNullOrBlank()) {
            if (rule.isAllowRule) {
                allowSet.add(rule.domainPattern)
            } else {
                when (rule.category) {
                    ShieldRuleCategory.ADVERTISEMENT -> adSet.add(rule.domainPattern)
                    ShieldRuleCategory.TRACKER -> trackerSet.add(rule.domainPattern)
                    ShieldRuleCategory.MALICIOUS_DNS -> dnsSet.add(rule.domainPattern)
                }
            }
        } else {
            advancedList.add(rule)
        }
    }

    fun getTotalActiveRuleCount(): Int {
        val enabled = currentConfig.enabledFilterListIds
        var count = 0
        if (ShieldFilterListId.ADGUARD_BASE.id in enabled) {
            count += ADGUARD_BASE_DOMAINS.size + ADGUARD_BASE_PATH_TOKENS.size
        }
        if (ShieldFilterListId.EASYLIST.id in enabled) {
            count += EASYLIST_DOMAINS.size + EASYLIST_PATH_TOKENS.size
        }
        if (ShieldFilterListId.EASYPRIVACY.id in enabled) {
            count += EASYPRIVACY_DOMAINS.size + EASYPRIVACY_PATH_TOKENS.size
        }
        if (ShieldFilterListId.PETER_LOWE.id in enabled) {
            count += PETER_LOWE_DOMAINS.size
        }
        if (ShieldFilterListId.MALVERTISING_GUARD.id in enabled) {
            count += MALVERTISING_AND_DNS_GUARD_DOMAINS.size + MALVERTISING_PATH_TOKENS.size
        }
        for ((listId, rules) in remoteRulesByListId) {
            if (listId in enabled) {
                count += rules.size
            }
        }
        count += customParsedRules.size + customCosmeticRules.size
        return count
    }

    fun getFilterListStatuses(): List<FilterListStatusItem> {
        val enabled = currentConfig.enabledFilterListIds
        return ShieldFilterListId.entries.map { list ->
            val builtInCount = when (list) {
                ShieldFilterListId.ADGUARD_BASE -> ADGUARD_BASE_DOMAINS.size + ADGUARD_BASE_PATH_TOKENS.size
                ShieldFilterListId.EASYLIST -> EASYLIST_DOMAINS.size + EASYLIST_PATH_TOKENS.size
                ShieldFilterListId.EASYPRIVACY -> EASYPRIVACY_DOMAINS.size + EASYPRIVACY_PATH_TOKENS.size
                ShieldFilterListId.PETER_LOWE -> PETER_LOWE_DOMAINS.size
                ShieldFilterListId.MALVERTISING_GUARD -> MALVERTISING_AND_DNS_GUARD_DOMAINS.size + MALVERTISING_PATH_TOKENS.size
            }
            val remoteCount = remoteRulesByListId[list.id]?.size ?: 0
            val isEnabled = list.id in enabled
            FilterListStatusItem(
                listId = list,
                enabled = isEnabled,
                builtInRuleCount = builtInCount,
                remoteRuleCount = remoteCount,
                totalActiveRules = if (isEnabled) builtInCount + remoteCount else 0
            )
        }
    }

    /**
     * Checks whether a website host is whitelisted in NYXORA Shield or per-site settings.
     */
    fun isWebsiteWhitelisted(
        pageHostOrUrl: String,
        siteSetting: SiteSettingItem? = null,
        whitelistedHosts: Set<String> = currentConfig.whitelistedHosts
    ): Boolean {
        if (siteSetting?.allowAds == true) return true
        val cleanHost = normalizeDomain(
            if (pageHostOrUrl.contains("://")) UrlAndSearchHandler.extractHost(pageHostOrUrl) else pageHostOrUrl
        )
        if (cleanHost.isEmpty()) return false
        if (matchesDomainSet(cleanHost, whitelistedHosts)) return true
        if (activeAllowDomains.isNotEmpty() && matchesDomainSet(cleanHost, activeAllowDomains)) return true
        return false
    }

    // --- 4. RESOURCE TYPE & REQUEST EVALUATION ---

    fun inferResourceType(
        requestUrl: String,
        requestHeaders: Map<String, String> = emptyMap(),
        isForMainFrame: Boolean = false
    ): ShieldResourceType {
        if (isForMainFrame) return ShieldResourceType.DOCUMENT

        val secDest = (requestHeaders["Sec-Fetch-Dest"] ?: requestHeaders["sec-fetch-dest"]).orEmpty().lowercase()
        val accept = (requestHeaders["Accept"] ?: requestHeaders["accept"]).orEmpty().lowercase()
        val requestedWith = (requestHeaders["X-Requested-With"] ?: requestHeaders["x-requested-with"]).orEmpty().lowercase()

        when (secDest) {
            "document" -> return ShieldResourceType.DOCUMENT
            "iframe", "frame", "subdocument" -> return ShieldResourceType.SUBDOCUMENT
            "script" -> return ShieldResourceType.SCRIPT
            "style" -> return ShieldResourceType.STYLESHEET
            "image" -> return ShieldResourceType.IMAGE
            "video", "audio", "track" -> return ShieldResourceType.MEDIA
            "font" -> return ShieldResourceType.FONT
            "empty" -> return ShieldResourceType.XMLHTTPREQUEST
        }

        if (requestedWith == "xmlhttprequest" || accept.contains("application/json")) {
            return ShieldResourceType.XMLHTTPREQUEST
        }
        if (accept.startsWith("text/css")) return ShieldResourceType.STYLESHEET
        if (accept.startsWith("image/")) return ShieldResourceType.IMAGE
        if (accept.startsWith("video/") || accept.startsWith("audio/")) return ShieldResourceType.MEDIA

        val path = requestUrl.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") || path.contains("/js/") -> ShieldResourceType.SCRIPT
            path.endsWith(".css") -> ShieldResourceType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") -> ShieldResourceType.IMAGE
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") ||
                path.endsWith(".mpd") || path.endsWith(".ts") || path.endsWith(".m4s") ||
                path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".ogg") ||
                path.contains("/videoplayback") -> ShieldResourceType.MEDIA
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") -> ShieldResourceType.FONT
            path.endsWith(".html") || path.endsWith(".htm") -> ShieldResourceType.SUBDOCUMENT
            else -> ShieldResourceType.OTHER
        }
    }

    /**
     * Evaluates a subresource network request against NYXORA Shield's multi-layer protection engine.
     */
    fun evaluateSubresourceRequest(
        requestUrl: String,
        pageUrl: String,
        resourceType: ShieldResourceType = inferResourceType(requestUrl),
        config: ShieldProtectionConfig = currentConfig,
        siteSetting: SiteSettingItem? = null,
        isPrivateTab: Boolean = false
    ): ShieldEvaluationResult {
        if (!config.shieldMasterEnabled) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }
        if (!config.adBlockingEnabled && !config.trackerProtectionEnabled && !config.dnsProtectionEnabled) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }

        val pageHost = UrlAndSearchHandler.extractHost(pageUrl)
        if (isWebsiteWhitelisted(pageHost, siteSetting, config.whitelistedHosts)) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }

        val reqHost = UrlAndSearchHandler.extractHost(requestUrl)
        if (reqHost.isEmpty()) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }

        val startNs = System.nanoTime()
        val result = evaluateSubresourceInternal(
            requestUrl = requestUrl,
            reqHost = reqHost,
            pageHost = pageHost,
            resourceType = resourceType,
            config = config
        )
        val elapsedNs = System.nanoTime() - startNs

        // Record non-URL-exposing telemetry (never logs private browsing URLs)
        BrowserDiagnosticsEngine.recordSubresourceCheck(elapsedNs, result.blocked)
        if (result.blocked) {
            when (result.outcome) {
                ShieldBlockOutcome.BLOCKED_AD -> sessionAdsBlocked.incrementAndGet()
                ShieldBlockOutcome.BLOCKED_TRACKER -> sessionTrackersBlocked.incrementAndGet()
                ShieldBlockOutcome.BLOCKED_MALICIOUS_DNS -> sessionMaliciousDnsBlocked.incrementAndGet()
                else -> Unit
            }
        }
        return result
    }

    fun evaluateWebResourceRequest(
        request: WebResourceRequest,
        pageUrl: String,
        config: ShieldProtectionConfig = currentConfig,
        siteSetting: SiteSettingItem? = null,
        isPrivateTab: Boolean = false
    ): ShieldEvaluationResult {
        if (request.isForMainFrame) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }
        val reqUrl = request.url?.toString() ?: return ShieldEvaluationResult(false, ShieldBlockOutcome.ALLOWED)
        val headers = request.requestHeaders ?: emptyMap()
        val resourceType = inferResourceType(reqUrl, headers, isForMainFrame = false)
        return evaluateSubresourceRequest(
            requestUrl = reqUrl,
            pageUrl = pageUrl,
            resourceType = resourceType,
            config = config,
            siteSetting = siteSetting,
            isPrivateTab = isPrivateTab
        )
    }

    private fun evaluateSubresourceInternal(
        requestUrl: String,
        reqHost: String,
        pageHost: String,
        resourceType: ShieldResourceType,
        config: ShieldProtectionConfig
    ): ShieldEvaluationResult {
        // 1. Protect all video/audio media streams unconditionally
        if (resourceType == ShieldResourceType.MEDIA || isProtectedVideoStreamOrPlayerRequest(requestUrl, reqHost)) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }

        // 2. Check domain flags from concurrent cache
        val reqFlags = getOrComputeDomainFlags(reqHost)
        if ((reqFlags and FLAG_ALLOW_DOMAIN) != 0) {
            return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
        }

        val isThirdParty = pageHost.isNotEmpty() && !isSameRegistrableDomain(reqHost, pageHost)
        val lowerUrl = requestUrl.lowercase()

        // 3. Evaluate advanced/modifier rules (allow rules take priority over block rules)
        val advRules = activeAdvancedRules
        if (advRules.isNotEmpty()) {
            var matchedBlockRule: ParsedFilterRule? = null
            for (rule in advRules) {
                if (matchesParsedFilterRule(rule, lowerUrl, reqHost, pageHost, isThirdParty, resourceType)) {
                    if (rule.isAllowRule) {
                        return ShieldEvaluationResult(
                            blocked = false,
                            outcome = ShieldBlockOutcome.ALLOWED,
                            matchedRule = rule.rawRule,
                            matchedListId = rule.sourceListId
                        )
                    } else if (matchedBlockRule == null && isCategoryEnabled(rule.category, config)) {
                        matchedBlockRule = rule
                    }
                }
            }
            if (matchedBlockRule != null) {
                // Verify it does not break essential auth/payment/video before blocking
                if (!isProtectedAuthPaymentOrEssentialApi(lowerUrl, reqHost, pageHost)) {
                    return ShieldEvaluationResult(
                        blocked = true,
                        outcome = categoryToOutcome(matchedBlockRule.category),
                        matchedRule = matchedBlockRule.rawRule,
                        matchedListId = matchedBlockRule.sourceListId
                    )
                }
            }
        }

        val isExplicitAdPath = lowerUrl.contains("/pagead/") ||
            lowerUrl.contains("/adserver/") ||
            lowerUrl.contains("/adsbygoogle") ||
            lowerUrl.contains("/gpt/pubads") ||
            lowerUrl.contains("/doubleclick/") ||
            lowerUrl.contains("/ad_iframe") ||
            lowerUrl.contains("/adframe.")

        // 4. Never block essential login, OAuth, SSO, payments, banking, or core CDNs unless an explicit /pagead/ path
        if (!isExplicitAdPath) {
            if ((reqFlags and FLAG_SAFE_AUTH_OR_PAYMENT) != 0 ||
                (reqFlags and FLAG_SAFE_VIDEO_OR_CDN) != 0 ||
                isProtectedAuthPaymentOrEssentialApi(lowerUrl, reqHost, pageHost)
            ) {
                return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
            }
        }

        // 5. Malicious advertising / DNS Guard check
        if (config.dnsProtectionEnabled && (reqFlags and FLAG_MALICIOUS_DNS_DOMAIN) != 0) {
            return ShieldEvaluationResult(
                blocked = true,
                outcome = ShieldBlockOutcome.BLOCKED_MALICIOUS_DNS,
                matchedRule = "||$reqHost^",
                matchedListId = ShieldFilterListId.MALVERTISING_GUARD.id
            )
        }

        // 6. Third-party Tracker domain check
        if (config.trackerProtectionEnabled && (reqFlags and FLAG_TRACKER_DOMAIN) != 0) {
            // Only block if third-party or dedicated analytics subdomain
            if (isThirdParty || reqHost.startsWith("analytics.") || reqHost.startsWith("pixel.") || reqHost.startsWith("metrics.")) {
                return ShieldEvaluationResult(
                    blocked = true,
                    outcome = ShieldBlockOutcome.BLOCKED_TRACKER,
                    matchedRule = "||$reqHost^",
                    matchedListId = ShieldFilterListId.EASYPRIVACY.id
                )
            }
        }

        // 7. Advertising domain check
        if (config.adBlockingEnabled && (reqFlags and FLAG_AD_DOMAIN) != 0) {
            return ShieldEvaluationResult(
                blocked = true,
                outcome = ShieldBlockOutcome.BLOCKED_AD,
                matchedRule = "||$reqHost^",
                matchedListId = ShieldFilterListId.EASYLIST.id
            )
        }

        // 8. Path token checks (respecting enabled lists & first-party safety)
        val enabledLists = config.enabledFilterListIds
        if (config.dnsProtectionEnabled && ShieldFilterListId.MALVERTISING_GUARD.id in enabledLists) {
            for (token in MALVERTISING_PATH_TOKENS) {
                if (lowerUrl.contains(token)) {
                    return ShieldEvaluationResult(
                        blocked = true,
                        outcome = ShieldBlockOutcome.BLOCKED_MALICIOUS_DNS,
                        matchedRule = token,
                        matchedListId = ShieldFilterListId.MALVERTISING_GUARD.id
                    )
                }
            }
        }

        if (config.trackerProtectionEnabled && isThirdParty && ShieldFilterListId.EASYPRIVACY.id in enabledLists) {
            for (token in EASYPRIVACY_PATH_TOKENS) {
                if (lowerUrl.contains(token)) {
                    return ShieldEvaluationResult(
                        blocked = true,
                        outcome = ShieldBlockOutcome.BLOCKED_TRACKER,
                        matchedRule = token,
                        matchedListId = ShieldFilterListId.EASYPRIVACY.id
                    )
                }
            }
        }

        if (config.adBlockingEnabled && (isThirdParty || isExplicitAdPath)) {
            if (ShieldFilterListId.ADGUARD_BASE.id in enabledLists) {
                for (token in ADGUARD_BASE_PATH_TOKENS) {
                    if (lowerUrl.contains(token)) {
                        return ShieldEvaluationResult(
                            blocked = true,
                            outcome = ShieldBlockOutcome.BLOCKED_AD,
                            matchedRule = token,
                            matchedListId = ShieldFilterListId.ADGUARD_BASE.id
                        )
                    }
                }
            }
            if (ShieldFilterListId.EASYLIST.id in enabledLists) {
                for (token in EASYLIST_PATH_TOKENS) {
                    if (lowerUrl.contains(token)) {
                        return ShieldEvaluationResult(
                            blocked = true,
                            outcome = ShieldBlockOutcome.BLOCKED_AD,
                            matchedRule = token,
                            matchedListId = ShieldFilterListId.EASYLIST.id
                        )
                    }
                }
            }
        }

        return ShieldEvaluationResult(blocked = false, outcome = ShieldBlockOutcome.ALLOWED)
    }

    private fun isCategoryEnabled(category: ShieldRuleCategory, config: ShieldProtectionConfig): Boolean {
        return when (category) {
            ShieldRuleCategory.ADVERTISEMENT -> config.adBlockingEnabled
            ShieldRuleCategory.TRACKER -> config.trackerProtectionEnabled
            ShieldRuleCategory.MALICIOUS_DNS -> config.dnsProtectionEnabled
        }
    }

    private fun categoryToOutcome(category: ShieldRuleCategory): ShieldBlockOutcome {
        return when (category) {
            ShieldRuleCategory.ADVERTISEMENT -> ShieldBlockOutcome.BLOCKED_AD
            ShieldRuleCategory.TRACKER -> ShieldBlockOutcome.BLOCKED_TRACKER
            ShieldRuleCategory.MALICIOUS_DNS -> ShieldBlockOutcome.BLOCKED_MALICIOUS_DNS
        }
    }

    /**
     * Ensures YouTube and HTML5 video playback, player scripts, thumbnails, and media segments are never blocked.
     */
    fun isProtectedVideoStreamOrPlayerRequest(requestUrl: String, reqHost: String = UrlAndSearchHandler.extractHost(requestUrl)): Boolean {
        val cleanHost = reqHost.lowercase()
        if (cleanHost.isEmpty()) return false
        val lowerUrl = requestUrl.lowercase()

        // Never block YouTube video chunks, player APIs, or player assets
        if (cleanHost == "googlevideo.com" || cleanHost.endsWith(".googlevideo.com") ||
            cleanHost == "ytimg.com" || cleanHost.endsWith(".ytimg.com") ||
            cleanHost == "ggpht.com" || cleanHost.endsWith(".ggpht.com")
        ) {
            return true
        }

        if (cleanHost == "youtube.com" || cleanHost.endsWith(".youtube.com") ||
            cleanHost == "youtu.be" || cleanHost == "youtube-nocookie.com" ||
            cleanHost.endsWith(".youtube-nocookie.com")
        ) {
            // Allow all YouTube player, watch, embed, and API endpoints; only explicit /pagead/ or /ptracking is not protected
            if (lowerUrl.contains("/pagead/") || lowerUrl.contains("/ptracking")) {
                return false
            }
            return true
        }

        // Other HTML5 video CDNs
        if (cleanHost == "vimeo.com" || cleanHost.endsWith(".vimeo.com") ||
            cleanHost == "vimeocdn.com" || cleanHost.endsWith(".vimeocdn.com") ||
            cleanHost == "jwplayer.com" || cleanHost.endsWith(".jwplayer.com") ||
            cleanHost == "jwpcdn.com" || cleanHost.endsWith(".jwpcdn.com") ||
            cleanHost == "wistia.com" || cleanHost.endsWith(".wistia.com") ||
            cleanHost == "wistia.net" || cleanHost.endsWith(".wistia.net") ||
            cleanHost == "dailymotion.com" || cleanHost.endsWith(".dailymotion.com") ||
            cleanHost == "dmcdn.net" || cleanHost.endsWith(".dmcdn.net") ||
            cleanHost == "ttvnw.net" || cleanHost.endsWith(".ttvnw.net") ||
            cleanHost == "mux.com" || cleanHost.endsWith(".mux.com")
        ) {
            return true
        }

        // Direct media file extensions
        val path = lowerUrl.substringBefore('?').substringBefore('#')
        if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".webm") || path.endsWith(".m4s") || path.endsWith(".ts") ||
            path.contains("/videoplayback")
        ) {
            return true
        }

        return false
    }

    /**
     * Ensures login, OAuth, SSO, banking, payment gateways, and security challenges never break.
     */
    fun isProtectedAuthPaymentOrEssentialApi(
        lowerUrl: String,
        reqHost: String,
        pageHost: String
    ): Boolean {
        if (matchesDomainSet(reqHost, ESSENTIAL_AUTH_PAYMENT_BANKING_HOSTS) ||
            matchesDomainSet(pageHost, ESSENTIAL_AUTH_PAYMENT_BANKING_HOSTS)
        ) {
            return true
        }
        // First-party API, login, session, checkout, and payment endpoints must never be blocked
        val isFirstParty = pageHost.isNotEmpty() && isSameRegistrableDomain(reqHost, pageHost)
        if (isFirstParty) {
            if (lowerUrl.contains("/login") ||
                lowerUrl.contains("/signin") ||
                lowerUrl.contains("/sign-in") ||
                lowerUrl.contains("/auth") ||
                lowerUrl.contains("/oauth") ||
                lowerUrl.contains("/sso") ||
                lowerUrl.contains("/saml") ||
                lowerUrl.contains("/session") ||
                lowerUrl.contains("/checkout") ||
                lowerUrl.contains("/payment") ||
                lowerUrl.contains("/billing") ||
                lowerUrl.contains("/cart") ||
                lowerUrl.contains("/api/") ||
                lowerUrl.contains("/graphql")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether a top-level navigation target is a known malicious advertising or DNS-blocked host.
     */
    fun isBlockedTopLevelMaliciousOrAdDomain(
        targetHost: String,
        pageHost: String = "",
        config: ShieldProtectionConfig = currentConfig,
        siteSetting: SiteSettingItem? = null
    ): Boolean {
        if (!config.shieldMasterEnabled) return false
        val cleanTarget = normalizeDomain(targetHost)
        if (cleanTarget.isEmpty()) return false
        if (isWebsiteWhitelisted(cleanTarget, siteSetting, config.whitelistedHosts) ||
            (pageHost.isNotEmpty() && isWebsiteWhitelisted(pageHost, siteSetting, config.whitelistedHosts))
        ) {
            return false
        }
        val flags = getOrComputeDomainFlags(cleanTarget)
        if ((flags and FLAG_ALLOW_DOMAIN) != 0 ||
            (flags and FLAG_SAFE_AUTH_OR_PAYMENT) != 0 ||
            (flags and FLAG_SAFE_VIDEO_OR_CDN) != 0
        ) {
            return false
        }
        if (config.dnsProtectionEnabled && (flags and FLAG_MALICIOUS_DNS_DOMAIN) != 0) {
            return true
        }
        if (config.adBlockingEnabled && (flags and FLAG_AD_DOMAIN) != 0) {
            return true
        }
        return false
    }

    private fun getOrComputeDomainFlags(host: String): Int {
        val clean = normalizeDomain(host)
        if (clean.isEmpty()) return 0
        domainCategoryCache[clean]?.let { return it }

        var flags = 0
        if (activeAllowDomains.isNotEmpty() && matchesDomainSet(clean, activeAllowDomains)) {
            flags = flags or FLAG_ALLOW_DOMAIN
        }
        if (matchesDomainSet(clean, ESSENTIAL_AUTH_PAYMENT_BANKING_HOSTS)) {
            flags = flags or FLAG_SAFE_AUTH_OR_PAYMENT
        }
        if (matchesDomainSet(clean, SAFE_VIDEO_AND_ESSENTIAL_CDN_HOSTS)) {
            flags = flags or FLAG_SAFE_VIDEO_OR_CDN
        }
        if (matchesDomainSet(clean, activeMaliciousDnsDomains)) {
            flags = flags or FLAG_MALICIOUS_DNS_DOMAIN
        }
        if (matchesDomainSet(clean, activeTrackerDomains)) {
            flags = flags or FLAG_TRACKER_DOMAIN
        }
        if (matchesDomainSet(clean, activeAdDomains)) {
            flags = flags or FLAG_AD_DOMAIN
        }

        if (domainCategoryCache.size >= MAX_DECISION_CACHE_SIZE) {
            domainCategoryCache.clear()
        }
        domainCategoryCache[clean] = flags
        return flags
    }

    private fun matchesParsedFilterRule(
        rule: ParsedFilterRule,
        lowerUrl: String,
        reqHost: String,
        pageHost: String,
        isThirdParty: Boolean,
        resourceType: ShieldResourceType
    ): Boolean {
        if (rule.thirdPartyOnly && !isThirdParty) return false
        if (rule.firstPartyOnly && isThirdParty) return false

        if (rule.allowedResourceTypes.isNotEmpty() && resourceType !in rule.allowedResourceTypes) {
            return false
        }
        if (rule.excludedResourceTypes.isNotEmpty() && resourceType in rule.excludedResourceTypes) {
            return false
        }

        if (rule.excludedPageDomains.isNotEmpty() && pageHost.isNotEmpty() &&
            matchesDomainSet(pageHost, rule.excludedPageDomains)
        ) {
            return false
        }
        if (rule.includedPageDomains.isNotEmpty()) {
            if (pageHost.isEmpty() || !matchesDomainSet(pageHost, rule.includedPageDomains)) {
                return false
            }
        }

        val domainPat = rule.domainPattern
        if (!domainPat.isNullOrBlank()) {
            if (!matchesDomain(reqHost, domainPat)) return false
        }

        val tokenPat = rule.urlTokenPattern
        if (!tokenPat.isNullOrBlank()) {
            if (!lowerUrl.contains(tokenPat)) return false
        }

        return !domainPat.isNullOrBlank() || !tokenPat.isNullOrBlank()
    }

    // --- 5. URL TRACKING PARAMETER PROTECTION ---

    /**
     * Strips known marketing/tracking query parameters (`utm_*`, `fbclid`, `gclid`, `msclkid`, `mc_eid`, `igshid`, etc.)
     * while strictly preserving functional parameters (`v=`, `q=`, `t=`, `list=`, `id=`, `page=`) and never mutating
     * OAuth/SSO/payment/signed URLs.
     */
    fun cleanUrlTrackingParameters(
        rawUrl: String,
        config: ShieldProtectionConfig = currentConfig,
        siteSetting: SiteSettingItem? = null
    ): UrlCleanResult {
        val trimmed = rawUrl.trim()
        if (!config.shieldMasterEnabled || !config.urlTrackingProtectionEnabled) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }
        if (!trimmed.contains('?')) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }

        val host = UrlAndSearchHandler.extractHost(trimmed)
        if (host.isEmpty() || isWebsiteWhitelisted(host, siteSetting, config.whitelistedHosts)) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }
        if (matchesDomainSet(host, ESSENTIAL_AUTH_PAYMENT_BANKING_HOSTS)) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }

        val lowerUrl = trimmed.lowercase()
        val pathPart = lowerUrl.substringBefore('?')
        if (pathPart.contains("/oauth") ||
            pathPart.contains("/authorize") ||
            pathPart.contains("/login") ||
            pathPart.contains("/signin") ||
            pathPart.contains("/callback") ||
            pathPart.contains("/saml") ||
            pathPart.contains("/checkout") ||
            pathPart.contains("/payment")
        ) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }

        val beforeQuery = trimmed.substringBefore('?')
        val afterQuestion = trimmed.substringAfter('?')
        val queryPart = afterQuestion.substringBefore('#')
        val fragmentPart = if (afterQuestion.contains('#')) "#" + afterQuestion.substringAfter('#') else ""

        if (queryPart.isBlank()) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }

        val pairs = queryPart.split('&')
        val retainedPairs = mutableListOf<String>()
        val strippedKeys = mutableListOf<String>()

        for (pair in pairs) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            if (key in PROTECTED_AUTH_OR_SIGNATURE_PARAMS) {
                // Signed or OAuth URL: abort stripping so signature validation never fails
                return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
            }
            if (isTrackingParameterName(key)) {
                strippedKeys.add(pair.substringBefore('='))
            } else {
                retainedPairs.add(pair)
            }
        }

        if (strippedKeys.isEmpty()) {
            return UrlCleanResult(cleanedUrl = trimmed, removedParams = emptyList(), wasModified = false)
        }

        sessionTrackingUrlsCleaned.addAndGet(strippedKeys.size.toLong())
        val rebuiltQuery = if (retainedPairs.isEmpty()) "" else "?" + retainedPairs.joinToString("&")
        val cleaned = beforeQuery + rebuiltQuery + fragmentPart
        return UrlCleanResult(
            cleanedUrl = cleaned,
            removedParams = strippedKeys,
            wasModified = cleaned != trimmed
        )
    }

    internal fun isTrackingParameterName(lowerKey: String): Boolean {
        if (lowerKey in TRACKING_QUERY_PARAMETERS) return true
        if (lowerKey.startsWith("utm_")) return true
        if (lowerKey.startsWith("hsa_")) return true
        if (lowerKey.startsWith("matomo_") || lowerKey.startsWith("pk_campaign") || lowerKey.startsWith("pk_source") || lowerKey.startsWith("pk_kwd")) {
            return true
        }
        return false
    }

    // --- 6. SAFE COSMETIC FILTERING ---

    /**
     * Builds the safe cosmetic CSS & dark-mode injection script for a given page URL.
     * Never hides `<video>`, `<audio>`, `#movie_player`, `.html5-video-player`, or login/checkout forms.
     */
    fun buildSafeCosmeticScript(
        pageUrl: String = "",
        hideCookieBanners: Boolean = true,
        forceDarkWebPages: Boolean = false,
        adBlockingEnabled: Boolean = currentConfig.adBlockingEnabled,
        cosmeticFilteringEnabled: Boolean = currentConfig.cosmeticFilteringEnabled,
        shieldMasterEnabled: Boolean = currentConfig.shieldMasterEnabled,
        siteSetting: SiteSettingItem? = null
    ): String {
        val pageHost = UrlAndSearchHandler.extractHost(pageUrl)
        val siteWhitelisted = isWebsiteWhitelisted(pageHost, siteSetting, currentConfig.whitelistedHosts)
        val isEssentialAuthOrBank = pageHost.isNotEmpty() && matchesDomainSet(pageHost, ESSENTIAL_AUTH_PAYMENT_BANKING_HOSTS)
        val isYouTube = pageHost == "youtube.com" || pageHost.endsWith(".youtube.com") || pageHost == "youtu.be"

        val applyAdCosmetics = shieldMasterEnabled &&
            adBlockingEnabled &&
            cosmeticFilteringEnabled &&
            !siteWhitelisted &&
            !isEssentialAuthOrBank

        val applyCookieCosmetics = hideCookieBanners && !siteWhitelisted && !isEssentialAuthOrBank

        if (!applyAdCosmetics && !applyCookieCosmetics && !forceDarkWebPages) return ""

        val cssParts = StringBuilder()
        if (applyAdCosmetics) {
            if (isYouTube) {
                // On YouTube, only hide non-player companion/masthead ad slots so #movie_player and video controls are 100% untouched
                cssParts.append(
                    """
                    ytd-ad-slot-renderer, ytm-promoted-sparkles-web-renderer,
                    ytm-companion-ad-renderer, ytd-InFeedAdLayoutRenderer,
                    ytd-banner-promo-renderer, #masthead-ad,
                    ins.adsbygoogle, .adsbygoogle {
                        display: none !important;
                    }
                    """.trimIndent()
                )
            } else {
                cssParts.append(
                    """
                    ins.adsbygoogle, .adsbygoogle,
                    [id^="div-gpt-ad"]:not(:has(video)), [id^="google_ads_iframe"]:not(:has(video)),
                    iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"],
                    iframe[src*="adnxs.com"], iframe[src*="taboola.com"],
                    iframe[src*="outbrain.com"], iframe[src*="criteo.com"],
                    .taboola-container:not(:has(video)), [id^="taboola-"]:not(:has(video)),
                    .OUTBRAIN:not(:has(video)), [data-widget-id^="outbrain"]:not(:has(video)),
                    .mgbox:not(:has(video)), [id^="mgid_"]:not(:has(video)),
                    .ad-banner:not(:has(video)), .ad-slot:not(:has(video)),
                    .advertisement-box:not(:has(video)), .sponsored-banner:not(:has(video)),
                    [id*="ad-container"]:not(:has(video)):not(#movie_player):not(.html5-video-player) {
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

                // Append matching user/custom cosmetic selectors for this domain
                val extraSelectors = resolveCustomCosmeticSelectorsForHost(pageHost)
                if (extraSelectors.isNotEmpty()) {
                    cssParts.append(" ")
                    cssParts.append(extraSelectors.joinToString(", "))
                    cssParts.append(" { display: none !important; visibility: hidden !important; }")
                }
            }
        }

        if (applyCookieCosmetics) {
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

    private fun resolveCustomCosmeticSelectorsForHost(pageHost: String): List<String> {
        val rules = customCosmeticRules
        if (rules.isEmpty()) return emptyList()
        val exceptions = rules.filter { it.isException && matchesCosmeticDomainConstraint(it, pageHost) }
            .map { it.selector }
            .toSet()

        return rules.asSequence()
            .filter { !it.isException && it.selector !in exceptions }
            .filter { matchesCosmeticDomainConstraint(it, pageHost) }
            .map { it.selector }
            .filter { sel ->
                val lower = sel.lowercase()
                !lower.contains("video") && !lower.contains("movie_player") && !lower.contains("body") && !lower.contains("html")
            }
            .take(50)
            .toList()
    }

    private fun matchesCosmeticDomainConstraint(rule: CosmeticFilterRule, pageHost: String): Boolean {
        if (rule.excludedDomains.isNotEmpty() && pageHost.isNotEmpty() && matchesDomainSet(pageHost, rule.excludedDomains)) {
            return false
        }
        if (rule.domains.isEmpty()) return true
        return pageHost.isNotEmpty() && matchesDomainSet(pageHost, rule.domains)
    }

    // --- 7. RULE PARSER (ABP / AdGuard / Hosts Syntax) ---

    fun parseNetworkFilterRule(
        rawLine: String,
        sourceListId: String = ShieldFilterListId.EASYLIST.id,
        defaultCategory: ShieldRuleCategory = ShieldRuleCategory.ADVERTISEMENT
    ): ParsedFilterRule? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("#")) return null
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) return null

        // Hosts file format: 0.0.0.0 domain.com or 127.0.0.1 domain.com
        if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
            val host = line.substringAfter(' ').trim().substringBefore('#').trim().lowercase()
            if (host.contains('.') && host != "localhost" && !host.contains('*') && !host.contains('/')) {
                return ParsedFilterRule(
                    rawRule = line,
                    isAllowRule = false,
                    domainPattern = normalizeDomain(host),
                    category = defaultCategory,
                    sourceListId = sourceListId
                )
            }
            return null
        }

        val isAllow = line.startsWith("@@")
        val withoutAllow = if (isAllow) line.removePrefix("@@").trim() else line

        val patternPart = withoutAllow.substringBefore('$').trim()
        val optionsPart = if (withoutAllow.contains('$')) withoutAllow.substringAfter('$').trim() else ""

        var thirdPartyOnly = false
        var firstPartyOnly = false
        val allowedTypes = mutableSetOf<ShieldResourceType>()
        val excludedTypes = mutableSetOf<ShieldResourceType>()
        val incDomains = mutableSetOf<String>()
        val excDomains = mutableSetOf<String>()
        var inferredCategory = defaultCategory

        if (optionsPart.isNotEmpty()) {
            val opts = optionsPart.split(',')
            for (rawOpt in opts) {
                val opt = rawOpt.trim().lowercase()
                when {
                    opt == "third-party" || opt == "3p" -> thirdPartyOnly = true
                    opt == "~third-party" || opt == "first-party" || opt == "1p" -> firstPartyOnly = true
                    opt == "script" -> allowedTypes.add(ShieldResourceType.SCRIPT)
                    opt == "~script" -> excludedTypes.add(ShieldResourceType.SCRIPT)
                    opt == "image" -> allowedTypes.add(ShieldResourceType.IMAGE)
                    opt == "~image" -> excludedTypes.add(ShieldResourceType.IMAGE)
                    opt == "stylesheet" || opt == "css" -> allowedTypes.add(ShieldResourceType.STYLESHEET)
                    opt == "~stylesheet" || opt == "~css" -> excludedTypes.add(ShieldResourceType.STYLESHEET)
                    opt == "xmlhttprequest" || opt == "xhr" -> allowedTypes.add(ShieldResourceType.XMLHTTPREQUEST)
                    opt == "~xmlhttprequest" || opt == "~xhr" -> excludedTypes.add(ShieldResourceType.XMLHTTPREQUEST)
                    opt == "subdocument" || opt == "frame" -> allowedTypes.add(ShieldResourceType.SUBDOCUMENT)
                    opt == "~subdocument" || opt == "~frame" -> excludedTypes.add(ShieldResourceType.SUBDOCUMENT)
                    opt == "media" -> allowedTypes.add(ShieldResourceType.MEDIA)
                    opt == "font" -> allowedTypes.add(ShieldResourceType.FONT)
                    opt.startsWith("domain=") -> {
                        val doms = opt.removePrefix("domain=").split('|')
                        for (d in doms) {
                            val cleanD = d.trim()
                            if (cleanD.startsWith("~")) {
                                val dom = normalizeDomain(cleanD.removePrefix("~"))
                                if (dom.isNotEmpty()) excDomains.add(dom)
                            } else {
                                val dom = normalizeDomain(cleanD)
                                if (dom.isNotEmpty()) incDomains.add(dom)
                            }
                        }
                    }
                }
            }
        }

        // Parse domain & URL token from patternPart
        var domainPattern: String? = null
        var urlTokenPattern: String? = null

        if (patternPart.startsWith("||")) {
            val afterPipes = patternPart.removePrefix("||")
            val hostEndIdx = afterPipes.indexOfAny(charArrayOf('^', '/', '*', '?'))
            if (hostEndIdx == -1) {
                val dom = normalizeDomain(afterPipes)
                if (dom.contains('.')) domainPattern = dom
            } else {
                val dom = normalizeDomain(afterPipes.substring(0, hostEndIdx))
                if (dom.contains('.')) domainPattern = dom
                val remainder = afterPipes.substring(hostEndIdx).removePrefix("^").trim()
                if (remainder.isNotEmpty() && remainder != "*" && remainder.length >= 3) {
                    urlTokenPattern = remainder.substringBefore('*').lowercase().takeIf { it.length >= 3 }
                }
            }
        } else if (patternPart.startsWith("/") && patternPart.length >= 4 && !patternPart.endsWith("/")) {
            urlTokenPattern = patternPart.substringBefore('*').lowercase()
        } else {
            val clean = patternPart
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('^')
                .trim()
            if (!clean.contains('/') && !clean.contains('*') && clean.contains('.')) {
                domainPattern = normalizeDomain(clean)
            } else if (clean.contains('/')) {
                val hostCandidate = normalizeDomain(clean.substringBefore('/'))
                val pathCandidate = "/" + clean.substringAfter('/').substringBefore('*').lowercase()
                if (hostCandidate.contains('.')) domainPattern = hostCandidate
                if (pathCandidate.length >= 4) urlTokenPattern = pathCandidate
            }
        }

        if (domainPattern.isNullOrBlank() && urlTokenPattern.isNullOrBlank()) return null

        if (domainPattern != null && matchesDomainSet(domainPattern, EASYPRIVACY_DOMAINS)) {
            inferredCategory = ShieldRuleCategory.TRACKER
        } else if (domainPattern != null && matchesDomainSet(domainPattern, MALVERTISING_AND_DNS_GUARD_DOMAINS)) {
            inferredCategory = ShieldRuleCategory.MALICIOUS_DNS
        }

        return ParsedFilterRule(
            rawRule = line,
            isAllowRule = isAllow,
            domainPattern = domainPattern,
            urlTokenPattern = urlTokenPattern,
            category = inferredCategory,
            sourceListId = sourceListId,
            thirdPartyOnly = thirdPartyOnly,
            firstPartyOnly = firstPartyOnly,
            allowedResourceTypes = allowedTypes,
            excludedResourceTypes = excludedTypes,
            includedPageDomains = incDomains,
            excludedPageDomains = excDomains
        )
    }

    fun parseCosmeticRule(rawLine: String, sourceListId: String = ShieldFilterListId.ADGUARD_BASE.id): CosmeticFilterRule? {
        val line = rawLine.trim()
        val isException = line.contains("#@#")
        val delimiter = if (isException) "#@#" else if (line.contains("##")) "##" else return null
        val domainPart = line.substringBefore(delimiter).trim()
        val selectorPart = line.substringAfter(delimiter).trim()
        if (selectorPart.isEmpty() || selectorPart.contains('{') || selectorPart.contains("script")) return null

        val domains = mutableSetOf<String>()
        val excluded = mutableSetOf<String>()
        if (domainPart.isNotEmpty()) {
            for (d in domainPart.split(',')) {
                val trimmed = d.trim()
                if (trimmed.startsWith("~")) {
                    val clean = normalizeDomain(trimmed.removePrefix("~"))
                    if (clean.isNotEmpty()) excluded.add(clean)
                } else {
                    val clean = normalizeDomain(trimmed)
                    if (clean.isNotEmpty()) domains.add(clean)
                }
            }
        }
        return CosmeticFilterRule(
            selector = selectorPart,
            isException = isException,
            domains = domains,
            excludedDomains = excluded,
            sourceListId = sourceListId
        )
    }

    // --- 8. RESILIENT REMOTE FILTER UPDATER (RETAINS LAST WORKING DATABASE ON FAILURE) ---

    suspend fun fetchFilterListRulesFromUrl(
        listUrl: String,
        sourceTag: String,
        customConnectionFactory: ((String) -> HttpURLConnection)? = null
    ): Result<List<CustomFilterRule>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (customConnectionFactory?.invoke(listUrl) ?: (URL(listUrl).openConnection() as HttpURLConnection)).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code fetching filter list from $listUrl")
                }
                val lines = conn.inputStream.bufferedReader().useLines { seq ->
                    seq.take(1500).toList()
                }
                val parsed = ContentBlockingEngine.parseFilterListLines(lines, sourceTag)
                if (parsed.isEmpty()) {
                    throw IllegalStateException("Filter list response contained 0 valid rules")
                }
                parsed
            } finally {
                conn.disconnect()
            }
        }
    }

    // --- 9. DOMAIN MATCHING HELPERS ---

    fun normalizeDomain(rawHost: String): String {
        return rawHost.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("mobile.")
            .trimEnd('.')
    }

    internal fun matchesDomain(host: String, targetDomain: String): Boolean {
        val cleanHost = host.lowercase().trimEnd('.')
        val cleanTarget = targetDomain.lowercase().trimEnd('.')
        return cleanHost == cleanTarget || cleanHost.endsWith(".$cleanTarget")
    }

    internal fun matchesDomainSet(host: String, domainSet: Set<String>): Boolean {
        if (domainSet.isEmpty() || host.isEmpty()) return false
        val clean = host.lowercase().trimEnd('.')
        if (clean in domainSet) return true
        var dotIdx = clean.indexOf('.')
        while (dotIdx != -1 && dotIdx + 1 < clean.length) {
            val suffix = clean.substring(dotIdx + 1)
            if (suffix in domainSet) return true
            dotIdx = clean.indexOf('.', dotIdx + 1)
        }
        return false
    }

    internal fun isSameRegistrableDomain(hostA: String, hostB: String): Boolean {
        return ContentBlockingEngine.isSameRegistrableDomain(hostA, hostB)
    }

    fun resetSessionCountersForTest() {
        sessionAdsBlocked.set(0L)
        sessionTrackersBlocked.set(0L)
        sessionMaliciousDnsBlocked.set(0L)
        sessionPopupsBlocked.set(0L)
        sessionRedirectsBlocked.set(0L)
        sessionTrackingUrlsCleaned.set(0L)
    }
}
