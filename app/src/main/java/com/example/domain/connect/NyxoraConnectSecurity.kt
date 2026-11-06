package com.example.domain.connect

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class InvitationValidationStatus(val errorCode: String, val userMessage: String) {
    VALID("OK", "Invitation valid"),
    INVALID_FORMAT("INVITE_INVALID", "Invalid room invitation link or token format"),
    EXPIRED("INVITE_EXPIRED", "This room invitation has expired"),
    REVOKED("INVITE_REVOKED", "This room invitation has been revoked by the host"),
    ALREADY_USED("INVITE_ALREADY_USED", "This single-use room invitation has already been used"),
    UNAUTHORIZED_SIGNATURE("INVITE_UNAUTHORIZED", "Invalid room invitation signature or credentials")
}

data class ParsedRoomInvite(
    val roomId: String,
    val accessKey: String = "",
    val endpointUrl: String = "",
    val roomName: String = "",
    val inviteToken: String = "",
    val tokenId: String = "",
    val expiresAtMillis: Long = 0L,
    val singleUse: Boolean = false,
    val tokenHmac: String = "",
    val validationStatus: InvitationValidationStatus = InvitationValidationStatus.VALID
) {
    /**
     * Effective signing secret used by a client during the WebSocket join handshake:
     * - For modern HTTPS invitations (`inv` token), uses the time-bound `tokenHmac` without
     *   exposing the room's master `accessKey` in the URL.
     * - For manual Room ID + Access Key entry or legacy deep links, uses `accessKey`.
     */
    val handshakeSigningSecret: String
        get() = accessKey.ifBlank {
            tokenHmac.ifBlank {
                NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(roomId)
            }
        }
}

/**
 * Security, cryptographic authorization, Room Code handshake derivation,
 * privacy sanitization, rate limiting, and deduplication utilities for NYXORA Connect.
 */
object NyxoraConnectSecurity {

    const val DEFAULT_APP_LINK_DOMAIN = "nyxora.app"
    const val SHARED_PREVIEW_APP_LINK_DOMAIN = "ais-pre-u42jr4njt6qbwei6oxmogw-667500831282.asia-east1.run.app"
    const val DEFAULT_PRODUCTION_WSS_ENDPOINT = "wss://n-browser.onrender.com/nyxora"
    const val LEGACY_PRODUCTION_WSS_ENDPOINT = "wss://connect.nyxora.app/nyxora"
    const val DEFAULT_INVITE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    private const val CROCKFORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    private val ROOM_ID_REGEX = Regex("^NYX-[A-Z2-9]{4}-[A-Z2-9]{4}$")
    private val EMBEDDED_ROOM_ID_REGEX = Regex("NYX-[A-Z2-9]{4}-[A-Z2-9]{4}")
    private val TOKEN_ID_REGEX = Regex("^[A-Z2-9]{8,16}$")
    private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val LOG_QUERY_SECRET_REGEX = Regex("(?i)(inv|key|token|access_key|access_token|id_token|refresh_token|password|passwd|pwd|secret|client_secret|session_id|api_key|apikey|bearer|turn_credential|turncredential)=([^&\\s\"']+)")
    private val LOG_JSON_SECRET_REGEX = Regex("(?i)\"(accessKey|inviteToken|hmacSignature|handshakeSecret|password|secret|token|turnCredential|turnPassword|iceCredential)\"\\s*:\\s*\"([^\"]+)\"")
    private val LOG_INVITE_TOKEN_INLINE_REGEX = Regex("\\bINV[A-Z2-9]{6,16}\\.\\d{10,16}\\.[01]\\.[0-9a-fA-F]{32,64}\\b")
    private val LOG_HEX64_INLINE_REGEX = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val LOG_ACCESS_KEY_PATTERN = Regex("\\b[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}\\b")
    private val LOG_BEARER_HEADER_REGEX = Regex("(?i)Bearer\\s+[A-Za-z0-9\\-_\\.=]+")
    private val LOG_URL_USERINFO_REGEX = Regex("(?i)(https?://)([^/\\s:@]+:[^/\\s@]+)@")
    const val MAX_CHAT_MESSAGE_LENGTH = 500
    const val DEFAULT_WEBRTC_STUN_URLS = NyxoraWebRtcIceConfigProvider.DEFAULT_GOOGLE_STUN_URL

    /**
     * Resolves the configured HTTPS App Link domain from BuildConfig (populated via .env / AI Studio Secrets)
     * or falls back to [DEFAULT_APP_LINK_DOMAIN].
     */
    fun configuredInviteDomain(): String {
        val fromBuildConfig = runCatching {
            val clazz = Class.forName("com.example.BuildConfig")
            (clazz.getField("NYXORA_INVITE_DOMAIN").get(null) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        return fromBuildConfig
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .ifBlank { DEFAULT_APP_LINK_DOMAIN }
    }

    /**
     * Resolves the configured public WSS relay endpoint from BuildConfig (populated via .env / AI Studio Secrets)
     * or falls back to [DEFAULT_PRODUCTION_WSS_ENDPOINT] ("wss://n-browser.onrender.com/nyxora").
     */
    fun configuredPublicWssEndpoint(): String {
        val fromBuildConfig = runCatching {
            val clazz = Class.forName("com.example.BuildConfig")
            (clazz.getField("NYXORA_CONNECT_WSS_ENDPOINT").get(null) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val sanitized = sanitizePublicEndpointForInvite(fromBuildConfig)
        return if (sanitized.isBlank() || sanitized.equals(LEGACY_PRODUCTION_WSS_ENDPOINT, ignoreCase = true)) {
            DEFAULT_PRODUCTION_WSS_ENDPOINT
        } else {
            sanitized
        }
    }

    /**
     * Resolves configured WebRTC STUN and optional TURN ICE servers via the centralized,
     * validated [NyxoraWebRtcIceConfigProvider]. Never hardcodes TURN credentials in source code.
     */
    fun configuredWebRtcIceServers(
        overrides: Map<String, String?>? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): List<WebRtcIceServerConfig> {
        return NyxoraWebRtcIceConfigProvider.resolveIceConfiguration(
            overrides = overrides,
            nowMillis = nowMillis
        ).iceServers
    }

    /**
     * Reports whether an authenticated TURN relay server is configured via short-lived backend credentials
     * or environment/BuildConfig.
     */
    fun hasConfiguredTurnServer(
        overrides: Map<String, String?>? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return NyxoraWebRtcIceConfigProvider.resolveIceConfiguration(
            overrides = overrides,
            nowMillis = nowMillis
        ).isTurnConfigured
    }

    /**
     * Redacts invitation tokens, room access keys, TURN credentials, and HMAC signatures from diagnostic log strings
     * so sensitive credentials are never written to logs or crash reports.
     */
    fun redactSecretsForLog(rawMessage: String): String {
        if (rawMessage.isBlank()) return rawMessage
        return rawMessage
            .replace(LOG_URL_USERINFO_REGEX) { match -> "${match.groupValues[1]}[REDACTED]@" }
            .replace(LOG_QUERY_SECRET_REGEX) { match -> "${match.groupValues[1]}=[REDACTED]" }
            .replace(LOG_JSON_SECRET_REGEX) { match -> "\"${match.groupValues[1]}\":\"[REDACTED]\"" }
            .replace(LOG_INVITE_TOKEN_INLINE_REGEX, "[REDACTED_TOKEN]")
            .replace(LOG_BEARER_HEADER_REGEX, "Bearer [REDACTED]")
            .replace(LOG_HEX64_INLINE_REGEX, "[REDACTED_HMAC]")
            .replace(LOG_ACCESS_KEY_PATTERN, "[REDACTED_KEY]")
    }

    /**
     * Sanitizes a room chat message before transmitting or broadcasting across connected devices:
     * - Rejects empty/blank messages or control-only strings.
     * - Strips control characters and caps length at [MAX_CHAT_MESSAGE_LENGTH].
     * - Redacts any invitation tokens, room access keys, HMAC signatures, Bearer tokens, or URL credentials
     *   so sensitive secrets are never exposed in chat messages.
     */
    fun sanitizeChatMessageForSync(
        rawMessage: String,
        roomAccessKey: String = ""
    ): String? {
        val normalizedWhitespace = rawMessage
            .replace(Regex("[\\p{Cntrl}&&[^\n\t]]"), "")
            .replace('\t', ' ')
            .trim()
        if (normalizedWhitespace.isEmpty()) return null
        var cleaned = redactSecretsForLog(normalizedWhitespace)
        val trimmedKey = roomAccessKey.trim()
        if (trimmedKey.length >= 8) {
            cleaned = cleaned.replace(trimmedKey, "[REDACTED_KEY]", ignoreCase = true)
        }
        val bounded = cleaned.take(MAX_CHAT_MESSAGE_LENGTH).trim()
        return bounded.ifEmpty { null }
    }

    // Client-side registry of revoked and consumed single-use invitation token IDs
    private val revokedInviteTokenIds = ConcurrentHashMap.newKeySet<String>()
    private val consumedSingleUseTokenOwners = ConcurrentHashMap<String, String>()

    // Sensitive query parameter keys that must NEVER be transmitted across devices
    private val SENSITIVE_QUERY_KEYS = setOf(
        "access_token",
        "id_token",
        "refresh_token",
        "token",
        "auth",
        "auth_token",
        "password",
        "passwd",
        "pwd",
        "secret",
        "client_secret",
        "session",
        "session_id",
        "sessionid",
        "jsessionid",
        "phpsessid",
        "sid",
        "code",
        "api_key",
        "apikey",
        "key",
        "jwt",
        "bearer",
        "csrf",
        "csrf_token",
        "xsrf_token",
        "signature",
        "sig"
    )

    fun generateSecureRoomId(): String {
        val sb = StringBuilder("NYX-")
        for (i in 0 until 8) {
            if (i == 4) sb.append('-')
            val idx = secureRandom.nextInt(CROCKFORD_ALPHABET.length)
            sb.append(CROCKFORD_ALPHABET[idx])
        }
        return sb.toString()
    }

    fun generateRoomAccessKey(): String {
        val sb = StringBuilder()
        for (i in 0 until 16) {
            if (i > 0 && i % 4 == 0) sb.append('-')
            val idx = secureRandom.nextInt(CROCKFORD_ALPHABET.length)
            sb.append(CROCKFORD_ALPHABET[idx])
        }
        return sb.toString()
    }

    fun generateInvitationTokenId(): String {
        val sb = StringBuilder("INV")
        for (i in 0 until 9) {
            val idx = secureRandom.nextInt(CROCKFORD_ALPHABET.length)
            sb.append(CROCKFORD_ALPHABET[idx])
        }
        return sb.toString()
    }

    fun validateRoomIdFormat(rawRoomId: String): Boolean {
        val normalized = rawRoomId.trim().uppercase()
        return ROOM_ID_REGEX.matches(normalized)
    }

    fun normalizeRoomId(rawRoomId: String): String {
        val cleaned = rawRoomId.trim().uppercase().replace(" ", "")
        if (ROOM_ID_REGEX.matches(cleaned)) return cleaned
        val embedded = EMBEDDED_ROOM_ID_REGEX.find(rawRoomId.uppercase())?.value
        if (embedded != null && ROOM_ID_REGEX.matches(embedded)) return embedded
        val stripped = cleaned.removePrefix("NYX-").removePrefix("NYX").replace("-", "")
        if (stripped.length == 8 && stripped.all { it in CROCKFORD_ALPHABET }) {
            return "NYX-${stripped.substring(0, 4)}-${stripped.substring(4, 8)}"
        }
        return cleaned
    }

    /**
     * Derives an ephemeral, room-scoped 16-character Crockford Base32 handshake key
     * (`XXXX-XXXX-XXXX-XXXX`) from the normalized Room Code (`NYX-XXXX-XXXX`).
     *
     * Enables code-only participant joining over TLS (`wss://`) without requiring manual
     * access key entry, invitation URLs, or storing permanent server credentials in the app.
     */
    fun deriveRoomCodeHandshakeKey(rawRoomId: String): String {
        val normalized = normalizeRoomId(rawRoomId)
        if (!validateRoomIdFormat(normalized)) return ""
        val mac = Mac.getInstance("HmacSHA256")
        val domainKey = "NYXORA_ROOM_CODE_HANDSHAKE_V1".toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(domainKey, "HmacSHA256"))
        val digest = mac.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 16) {
            if (i > 0 && i % 4 == 0) sb.append('-')
            val idx = (digest[i].toInt() and 0xFF) % CROCKFORD_ALPHABET.length
            sb.append(CROCKFORD_ALPHABET[idx])
        }
        return sb.toString()
    }

    /**
     * Computes the HMAC-SHA256 signature for a time-bound, revocable room invitation token.
     * The raw room accessKey is used as the HMAC key and is NEVER embedded in the token or URL.
     */
    fun computeInviteTokenHmac(
        accessKey: String,
        roomId: String,
        tokenId: String,
        expiresAtMillis: Long,
        singleUse: Boolean
    ): String {
        val cleanKey = accessKey.trim().uppercase().ifBlank { "DEFAULT-UNAUTH-KEY" }
        val canonical = "NYXORA_INVITE_V1|${roomId.trim().uppercase()}|${tokenId.trim().uppercase()}|$expiresAtMillis|${if (singleUse) 1 else 0}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cleanKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Issues a signed, time-bound room invitation token (`<tokenId>.<expiresAtMillis>.<singleUseFlag>.<hmac>`).
     */
    fun issueRoomInvitationToken(
        roomId: String,
        accessKey: String,
        ttlMillis: Long = DEFAULT_INVITE_TTL_MS,
        singleUse: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        explicitTokenId: String = ""
    ): String {
        val normalizedRoom = normalizeRoomId(roomId)
        val tokenId = explicitTokenId.trim().uppercase().ifBlank { generateInvitationTokenId() }
        val expiresAt = nowMillis + ttlMillis
        val hmac = computeInviteTokenHmac(
            accessKey = accessKey,
            roomId = normalizedRoom,
            tokenId = tokenId,
            expiresAtMillis = expiresAt,
            singleUse = singleUse
        )
        val singleUseFlag = if (singleUse) "1" else "0"
        return "$tokenId.$expiresAt.$singleUseFlag.$hmac"
    }

    fun revokeInvitationToken(tokenOrId: String) {
        val trimmed = tokenOrId.trim()
        if (trimmed.isEmpty()) return
        val extractedId = trimmed.substringBefore('.').trim().uppercase()
        if (extractedId.isNotEmpty()) {
            revokedInviteTokenIds.add(extractedId)
        }
    }

    fun isInvitationTokenRevoked(tokenId: String): Boolean {
        return revokedInviteTokenIds.contains(tokenId.trim().uppercase())
    }

    fun markSingleUseInvitationConsumed(tokenId: String, deviceId: String): Boolean {
        val cleanId = tokenId.trim().uppercase()
        val cleanDevice = deviceId.trim()
        if (cleanId.isEmpty() || cleanDevice.isEmpty()) return false
        val existingOwner = consumedSingleUseTokenOwners.putIfAbsent(cleanId, cleanDevice)
        return existingOwner == null || existingOwner == cleanDevice
    }

    fun isSingleUseInvitationAlreadyUsedByOther(tokenId: String, deviceId: String): Boolean {
        val cleanId = tokenId.trim().uppercase()
        if (cleanId.isEmpty()) return false
        val owner = consumedSingleUseTokenOwners[cleanId] ?: return false
        return owner != deviceId.trim()
    }

    fun clearInvitationRegistryForTesting() {
        revokedInviteTokenIds.clear()
        consumedSingleUseTokenOwners.clear()
    }

    /**
     * Validates a parsed invitation against expiration, revocation, single-use state,
     * and (when the room authority verifies it) cryptographic HMAC signature.
     */
    fun verifyInvitationTokenAgainstRoom(
        parsed: ParsedRoomInvite,
        roomId: String,
        roomAccessKey: String,
        requestingDeviceId: String,
        nowMillis: Long = System.currentTimeMillis(),
        serverRevokedTokenIds: Set<String> = emptySet(),
        serverConsumedTokenOwners: Map<String, String> = emptyMap()
    ): InvitationValidationStatus {
        if (!validateRoomIdFormat(roomId) || !parsed.roomId.equals(roomId.trim(), ignoreCase = true)) {
            return InvitationValidationStatus.INVALID_FORMAT
        }
        if (parsed.validationStatus != InvitationValidationStatus.VALID) {
            return parsed.validationStatus
        }
        if (parsed.inviteToken.isNotBlank()) {
            val cleanTokenId = parsed.tokenId.trim().uppercase()
            if (!TOKEN_ID_REGEX.matches(cleanTokenId) || !HEX_64_REGEX.matches(parsed.tokenHmac)) {
                return InvitationValidationStatus.INVALID_FORMAT
            }
            if (isInvitationTokenRevoked(cleanTokenId) || serverRevokedTokenIds.contains(cleanTokenId)) {
                return InvitationValidationStatus.REVOKED
            }
            if (nowMillis > parsed.expiresAtMillis) {
                return InvitationValidationStatus.EXPIRED
            }
            if (parsed.singleUse) {
                val serverOwner = serverConsumedTokenOwners[cleanTokenId]
                if ((serverOwner != null && serverOwner != requestingDeviceId) ||
                    isSingleUseInvitationAlreadyUsedByOther(cleanTokenId, requestingDeviceId)
                ) {
                    return InvitationValidationStatus.ALREADY_USED
                }
            }
            val expectedHmac = computeInviteTokenHmac(
                accessKey = roomAccessKey,
                roomId = roomId,
                tokenId = cleanTokenId,
                expiresAtMillis = parsed.expiresAtMillis,
                singleUse = parsed.singleUse
            )
            val derivedKey = deriveRoomCodeHandshakeKey(roomId)
            val expectedDerivedHmac = if (derivedKey.isNotBlank()) {
                computeInviteTokenHmac(
                    accessKey = derivedKey,
                    roomId = roomId,
                    tokenId = cleanTokenId,
                    expiresAtMillis = parsed.expiresAtMillis,
                    singleUse = parsed.singleUse
                )
            } else {
                ""
            }
            val matchesRoomKey = MessageDigest.isEqual(
                expectedHmac.lowercase().toByteArray(Charsets.UTF_8),
                parsed.tokenHmac.lowercase().toByteArray(Charsets.UTF_8)
            )
            val matchesDerivedKey = expectedDerivedHmac.isNotBlank() && MessageDigest.isEqual(
                expectedDerivedHmac.lowercase().toByteArray(Charsets.UTF_8),
                parsed.tokenHmac.lowercase().toByteArray(Charsets.UTF_8)
            )
            return if (matchesRoomKey || matchesDerivedKey) {
                InvitationValidationStatus.VALID
            } else {
                InvitationValidationStatus.UNAUTHORIZED_SIGNATURE
            }
        }

        // Legacy or manual Room ID + Access Key join
        if (parsed.accessKey.isNotBlank() && roomAccessKey.isNotBlank()) {
            val derivedKey = deriveRoomCodeHandshakeKey(roomId)
            val matchesRoomKey = MessageDigest.isEqual(
                roomAccessKey.trim().uppercase().toByteArray(Charsets.UTF_8),
                parsed.accessKey.trim().uppercase().toByteArray(Charsets.UTF_8)
            )
            val matchesDerivedKey = derivedKey.isNotBlank() && MessageDigest.isEqual(
                derivedKey.trim().uppercase().toByteArray(Charsets.UTF_8),
                parsed.accessKey.trim().uppercase().toByteArray(Charsets.UTF_8)
            )
            return if (matchesRoomKey || matchesDerivedKey) {
                InvitationValidationStatus.VALID
            } else {
                InvitationValidationStatus.UNAUTHORIZED_SIGNATURE
            }
        }

        return InvitationValidationStatus.VALID
    }

    /**
     * Checks whether an endpoint URL points to a private/internal/loopback network address
     * (e.g. `127.0.0.1`, `localhost`, `192.0.0.2`, `10.x.x.x`, `192.168.x.x`, `172.16-31.x.x`).
     * Internal addresses must NEVER be embedded in publicly shared invitation URLs.
     */
    fun isInternalOrPrivateEndpoint(rawEndpoint: String): Boolean {
        val trimmed = rawEndpoint.trim()
        if (trimmed.isEmpty()) return true
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return true
            if (scheme != "wss" && scheme != "ws") return true
            val host = uri.host?.lowercase()?.trim('[', ']') ?: return true
            if (host.isEmpty()) return true
            if (host == "localhost" ||
                host == "0.0.0.0" ||
                host == "::1" ||
                host.endsWith(".local") ||
                host.endsWith(".internal") ||
                host.endsWith(".lan") ||
                host.endsWith(".invalid") ||
                host.startsWith("127.") ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.startsWith("192.0.0.") ||
                host.startsWith("192.0.2.") ||
                host.startsWith("198.51.100.") ||
                host.startsWith("203.0.113.") ||
                host.startsWith("169.254.") ||
                host.startsWith("100.64.")
            ) {
                return true
            }
            if (host.startsWith("172.")) {
                val secondOctet = host.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
            false
        }.getOrDefault(true)
    }

    /**
     * Returns the endpoint ONLY if it is a publicly reachable `wss://` endpoint on a non-private domain.
     * Strips any `ws://`, `localhost`, `127.0.0.1`, `192.0.0.2`, `192.168.x.x`, or `10.x.x.x` endpoint.
     */
    fun sanitizePublicEndpointForInvite(rawEndpoint: String): String {
        val trimmed = rawEndpoint.trim()
        if (!trimmed.startsWith("wss://", ignoreCase = true)) return ""
        if (isInternalOrPrivateEndpoint(trimmed)) return ""
        return trimmed
    }

    /**
     * Determines whether a URL or user input is a NYXORA Connect room invitation deep link
     * (`https://<domain>/connect?room=...`, `https://<domain>/invite?room=...`, or `nyxora://connect?room=...`).
     */
    fun isNyxoraConnectInvitationUrl(rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("nyxora://connect", ignoreCase = true)) return true
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return false
        }
        return runCatching {
            val uri = URI(trimmed)
            val path = uri.path?.lowercase() ?: ""
            val query = uri.rawQuery?.lowercase() ?: ""
            val isConnectPath = path == "/connect" ||
                path.startsWith("/connect/") ||
                path == "/invite" ||
                path.startsWith("/invite/")
            val hasRoomParam = query.contains("room=") || query.contains("inv=")
            isConnectPath && hasRoomParam
        }.getOrDefault(false)
    }

    fun computeEventHmac(
        accessKey: String,
        roomId: String,
        eventId: String,
        type: String,
        senderDeviceId: String,
        authorityEpoch: Long,
        sequenceNumber: Long,
        timestampMillis: Long,
        payloadJson: String
    ): String {
        val cleanKey = accessKey.trim().uppercase().ifBlank { "DEFAULT-UNAUTH-KEY" }
        val canonicalMessage = buildString {
            append(roomId.trim().uppercase())
            append('|')
            append(eventId)
            append('|')
            append(type)
            append('|')
            append(senderDeviceId)
            append('|')
            append(authorityEpoch)
            append('|')
            append(sequenceNumber)
            append('|')
            append(timestampMillis)
            append('|')
            append(payloadJson)
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cleanKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(canonicalMessage.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyEventHmac(
        accessKey: String,
        envelope: RealtimeEventEnvelope
    ): Boolean {
        if (accessKey.isBlank() || envelope.hmacSignature.isBlank()) return false
        val expected = computeEventHmac(
            accessKey = accessKey,
            roomId = envelope.roomId,
            eventId = envelope.eventId,
            type = envelope.type.name,
            senderDeviceId = envelope.senderDeviceId,
            authorityEpoch = envelope.authorityEpoch,
            sequenceNumber = envelope.sequenceNumber,
            timestampMillis = envelope.timestampMillis,
            payloadJson = envelope.payloadJson
        )
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            envelope.hmacSignature.lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Strictly sanitizes a URL before it can be synchronized across devices:
     * - Blocks all synchronization when in Private / Incognito mode or on a Private tab.
     * - Permits only http://, https://, or nyxora://home.
     * - Strips userinfo credentials (user:pass@host) and sensitive query parameters.
     */
    fun sanitizeUrlForSync(
        rawUrl: String,
        isPrivateTab: Boolean,
        isPrivateMode: Boolean
    ): String? {
        if (isPrivateTab || isPrivateMode) return null
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        if (trimmed.equals("nyxora://home", ignoreCase = true)) {
            return "nyxora://home"
        }

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            if (host.isBlank()) return null

            // Strip any embedded user:password@ credentials by reconstructing with null userInfo
            val rawQuery = uri.rawQuery
            val sanitizedQuery = if (rawQuery.isNullOrBlank()) {
                null
            } else {
                rawQuery.split('&')
                    .mapNotNull { param ->
                        val key = param.substringBefore('=').trim()
                        val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key).lowercase()
                        if (decodedKey in SENSITIVE_QUERY_KEYS) {
                            null
                        } else {
                            param
                        }
                    }
                    .joinToString("&")
                    .ifBlank { null }
            }

            // Also sanitize fragment if it contains token-like parameters (e.g. #access_token=...)
            val rawFragment = uri.rawFragment
            val sanitizedFragment = if (rawFragment.isNullOrBlank()) {
                null
            } else {
                val lowerFrag = rawFragment.lowercase()
                if (SENSITIVE_QUERY_KEYS.any { lowerFrag.contains("$it=") }) null else rawFragment
            }

            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val pathPart = uri.rawPath?.ifEmpty { "/" } ?: "/"
            buildString {
                append(scheme)
                append("://")
                append(host)
                append(portPart)
                append(pathPart)
                if (!sanitizedQuery.isNullOrBlank()) {
                    append('?')
                    append(sanitizedQuery)
                }
                if (!sanitizedFragment.isNullOrBlank()) {
                    append('#')
                    append(sanitizedFragment)
                }
            }
        }.getOrNull()
    }

    /**
     * Generates a standard, clickable HTTPS invitation URL suitable for WhatsApp, Chrome, SMS,
     * and Android App Links (`https://<domain>/connect?room=...&inv=...&name=...`).
     *
     * Security guarantees:
     * 1. Uses `https://` instead of a custom `nyxora://` URI scheme so messaging apps render a clickable link.
     * 2. Embeds a signed, time-bound, revocable `inv` token instead of exposing the room's permanent `accessKey`.
     * 3. Never exposes internal/private LAN or carrier-NAT IP addresses (`192.0.0.2`, `127.0.0.1`, `192.168.x.x`, `10.x.x.x`).
     */
    fun buildRoomInviteLink(
        roomId: String,
        accessKey: String,
        endpointUrl: String = "",
        roomName: String = "",
        domain: String = configuredInviteDomain(),
        ttlMillis: Long = DEFAULT_INVITE_TTL_MS,
        singleUse: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        explicitInviteToken: String = ""
    ): String {
        val cleanDomain = domain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            .ifBlank { configuredInviteDomain() }
        val normalizedRoom = normalizeRoomId(roomId)
        val inviteToken = explicitInviteToken.trim().ifBlank {
            issueRoomInvitationToken(
                roomId = normalizedRoom,
                accessKey = accessKey,
                ttlMillis = ttlMillis,
                singleUse = singleUse,
                nowMillis = nowMillis
            )
        }
        val publicWssEndpoint = sanitizePublicEndpointForInvite(endpointUrl)
        val encRoom = URLEncoder.encode(normalizedRoom, "UTF-8")
        val encInv = URLEncoder.encode(inviteToken, "UTF-8")
        val encName = URLEncoder.encode(roomName.trim().take(48), "UTF-8")

        return buildString {
            append("https://")
            append(cleanDomain)
            append("/connect?room=")
            append(encRoom)
            append("&inv=")
            append(encInv)
            if (encName.isNotEmpty()) {
                append("&name=")
                append(encName)
            }
            if (publicWssEndpoint.isNotEmpty()) {
                append("&wss=")
                append(URLEncoder.encode(publicWssEndpoint, "UTF-8"))
            }
        }
    }

    /**
     * Parses a room invitation from:
     * - Standard HTTPS App Link URLs (`https://nyxora.app/connect?room=NYX-XXXX-XXXX&inv=...`)
     * - Legacy `nyxora://connect?room=...` deep links
     * - Plain Room ID (`NYX-XXXX-XXXX`) or `ROOM_ID|KEY|ENDPOINT` shorthand
     */
    fun parseRoomInviteInput(
        rawInput: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ParsedRoomInvite? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("nyxora://connect", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return runCatching {
                val queryPart = trimmed.substringAfter('?', "").substringBefore('#')
                if (queryPart.isBlank()) return null
                val params = mutableMapOf<String, String>()
                for (pair in queryPart.split('&')) {
                    val k = pair.substringBefore('=').trim()
                    val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                    if (k.isNotEmpty()) {
                        params[k.lowercase()] = v
                    }
                }
                val room = normalizeRoomId(params["room"] ?: params["id"] ?: "")
                if (!validateRoomIdFormat(room)) return null

                val rawEndpoint = (params["wss"] ?: params["endpoint"] ?: params["ws"] ?: "").trim()
                val safeEndpoint = if (trimmed.startsWith("http", ignoreCase = true)) {
                    // For shared HTTPS links, strip any injected private/internal LAN IP
                    sanitizePublicEndpointForInvite(rawEndpoint)
                } else {
                    rawEndpoint
                }

                val invToken = (params["inv"] ?: "").trim()
                if (invToken.isNotEmpty()) {
                    val parts = invToken.split('.')
                    if (parts.size != 4) {
                        return ParsedRoomInvite(
                            roomId = room,
                            roomName = (params["name"] ?: "").trim(),
                            inviteToken = invToken,
                            validationStatus = InvitationValidationStatus.INVALID_FORMAT
                        )
                    }
                    val tokenId = parts[0].trim().uppercase()
                    val expMillis = parts[1].trim().toLongOrNull() ?: 0L
                    val singleUseFlag = parts[2].trim() == "1"
                    val tokenHmac = parts[3].trim().lowercase()

                    val status = when {
                        !TOKEN_ID_REGEX.matches(tokenId) || expMillis <= 0L || !HEX_64_REGEX.matches(tokenHmac) ->
                            InvitationValidationStatus.INVALID_FORMAT
                        isInvitationTokenRevoked(tokenId) ->
                            InvitationValidationStatus.REVOKED
                        nowMillis > expMillis ->
                            InvitationValidationStatus.EXPIRED
                        else ->
                            InvitationValidationStatus.VALID
                    }

                    return ParsedRoomInvite(
                        roomId = room,
                        accessKey = "",
                        endpointUrl = safeEndpoint,
                        roomName = (params["name"] ?: "").trim(),
                        inviteToken = invToken,
                        tokenId = tokenId,
                        expiresAtMillis = expMillis,
                        singleUse = singleUseFlag,
                        tokenHmac = tokenHmac,
                        validationStatus = status
                    )
                }

                val legacyKey = (params["key"] ?: params["token"] ?: "").trim().uppercase()
                ParsedRoomInvite(
                    roomId = room,
                    accessKey = legacyKey,
                    endpointUrl = safeEndpoint,
                    roomName = (params["name"] ?: "").trim(),
                    validationStatus = if (legacyKey.isNotEmpty()) {
                        InvitationValidationStatus.VALID
                    } else {
                        InvitationValidationStatus.INVALID_FORMAT
                    }
                )
            }.getOrNull()
        }

        // Plain Room ID or "ROOM_ID|KEY|ENDPOINT" shorthand
        if (trimmed.contains('|')) {
            val parts = trimmed.split('|').map { it.trim() }
            val room = normalizeRoomId(parts.getOrNull(0) ?: "")
            if (!validateRoomIdFormat(room)) return null
            return ParsedRoomInvite(
                roomId = room,
                accessKey = (parts.getOrNull(1) ?: "").uppercase(),
                endpointUrl = parts.getOrNull(2) ?: ""
            )
        }

        val normalized = normalizeRoomId(trimmed)
        return if (validateRoomIdFormat(normalized)) {
            ParsedRoomInvite(
                roomId = normalized,
                accessKey = "",
                endpointUrl = ""
            )
        } else {
            null
        }
    }

    fun detectLocalLanIpAddress(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) return ip
                    }
                }
            }
            "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }
}

/**
 * Thread-safe bounded LRU deduplicator protecting against duplicate event processing.
 */
class BoundedEventDeduplicator(private val maxCapacity: Int = 512) {
    private val seenIds = object : LinkedHashMap<String, Boolean>(maxCapacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > maxCapacity
        }
    }

    @Synchronized
    fun markIfNew(eventId: String): Boolean {
        if (eventId.isBlank()) return false
        if (seenIds.containsKey(eventId)) return false
        seenIds[eventId] = true
        return true
    }

    @Synchronized
    fun clear() {
        seenIds.clear()
    }
}

/**
 * Sliding-window rate limiter per device to prevent message flooding.
 */
class SlidingWindowRateLimiter(
    private val maxEventsPerWindow: Int = 24,
    private val windowMillis: Long = 3000L
) {
    private val timestampsByDevice = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(deviceId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (deviceId.isBlank()) return false
        val queue = timestampsByDevice.getOrPut(deviceId) { ArrayDeque() }
        val cutoff = nowMillis - windowMillis
        while (queue.isNotEmpty() && queue.first() < cutoff) {
            queue.removeFirst()
        }
        if (queue.size >= maxEventsPerWindow) {
            return false
        }
        queue.addLast(nowMillis)
        return true
    }

    @Synchronized
    fun removeDevice(deviceId: String) {
        timestampsByDevice.remove(deviceId)
    }

    @Synchronized
    fun clear() {
        timestampsByDevice.clear()
    }
}
