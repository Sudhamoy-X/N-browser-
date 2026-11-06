package com.example.domain.connect

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short-lived WebRTC TURN relay credentials issued dynamically by the NYXORA Connect backend.
 *
 * Security guarantees:
 * - Held strictly in volatile memory; never persisted to disk or SharedPreferences.
 * - [toString] redacts [credential] so credentials are never leaked in logs or stack traces.
 * - Automatically invalidated when [isExpired] becomes true.
 */
data class ShortLivedTurnCredentials(
    val turnUrls: List<String>,
    val username: String,
    val credential: String,
    val ttlSeconds: Long = DEFAULT_SHORT_LIVED_TTL_SECONDS,
    val expiresAtMillis: Long,
    val issuedAtMillis: Long = System.currentTimeMillis(),
    val stunUrls: List<String> = emptyList()
) {
    fun isExpired(
        nowMillis: Long = System.currentTimeMillis(),
        safetyMarginMillis: Long = EXPIRY_SAFETY_MARGIN_MS
    ): Boolean {
        return nowMillis + safetyMarginMillis >= expiresAtMillis
    }

    override fun toString(): String {
        val masked = if (credential.isNotEmpty()) "[REDACTED]" else ""
        return "ShortLivedTurnCredentials(turnUrls=$turnUrls, username=$username, credential=$masked, ttlSeconds=$ttlSeconds, expiresAtMillis=$expiresAtMillis)"
    }

    companion object {
        const val DEFAULT_SHORT_LIVED_TTL_SECONDS = 3600L
        const val EXPIRY_SAFETY_MARGIN_MS = 30_000L
    }
}

/**
 * Immutable snapshot of the validated WebRTC ICE configuration resolved by [NyxoraWebRtcIceConfigProvider].
 */
data class ResolvedWebRtcIceConfiguration(
    val iceServers: List<WebRtcIceServerConfig>,
    val stunUrls: List<String>,
    val turnUrls: List<String>,
    val isTurnConfigured: Boolean,
    val isShortLivedTurnActive: Boolean = false,
    val usedDefaultStunFallback: Boolean = false,
    val validationWarnings: List<String> = emptyList()
)

/**
 * Centralized, validated WebRTC ICE configuration provider for NYXORA Browser.
 *
 * Responsibilities:
 * 1. Reads the exact build/environment configuration keys:
 *    - [KEY_WEBRTC_STUN_URLS] (`NYXORA_WEBRTC_STUN_URLS`)
 *    - [KEY_WEBRTC_TURN_URLS] (`NYXORA_WEBRTC_TURN_URLS`)
 *    - [KEY_WEBRTC_TURN_USERNAME] (`NYXORA_WEBRTC_TURN_USERNAME`)
 *    - [KEY_WEBRTC_TURN_CREDENTIAL] (`NYXORA_WEBRTC_TURN_CREDENTIAL`)
 * 2. Parses comma-separated ICE server URLs safely, rejecting malformed URIs, invalid ports,
 *    unsupported schemes, or embedded userinfo credentials.
 * 3. Uses Google STUN (`stun:stun.l.google.com:19302`) as the initial development default and
 *    automatic fallback when `NYXORA_WEBRTC_STUN_URLS` is missing, empty, or malformed.
 * 4. Supports optional TURN configuration (only enabling TURN when valid `turn:`/`turns:` URLs
 *    and non-placeholder `username` + `credential` are provided) without ever hardcoding production
 *    TURN credentials in source code, BuildConfig defaults, or the APK.
 * 5. Supports short-lived TURN credentials fetched securely over authenticated HTTP/HTTPS from
 *    the NYXORA Connect backend (`/api/webrtc/ice-config`).
 */
object NyxoraWebRtcIceConfigProvider {

    const val KEY_WEBRTC_STUN_URLS = "NYXORA_WEBRTC_STUN_URLS"
    const val KEY_WEBRTC_TURN_URLS = "NYXORA_WEBRTC_TURN_URLS"
    const val KEY_WEBRTC_TURN_USERNAME = "NYXORA_WEBRTC_TURN_USERNAME"
    const val KEY_WEBRTC_TURN_CREDENTIAL = "NYXORA_WEBRTC_TURN_CREDENTIAL"

    /**
     * Initial development default STUN server URL (Google STUN).
     */
    const val DEFAULT_GOOGLE_STUN_URL = "stun:stun.l.google.com:19302"

    private val PLACEHOLDER_TOKENS = setOf(
        "UNCONFIGURED",
        "NONE",
        "NULL",
        "UNDEFINED",
        "PLACEHOLDER",
        "CHANGEME",
        "CHANGE_ME",
        "TODO",
        "DISABLED",
        "YOUR_TURN_URL",
        "YOUR_TURN_URLS",
        "YOUR_TURN_USERNAME",
        "YOUR_TURN_CREDENTIAL",
        "YOUR_TURN_PASSWORD",
        "MY_TURN_CREDENTIAL",
        "MY_WEBRTC_TURN_CREDENTIAL",
        "NYXORA_WEBRTC_STUN_URLS_DEFAULT_VALUE",
        "NYXORA_WEBRTC_TURN_URLS_DEFAULT_VALUE",
        "NYXORA_WEBRTC_TURN_USERNAME_DEFAULT_VALUE",
        "NYXORA_WEBRTC_TURN_CREDENTIAL_DEFAULT_VALUE"
    )

    private val ALLOWED_TURN_TRANSPORTS = setOf("udp", "tcp", "tls")
    private val HOSTNAME_LABEL_REGEX = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
    private val IPV6_HEX_COLON_REGEX = Regex("^[0-9a-fA-F:]+$")
    private val ALL_DIGITS_AND_DOTS_REGEX = Regex("^[0-9.]+$")

    @Volatile
    private var cachedShortLivedTurn: ShortLivedTurnCredentials? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns true if [rawValue] is null, blank, or a known placeholder value such as `UNCONFIGURED`.
     */
    fun isPlaceholderOrEmpty(rawValue: String?): Boolean {
        if (rawValue == null) return true
        val trimmed = rawValue.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return true
        return trimmed.uppercase() in PLACEHOLDER_TOKENS
    }

    /**
     * Validates a single STUN or TURN URI according to RFC 7064 (`stun:` / `stuns:`) and
     * RFC 7065 (`turn:` / `turns:`).
     *
     * Returns the normalized URI string if valid, or `null` if malformed.
     */
    fun validateIceServerUri(
        rawUri: String?,
        allowStun: Boolean = true,
        allowTurn: Boolean = true
    ): String? {
        if (rawUri == null) return null
        val trimmed = rawUri.trim().trim('"', '\'')
        if (isPlaceholderOrEmpty(trimmed)) return null

        // Reject internal whitespace, control characters, fragments, path segments, or embedded credentials
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        if (trimmed.contains('#') || trimmed.contains('@') || trimmed.contains('\\')) return null

        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return null

        val scheme = trimmed.substring(0, colonIdx).lowercase()
        val isStunScheme = scheme == "stun" || scheme == "stuns"
        val isTurnScheme = scheme == "turn" || scheme == "turns"
        if (!isStunScheme && !isTurnScheme) return null
        if (isStunScheme && !allowStun) return null
        if (isTurnScheme && !allowTurn) return null

        val remainder = trimmed.substring(colonIdx + 1)
        if (remainder.isEmpty() || remainder.startsWith("//") || remainder.contains('/')) {
            return null
        }

        val queryIdx = remainder.indexOf('?')
        val hostPortPart = if (queryIdx >= 0) remainder.substring(0, queryIdx) else remainder
        val queryPart = if (queryIdx >= 0) remainder.substring(queryIdx + 1) else null

        if (hostPortPart.isEmpty()) return null

        // Validate optional query parameter: only allowed on TURN/TURNS and only transport=udp|tcp|tls
        var normalizedTransport: String? = null
        if (queryPart != null) {
            if (isStunScheme) return null
            if (queryPart.isEmpty() || queryPart.contains('&') || queryPart.contains('?')) return null
            val eqIdx = queryPart.indexOf('=')
            if (eqIdx <= 0) return null
            val paramKey = queryPart.substring(0, eqIdx).lowercase()
            val paramVal = queryPart.substring(eqIdx + 1).lowercase()
            if (paramKey != "transport" || paramVal !in ALLOWED_TURN_TRANSPORTS) {
                return null
            }
            normalizedTransport = paramVal
        }

        // Extract and validate host and optional port
        val host: String
        val portStr: String?
        if (hostPortPart.startsWith('[')) {
            // Bracketed IPv6 literal, e.g. [::1]:3478 or [2001:db8::1]
            val closeBracketIdx = hostPortPart.indexOf(']')
            if (closeBracketIdx <= 1) return null
            val ipv6Literal = hostPortPart.substring(1, closeBracketIdx)
            if (!isValidIpv6Literal(ipv6Literal)) return null
            host = "[${ipv6Literal.lowercase()}]"
            val afterBracket = hostPortPart.substring(closeBracketIdx + 1)
            if (afterBracket.isEmpty()) {
                portStr = null
            } else if (afterBracket.startsWith(':')) {
                portStr = afterBracket.substring(1)
                if (portStr.isEmpty()) return null
            } else {
                return null
            }
        } else {
            val firstColon = hostPortPart.indexOf(':')
            val lastColon = hostPortPart.lastIndexOf(':')
            if (firstColon != lastColon) {
                // Unbracketed multi-colon host (invalid IPv6 without brackets)
                return null
            }
            if (firstColon >= 0) {
                host = hostPortPart.substring(0, firstColon).lowercase()
                portStr = hostPortPart.substring(firstColon + 1)
                if (portStr.isEmpty()) return null
            } else {
                host = hostPortPart.lowercase()
                portStr = null
            }
            if (!isValidHostOrIpv4(host)) return null
        }

        val validatedPort: Int? = if (portStr != null) {
            if (!portStr.all { it.isDigit() } || portStr.length > 5) return null
            val parsedPort = portStr.toIntOrNull() ?: return null
            if (parsedPort !in 1..65535) return null
            parsedPort
        } else {
            null
        }

        return buildString {
            append(scheme)
            append(':')
            append(host)
            if (validatedPort != null) {
                append(':')
                append(validatedPort)
            }
            if (normalizedTransport != null) {
                append("?transport=")
                append(normalizedTransport)
            }
        }
    }

    private fun isValidIpv6Literal(literal: String): Boolean {
        if (literal.length < 2 || literal.length > 45) return false
        if (!literal.contains(':')) return false
        if (literal.contains(":::")) return false
        return IPV6_HEX_COLON_REGEX.matches(literal)
    }

    private fun isValidHostOrIpv4(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false

        if (ALL_DIGITS_AND_DOTS_REGEX.matches(host)) {
            val parts = host.split('.')
            if (parts.size != 4) return false
            for (part in parts) {
                if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return false
                val octet = part.toIntOrNull() ?: return false
                if (octet !in 0..255) return false
            }
            return true
        }

        val labels = host.split('.')
        for (label in labels) {
            if (!HOSTNAME_LABEL_REGEX.matches(label)) return false
        }
        // Reject single-label all-numeric hosts like "12345"
        if (labels.size == 1 && labels[0].all { it.isDigit() }) return false
        return true
    }

    /**
     * Parses comma-separated (or semicolon-separated) STUN server URLs (`stun:` / `stuns:`).
     * Safely ignores blank entries, placeholder tokens, and malformed URLs, and deduplicates results.
     */
    fun parseStunUrls(rawCsv: String?): List<String> {
        return parseIceUrlsInternal(rawCsv, allowStun = true, allowTurn = false).validUrls
    }

    /**
     * Parses comma-separated (or semicolon-separated) TURN server URLs (`turn:` / `turns:`).
     * Safely ignores blank entries, placeholder tokens, and malformed URLs, and deduplicates results.
     */
    fun parseTurnUrls(rawCsv: String?): List<String> {
        return parseIceUrlsInternal(rawCsv, allowStun = false, allowTurn = true).validUrls
    }

    private data class UrlParseOutcome(
        val validUrls: List<String>,
        val rejectedEntries: List<String>
    )

    private fun parseIceUrlsInternal(
        rawCsv: String?,
        allowStun: Boolean,
        allowTurn: Boolean
    ): UrlParseOutcome {
        if (isPlaceholderOrEmpty(rawCsv)) {
            return UrlParseOutcome(emptyList(), emptyList())
        }
        val valid = LinkedHashSet<String>()
        val rejected = mutableListOf<String>()
        val tokens = rawCsv!!.split(',', ';')
        for (rawToken in tokens) {
            val trimmed = rawToken.trim().trim('"', '\'')
            if (trimmed.isEmpty() || isPlaceholderOrEmpty(trimmed)) continue
            val normalized = validateIceServerUri(trimmed, allowStun = allowStun, allowTurn = allowTurn)
            if (normalized != null) {
                valid.add(normalized)
            } else {
                rejected.add(NyxoraConnectSecurity.redactSecretsForLog(trimmed))
            }
        }
        return UrlParseOutcome(valid.toList(), rejected)
    }

    /**
     * Resolves and validates a complete [ResolvedWebRtcIceConfiguration] from raw configuration values.
     *
     * Rules:
     * - Always includes at least one valid STUN server, defaulting to [DEFAULT_GOOGLE_STUN_URL]
     *   (`stun:stun.l.google.com:19302`) when [rawStunUrls] is missing, blank, or all entries are malformed.
     * - Prioritizes non-expired [shortLivedOverride] TURN credentials fetched from the backend.
     * - Otherwise enables optional TURN only when [rawTurnUrls] contains at least one valid `turn:`/`turns:`
     *   URL AND both [rawTurnUsername] and [rawTurnCredential] are non-empty and non-placeholder.
     */
    fun resolveFromValues(
        rawStunUrls: String?,
        rawTurnUrls: String?,
        rawTurnUsername: String?,
        rawTurnCredential: String?,
        shortLivedOverride: ShortLivedTurnCredentials? = cachedShortLivedTurn,
        nowMillis: Long = System.currentTimeMillis()
    ): ResolvedWebRtcIceConfiguration {
        val warnings = mutableListOf<String>()

        val stunOutcome = parseIceUrlsInternal(rawStunUrls, allowStun = true, allowTurn = false)
        if (stunOutcome.rejectedEntries.isNotEmpty()) {
            warnings.add("Ignored ${stunOutcome.rejectedEntries.size} malformed STUN URL(s): ${stunOutcome.rejectedEntries.joinToString(", ")}")
        }

        val shortLivedValid = shortLivedOverride?.takeIf { !it.isExpired(nowMillis) }
        val shortLivedStun = shortLivedValid?.stunUrls
            ?.mapNotNull { validateIceServerUri(it, allowStun = true, allowTurn = false) }
            .orEmpty()

        val effectiveStunUrls: List<String>
        val usedDefaultStunFallback: Boolean
        when {
            stunOutcome.validUrls.isNotEmpty() -> {
                effectiveStunUrls = (stunOutcome.validUrls + shortLivedStun).distinct()
                usedDefaultStunFallback = false
            }
            shortLivedStun.isNotEmpty() -> {
                effectiveStunUrls = shortLivedStun.distinct()
                usedDefaultStunFallback = false
            }
            else -> {
                effectiveStunUrls = listOf(DEFAULT_GOOGLE_STUN_URL)
                usedDefaultStunFallback = true
                if (!isPlaceholderOrEmpty(rawStunUrls)) {
                    warnings.add("All configured NYXORA_WEBRTC_STUN_URLS were invalid; falling back to $DEFAULT_GOOGLE_STUN_URL")
                }
            }
        }

        val servers = mutableListOf<WebRtcIceServerConfig>()
        servers.add(
            WebRtcIceServerConfig(
                urls = effectiveStunUrls,
                isTurnRelay = false
            )
        )

        var effectiveTurnUrls: List<String> = emptyList()
        var isTurnConfigured = false
        var isShortLivedTurnActive = false

        // 1. Prefer valid, non-expired short-lived TURN credentials from the backend
        if (shortLivedValid != null) {
            val validShortTurnUrls = shortLivedValid.turnUrls.mapNotNull {
                validateIceServerUri(it, allowStun = false, allowTurn = true)
            }.distinct()
            val cleanUser = shortLivedValid.username.trim()
            val cleanCred = shortLivedValid.credential.trim()
            if (validShortTurnUrls.isNotEmpty() &&
                !isPlaceholderOrEmpty(cleanUser) &&
                !isPlaceholderOrEmpty(cleanCred)
            ) {
                effectiveTurnUrls = validShortTurnUrls
                isTurnConfigured = true
                isShortLivedTurnActive = true
                servers.add(
                    WebRtcIceServerConfig(
                        urls = validShortTurnUrls,
                        username = cleanUser,
                        credential = cleanCred,
                        isTurnRelay = true,
                        expiresAtMillis = shortLivedValid.expiresAtMillis
                    )
                )
            }
        } else if (shortLivedOverride != null && shortLivedOverride.isExpired(nowMillis)) {
            warnings.add("Short-lived TURN credentials expired at ${shortLivedOverride.expiresAtMillis}; falling back to static/STUN configuration")
        }

        // 2. Fall back to build/environment optional TURN configuration if short-lived TURN is not active
        if (!isTurnConfigured) {
            val turnOutcome = parseIceUrlsInternal(rawTurnUrls, allowStun = false, allowTurn = true)
            if (turnOutcome.rejectedEntries.isNotEmpty()) {
                warnings.add("Ignored ${turnOutcome.rejectedEntries.size} malformed TURN URL(s): ${turnOutcome.rejectedEntries.joinToString(", ")}")
            }
            val hasTurnUsername = !isPlaceholderOrEmpty(rawTurnUsername)
            val hasTurnCredential = !isPlaceholderOrEmpty(rawTurnCredential)

            if (turnOutcome.validUrls.isNotEmpty() && hasTurnUsername && hasTurnCredential) {
                effectiveTurnUrls = turnOutcome.validUrls
                isTurnConfigured = true
                servers.add(
                    WebRtcIceServerConfig(
                        urls = turnOutcome.validUrls,
                        username = rawTurnUsername!!.trim(),
                        credential = rawTurnCredential!!.trim(),
                        isTurnRelay = true
                    )
                )
            } else if (turnOutcome.validUrls.isNotEmpty() || hasTurnUsername || hasTurnCredential) {
                warnings.add(
                    "Incomplete optional TURN configuration (urls=${turnOutcome.validUrls.size}, " +
                        "hasUsername=$hasTurnUsername, hasCredential=$hasTurnCredential); TURN relay disabled"
                )
            }
        }

        return ResolvedWebRtcIceConfiguration(
            iceServers = servers,
            stunUrls = effectiveStunUrls,
            turnUrls = effectiveTurnUrls,
            isTurnConfigured = isTurnConfigured,
            isShortLivedTurnActive = isShortLivedTurnActive,
            usedDefaultStunFallback = usedDefaultStunFallback,
            validationWarnings = warnings
        )
    }

    /**
     * Reads the four WebRTC ICE configuration keys from `com.example.BuildConfig` (generated by the
     * Google Secrets Gradle Plugin from `.env` and `.env.example`), with fallback to process environment variables.
     */
    fun readBuildConfigOrEnvironmentValues(): Map<String, String> {
        val keys = listOf(
            KEY_WEBRTC_STUN_URLS,
            KEY_WEBRTC_TURN_URLS,
            KEY_WEBRTC_TURN_USERNAME,
            KEY_WEBRTC_TURN_CREDENTIAL
        )
        val result = LinkedHashMap<String, String>(keys.size)
        val buildConfigClass = runCatching { Class.forName("com.example.BuildConfig") }.getOrNull()
        for (key in keys) {
            val fromBuildConfig = runCatching {
                (buildConfigClass?.getField(key)?.get(null) as? String)?.trim().orEmpty()
            }.getOrDefault("")
            val fromEnv = runCatching { System.getenv(key)?.trim().orEmpty() }.getOrDefault("")
            val resolved = when {
                !isPlaceholderOrEmpty(fromBuildConfig) -> fromBuildConfig
                !isPlaceholderOrEmpty(fromEnv) -> fromEnv
                else -> fromBuildConfig.ifBlank { fromEnv }
            }
            result[key] = resolved
        }
        return result
    }

    /**
     * Resolves the active [ResolvedWebRtcIceConfiguration] using [overrides] (when provided) or
     * [readBuildConfigOrEnvironmentValues].
     */
    fun resolveIceConfiguration(
        overrides: Map<String, String?>? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): ResolvedWebRtcIceConfiguration {
        val source = overrides ?: readBuildConfigOrEnvironmentValues()
        return resolveFromValues(
            rawStunUrls = source[KEY_WEBRTC_STUN_URLS],
            rawTurnUrls = source[KEY_WEBRTC_TURN_URLS],
            rawTurnUsername = source[KEY_WEBRTC_TURN_USERNAME],
            rawTurnCredential = source[KEY_WEBRTC_TURN_CREDENTIAL],
            shortLivedOverride = cachedShortLivedTurn,
            nowMillis = nowMillis
        )
    }

    fun getCachedShortLivedTurnCredentials(): ShortLivedTurnCredentials? = cachedShortLivedTurn

    fun cacheShortLivedTurnCredentials(credentials: ShortLivedTurnCredentials?) {
        cachedShortLivedTurn = credentials
    }

    fun clearCachedShortLivedTurnCredentials() {
        cachedShortLivedTurn = null
    }

    /**
     * Computes the HMAC-SHA256 signature used to authenticate `/api/webrtc/ice-config` requests
     * against the NYXORA Connect backend so short-lived TURN credentials are only issued to authorized room members.
     */
    fun computeTurnConfigRequestHmac(
        signingKey: String,
        roomId: String,
        deviceId: String,
        timestampMillis: Long
    ): String {
        val cleanKey = signingKey.trim().uppercase().ifBlank { "DEFAULT-UNAUTH-KEY" }
        val canonical = "NYXORA_TURN_CRED_V1|${roomId.trim().uppercase()}|${deviceId.trim()}|$timestampMillis"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cleanKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Converts a NYXORA Connect WebSocket (`wss://` or `ws://`) or HTTP(S) endpoint into the
     * backend `/api/webrtc/ice-config` URL.
     */
    fun buildBackendIceConfigUrl(
        endpointUrl: String,
        roomId: String,
        deviceId: String
    ): String? {
        val trimmed = endpointUrl.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val uri = URI(trimmed)
            val rawScheme = uri.scheme?.lowercase() ?: return null
            val httpScheme = when (rawScheme) {
                "wss", "https" -> "https"
                "ws", "http" -> "http"
                else -> return null
            }
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            val encRoom = URLEncoder.encode(roomId.trim().uppercase(), "UTF-8")
            val encDevice = URLEncoder.encode(deviceId.trim(), "UTF-8")
            "$httpScheme://$host$portPart/api/webrtc/ice-config?room=$encRoom&device=$encDevice"
        }.getOrNull()
    }

    /**
     * Parses and validates a JSON response from the backend `/api/webrtc/ice-config` endpoint into
     * [ShortLivedTurnCredentials]. Returns `null` if TURN is not configured on the backend, if
     * credentials are missing/malformed, or if the credentials are already expired.
     */
    fun parseShortLivedTurnResponse(
        rawJson: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): ShortLivedTurnCredentials? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(rawJson)
            val stunUrls = extractUrlListFromJsonField(obj, "stunUrls", allowStun = true, allowTurn = false)
            val turnUrls = extractUrlListFromJsonField(obj, "turnUrls", allowStun = false, allowTurn = true)
                .ifEmpty {
                    extractUrlListFromJsonField(obj, "urls", allowStun = false, allowTurn = true)
                }

            val username = obj.optString("turnUsername", "")
                .ifBlank { obj.optString("username", "") }
                .trim()
            val credential = obj.optString("turnCredential", "")
                .ifBlank { obj.optString("credential", "") }
                .trim()

            if (turnUrls.isEmpty() || isPlaceholderOrEmpty(username) || isPlaceholderOrEmpty(credential)) {
                return null
            }

            val ttlSeconds = obj.optLong(
                "ttlSeconds",
                ShortLivedTurnCredentials.DEFAULT_SHORT_LIVED_TTL_SECONDS
            ).coerceIn(60L, 86_400L)

            val explicitExpiresAt = obj.optLong("expiresAtMillis", 0L)
            val expiresAtMillis = if (explicitExpiresAt > 0L) {
                explicitExpiresAt
            } else {
                nowMillis + (ttlSeconds * 1000L)
            }

            val creds = ShortLivedTurnCredentials(
                turnUrls = turnUrls,
                username = username,
                credential = credential,
                ttlSeconds = ttlSeconds,
                expiresAtMillis = expiresAtMillis,
                issuedAtMillis = nowMillis,
                stunUrls = stunUrls
            )
            if (creds.isExpired(nowMillis, safetyMarginMillis = 0L)) {
                null
            } else {
                creds
            }
        }.getOrNull()
    }

    private fun extractUrlListFromJsonField(
        obj: JSONObject,
        fieldName: String,
        allowStun: Boolean,
        allowTurn: Boolean
    ): List<String> {
        val rawValue = obj.opt(fieldName) ?: return emptyList()
        return when (rawValue) {
            is JSONArray -> {
                val list = mutableListOf<String>()
                for (i in 0 until rawValue.length()) {
                    val item = rawValue.optString(i, "")
                    val validated = validateIceServerUri(item, allowStun = allowStun, allowTurn = allowTurn)
                    if (validated != null) {
                        list.add(validated)
                    }
                }
                list.distinct()
            }
            is String -> {
                parseIceUrlsInternal(rawValue, allowStun = allowStun, allowTurn = allowTurn).validUrls
            }
            else -> emptyList()
        }
    }

    /**
     * Fetches short-lived TURN credentials from the NYXORA Connect backend over HTTP/HTTPS,
     * validates the returned URLs and expiry, and caches them in memory for WebRTC peer connections.
     */
    fun fetchShortLivedTurnCredentials(
        endpointUrl: String,
        roomId: String,
        deviceId: String,
        signingKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<ShortLivedTurnCredentials> {
        val targetUrl = buildBackendIceConfigUrl(endpointUrl, roomId, deviceId)
            ?: return Result.failure(IllegalArgumentException("Invalid backend endpoint URL for ICE config"))
        val cleanRoom = roomId.trim().uppercase()
        val cleanDevice = deviceId.trim()
        val signature = computeTurnConfigRequestHmac(
            signingKey = signingKey,
            roomId = cleanRoom,
            deviceId = cleanDevice,
            timestampMillis = nowMillis
        )

        return runCatching {
            val request = Request.Builder()
                .url(URL(targetUrl))
                .get()
                .header("Accept", "application/json")
                .header("X-Nyxora-Room", cleanRoom)
                .header("X-Nyxora-Device", cleanDevice)
                .header("X-Nyxora-Timestamp", nowMillis.toString())
                .header("X-Nyxora-Signature", signature)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Backend ICE config endpoint returned HTTP ${response.code}")
                }
                val bodyText = response.body?.string().orEmpty()
                val parsed = parseShortLivedTurnResponse(bodyText, nowMillis = nowMillis)
                    ?: throw IllegalStateException("Backend did not return valid active short-lived TURN credentials")
                cachedShortLivedTurn = parsed
                parsed
            }
        }
    }
}
