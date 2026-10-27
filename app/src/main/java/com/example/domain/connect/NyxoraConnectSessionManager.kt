package com.example.domain.connect

import android.os.Build
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed class IncomingRemoteSyncCommand {
    data class NavigateToUrl(val url: String, val title: String) : IncomingRemoteSyncCommand()
    data object NavigateBack : IncomingRemoteSyncCommand()
    data object NavigateForward : IncomingRemoteSyncCommand()
    data class OpenNewSyncedTab(val url: String, val title: String) : IncomingRemoteSyncCommand()
    data class ApplyPlaybackState(
        val state: WatchTogetherPlaybackState,
        val forceManualResync: Boolean
    ) : IncomingRemoteSyncCommand()
}

/**
 * Realtime Session Manager for NYXORA Connect.
 *
 * Uses a real `okhttp3.OkHttpClient` WebSocket connection (`ws://` for local/LAN hosted rooms
 * via `NyxoraWebSocketRelayServer` or `wss://` when connecting to a remote WSS relay server).
 *
 * Handles:
 * - Room creation & membership
 * - Cryptographic HMAC-SHA256 frame signing & verification
 * - Automatic reconnection with exponential backoff & room-state restoration
 * - Heartbeats (`HEARTBEAT_PING` / `HEARTBEAT_PONG`)
 * - Duplicate-event protection (`BoundedEventDeduplicator`) & sequence ordering
 * - Strict Incognito isolation (never emits or applies sync events while in Private Mode)
 */
class NyxoraConnectSessionManager(
    private val scope: CoroutineScope,
    val localDeviceId: String = UUID.randomUUID().toString(),
    initialDeviceName: String = defaultDeviceDisplayName(),
    private val sharedRelayServer: NyxoraWebSocketRelayServer = defaultSharedRelayServer
) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val deduplicator = BoundedEventDeduplicator(maxCapacity = 512)
    private val outgoingRateLimiter = SlidingWindowRateLimiter(maxEventsPerWindow = 24, windowMillis = 3000L)
    private val clientSequence = AtomicLong(1L)
    private val intentionalDisconnect = AtomicBoolean(false)

    @Volatile
    private var activeWebSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0

    @Volatile
    private var activeAccessKey: String = ""

    @Volatile
    private var lastAppliedSequence: Long = 0L

    private val _deviceDisplayName = MutableStateFlow(initialDeviceName)
    val deviceDisplayName: StateFlow<String> = _deviceDisplayName.asStateFlow()

    private val _roomState = MutableStateFlow<NyxoraConnectRoomState?>(null)
    val roomState: StateFlow<NyxoraConnectRoomState?> = _roomState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(SyncConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<SyncConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isFollowingPaused = MutableStateFlow(false)
    val isFollowingPaused: StateFlow<Boolean> = _isFollowingPaused.asStateFlow()

    private val _videoCompatibility = MutableStateFlow(VideoCompatibilityState.UNKNOWN)
    val videoCompatibility: StateFlow<VideoCompatibilityState> = _videoCompatibility.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    private val _remoteSyncCommands = MutableSharedFlow<IncomingRemoteSyncCommand>(extraBufferCapacity = 16)
    val remoteSyncCommands: SharedFlow<IncomingRemoteSyncCommand> = _remoteSyncCommands.asSharedFlow()

    val isInRoom: Boolean
        get() = _roomState.value != null && _connectionStatus.value != SyncConnectionStatus.DISCONNECTED

    val isCurrentDeviceHost: Boolean
        get() = _roomState.value?.hostDeviceId == localDeviceId

    fun updateDeviceDisplayName(name: String) {
        val cleaned = name.trim().take(36).ifBlank { defaultDeviceDisplayName() }
        _deviceDisplayName.value = cleaned
    }

    fun clearSyncError() {
        _lastSyncError.value = null
    }

    /**
     * Creates a new NYXORA Connect room and connects the Host over WebSockets.
     */
    fun createRoom(
        roomName: String,
        mode: RoomSyncMode,
        permissions: RoomPermissionsConfig = RoomPermissionsConfig(),
        initialUrl: String = "",
        initialTitle: String = "",
        isPrivateMode: Boolean = false,
        customRelayEndpoint: String = ""
    ): NyxoraConnectRoomState {
        leaveOrCloseRoom(notifyServer = false)
        _lastSyncError.value = null
        intentionalDisconnect.set(false)
        deduplicator.clear()
        lastAppliedSequence = 0L

        val roomId = NyxoraConnectSecurity.generateSecureRoomId()
        val accessKey = NyxoraConnectSecurity.generateRoomAccessKey()
        activeAccessKey = accessKey

        val port = sharedRelayServer.startIfNeeded()
        val lanIp = NyxoraConnectSecurity.detectLocalLanIpAddress()
        val endpointUrl = customRelayEndpoint.trim().takeIf {
            it.startsWith("wss://", ignoreCase = true) || it.startsWith("ws://", ignoreCase = true)
        } ?: "ws://$lanIp:$port/nyxora"

        val safeInitialUrl = NyxoraConnectSecurity.sanitizeUrlForSync(
            rawUrl = initialUrl,
            isPrivateTab = isPrivateMode,
            isPrivateMode = isPrivateMode
        ) ?: ""

        val now = System.currentTimeMillis()
        val hostMember = ConnectedDeviceMember(
            deviceId = localDeviceId,
            deviceName = _deviceDisplayName.value,
            role = RoomRole.HOST,
            status = SyncConnectionStatus.CONNECTED,
            joinedAtMillis = now,
            lastHeartbeatMillis = now,
            isFollowingPaused = false
        )

        val initialRoomState = NyxoraConnectRoomState(
            roomId = roomId,
            roomName = roomName.trim().take(48).ifBlank { "${_deviceDisplayName.value}'s Room" },
            accessKey = accessKey,
            endpointUrl = endpointUrl,
            hostDeviceId = localDeviceId,
            authorityEpoch = 1L,
            mode = mode,
            isSyncActive = true,
            permissions = permissions.copy(maxParticipants = permissions.maxParticipants.coerceIn(2, 10)),
            members = listOf(hostMember),
            currentSyncedUrl = safeInitialUrl,
            currentSyncedTitle = if (safeInitialUrl.isNotEmpty()) initialTitle.take(120) else "",
            playbackState = null,
            createdAtMillis = now,
            expiresAtMillis = now + NyxoraConnectRoomState.DEFAULT_ROOM_TTL_MS,
            lastSequenceNumber = 1L
        )

        sharedRelayServer.registerNewRoom(initialRoomState)
        _roomState.value = initialRoomState
        _isFollowingPaused.value = false
        _connectionStatus.value = SyncConnectionStatus.CONNECTING

        val connectUrl = if (customRelayEndpoint.isNotBlank() &&
            (customRelayEndpoint.startsWith("wss://", ignoreCase = true) || customRelayEndpoint.startsWith("ws://", ignoreCase = true))
        ) {
            customRelayEndpoint.trim()
        } else {
            "ws://127.0.0.1:$port/nyxora"
        }

        openWebSocketConnection(
            connectEndpoint = connectUrl,
            roomId = roomId,
            accessKey = accessKey
        )
        return initialRoomState
    }

    /**
     * Joins an existing room using a Room ID + Access Key or a full Invitation Link.
     */
    fun joinRoom(
        rawRoomOrInvite: String,
        explicitAccessKey: String = "",
        explicitEndpointUrl: String = ""
    ): Boolean {
        val parsed = NyxoraConnectSecurity.parseRoomInviteInput(rawRoomOrInvite)
        if (parsed == null) {
            _lastSyncError.value = "Invalid Room ID or invitation link format (expected NYX-XXXX-XXXX)"
            return false
        }

        val resolvedKey = explicitAccessKey.trim().uppercase().ifBlank { parsed.accessKey }
        if (resolvedKey.isBlank()) {
            _lastSyncError.value = "Room Access Key is required to join ${parsed.roomId}"
            return false
        }

        leaveOrCloseRoom(notifyServer = false)
        _lastSyncError.value = null
        intentionalDisconnect.set(false)
        deduplicator.clear()
        lastAppliedSequence = 0L
        activeAccessKey = resolvedKey

        val fallbackPort = sharedRelayServer.startIfNeeded()
        val resolvedEndpoint = explicitEndpointUrl.trim()
            .ifBlank { parsed.endpointUrl }
            .ifBlank { "ws://127.0.0.1:$fallbackPort/nyxora" }

        // If the endpoint points to this device's LAN IP and local relay port, route via loopback for reliability
        val localConnectEndpoint = if (resolvedEndpoint.contains(":$fallbackPort/")) {
            "ws://127.0.0.1:$fallbackPort/nyxora"
        } else {
            resolvedEndpoint
        }

        _connectionStatus.value = SyncConnectionStatus.CONNECTING
        _isFollowingPaused.value = false
        _roomState.value = NyxoraConnectRoomState(
            roomId = parsed.roomId,
            roomName = parsed.roomName.ifBlank { "Connecting to ${parsed.roomId}…" },
            accessKey = resolvedKey,
            endpointUrl = resolvedEndpoint,
            hostDeviceId = "",
            mode = RoomSyncMode.BROWSE_TOGETHER,
            isSyncActive = true
        )

        openWebSocketConnection(
            connectEndpoint = localConnectEndpoint,
            roomId = parsed.roomId,
            accessKey = resolvedKey
        )
        return true
    }

    /**
     * Leaves the room (if Participant) or closes the room for all participants (if Host).
     * Immediately clears sensitive room state and access keys.
     */
    fun leaveOrCloseRoom(notifyServer: Boolean = true) {
        intentionalDisconnect.set(true)
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        val current = _roomState.value
        val ws = activeWebSocket
        if (notifyServer && current != null && ws != null) {
            val eventType = if (current.hostDeviceId == localDeviceId) {
                RealtimeEventType.ROOM_CLOSED
            } else {
                RealtimeEventType.LEAVE_ROOM
            }
            sendSignedEvent(
                type = eventType,
                payloadJson = JSONObject().put("deviceId", localDeviceId).toString(),
                bypassRateLimit = true
            )
        }

        runCatching { ws?.close(1000, "Session ended") }
        activeWebSocket = null
        activeAccessKey = ""
        lastAppliedSequence = 0L
        deduplicator.clear()
        outgoingRateLimiter.clear()
        _roomState.value = null
        _isFollowingPaused.value = false
        _videoCompatibility.value = VideoCompatibilityState.UNKNOWN
        _connectionStatus.value = SyncConnectionStatus.DISCONNECTED
    }

    fun updateRoomConfiguration(
        roomName: String? = null,
        mode: RoomSyncMode? = null,
        isSyncActive: Boolean? = null,
        permissions: RoomPermissionsConfig? = null
    ) {
        val current = _roomState.value ?: return
        if (current.hostDeviceId != localDeviceId) return

        val updatedName = roomName?.trim()?.take(48)?.ifBlank { current.roomName } ?: current.roomName
        val updatedMode = mode ?: current.mode
        val updatedActive = isSyncActive ?: current.isSyncActive
        val updatedPerms = permissions ?: current.permissions

        val payload = JSONObject().apply {
            put("roomName", updatedName)
            put("mode", updatedMode.name)
            put("isSyncActive", updatedActive)
            put("permissions", updatedPerms.toJson())
        }
        sendSignedEvent(
            type = RealtimeEventType.UPDATE_ROOM_CONFIG,
            payloadJson = payload.toString(),
            bypassRateLimit = true
        )
    }

    fun setParticipantFollowingPaused(paused: Boolean) {
        val current = _roomState.value ?: return
        _isFollowingPaused.value = paused
        _connectionStatus.value = if (paused) {
            SyncConnectionStatus.PAUSED_FOLLOWING
        } else {
            SyncConnectionStatus.SYNCED
        }
        sendSignedEvent(
            type = RealtimeEventType.PARTICIPANT_FOLLOW_STATE,
            payloadJson = JSONObject().put("isFollowingPaused", paused).toString(),
            bypassRateLimit = true
        )
        if (!paused && current.currentSyncedUrl.isNotBlank()) {
            requestManualResync()
        }
    }

    fun removeParticipant(targetDeviceId: String) {
        val current = _roomState.value ?: return
        if (current.hostDeviceId != localDeviceId || targetDeviceId == localDeviceId) return
        sendSignedEvent(
            type = RealtimeEventType.REMOVE_PARTICIPANT,
            payloadJson = JSONObject().put("targetDeviceId", targetDeviceId).toString(),
            bypassRateLimit = true
        )
    }

    fun transferHostRole(targetDeviceId: String) {
        val current = _roomState.value ?: return
        if (current.hostDeviceId != localDeviceId || targetDeviceId == localDeviceId) return
        sendSignedEvent(
            type = RealtimeEventType.TRANSFER_HOST,
            payloadJson = JSONObject().put("targetDeviceId", targetDeviceId).toString(),
            bypassRateLimit = true
        )
    }

    fun requestManualResync() {
        val current = _roomState.value ?: return
        _isFollowingPaused.value = false
        _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
        sendSignedEvent(
            type = RealtimeEventType.REQUEST_RESYNC,
            payloadJson = JSONObject().put("requestedBy", localDeviceId).toString(),
            bypassRateLimit = true
        )
        // Also immediately apply latest known snapshot state if available
        if (current.currentSyncedUrl.isNotBlank()) {
            _remoteSyncCommands.tryEmit(
                IncomingRemoteSyncCommand.NavigateToUrl(
                    url = current.currentSyncedUrl,
                    title = current.currentSyncedTitle
                )
            )
        }
        val pb = current.playbackState
        if (current.mode == RoomSyncMode.WATCH_TOGETHER && pb != null) {
            _remoteSyncCommands.tryEmit(
                IncomingRemoteSyncCommand.ApplyPlaybackState(
                    state = pb,
                    forceManualResync = true
                )
            )
        }
    }

    // --- BROWSE TOGETHER OUTGOING EVENTS ---

    fun publishUrlNavigation(
        url: String,
        title: String,
        isPrivateTab: Boolean,
        isPrivateMode: Boolean,
        eventType: RealtimeEventType = RealtimeEventType.NAVIGATE_URL
    ): Boolean {
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || current.hostDeviceId != localDeviceId) return false
        if (!current.permissions.syncUrlNavigation && eventType == RealtimeEventType.NAVIGATE_URL) return false
        if (!current.permissions.syncTabSelection && eventType == RealtimeEventType.SELECT_TAB_NAVIGATION) return false
        if (!current.permissions.syncNewlyOpenedPages && eventType == RealtimeEventType.NEW_PAGE_OPENED) return false

        val sanitized = NyxoraConnectSecurity.sanitizeUrlForSync(
            rawUrl = url,
            isPrivateTab = isPrivateTab,
            isPrivateMode = isPrivateMode
        ) ?: return false

        val payload = JSONObject().apply {
            put("url", sanitized)
            put("title", title.trim().take(120))
        }
        return sendSignedEvent(
            type = eventType,
            payloadJson = payload.toString()
        )
    }

    fun publishBackForwardAction(
        isBack: Boolean,
        isPrivateTab: Boolean,
        isPrivateMode: Boolean
    ): Boolean {
        if (isPrivateTab || isPrivateMode) return false
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || current.hostDeviceId != localDeviceId) return false
        if (!current.permissions.syncBackForward) return false

        val type = if (isBack) RealtimeEventType.NAVIGATE_BACK else RealtimeEventType.NAVIGATE_FORWARD
        return sendSignedEvent(
            type = type,
            payloadJson = JSONObject().put("direction", if (isBack) "BACK" else "FORWARD").toString()
        )
    }

    // --- WATCH TOGETHER OUTGOING EVENTS ---

    fun updateVideoCompatibility(compatibility: VideoCompatibilityState) {
        _videoCompatibility.value = compatibility
    }

    fun publishPlaybackEvent(
        pageUrl: String,
        isPlaying: Boolean,
        positionSeconds: Double,
        durationSeconds: Double,
        playbackRate: Double = 1.0,
        isBeacon: Boolean = false,
        isPrivateTab: Boolean = false,
        isPrivateMode: Boolean = false
    ): Boolean {
        if (isPrivateTab || isPrivateMode) return false
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || current.mode != RoomSyncMode.WATCH_TOGETHER) return false

        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost && (!current.permissions.allowParticipantPlaybackControl || isBeacon)) {
            return false
        }

        val safeUrl = NyxoraConnectSecurity.sanitizeUrlForSync(pageUrl, false, false) ?: return false
        val payload = JSONObject().apply {
            put("pageUrl", safeUrl)
            put("isPlaying", isPlaying)
            put("positionSeconds", positionSeconds.coerceAtLeast(0.0))
            put("durationSeconds", durationSeconds.coerceAtLeast(0.0))
            put("playbackRate", playbackRate.coerceIn(0.25, 2.0))
        }
        val eventType = if (isBeacon) RealtimeEventType.PLAYBACK_BEACON else RealtimeEventType.PLAYBACK_ACTION
        return sendSignedEvent(
            type = eventType,
            payloadJson = payload.toString()
        )
    }

    /**
     * Simulates a temporary network drop for testing reconnection & room-state restoration.
     */
    fun simulateNetworkInterruptionForReconnect() {
        if (_roomState.value == null) return
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        runCatching { activeWebSocket?.cancel() }
    }

    // --- INTERNAL WEBSOCKET & RECONNECTION ENGINE ---

    private fun openWebSocketConnection(
        connectEndpoint: String,
        roomId: String,
        accessKey: String
    ) {
        val request = Request.Builder()
            .url(connectEndpoint)
            .header("X-Nyxora-Room", roomId)
            .header("X-Nyxora-Device", localDeviceId)
            .build()

        activeWebSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempt = 0
                    _connectionStatus.value = SyncConnectionStatus.CONNECTED
                    val joinPayload = JSONObject().apply {
                        put("deviceName", _deviceDisplayName.value)
                    }.toString()
                    sendSignedEventOnSocket(
                        webSocket = webSocket,
                        roomId = roomId,
                        accessKey = accessKey,
                        type = RealtimeEventType.JOIN_REQUEST,
                        payloadJson = joinPayload
                    )
                    startHeartbeatLoop(webSocket, roomId, accessKey)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingSocketFrame(text, connectEndpoint)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!intentionalDisconnect.get() && code != 1000 && code != 4003) {
                        scheduleReconnect(connectEndpoint, roomId, accessKey)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!intentionalDisconnect.get()) {
                        scheduleReconnect(connectEndpoint, roomId, accessKey)
                    }
                }
            }
        )
    }

    private fun startHeartbeatLoop(webSocket: WebSocket, roomId: String, accessKey: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !intentionalDisconnect.get() && activeWebSocket === webSocket) {
                delay(10_000L)
                sendSignedEventOnSocket(
                    webSocket = webSocket,
                    roomId = roomId,
                    accessKey = accessKey,
                    type = RealtimeEventType.HEARTBEAT_PING,
                    payloadJson = JSONObject().put("clientTimeMillis", System.currentTimeMillis()).toString()
                )
            }
        }
    }

    private fun scheduleReconnect(connectEndpoint: String, roomId: String, accessKey: String) {
        if (intentionalDisconnect.get() || _roomState.value == null) return
        heartbeatJob?.cancel()
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt.coerceAtMost(4)
            reconnectAttempt++
            val backoffMs = (400L * (1 shl attempt)).coerceAtMost(6000L)
            delay(backoffMs)
            if (!intentionalDisconnect.get() && _roomState.value != null) {
                openWebSocketConnection(connectEndpoint, roomId, accessKey)
            }
        }
    }

    private fun handleIncomingSocketFrame(rawText: String, connectEndpoint: String) {
        val envelope = RealtimeEventEnvelope.fromJsonString(rawText) ?: return
        if (!deduplicator.markIfNew(envelope.eventId)) return

        if (envelope.type == RealtimeEventType.JOIN_REJECTED) {
            val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull()
            val message = payload?.optString("message", "Unable to join room") ?: "Unable to join room"
            _lastSyncError.value = message
            leaveOrCloseRoom(notifyServer = false)
            return
        }

        val currentAccessKey = activeAccessKey
        if (!NyxoraConnectSecurity.verifyEventHmac(currentAccessKey, envelope)) {
            return
        }

        when (envelope.type) {
            RealtimeEventType.JOIN_ACCEPTED,
            RealtimeEventType.ROOM_STATE_SNAPSHOT -> {
                if (envelope.sequenceNumber < lastAppliedSequence && envelope.type == RealtimeEventType.ROOM_STATE_SNAPSHOT) {
                    return
                }
                lastAppliedSequence = maxOf(lastAppliedSequence, envelope.sequenceNumber)
                val snapshotJson = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val previousUrl = _roomState.value?.currentSyncedUrl ?: ""
                val updatedState = NyxoraConnectRoomState.fromSnapshotJson(
                    obj = snapshotJson,
                    existingAccessKey = currentAccessKey,
                    fallbackEndpoint = _roomState.value?.endpointUrl ?: connectEndpoint
                )
                _roomState.value = updatedState
                _connectionStatus.value = if (_isFollowingPaused.value) {
                    SyncConnectionStatus.PAUSED_FOLLOWING
                } else {
                    SyncConnectionStatus.SYNCED
                }

                // If participant just joined/reconnected and room has an active synced URL, restore state
                val isParticipant = updatedState.hostDeviceId != localDeviceId
                if (isParticipant &&
                    updatedState.isSyncActive &&
                    !_isFollowingPaused.value &&
                    updatedState.currentSyncedUrl.isNotBlank() &&
                    updatedState.currentSyncedUrl != previousUrl
                ) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.NavigateToUrl(
                            url = updatedState.currentSyncedUrl,
                            title = updatedState.currentSyncedTitle
                        )
                    )
                }
                val pb = updatedState.playbackState
                if (isParticipant &&
                    updatedState.isSyncActive &&
                    !_isFollowingPaused.value &&
                    updatedState.mode == RoomSyncMode.WATCH_TOGETHER &&
                    pb != null &&
                    envelope.type == RealtimeEventType.JOIN_ACCEPTED
                ) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.ApplyPlaybackState(
                            state = pb,
                            forceManualResync = true
                        )
                    )
                }
            }
            RealtimeEventType.ROOM_CLOSED -> {
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull()
                val reason = payload?.optString("reason", "Room was closed by the host") ?: "Room closed"
                if (_roomState.value?.hostDeviceId != localDeviceId) {
                    _lastSyncError.value = reason
                }
                leaveOrCloseRoom(notifyServer = false)
            }
            RealtimeEventType.HEARTBEAT_PONG -> {
                if (_connectionStatus.value == SyncConnectionStatus.CONNECTED ||
                    _connectionStatus.value == SyncConnectionStatus.RECONNECTING
                ) {
                    _connectionStatus.value = if (_isFollowingPaused.value) {
                        SyncConnectionStatus.PAUSED_FOLLOWING
                    } else {
                        SyncConnectionStatus.SYNCED
                    }
                }
            }
            RealtimeEventType.NAVIGATE_URL,
            RealtimeEventType.SELECT_TAB_NAVIGATION,
            RealtimeEventType.NEW_PAGE_OPENED -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val rawUrl = payload.optString("url", "")
                val title = payload.optString("title", "")
                val safeUrl = NyxoraConnectSecurity.sanitizeUrlForSync(rawUrl, false, false) ?: return

                _roomState.value = _roomState.value?.copy(
                    currentSyncedUrl = safeUrl,
                    currentSyncedTitle = title,
                    lastSequenceNumber = envelope.sequenceNumber
                )

                if (envelope.senderDeviceId != localDeviceId && !_isFollowingPaused.value) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    if (envelope.type == RealtimeEventType.NEW_PAGE_OPENED) {
                        _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.OpenNewSyncedTab(safeUrl, title))
                    } else {
                        _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateToUrl(safeUrl, title))
                    }
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                }
            }
            RealtimeEventType.NAVIGATE_BACK -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                if (envelope.senderDeviceId != localDeviceId && !_isFollowingPaused.value) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateBack)
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                }
            }
            RealtimeEventType.NAVIGATE_FORWARD -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                if (envelope.senderDeviceId != localDeviceId && !_isFollowingPaused.value) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateForward)
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                }
            }
            RealtimeEventType.PLAYBACK_ACTION,
            RealtimeEventType.PLAYBACK_BEACON -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val pbState = WatchTogetherPlaybackState.fromJson(payload) ?: return
                _roomState.value = _roomState.value?.copy(
                    playbackState = pbState,
                    lastSequenceNumber = envelope.sequenceNumber
                )
                if (envelope.senderDeviceId != localDeviceId && !_isFollowingPaused.value) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.ApplyPlaybackState(
                            state = pbState,
                            forceManualResync = envelope.type == RealtimeEventType.PLAYBACK_ACTION
                        )
                    )
                }
            }
            else -> Unit
        }
    }

    private fun sendSignedEvent(
        type: RealtimeEventType,
        payloadJson: String,
        bypassRateLimit: Boolean = false
    ): Boolean {
        val current = _roomState.value ?: return false
        val ws = activeWebSocket ?: return false
        if (!bypassRateLimit && !outgoingRateLimiter.tryAcquire(localDeviceId)) {
            return false
        }
        return sendSignedEventOnSocket(
            webSocket = ws,
            roomId = current.roomId,
            accessKey = activeAccessKey,
            type = type,
            payloadJson = payloadJson,
            authorityEpoch = current.authorityEpoch
        )
    }

    private fun sendSignedEventOnSocket(
        webSocket: WebSocket,
        roomId: String,
        accessKey: String,
        type: RealtimeEventType,
        payloadJson: String,
        authorityEpoch: Long = _roomState.value?.authorityEpoch ?: 1L
    ): Boolean {
        val eventId = UUID.randomUUID().toString()
        val seq = clientSequence.incrementAndGet()
        val now = System.currentTimeMillis()
        val role = if (_roomState.value?.hostDeviceId == localDeviceId) RoomRole.HOST else RoomRole.PARTICIPANT
        val hmac = NyxoraConnectSecurity.computeEventHmac(
            accessKey = accessKey,
            roomId = roomId,
            eventId = eventId,
            type = type.name,
            senderDeviceId = localDeviceId,
            authorityEpoch = authorityEpoch,
            sequenceNumber = seq,
            timestampMillis = now,
            payloadJson = payloadJson
        )
        val envelope = RealtimeEventEnvelope(
            eventId = eventId,
            roomId = roomId,
            type = type,
            senderDeviceId = localDeviceId,
            senderRole = role,
            authorityEpoch = authorityEpoch,
            sequenceNumber = seq,
            timestampMillis = now,
            payloadJson = payloadJson,
            hmacSignature = hmac
        )
        return webSocket.send(envelope.toJsonString())
    }

    companion object {
        val defaultSharedRelayServer = NyxoraWebSocketRelayServer()

        fun defaultDeviceDisplayName(): String {
            val brand = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
            val model = Build.MODEL ?: "Device"
            return if (model.startsWith(brand, ignoreCase = true)) {
                model.take(28)
            } else {
                "$brand $model".take(28)
            }
        }
    }
}
