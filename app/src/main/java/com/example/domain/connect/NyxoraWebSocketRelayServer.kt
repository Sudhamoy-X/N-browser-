package com.example.domain.connect

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Standards-compliant RFC 6455 WebSocket Room Authority & Relay Server.
 *
 * Enforces:
 * - Room ID format & expiration checks
 * - Signed HTTPS Invitation Token (`inv`) validation (expiration, revocation, single-use)
 * - Access Key authentication & HMAC-SHA256 frame verification
 * - Configurable max participant limit (default 5) & room lock
 * - Bidirectional URL & navigation sync (Host + authorized Participants)
 * - Duplicate session prevention on disconnect/reconnect
 * - Sliding-window rate limiting & bounded event deduplication
 * - Heartbeat monitoring & stale connection cleanup
 */
class NyxoraWebSocketRelayServer {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private var maintenanceJob: Job? = null

    private val rooms = ConcurrentHashMap<String, HostedRoomContext>()
    private val clients = CopyOnWriteArrayList<ClientSessionConnection>()
    private val rateLimiter = SlidingWindowRateLimiter(maxEventsPerWindow = 30, windowMillis = 3000L)
    private val deduplicator = BoundedEventDeduplicator(maxCapacity = 1024)

    val listeningPort: Int
        get() = serverSocket?.localPort ?: -1

    private class HostedRoomContext(
        @Volatile var state: NyxoraConnectRoomState,
        val sequenceGenerator: AtomicLong = AtomicLong(1L),
        val kickedDeviceIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val revokedInviteTokenIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val consumedSingleUseTokenOwners: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    )

    private inner class ClientSessionConnection(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
        @Volatile var deviceId: String = "",
        @Volatile var roomId: String = "",
        @Volatile var authenticated: Boolean = false,
        @Volatile var lastSeenMillis: Long = System.currentTimeMillis()
    ) {
        private val writeLock = Any()
        private val closed = AtomicBoolean(false)

        fun sendTextFrame(text: String): Boolean {
            if (closed.get() || socket.isClosed) return false
            return synchronized(writeLock) {
                runCatching {
                    val payload = text.toByteArray(Charsets.UTF_8)
                    writeWebSocketFrame(output, opcode = 0x1, payload = payload)
                    output.flush()
                    true
                }.getOrDefault(false)
            }
        }

        fun sendPongFrame(payload: ByteArray) {
            if (closed.get() || socket.isClosed) return
            synchronized(writeLock) {
                runCatching {
                    writeWebSocketFrame(output, opcode = 0xA, payload = payload)
                    output.flush()
                }
            }
        }

        fun closeConnection(code: Int = 1000, reason: String = "Normal closure") {
            if (!closed.compareAndSet(false, true)) return
            synchronized(writeLock) {
                runCatching {
                    val reasonBytes = reason.take(64).toByteArray(Charsets.UTF_8)
                    val closePayload = ByteArray(2 + reasonBytes.size)
                    closePayload[0] = ((code shr 8) and 0xFF).toByte()
                    closePayload[1] = (code and 0xFF).toByte()
                    System.arraycopy(reasonBytes, 0, closePayload, 2, reasonBytes.size)
                    writeWebSocketFrame(output, opcode = 0x8, payload = closePayload)
                    output.flush()
                }
                runCatching { socket.close() }
            }
        }
    }

    @Synchronized
    fun startIfNeeded(): Int {
        if (running.get() && serverSocket != null && serverSocket?.isClosed == false) {
            return serverSocket!!.localPort
        }
        val sock = ServerSocket(0)
        sock.reuseAddress = true
        serverSocket = sock
        running.set(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        acceptJob = scope.launch {
            while (isActive && running.get()) {
                val clientSocket = runCatching { sock.accept() }.getOrNull() ?: break
                scope.launch {
                    handleIncomingSocket(clientSocket)
                }
            }
        }

        maintenanceJob = scope.launch {
            while (isActive && running.get()) {
                delay(5000L)
                performRoomAndHeartbeatMaintenance()
            }
        }

        return sock.localPort
    }

    fun registerNewRoom(initialState: NyxoraConnectRoomState) {
        startIfNeeded()
        rooms[initialState.roomId.uppercase()] = HostedRoomContext(
            state = initialState.copy(roomId = initialState.roomId.uppercase())
        )
    }

    fun getRoomState(roomId: String): NyxoraConnectRoomState? {
        return rooms[roomId.trim().uppercase()]?.state
    }

    fun revokeInvitationToken(roomId: String, tokenOrId: String) {
        val cleanId = tokenOrId.trim().substringBefore('.').trim().uppercase()
        if (cleanId.isEmpty()) return
        NyxoraConnectSecurity.revokeInvitationToken(cleanId)
        val ctx = rooms[roomId.trim().uppercase()] ?: return
        synchronized(ctx) {
            ctx.revokedInviteTokenIds.add(cleanId)
        }
    }

    fun expireRoomForTesting(roomId: String) {
        val ctx = rooms[roomId.trim().uppercase()] ?: return
        synchronized(ctx) {
            ctx.state = ctx.state.copy(expiresAtMillis = System.currentTimeMillis() - 1000L)
        }
    }

    @Synchronized
    fun stopServer() {
        if (!running.compareAndSet(true, false)) return
        for (client in clients) {
            client.closeConnection(1001, "Server shutting down")
        }
        clients.clear()
        rooms.clear()
        rateLimiter.clear()
        deduplicator.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        maintenanceJob?.cancel()
        serverScope?.cancel()
        serverScope = null
    }

    private fun handleIncomingSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 45000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        if (!performWebSocketHandshake(input, output)) {
            runCatching { socket.close() }
            return
        }

        val connection = ClientSessionConnection(socket = socket, input = input, output = output)
        clients.add(connection)

        try {
            while (running.get() && !socket.isClosed) {
                val frame = readWebSocketFrame(input) ?: break
                connection.lastSeenMillis = System.currentTimeMillis()
                when (frame.opcode) {
                    0x1 -> { // Text frame
                        val text = String(frame.payload, Charsets.UTF_8)
                        processIncomingMessage(connection, text)
                    }
                    0x8 -> { // Close frame
                        handleClientDisconnected(connection, explicitLeave = false)
                        break
                    }
                    0x9 -> { // Ping frame
                        connection.sendPongFrame(frame.payload)
                    }
                    0xA -> { // Pong frame
                        // Heartbeat updated via lastSeenMillis
                    }
                }
            }
        } catch (_: Exception) {
            // Socket closed or interrupted
        } finally {
            clients.remove(connection)
            handleClientDisconnected(connection, explicitLeave = false)
            connection.closeConnection()
        }
    }

    private fun processIncomingMessage(conn: ClientSessionConnection, rawText: String) {
        val envelope = RealtimeEventEnvelope.fromJsonString(rawText) ?: return
        val now = System.currentTimeMillis()

        // Rate limit check per sender device
        if (!rateLimiter.tryAcquire(envelope.senderDeviceId, now)) {
            sendErrorEvent(conn, envelope.roomId, "RATE_LIMITED", "Too many requests; slowing down")
            return
        }

        // Duplicate event protection
        if (!deduplicator.markIfNew(envelope.eventId)) {
            return
        }

        val normalizedRoomId = envelope.roomId.trim().uppercase()
        if (!NyxoraConnectSecurity.validateRoomIdFormat(normalizedRoomId)) {
            sendRejectEvent(conn, normalizedRoomId, "INVALID_ROOM_ID", "Invalid Room ID format")
            return
        }

        var roomCtx = rooms[normalizedRoomId]
        if (roomCtx == null && envelope.type == RealtimeEventType.JOIN_REQUEST && envelope.senderRole == RoomRole.HOST) {
            val payloadObj = runCatching { JSONObject(envelope.payloadJson) }.getOrNull()
            val snapshotObj = payloadObj?.optJSONObject("hostRoomSnapshot")
            if (snapshotObj != null) {
                val derivedKey = NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(normalizedRoomId)
                val candidateKey = snapshotObj.optString("accessKey", "").trim().uppercase().ifBlank { derivedKey }
                val validSig = (candidateKey.isNotBlank() && NyxoraConnectSecurity.verifyEventHmac(candidateKey, envelope)) ||
                    (derivedKey.isNotBlank() && NyxoraConnectSecurity.verifyEventHmac(derivedKey, envelope))
                if (validSig) {
                    val parsedRoom = NyxoraConnectRoomState.fromSnapshotJson(
                        obj = snapshotObj,
                        existingAccessKey = candidateKey,
                        fallbackEndpoint = NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT
                    ).copy(
                        roomId = normalizedRoomId,
                        accessKey = candidateKey,
                        hostDeviceId = envelope.senderDeviceId
                    )
                    registerNewRoom(parsedRoom)
                    roomCtx = rooms[normalizedRoomId]
                }
            }
        }
        if (roomCtx == null) {
            sendRejectEvent(conn, normalizedRoomId, "ROOM_NOT_FOUND", "Room does not exist or has closed")
            return
        }

        synchronized(roomCtx) {
            val currentRoom = roomCtx.state
            if (now > currentRoom.expiresAtMillis) {
                rooms.remove(normalizedRoomId)
                sendRejectEvent(conn, normalizedRoomId, "ROOM_EXPIRED", "This room has expired")
                return
            }

            if (roomCtx.kickedDeviceIds.contains(envelope.senderDeviceId)) {
                sendRejectEvent(conn, normalizedRoomId, "PARTICIPANT_REMOVED", "You were removed from this room by the host")
                return
            }

            if (envelope.type == RealtimeEventType.JOIN_REQUEST) {
                handleJoinRequest(conn, roomCtx, envelope, now)
                return
            }

            // For all post-join events, verify HMAC signature using the room's session accessKey or derived room code key
            val derivedPostJoinKey = NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(normalizedRoomId)
            val verifiedPostJoin = NyxoraConnectSecurity.verifyEventHmac(currentRoom.accessKey, envelope) ||
                (derivedPostJoinKey.isNotBlank() && NyxoraConnectSecurity.verifyEventHmac(derivedPostJoinKey, envelope))
            if (!verifiedPostJoin) {
                sendRejectEvent(conn, normalizedRoomId, "UNAUTHORIZED_KEY", "Invalid Room Access Key or signature")
                return
            }

            // Strictly verify that the socket and senderDeviceId belong to an authenticated member of this room
            val authenticatedMember = currentRoom.members.firstOrNull { it.deviceId == envelope.senderDeviceId }
            val isSocketAuthenticatedForRoom = conn.authenticated &&
                conn.roomId.equals(normalizedRoomId, ignoreCase = true) &&
                conn.deviceId == envelope.senderDeviceId &&
                authenticatedMember != null
            if (!isSocketAuthenticatedForRoom) {
                sendRejectEvent(
                    conn,
                    normalizedRoomId,
                    "NOT_ROOM_MEMBER",
                    "Only authenticated room members can send chat or use shared controls"
                )
                return
            }

            when (envelope.type) {
                RealtimeEventType.HEARTBEAT_PING -> {
                    updateMemberHeartbeat(roomCtx, envelope.senderDeviceId, now)
                    val pongEnvelope = createServerSignedEnvelope(
                        room = roomCtx.state,
                        seq = roomCtx.sequenceGenerator.incrementAndGet(),
                        type = RealtimeEventType.HEARTBEAT_PONG,
                        payloadJson = JSONObject().put("serverTimeMillis", now).toString()
                    )
                    conn.sendTextFrame(pongEnvelope.toJsonString())
                }
                RealtimeEventType.LEAVE_ROOM -> {
                    handleExplicitLeave(conn, roomCtx, envelope.senderDeviceId)
                }
                RealtimeEventType.ROOM_CLOSED -> {
                    if (envelope.senderDeviceId == currentRoom.hostDeviceId) {
                        val closedEnvelope = createServerSignedEnvelope(
                            room = currentRoom,
                            seq = roomCtx.sequenceGenerator.incrementAndGet(),
                            type = RealtimeEventType.ROOM_CLOSED,
                            payloadJson = JSONObject().put("reason", "Host closed the room").toString()
                        )
                        broadcastToRoom(normalizedRoomId, closedEnvelope)
                        rooms.remove(normalizedRoomId)
                    }
                }
                RealtimeEventType.UPDATE_ROOM_CONFIG -> {
                    if (envelope.senderDeviceId == currentRoom.hostDeviceId) {
                        val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                        val newMode = runCatching {
                            RoomSyncMode.valueOf(payload.optString("mode", currentRoom.mode.name))
                        }.getOrDefault(currentRoom.mode)
                        val newSyncActive = payload.optBoolean("isSyncActive", currentRoom.isSyncActive)
                        val newName = payload.optString("roomName", currentRoom.roomName).take(48).ifBlank { currentRoom.roomName }
                        val newPerms = if (payload.has("permissions")) {
                            RoomPermissionsConfig.fromJson(payload.optJSONObject("permissions"))
                        } else {
                            currentRoom.permissions
                        }
                        val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                        roomCtx.state = currentRoom.copy(
                            roomName = newName,
                            mode = newMode,
                            isSyncActive = newSyncActive,
                            permissions = newPerms,
                            lastSequenceNumber = nextSeq
                        )
                        broadcastSnapshotToRoom(roomCtx)
                    }
                }
                RealtimeEventType.REMOVE_PARTICIPANT -> {
                    if (envelope.senderDeviceId == currentRoom.hostDeviceId) {
                        val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                        val targetId = payload.optString("targetDeviceId", "")
                        if (targetId.isNotBlank() && targetId != currentRoom.hostDeviceId) {
                            roomCtx.kickedDeviceIds.add(targetId)
                            val updatedMembers = currentRoom.members.filterNot { it.deviceId == targetId }
                            val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                            roomCtx.state = currentRoom.copy(
                                members = updatedMembers,
                                lastSequenceNumber = nextSeq
                            )
                            for (c in clients) {
                                if (c.roomId == normalizedRoomId && c.deviceId == targetId) {
                                    c.authenticated = false
                                    c.roomId = ""
                                    c.deviceId = ""
                                    sendRejectEvent(c, normalizedRoomId, "PARTICIPANT_REMOVED", "Removed from room by host")
                                    c.closeConnection(4003, "Removed by host")
                                }
                            }
                            broadcastSnapshotToRoom(roomCtx)
                        }
                    }
                }
                RealtimeEventType.TRANSFER_HOST -> {
                    if (envelope.senderDeviceId == currentRoom.hostDeviceId) {
                        val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                        val newHostId = payload.optString("targetDeviceId", "")
                        if (newHostId.isNotBlank() && currentRoom.members.any { it.deviceId == newHostId }) {
                            val nextEpoch = currentRoom.authorityEpoch + 1L
                            val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                            val updatedMembers = currentRoom.members.map { m ->
                                when (m.deviceId) {
                                    newHostId -> m.copy(role = RoomRole.HOST, isFollowingPaused = false)
                                    currentRoom.hostDeviceId -> m.copy(role = RoomRole.PARTICIPANT)
                                    else -> m
                                }
                            }
                            roomCtx.state = currentRoom.copy(
                                hostDeviceId = newHostId,
                                authorityEpoch = nextEpoch,
                                members = updatedMembers,
                                lastSequenceNumber = nextSeq
                            )
                            broadcastSnapshotToRoom(roomCtx)
                        }
                    }
                }
                RealtimeEventType.PARTICIPANT_FOLLOW_STATE -> {
                    val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                    val paused = payload.optBoolean("isFollowingPaused", false)
                    val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                    val updatedMembers = currentRoom.members.map { m ->
                        if (m.deviceId == envelope.senderDeviceId) {
                            m.copy(
                                isFollowingPaused = paused,
                                status = if (paused) SyncConnectionStatus.PAUSED_FOLLOWING else SyncConnectionStatus.SYNCED,
                                lastHeartbeatMillis = now
                            )
                        } else {
                            m
                        }
                    }
                    roomCtx.state = currentRoom.copy(
                        members = updatedMembers,
                        lastSequenceNumber = nextSeq
                    )
                    broadcastSnapshotToRoom(roomCtx)
                }
                RealtimeEventType.NAVIGATE_URL,
                RealtimeEventType.NAVIGATE_BACK,
                RealtimeEventType.NAVIGATE_FORWARD,
                RealtimeEventType.SELECT_TAB_NAVIGATION,
                RealtimeEventType.NEW_PAGE_OPENED -> {
                    val isHost = envelope.senderDeviceId == currentRoom.hostDeviceId
                    val allowedByPermission = isHost ||
                        (currentRoom.permissions.allowParticipantControls &&
                            currentRoom.permissions.allowParticipantNavigationSync)
                    if (!allowedByPermission) {
                        val deniedEnv = createServerSignedEnvelope(
                            room = currentRoom,
                            seq = currentRoom.lastSequenceNumber,
                            type = RealtimeEventType.PERMISSION_DENIED,
                            payloadJson = JSONObject()
                                .put("code", "PARTICIPANT_CONTROLS_DISABLED")
                                .put(
                                    "message",
                                    "Shared controls are disabled by the host — only the host can control shared navigation and playback."
                                )
                                .toString()
                        )
                        conn.sendTextFrame(deniedEnv.toJsonString())
                        return
                    }
                    if (currentRoom.isSyncActive) {
                        val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: JSONObject()
                        val rawUrl = payload.optString("url", "")
                        val sanitizedUrl = if (rawUrl.isNotBlank()) {
                            NyxoraConnectSecurity.sanitizeUrlForSync(rawUrl, isPrivateTab = false, isPrivateMode = false)
                        } else {
                            null
                        }
                        if ((envelope.type == RealtimeEventType.NAVIGATE_URL ||
                                envelope.type == RealtimeEventType.NEW_PAGE_OPENED ||
                                envelope.type == RealtimeEventType.SELECT_TAB_NAVIGATION) && sanitizedUrl == null
                        ) {
                            return
                        }
                        val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                        val nextTitle = payload.optString("title", currentRoom.currentSyncedTitle).take(120)
                        roomCtx.state = currentRoom.copy(
                            currentSyncedUrl = sanitizedUrl ?: currentRoom.currentSyncedUrl,
                            currentSyncedTitle = if (sanitizedUrl != null) nextTitle else currentRoom.currentSyncedTitle,
                            lastSequenceNumber = nextSeq
                        )
                        val cleanPayload = JSONObject(payload.toString()).apply {
                            if (sanitizedUrl != null) put("url", sanitizedUrl)
                            put("initiatedBy", envelope.senderDeviceId)
                        }
                        val broadcastEnv = createServerSignedEnvelope(
                            room = roomCtx.state,
                            seq = nextSeq,
                            type = envelope.type,
                            senderId = envelope.senderDeviceId,
                            senderRole = if (isHost) RoomRole.HOST else RoomRole.PARTICIPANT,
                            payloadJson = cleanPayload.toString()
                        )
                        broadcastToRoom(normalizedRoomId, broadcastEnv)
                    }
                }
                RealtimeEventType.PLAYBACK_ACTION,
                RealtimeEventType.PLAYBACK_BEACON -> {
                    val isHost = envelope.senderDeviceId == currentRoom.hostDeviceId
                    val allowedByPermission = isHost ||
                        (envelope.type == RealtimeEventType.PLAYBACK_ACTION &&
                            currentRoom.permissions.allowParticipantControls &&
                            currentRoom.permissions.allowParticipantPlaybackControl)
                    if (!allowedByPermission) {
                        if (envelope.type == RealtimeEventType.PLAYBACK_ACTION) {
                            val deniedEnv = createServerSignedEnvelope(
                                room = currentRoom,
                                seq = currentRoom.lastSequenceNumber,
                                type = RealtimeEventType.PERMISSION_DENIED,
                                payloadJson = JSONObject()
                                    .put("code", "PARTICIPANT_CONTROLS_DISABLED")
                                    .put(
                                        "message",
                                        "Shared controls are disabled by the host — only the host can control shared navigation and playback."
                                    )
                                    .toString()
                            )
                            conn.sendTextFrame(deniedEnv.toJsonString())
                        }
                        return
                    }
                    if (currentRoom.isSyncActive) {
                        val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                        val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                        val rawUrl = payload.optString("pageUrl", currentRoom.currentSyncedUrl)
                        val safePageUrl = NyxoraConnectSecurity.sanitizeUrlForSync(rawUrl, false, false)
                            ?: currentRoom.currentSyncedUrl
                        val pbState = WatchTogetherPlaybackState(
                            pageUrl = safePageUrl,
                            isPlaying = payload.optBoolean("isPlaying", false),
                            positionSeconds = payload.optDouble("positionSeconds", 0.0).coerceAtLeast(0.0),
                            durationSeconds = payload.optDouble("durationSeconds", 0.0).coerceAtLeast(0.0),
                            playbackRate = payload.optDouble("playbackRate", 1.0).coerceIn(0.25, 2.0),
                            updatedAtMillis = now,
                            updatedByDeviceId = envelope.senderDeviceId,
                            sequenceNumber = nextSeq
                        )
                        roomCtx.state = currentRoom.copy(
                            currentSyncedUrl = safePageUrl.ifBlank { currentRoom.currentSyncedUrl },
                            playbackState = pbState,
                            lastSequenceNumber = nextSeq
                        )
                        val broadcastEnv = createServerSignedEnvelope(
                            room = roomCtx.state,
                            seq = nextSeq,
                            type = envelope.type,
                            senderId = envelope.senderDeviceId,
                            senderRole = if (isHost) RoomRole.HOST else RoomRole.PARTICIPANT,
                            payloadJson = pbState.toJson().toString()
                        )
                        broadcastToRoom(normalizedRoomId, broadcastEnv)
                    }
                }
                RealtimeEventType.CHAT_MESSAGE -> {
                    val isHost = envelope.senderDeviceId == currentRoom.hostDeviceId
                    val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                    val rawText = payload.optString("text", "")
                    val sanitizedText = NyxoraConnectSecurity.sanitizeChatMessageForSync(
                        rawMessage = rawText,
                        roomAccessKey = currentRoom.accessKey
                    ) ?: return
                    val msgId = payload.optString("messageId", "").trim().take(64).ifBlank {
                        UUID.randomUUID().toString()
                    }
                    val senderDisplayName = authenticatedMember.deviceName
                        .ifBlank { payload.optString("senderName", "Participant") }
                        .trim()
                        .take(36)
                        .ifBlank { if (isHost) "Host" else "Participant" }
                    val chatMessage = RoomChatMessage(
                        messageId = msgId,
                        roomId = normalizedRoomId,
                        senderDeviceId = envelope.senderDeviceId,
                        senderName = senderDisplayName,
                        senderRole = if (isHost) RoomRole.HOST else RoomRole.PARTICIPANT,
                        text = sanitizedText,
                        timestampMillis = now
                    )
                    val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
                    val updatedChatHistory = (currentRoom.chatMessages.filterNot { it.messageId == msgId } + chatMessage)
                        .takeLast(RoomChatMessage.MAX_CHAT_HISTORY)
                    roomCtx.state = currentRoom.copy(
                        chatMessages = updatedChatHistory,
                        lastSequenceNumber = nextSeq
                    )
                    val broadcastEnv = createServerSignedEnvelope(
                        room = roomCtx.state,
                        seq = nextSeq,
                        type = RealtimeEventType.CHAT_MESSAGE,
                        senderId = envelope.senderDeviceId,
                        senderRole = chatMessage.senderRole,
                        payloadJson = chatMessage.toJson().toString()
                    )
                    broadcastToRoom(normalizedRoomId, broadcastEnv)
                }
                RealtimeEventType.REQUEST_RESYNC -> {
                    val snapshotEnv = createServerSignedEnvelope(
                        room = roomCtx.state,
                        seq = roomCtx.state.lastSequenceNumber,
                        type = RealtimeEventType.ROOM_STATE_SNAPSHOT,
                        payloadJson = roomCtx.state.toSnapshotJson(includeAccessKey = false).toString()
                    )
                    conn.sendTextFrame(snapshotEnv.toJsonString())
                }
                else -> Unit
            }
        }
    }

    private fun handleJoinRequest(
        conn: ClientSessionConnection,
        roomCtx: HostedRoomContext,
        envelope: RealtimeEventEnvelope,
        now: Long
    ) {
        val currentRoom = roomCtx.state
        val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: JSONObject()
        val inviteTokenRaw = payload.optString("inviteToken", "").trim()

        // Authenticate JOIN_REQUEST via signed HTTPS inviteToken, room accessKey, or derived room code key
        val derivedCodeKey = NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(currentRoom.roomId)
        var handshakeSigningKey = currentRoom.accessKey
        if (inviteTokenRaw.isNotEmpty()) {
            val synthUrl = "https://${NyxoraConnectSecurity.DEFAULT_APP_LINK_DOMAIN}/connect?room=${currentRoom.roomId}&inv=$inviteTokenRaw"
            val parsedInvite = NyxoraConnectSecurity.parseRoomInviteInput(synthUrl, nowMillis = now)
            if (parsedInvite == null) {
                sendRejectEvent(
                    conn,
                    currentRoom.roomId,
                    InvitationValidationStatus.INVALID_FORMAT.errorCode,
                    InvitationValidationStatus.INVALID_FORMAT.userMessage
                )
                return
            }
            val tokenStatus = NyxoraConnectSecurity.verifyInvitationTokenAgainstRoom(
                parsed = parsedInvite,
                roomId = currentRoom.roomId,
                roomAccessKey = currentRoom.accessKey,
                requestingDeviceId = envelope.senderDeviceId,
                nowMillis = now,
                serverRevokedTokenIds = roomCtx.revokedInviteTokenIds,
                serverConsumedTokenOwners = roomCtx.consumedSingleUseTokenOwners
            )
            if (tokenStatus != InvitationValidationStatus.VALID) {
                sendRejectEvent(conn, currentRoom.roomId, tokenStatus.errorCode, tokenStatus.userMessage)
                return
            }
            // Verify envelope signature using the invite's tokenHmac, the room's accessKey, or derivedCodeKey
            val verifiedByTokenHmac = NyxoraConnectSecurity.verifyEventHmac(parsedInvite.tokenHmac, envelope)
            val verifiedByAccessKey = NyxoraConnectSecurity.verifyEventHmac(currentRoom.accessKey, envelope)
            val verifiedByDerivedKey = derivedCodeKey.isNotBlank() && NyxoraConnectSecurity.verifyEventHmac(derivedCodeKey, envelope)
            if (!verifiedByTokenHmac && !verifiedByAccessKey && !verifiedByDerivedKey) {
                sendRejectEvent(
                    conn,
                    currentRoom.roomId,
                    InvitationValidationStatus.UNAUTHORIZED_SIGNATURE.errorCode,
                    "Invalid Room Access Key or invitation signature"
                )
                return
            }
            if (parsedInvite.singleUse) {
                roomCtx.consumedSingleUseTokenOwners[parsedInvite.tokenId.uppercase()] = envelope.senderDeviceId
                NyxoraConnectSecurity.markSingleUseInvitationConsumed(parsedInvite.tokenId, envelope.senderDeviceId)
            }
            handshakeSigningKey = when {
                verifiedByTokenHmac -> parsedInvite.tokenHmac
                verifiedByAccessKey -> currentRoom.accessKey
                else -> derivedCodeKey
            }
        } else {
            val verifiedByAccessKey = NyxoraConnectSecurity.verifyEventHmac(currentRoom.accessKey, envelope)
            val verifiedByDerivedKey = derivedCodeKey.isNotBlank() && NyxoraConnectSecurity.verifyEventHmac(derivedCodeKey, envelope)
            if (!verifiedByAccessKey && !verifiedByDerivedKey) {
                sendRejectEvent(conn, currentRoom.roomId, "UNAUTHORIZED_KEY", "Invalid Room Access Key or signature")
                return
            }
            handshakeSigningKey = if (verifiedByAccessKey) currentRoom.accessKey else derivedCodeKey
        }

        val deviceName = payload.optString("deviceName", "NYXORA Device").trim().take(36).ifBlank { "NYXORA Device" }
        val isRejoin = currentRoom.members.any { it.deviceId == envelope.senderDeviceId }
        val isHost = envelope.senderDeviceId == currentRoom.hostDeviceId

        if (!isRejoin && !isHost) {
            if (currentRoom.permissions.isRoomLocked) {
                sendRejectEvent(conn, currentRoom.roomId, "ROOM_LOCKED", "Room is locked by the host")
                return
            }
            val activeCount = currentRoom.members.count { it.status != SyncConnectionStatus.DISCONNECTED }
            if (activeCount >= currentRoom.permissions.maxParticipants) {
                sendRejectEvent(
                    conn,
                    currentRoom.roomId,
                    "ROOM_FULL",
                    "Room has reached its maximum limit of ${currentRoom.permissions.maxParticipants} devices"
                )
                return
            }
        }

        // Prevent duplicate client sessions when a device disconnects and reconnects
        val staleConnections = clients.filter {
            it !== conn &&
                it.roomId.equals(currentRoom.roomId, ignoreCase = true) &&
                it.deviceId == envelope.senderDeviceId
        }
        for (stale in staleConnections) {
            stale.authenticated = false
            stale.deviceId = ""
            stale.roomId = ""
            clients.remove(stale)
            stale.closeConnection(1000, "Superseded by reconnected session")
        }

        conn.deviceId = envelope.senderDeviceId
        conn.roomId = currentRoom.roomId
        conn.authenticated = true
        conn.lastSeenMillis = now

        val existingMember = currentRoom.members.firstOrNull { it.deviceId == envelope.senderDeviceId }
        val memberEntry = ConnectedDeviceMember(
            deviceId = envelope.senderDeviceId,
            deviceName = deviceName,
            role = if (isHost) RoomRole.HOST else RoomRole.PARTICIPANT,
            status = if (existingMember?.isFollowingPaused == true) {
                SyncConnectionStatus.PAUSED_FOLLOWING
            } else {
                SyncConnectionStatus.SYNCED
            },
            joinedAtMillis = existingMember?.joinedAtMillis ?: now,
            lastHeartbeatMillis = now,
            isFollowingPaused = existingMember?.isFollowingPaused ?: false
        )

        val updatedMembers = (currentRoom.members.filterNot { it.deviceId == envelope.senderDeviceId } + memberEntry)
            .distinctBy { it.deviceId }
        val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
        roomCtx.state = currentRoom.copy(
            members = updatedMembers,
            lastSequenceNumber = nextSeq
        )

        // Include the session accessKey in JOIN_ACCEPTED (signed with handshakeSigningKey) so
        // clients joining via a token-only HTTPS link receive the room's session key securely.
        val acceptEnv = createServerSignedEnvelope(
            room = roomCtx.state,
            seq = nextSeq,
            type = RealtimeEventType.JOIN_ACCEPTED,
            payloadJson = roomCtx.state.toSnapshotJson(includeAccessKey = true).toString(),
            explicitSigningKey = handshakeSigningKey
        )
        conn.sendTextFrame(acceptEnv.toJsonString())
        broadcastSnapshotToRoom(roomCtx)
    }

    private fun handleExplicitLeave(
        conn: ClientSessionConnection,
        roomCtx: HostedRoomContext,
        deviceId: String
    ) {
        val currentRoom = roomCtx.state
        // Immediately detach leaving client so it receives no further broadcasts
        conn.authenticated = false
        conn.deviceId = ""
        conn.roomId = ""
        clients.remove(conn)

        if (deviceId == currentRoom.hostDeviceId) {
            val closedEnv = createServerSignedEnvelope(
                room = currentRoom,
                seq = roomCtx.sequenceGenerator.incrementAndGet(),
                type = RealtimeEventType.ROOM_CLOSED,
                payloadJson = JSONObject().put("reason", "Host left and closed the room").toString()
            )
            broadcastToRoom(currentRoom.roomId, closedEnv)
            rooms.remove(currentRoom.roomId)
        } else {
            val nextSeq = roomCtx.sequenceGenerator.incrementAndGet()
            val updatedMembers = currentRoom.members.filterNot { it.deviceId == deviceId }
            roomCtx.state = currentRoom.copy(
                members = updatedMembers,
                lastSequenceNumber = nextSeq
            )
            broadcastSnapshotToRoom(roomCtx)
        }
        conn.closeConnection(1000, "Left room")
    }

    private fun handleClientDisconnected(conn: ClientSessionConnection, explicitLeave: Boolean) {
        val rId = conn.roomId
        val dId = conn.deviceId
        if (rId.isBlank() || dId.isBlank()) return
        val roomCtx = rooms[rId] ?: return
        synchronized(roomCtx) {
            val currentRoom = roomCtx.state
            if (explicitLeave) {
                val updated = currentRoom.members.filterNot { it.deviceId == dId }
                roomCtx.state = currentRoom.copy(
                    members = updated,
                    lastSequenceNumber = roomCtx.sequenceGenerator.incrementAndGet()
                )
            } else {
                val stillConnectedElsewhere = clients.any {
                    it !== conn && it.authenticated && it.roomId == rId && it.deviceId == dId && !it.socket.isClosed
                }
                if (!stillConnectedElsewhere) {
                    val updated = currentRoom.members.map { m ->
                        if (m.deviceId == dId) m.copy(status = SyncConnectionStatus.RECONNECTING) else m
                    }
                    roomCtx.state = currentRoom.copy(
                        members = updated,
                        lastSequenceNumber = roomCtx.sequenceGenerator.incrementAndGet()
                    )
                }
            }
            broadcastSnapshotToRoom(roomCtx)
        }
    }

    private fun updateMemberHeartbeat(roomCtx: HostedRoomContext, deviceId: String, now: Long) {
        val currentRoom = roomCtx.state
        val updatedMembers = currentRoom.members.map { m ->
            if (m.deviceId == deviceId) {
                val nextStatus = if (m.isFollowingPaused) SyncConnectionStatus.PAUSED_FOLLOWING else SyncConnectionStatus.SYNCED
                m.copy(lastHeartbeatMillis = now, status = nextStatus)
            } else {
                m
            }
        }
        roomCtx.state = currentRoom.copy(members = updatedMembers)
    }

    private fun performRoomAndHeartbeatMaintenance() {
        val now = System.currentTimeMillis()
        val iterator = rooms.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val roomCtx = entry.value
            synchronized(roomCtx) {
                val room = roomCtx.state
                if (now > room.expiresAtMillis) {
                    val expiredEnv = createServerSignedEnvelope(
                        room = room,
                        seq = roomCtx.sequenceGenerator.incrementAndGet(),
                        type = RealtimeEventType.ROOM_CLOSED,
                        payloadJson = JSONObject().put("reason", "Room expired due to inactivity").toString()
                    )
                    broadcastToRoom(room.roomId, expiredEnv)
                    iterator.remove()
                } else {
                    var changed = false
                    val cleanedMembers = room.members.mapNotNull { m ->
                        val silenceMs = now - m.lastHeartbeatMillis
                        when {
                            silenceMs > 60_000L && m.deviceId != room.hostDeviceId -> {
                                changed = true
                                null
                            }
                            silenceMs > 20_000L && m.status != SyncConnectionStatus.RECONNECTING -> {
                                changed = true
                                m.copy(status = SyncConnectionStatus.RECONNECTING)
                            }
                            else -> m
                        }
                    }
                    if (changed) {
                        roomCtx.state = room.copy(
                            members = cleanedMembers,
                            lastSequenceNumber = roomCtx.sequenceGenerator.incrementAndGet()
                        )
                        broadcastSnapshotToRoom(roomCtx)
                    }
                }
            }
        }
    }

    private fun broadcastSnapshotToRoom(roomCtx: HostedRoomContext) {
        val room = roomCtx.state
        val snapshotEnv = createServerSignedEnvelope(
            room = room,
            seq = room.lastSequenceNumber,
            type = RealtimeEventType.ROOM_STATE_SNAPSHOT,
            payloadJson = room.toSnapshotJson(includeAccessKey = false).toString()
        )
        broadcastToRoom(room.roomId, snapshotEnv)
    }

    private fun broadcastToRoom(roomId: String, envelope: RealtimeEventEnvelope) {
        val json = envelope.toJsonString()
        for (client in clients) {
            if (client.authenticated && client.roomId.equals(roomId, ignoreCase = true)) {
                client.sendTextFrame(json)
            }
        }
    }

    private fun sendRejectEvent(conn: ClientSessionConnection, roomId: String, errorCode: String, message: String) {
        val payload = JSONObject()
            .put("code", errorCode)
            .put("errorCode", errorCode)
            .put("message", message)
            .toString()
        val env = RealtimeEventEnvelope(
            eventId = UUID.randomUUID().toString(),
            roomId = roomId,
            type = RealtimeEventType.JOIN_REJECTED,
            senderDeviceId = "server",
            senderRole = RoomRole.HOST,
            authorityEpoch = 1L,
            sequenceNumber = 0L,
            timestampMillis = System.currentTimeMillis(),
            payloadJson = payload,
            hmacSignature = "server_reject"
        )
        conn.sendTextFrame(env.toJsonString())
    }

    private fun sendErrorEvent(conn: ClientSessionConnection, roomId: String, errorCode: String, message: String) {
        sendRejectEvent(conn, roomId, errorCode, message)
    }

    private fun createServerSignedEnvelope(
        room: NyxoraConnectRoomState,
        seq: Long,
        type: RealtimeEventType,
        senderId: String = room.hostDeviceId,
        senderRole: RoomRole = RoomRole.HOST,
        payloadJson: String,
        explicitSigningKey: String = ""
    ): RealtimeEventEnvelope {
        val eventId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val signingKey = explicitSigningKey.ifBlank { room.accessKey }
        val sig = NyxoraConnectSecurity.computeEventHmac(
            accessKey = signingKey,
            roomId = room.roomId,
            eventId = eventId,
            type = type.name,
            senderDeviceId = senderId,
            authorityEpoch = room.authorityEpoch,
            sequenceNumber = seq,
            timestampMillis = now,
            payloadJson = payloadJson
        )
        return RealtimeEventEnvelope(
            eventId = eventId,
            roomId = room.roomId,
            type = type,
            senderDeviceId = senderId,
            senderRole = senderRole,
            authorityEpoch = room.authorityEpoch,
            sequenceNumber = seq,
            timestampMillis = now,
            payloadJson = payloadJson,
            hmacSignature = sig
        )
    }

    // --- RFC 6455 HTTP UPGRADE & FRAME CODEC ---

    private data class WebSocketFrame(val opcode: Int, val payload: ByteArray)

    private fun performWebSocketHandshake(input: InputStream, output: OutputStream): Boolean {
        val headers = mutableMapOf<String, String>()
        val lineBuffer = ByteArrayOutputStream()
        var requestLineRead = false
        while (true) {
            val b = input.read()
            if (b == -1) return false
            if (b == '\n'.code) {
                val line = String(lineBuffer.toByteArray(), Charsets.US_ASCII).trimEnd('\r')
                lineBuffer.reset()
                if (!requestLineRead) {
                    if (!line.startsWith("GET ", ignoreCase = true)) return false
                    requestLineRead = true
                } else if (line.isEmpty()) {
                    break
                } else {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[key] = value
                    }
                }
            } else {
                lineBuffer.write(b)
                if (lineBuffer.size() > 4096) return false
            }
        }

        val wsKey = headers["sec-websocket-key"] ?: return false
        val acceptConcat = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1").digest(acceptConcat.toByteArray(Charsets.US_ASCII))
        val acceptKey = Base64.getEncoder().encodeToString(sha1)

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $acceptKey\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
        return true
    }

    private fun readWebSocketFrame(input: InputStream): WebSocketFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        val isMasked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()

        if (payloadLen == 126L) {
            val lenBytes = readExactBytes(input, 2) ?: return null
            payloadLen = (((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)).toLong()
        } else if (payloadLen == 127L) {
            val lenBytes = readExactBytes(input, 8) ?: return null
            var length = 0L
            for (i in 0 until 8) {
                length = (length shl 8) or (lenBytes[i].toLong() and 0xFFL)
            }
            payloadLen = length
        }

        if (payloadLen < 0L || payloadLen > 16384L) {
            return null
        }

        val maskKey = if (isMasked) {
            readExactBytes(input, 4) ?: return null
        } else {
            null
        }

        val payload = readExactBytes(input, payloadLen.toInt()) ?: return null
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return WebSocketFrame(opcode = opcode, payload = payload)
    }

    private fun writeWebSocketFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        val firstByte = 0x80 or (opcode and 0x0F)
        output.write(firstByte)
        val size = payload.size
        when {
            size <= 125 -> {
                output.write(size)
            }
            size <= 65535 -> {
                output.write(126)
                output.write((size shr 8) and 0xFF)
                output.write(size and 0xFF)
            }
            else -> {
                output.write(127)
                val longLen = size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    output.write(((longLen ushr shift) and 0xFFL).toInt())
                }
            }
        }
        output.write(payload)
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }
}
