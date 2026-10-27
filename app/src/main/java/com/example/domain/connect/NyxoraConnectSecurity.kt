package com.example.domain.connect

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ParsedRoomInvite(
    val roomId: String,
    val accessKey: String,
    val endpointUrl: String,
    val roomName: String = ""
)

/**
 * Security, cryptographic authorization, privacy sanitization, rate limiting, and deduplication
 * utilities for NYXORA Connect.
 */
object NyxoraConnectSecurity {

    private const val CROCKFORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    private val ROOM_ID_REGEX = Regex("^NYX-[A-Z2-9]{4}-[A-Z2-9]{4}$")

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

    fun validateRoomIdFormat(rawRoomId: String): Boolean {
        val normalized = rawRoomId.trim().uppercase()
        return ROOM_ID_REGEX.matches(normalized)
    }

    fun normalizeRoomId(rawRoomId: String): String {
        val cleaned = rawRoomId.trim().uppercase().replace(" ", "")
        if (cleaned.length == 8 && cleaned.all { it in CROCKFORD_ALPHABET }) {
            return "NYX-${cleaned.substring(0, 4)}-${cleaned.substring(4, 8)}"
        }
        return cleaned
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

    fun buildRoomInviteLink(
        roomId: String,
        accessKey: String,
        endpointUrl: String,
        roomName: String
    ): String {
        val encRoom = URLEncoder.encode(roomId.trim(), "UTF-8")
        val encKey = URLEncoder.encode(accessKey.trim(), "UTF-8")
        val encEndpoint = URLEncoder.encode(endpointUrl.trim(), "UTF-8")
        val encName = URLEncoder.encode(roomName.trim().take(48), "UTF-8")
        return "nyxora://connect?room=$encRoom&key=$encKey&endpoint=$encEndpoint&name=$encName"
    }

    fun parseRoomInviteInput(rawInput: String): ParsedRoomInvite? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("nyxora://connect", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return runCatching {
                val queryPart = trimmed.substringAfter('?', "")
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
                ParsedRoomInvite(
                    roomId = room,
                    accessKey = (params["key"] ?: params["token"] ?: "").trim().uppercase(),
                    endpointUrl = (params["endpoint"] ?: params["ws"] ?: "").trim(),
                    roomName = (params["name"] ?: "").trim()
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
