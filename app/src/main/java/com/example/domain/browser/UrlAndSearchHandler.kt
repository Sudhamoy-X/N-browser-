package com.example.domain.browser

import android.content.Context
import android.net.http.HttpResponseCache
import com.example.data.local.SearchEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class InputClassification {
    DIRECT_URL,
    SEARCH_QUERY,
    INTERNAL_NYXORA,
    BLOCKED_DANGEROUS_SCHEME,
    EXTERNAL_INTENT_OR_CUSTOM_SCHEME
}

data class NormalizedDestination(
    val finalUrl: String,
    val classification: InputClassification,
    val isSecure: Boolean,
    val host: String,
    val safeDisplayHost: String = host,
    val wasImplicitHttps: Boolean = false,
    val fallbackHttpUrl: String? = null
)

/**
 * Pure Kotlin URL validator, normalizer, IDN/punycode homograph guard, and search router.
 */
object UrlAndSearchHandler {

    private val IPV4_REGEX = Regex(
        """^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$"""
    )

    private val HOST_WITH_OPTIONAL_PORT_AND_PATH = Regex(
        """^([a-zA-Z0-9\u00a1-\uffff-]+\.)+[a-zA-Z\u00a1-\uffff-]{2,63}(:\d{1,5})?([/?#].*)?$"""
    )

    private val SINGLE_LABEL_HOST_WITH_PORT = Regex(
        """^[a-zA-Z0-9-]{1,63}:\d{1,5}([/?#].*)?$"""
    )

    // Dangerous schemes that must NEVER be loaded when typed into the address bar
    private val DANGEROUS_ADDRESS_BAR_SCHEMES = listOf(
        "javascript:",
        "data:",
        "vbscript:",
        "file:",
        "content:",
        "jar:",
        "blob:"
    )

    private val EXTERNAL_APP_SCHEMES = listOf(
        "mailto:",
        "tel:",
        "sms:",
        "geo:",
        "market:",
        "intent:"
    )

    // Latin-lookalike Cyrillic and Greek homograph characters commonly abused in IDN spoofing
    private val CONFUSABLE_HOMOGRAPH_CHARS: Set<Char> = setOf(
        // Cyrillic lookalikes for a, c, e, i, j, o, p, s, x, y, d, g, h, k, m, t, v, w
        '\u0430', '\u0441', '\u0435', '\u0456', '\u0458', '\u043E', '\u0440', '\u0455',
        '\u0445', '\u0443', '\u0501', '\u051B', '\u051D', '\u04CF', '\u043A', '\u043C',
        '\u0442', '\u0432', '\u043D', '\u0410', '\u0421', '\u0415', '\u0406', '\u0408',
        '\u041E', '\u0420', '\u0405', '\u0425', '\u0423', '\u041A', '\u041C', '\u0422',
        '\u0412', '\u041D',
        // Greek lookalikes for a, b, e, i, k, m, n, o, p, t, u, x, y, z
        '\u03B1', '\u03B2', '\u03B5', '\u03B9', '\u03BA', '\u03BC', '\u03BD', '\u03BF',
        '\u03C1', '\u03C4', '\u03C5', '\u03C7', '\u03B6', '\u0391', '\u0392', '\u0395',
        '\u0399', '\u039A', '\u039C', '\u039D', '\u039F', '\u03A1', '\u03A4', '\u03A5',
        '\u03A7', '\u0396'
    )

    // Allowlist of canonical file:// URLs explicitly opened by the user via the system file picker
    private val userPickedFileUrls: MutableSet<String> = Collections.synchronizedSet( linkedSetOf<String>() )

    fun registerUserPickedFileUrl(fileUrl: String) {
        val trimmed = fileUrl.trim()
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            userPickedFileUrls.add(trimmed)
        }
    }

    fun isUserPickedFileUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("file://", ignoreCase = true)) return false
        return userPickedFileUrls.contains(trimmed)
    }

    fun clearUserPickedFileUrlsForTest() {
        userPickedFileUrls.clear()
    }

    /**
     * Normalizes raw address bar or search input into a safe destination:
     * - Trims leading/trailing whitespace.
     * - Blocks javascript:, data:, vbscript:, and typed file:// schemes.
     * - Validates http/https URLs; routes malformed URLs or inputs with spaces to search without crashing.
     * - Detects scheme-less domains, localhost, IPv4, IPv6, and host:port; tries HTTPS first with safe
     *   HTTP fallback if HTTPS fails (when httpsOnlyMode is false).
     * - Defaults to Google search for non-URL queries.
     */
    fun normalizeUserInput(
        rawInput: String,
        searchEngine: SearchEngine = SearchEngine.GOOGLE,
        httpsOnlyMode: Boolean = false
    ): NormalizedDestination {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return NormalizedDestination(
                finalUrl = "nyxora://home",
                classification = InputClassification.INTERNAL_NYXORA,
                isSecure = true,
                host = "nyxora.home",
                safeDisplayHost = "nyxora.home"
            )
        }

        val lower = trimmed.lowercase()

        // 1. Block dangerous schemes (javascript:, data:, vbscript:, file:, etc.) typed into the address bar
        // Even if obfuscated with leading control chars or internal whitespace/newlines before the colon
        val compactLower = lower.filterNot { it <= ' ' }
        if (DANGEROUS_ADDRESS_BAR_SCHEMES.any { compactLower.startsWith(it) }) {
            return NormalizedDestination(
                finalUrl = "about:blank",
                classification = InputClassification.BLOCKED_DANGEROUS_SCHEME,
                isSecure = false,
                host = "",
                safeDisplayHost = ""
            )
        }

        // 2. Internal NYXORA pages & NYXORA Connect deep links
        if (lower.startsWith("nyxora://")) {
            val pathAndQuery = trimmed.substring("nyxora://".length)
            val h = pathAndQuery.substringBefore('?').substringBefore('/').lowercase().ifEmpty { "home" }
            val normalizedInternal = if (h == "home") "nyxora://home" else "nyxora://$pathAndQuery"
            return NormalizedDestination(
                finalUrl = normalizedInternal,
                classification = InputClassification.INTERNAL_NYXORA,
                isSecure = true,
                host = "nyxora.$h",
                safeDisplayHost = "nyxora.$h"
            )
        }

        // 3. External intent / app schemes (mailto:, tel:, intent:, etc.)
        if (EXTERNAL_APP_SCHEMES.any { lower.startsWith(it) }) {
            val schemeName = lower.substringBefore(":")
            return NormalizedDestination(
                finalUrl = trimmed,
                classification = InputClassification.EXTERNAL_INTENT_OR_CUSTOM_SCHEME,
                isSecure = false,
                host = schemeName,
                safeDisplayHost = schemeName
            )
        }

        // 4. Any input containing whitespace (spaces, tabs, newlines) or control chars is a search query
        if (trimmed.any { it.isWhitespace() || it.code < 32 }) {
            return createSearchDestination(trimmed, searchEngine)
        }

        // 5. Explicit http:// or https:// scheme
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val parsed = validateAndNormalizeExplicitHttpUrl(trimmed, httpsOnlyMode)
                ?: return createSearchDestination(trimmed, searchEngine)
            return parsed
        }

        // 6. Unknown scheme with "://" (e.g. ftp:// or malformed ://) -> treat as search query
        if (trimmed.contains("://")) {
            return createSearchDestination(trimmed, searchEngine)
        }

        // 7. Check if scheme-less input looks like a domain, localhost, IPv4, IPv6, or host:port
        if (looksLikeImplicitUrl(trimmed)) {
            val parsedImplicit = buildImplicitHttpsFirstDestination(trimmed, httpsOnlyMode)
            if (parsedImplicit != null) {
                return parsedImplicit
            }
        }

        // 8. Otherwise treat as search query
        return createSearchDestination(trimmed, searchEngine)
    }

    private fun createSearchDestination(
        rawQuery: String,
        searchEngine: SearchEngine
    ): NormalizedDestination {
        // Normalize consecutive whitespace inside search queries neatly
        val normalizedQuery = rawQuery.trim().replace(Regex("\\s+"), " ")
        val searchUrl = buildSearchUrl(normalizedQuery, searchEngine)
        val host = extractHost(searchUrl)
        return NormalizedDestination(
            finalUrl = searchUrl,
            classification = InputClassification.SEARCH_QUERY,
            isSecure = true,
            host = host,
            safeDisplayHost = formatSafeDisplayHost(host)
        )
    }

    private fun validateAndNormalizeExplicitHttpUrl(
        input: String,
        httpsOnlyMode: Boolean
    ): NormalizedDestination? {
        return try {
            val isExplicitHttp = input.startsWith("http://", ignoreCase = true)
            val withoutScheme = input.substringAfter("://", "")
            if (withoutScheme.isBlank()) return null

            val authorityAndRest = splitAuthorityAndPath(withoutScheme) ?: return null
            val (rawHost, port, pathAndQuery) = authorityAndRest
            if (rawHost.isBlank()) return null

            val asciiHost = normalizeValidatedHostToAscii(rawHost) ?: return null
            val portSuffix = if (port != null) ":$port" else ""

            // Only upgrade explicit http:// to https:// if user explicitly enabled strict HTTPS-Only Mode
            // and the host is not loopback/private IP; otherwise honor explicit http:// and mark isSecure = false.
            val useHttps = if (isExplicitHttp) {
                httpsOnlyMode && !isLocalOrLoopbackHost(asciiHost)
            } else {
                true
            }
            val finalScheme = if (useHttps) "https" else "http"
            val finalUrl = "$finalScheme://$asciiHost$portSuffix$pathAndQuery"

            // Final URI sanity check so malformed query/fragment syntax never crashes downstream
            validateUriSyntaxOrNull(finalUrl) ?: return null

            NormalizedDestination(
                finalUrl = finalUrl,
                classification = InputClassification.DIRECT_URL,
                isSecure = finalScheme == "https",
                host = asciiHost,
                safeDisplayHost = formatSafeDisplayHost(rawHost),
                wasImplicitHttps = false,
                fallbackHttpUrl = null
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildImplicitHttpsFirstDestination(
        withoutScheme: String,
        httpsOnlyMode: Boolean
    ): NormalizedDestination? {
        return try {
            val authorityAndRest = splitAuthorityAndPath(withoutScheme) ?: return null
            val (rawHost, port, pathAndQuery) = authorityAndRest
            if (rawHost.isBlank()) return null

            val asciiHost = normalizeValidatedHostToAscii(rawHost) ?: return null
            val portSuffix = if (port != null) ":$port" else ""
            val httpsUrl = "https://$asciiHost$portSuffix$pathAndQuery"
            val httpFallback = if (httpsOnlyMode) null else "http://$asciiHost$portSuffix$pathAndQuery"

            validateUriSyntaxOrNull(httpsUrl) ?: return null

            NormalizedDestination(
                finalUrl = httpsUrl,
                classification = InputClassification.DIRECT_URL,
                isSecure = true,
                host = asciiHost,
                safeDisplayHost = formatSafeDisplayHost(rawHost),
                wasImplicitHttps = true,
                fallbackHttpUrl = httpFallback
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun validateUriSyntaxOrNull(url: String): String? {
        return try {
            val uri = URI(url)
            if (uri.scheme.isNullOrBlank() || uri.rawAuthority.isNullOrBlank()) null else url
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Splits "authority/path?query#fragment" into Triple(host, validatedPortOrNull, pathQueryFragment).
     * Returns null if authority or port is malformed.
     */
    private fun splitAuthorityAndPath(withoutScheme: String): Triple<String, Int?, String>? {
        val slashIdx = withoutScheme.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (slashIdx == -1) withoutScheme else withoutScheme.substring(0, slashIdx)
        val pathAndQuery = if (slashIdx == -1) "" else withoutScheme.substring(slashIdx)

        if (authority.isBlank() || authority.contains('@')) return null

        // Handle bracketed IPv6 literal: [::1] or [2001:db8::1]:8080
        if (authority.startsWith("[")) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket <= 1) return null
            val ipv6WithBrackets = authority.substring(0, closeBracket + 1)
            val remainder = authority.substring(closeBracket + 1)
            val port: Int? = if (remainder.isEmpty()) {
                null
            } else if (remainder.startsWith(":")) {
                val portNum = remainder.removePrefix(":").toIntOrNull() ?: return null
                if (portNum !in 1..65535) return null
                portNum
            } else {
                return null
            }
            return Triple(ipv6WithBrackets, port, pathAndQuery)
        }

        // Non-IPv6 authority: reject extra colons
        val colonCount = authority.count { it == ':' }
        if (colonCount > 1) return null

        val hostPart = if (colonCount == 1) authority.substringBefore(':') else authority
        val port: Int? = if (colonCount == 1) {
            val portStr = authority.substringAfter(':')
            val portNum = portStr.toIntOrNull() ?: return null
            if (portNum !in 1..65535) return null
            portNum
        } else {
            null
        }

        if (hostPart.isBlank()) return null
        return Triple(hostPart, port, pathAndQuery)
    }

    /**
     * Validates a raw host (domain, localhost, IPv4, or bracketed IPv6) and returns its normalized
     * lowercase ASCII/punycode representation, or null if invalid.
     */
    private fun normalizeValidatedHostToAscii(rawHost: String): String? {
        val trimmed = rawHost.trim()
        if (trimmed.isEmpty()) return null

        // Bracketed IPv6
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (!isValidIpv6Literal(inner)) return null
            return "[${inner.lowercase()}]"
        }

        // Reject leading/trailing dots or consecutive dots
        if (trimmed.startsWith(".") || trimmed.endsWith(".") || trimmed.contains("..")) {
            return null
        }

        // Convert IDN labels to ASCII punycode
        val ascii = try {
            IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (_: Exception) {
            return null
        }

        if (ascii.isBlank() || ascii.length > 253) return null
        if (ascii == "localhost") return "localhost"
        if (IPV4_REGEX.matches(ascii)) return ascii

        // If it looks like 4 numeric dot-separated segments (e.g., 999.999.999.999), reject as invalid IPv4
        val labels = ascii.split('.')
        if (labels.all { it.all(Char::isDigit) }) {
            return null
        }

        // Validate each DNS label
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return null
            if (label.startsWith("-") || label.endsWith("-")) return null
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
        }

        // Multi-label domain must have a valid TLD (at least 2 chars, not all digits)
        if (labels.size >= 2) {
            val tld = labels.last()
            if (tld.length < 2 || tld.all { it.isDigit() }) return null
        }

        return ascii
    }

    private fun isValidIpv6Literal(inner: String): Boolean {
        if (inner.isBlank() || inner.length > 45 || !inner.contains(':')) return false
        if (inner.contains(":::")) return false
        val doubleColonCount = Regex("::").findAll(inner).count()
        if (doubleColonCount > 1) return false
        val groups = inner.split(':').filter { it.isNotEmpty() }
        if (groups.isEmpty() && inner != "::") return false
        if (doubleColonCount == 0 && groups.size != 8) return false
        if (doubleColonCount == 1 && groups.size > 7) return false
        return groups.all { group ->
            group.length in 1..4 && group.all { ch ->
                ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            }
        }
    }

    /**
     * Checks whether a scheme-less input looks like a domain, localhost, IPv4, IPv6, or host:port.
     */
    fun looksLikeImplicitUrl(input: String): Boolean {
        if (input.isBlank() || input.any { it.isWhitespace() || it.code < 32 }) return false
        if (input.startsWith(".") || input.endsWith(".") || input.contains("..")) return false

        val split = splitAuthorityAndPath(input) ?: return false
        val (host, port, _) = split

        val lowerHost = host.lowercase()
        // 1. localhost (with or without port/path)
        if (lowerHost == "localhost") return true

        // 2. Bracketed IPv6 literal (e.g. [::1] or [2001:db8::1]:8080)
        if (lowerHost.startsWith("[") && lowerHost.endsWith("]")) {
            return isValidIpv6Literal(lowerHost.substring(1, lowerHost.length - 1))
        }

        // 3. IPv4 literal (e.g. 127.0.0.1 or 192.168.1.1:8080)
        if (IPV4_REGEX.matches(lowerHost)) return true

        // Reject broken numeric IPs like 999.1.1.1
        val rawLabels = lowerHost.split('.')
        if (rawLabels.size >= 2 && rawLabels.all { it.all(Char::isDigit) }) return false

        // 4. Single-label host WITH explicit valid port (e.g. intranet:8080 or devbox:3000/api)
        if (rawLabels.size == 1 && port != null && SINGLE_LABEL_HOST_WITH_PORT.matches(input)) {
            return normalizeValidatedHostToAscii(host) != null
        }

        // 5. Multi-label domain (e.g. example.com, sub.domain.org:8443/path, IDN domain)
        if (rawLabels.size >= 2 && HOST_WITH_OPTIONAL_PORT_AND_PATH.matches(input)) {
            return normalizeValidatedHostToAscii(host) != null
        }

        return false
    }

    private fun isLocalOrLoopbackHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" ||
            lower == "127.0.0.1" ||
            lower == "[::1]" ||
            lower.startsWith("192.168.") ||
            lower.startsWith("10.")
    }

    fun buildSearchUrl(query: String, searchEngine: SearchEngine = SearchEngine.GOOGLE): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return String.format(searchEngine.searchUrlTemplate, encoded)
    }

    /**
     * Converts any domain or URL host to its normalized ASCII punycode form.
     */
    fun toPunycodeHost(urlOrHost: String): String {
        val raw = extractRawHost(urlOrHost)
        if (raw.isBlank()) return ""
        if (raw.startsWith("[") && raw.endsWith("]")) return raw.lowercase()
        return try {
            IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) {
            raw.lowercase()
        }
    }

    /**
     * Detects whether an IDN/punycode host uses mixed scripts (e.g., Latin + Cyrillic),
     * whole-script Latin-lookalike homographs (e.g., Cyrillic "аррӏе.com" / xn--80ak6aa92e.com),
     * or non-standard characters that could spoof another domain.
     */
    fun isSpoofableIdnHost(urlOrHost: String): Boolean {
        val raw = extractRawHost(urlOrHost)
        if (raw.isBlank() || (raw.startsWith("[") && raw.endsWith("]"))) return false
        return try {
            val ascii = IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase()
            val unicode = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED)
            if (unicode.all { it.code < 128 } && !ascii.contains("xn--")) {
                return false
            }
            val labels = unicode.split('.')
            for (label in labels) {
                if (label.all { it.code < 128 }) continue
                // Check for confusable Cyrillic/Greek lookalike characters
                if (label.any { it in CONFUSABLE_HOMOGRAPH_CHARS }) {
                    return true
                }
                // Check for mixed Unicode scripts within the same label (e.g. Latin + Cyrillic/Greek/Han)
                val scripts = mutableSetOf<Character.UnicodeScript>()
                for (ch in label) {
                    if (ch == '-' || ch.isDigit()) continue
                    val script = Character.UnicodeScript.of(ch.code)
                    if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
                        scripts.add(script)
                    }
                }
                if (scripts.size > 1) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Formats a host or URL into a safe display host to prevent IDN/punycode lookalike spoofing:
     * - Spoofable/mixed-script/homograph IDN domains are forced to raw ASCII punycode ("xn--...").
     * - Non-spoofable single-script IDN domains include the punycode form alongside Unicode
     *   (or pure punycode if input was already punycode) so the underlying xn-- identity is never hidden.
     * - Standard ASCII domains return clean lowercase ASCII (without leading "www.").
     */
    fun formatSafeDisplayHost(urlOrHost: String): String {
        val raw = extractRawHost(urlOrHost).removePrefix("www.")
        if (raw.isBlank()) return ""
        if (raw.startsWith("[") && raw.endsWith("]")) return raw.lowercase()

        return try {
            val ascii = IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase().removePrefix("www.")
            val unicode = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED).lowercase().removePrefix("www.")
            when {
                // Pure ASCII domain
                unicode == ascii && !ascii.contains("xn--") -> ascii
                // Spoofable (mixed-script or Latin-confusable Cyrillic/Greek homograph): show ONLY xn-- punycode
                isSpoofableIdnHost(raw) -> ascii
                // Legitimate single-script IDN: still return punycode xn-- form so punycode is always safe
                else -> ascii
            }
        } catch (_: Exception) {
            raw.lowercase()
        }
    }

    private fun extractRawHost(urlOrHost: String): String {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("nyxora://", ignoreCase = true)) {
            return "nyxora." + trimmed.substringAfter("://").lowercase()
        }
        val withoutScheme = if (trimmed.contains("://")) {
            trimmed.substringAfter("://")
        } else {
            trimmed
        }
        val authority = withoutScheme
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
        if (authority.startsWith("[")) {
            val closeIdx = authority.indexOf(']')
            if (closeIdx != -1) {
                return authority.substring(0, closeIdx + 1)
            }
        }
        return authority.substringBefore(":")
    }

    fun extractHost(url: String): String {
        val raw = extractRawHost(url).removePrefix("www.")
        if (raw.isBlank()) return ""
        if (raw.startsWith("[") && raw.endsWith("]")) return raw.lowercase()
        return try {
            IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase().removePrefix("www.")
        } catch (_: Exception) {
            raw.lowercase()
        }
    }

    /**
     * Never show an insecure (http://, certificate error, or error page) URL as secure.
     */
    fun isSecureUrl(url: String, hasCertificateError: Boolean = false): Boolean {
        if (hasCertificateError) return false
        val lower = url.trim().lowercase()
        if (lower.startsWith("http://")) return false
        if (lower.startsWith("nyxora://")) return true
        if (lower.startsWith("file://") && isUserPickedFileUrl(url)) return true
        return lower.startsWith("https://")
    }

    fun isRiskyDownloadFile(fileNameOrUrl: String, mimeType: String?): Boolean {
        val lower = fileNameOrUrl.lowercase().substringBefore("?").substringBefore("#")
        val riskyExtensions = listOf(
            ".apk", ".aab", ".xapk", ".dex", ".jar", ".so",
            ".exe", ".msi", ".bat", ".cmd", ".sh", ".scr"
        )
        if (riskyExtensions.any { lower.endsWith(it) }) return true
        val lowerMime = mimeType?.lowercase() ?: ""
        return lowerMime == "application/vnd.android.package-archive" ||
            lowerMime == "application/x-msdownload" ||
            lowerMime == "application/x-sh"
    }
}

/**
 * Shared high-performance browser network layer providing:
 * - 20 MB HTTP disk cache via Android's native HttpResponseCache
 * - Non-blocking post-first-frame WebView engine warm-up
 * - Cheap, debounced DNS prefetch and TCP/TLS preconnect for typed or top-visited hosts
 */
object BrowserNetworkClient {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var engineWarmedUp: Boolean = false

    private val dnsPrefetchTimestamps = ConcurrentHashMap<String, Long>()
    private val resolvedAddressesCache = ConcurrentHashMap<String, List<InetAddress>>()

    private const val PREFETCH_TTL_MS = 60_000L
    private const val HTTP_CACHE_SIZE_BYTES = 20L * 1024L * 1024L // 20 MB

    fun ensureHttpCacheInstalled(context: Context? = null): Boolean {
        runCatching {
            if (HttpResponseCache.getInstalled() == null && context != null) {
                val httpCacheDir = File(context.applicationContext.cacheDir, "nyxora_http_cache")
                HttpResponseCache.install(httpCacheDir, HTTP_CACHE_SIZE_BYTES)
            }
        }
        return true
    }

    /**
     * Warms up the Android System WebView engine and HTTP cache on a background thread AFTER the first UI frame
     * has already been drawn, so cold start and first frame are never delayed.
     */
    fun warmUpEngineAfterFirstFrame(context: Context, topVisitedHost: String? = null) {
        if (engineWarmedUp) return
        engineWarmedUp = true
        val appContext = context.applicationContext
        backgroundScope.launch {
            runCatching {
                // 1. Install 20 MB native HTTP disk cache off the main thread
                ensureHttpCacheInstalled(appContext)
                // 2. Warm up content blocking filter structures off the main thread
                ContentBlockingEngine.shouldBlockSubresource(
                    requestUrl = "https://www.google.com/favicon.ico",
                    pageUrl = "https://www.google.com",
                    globalAdBlockingEnabled = true,
                    siteSetting = null
                )
                // 3. Prefetch DNS for default search engine (www.google.com) and top visited host
                prefetchDnsOnly("www.google.com")
                if (!topVisitedHost.isNullOrBlank()) {
                    prefetchDnsOnly(topVisitedHost)
                }
            }
        }
    }

    /**
     * Cheap, debounced DNS prefetch and lightweight TLS preconnect for typed or most-visited host.
     * Skipped in Private Mode or Data Saver mode.
     */
    fun prefetchDnsAndPreconnect(
        rawHostOrInput: String,
        isPrivateMode: Boolean,
        dataSaverEnabled: Boolean = false,
        preconnectSocket: Boolean = false
    ) {
        if (isPrivateMode || dataSaverEnabled) return
        val trimmed = rawHostOrInput.trim()
        if (trimmed.length < 4 || trimmed.contains(' ')) return
        if (!UrlAndSearchHandler.looksLikeImplicitUrl(trimmed) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return
        }

        val host = UrlAndSearchHandler.extractHost(trimmed)
        if (host.isBlank() || host == "localhost" || host.startsWith("nyxora.")) return

        val now = System.currentTimeMillis()
        val lastPrefetch = dnsPrefetchTimestamps[host] ?: 0L
        if (now - lastPrefetch < PREFETCH_TTL_MS) return
        dnsPrefetchTimestamps[host] = now

        backgroundScope.launch {
            prefetchDnsOnly(host)
            if (preconnectSocket) {
                runCatching {
                    val conn = (URL("https://$host/").openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 Nyxora/1.0"
                        )
                    }
                    try {
                        conn.responseCode
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }
    }

    private fun prefetchDnsOnly(host: String) {
        runCatching {
            val addresses = InetAddress.getAllByName(host).toList()
            if (addresses.isNotEmpty()) {
                resolvedAddressesCache[host] = addresses
            }
        }
    }

    fun isHostDnsPrefetched(host: String): Boolean {
        val clean = UrlAndSearchHandler.extractHost(host)
        return dnsPrefetchTimestamps.containsKey(clean) || resolvedAddressesCache.containsKey(clean)
    }
}

