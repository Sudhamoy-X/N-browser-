package com.example.domain.connect

import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed class IncomingRemoteSyncCommand {
    data class ParticipantJoinConfirmed(
        val roomId: String,
        val syncedUrl: String,
        val syncedTitle: String,
        val playbackState: WatchTogetherPlaybackState?
    ) : IncomingRemoteSyncCommand()
    data class NavigateToUrl(
        val url: String,
        val title: String,
        val isInitialJoinSnapshot: Boolean = false
    ) : IncomingRemoteSyncCommand()
    data object NavigateBack : IncomingRemoteSyncCommand()
    data object NavigateForward : IncomingRemoteSyncCommand()
    data class OpenNewSyncedTab(val url: String, val title: String) : IncomingRemoteSyncCommand()
    data class ApplyPlaybackState(
        val state: WatchTogetherPlaybackState,
        val forceManualResync: Boolean,
        val isInitialJoinSnapshot: Boolean = false
    ) : IncomingRemoteSyncCommand()
}

/**
 * Realtime Session Manager for NYXORA Connect.
 *
 * Uses a real `okhttp3.OkHttpClient` WebSocket connection (`wss://` for production cloud relay
 * or local relay server for same-host/LAN testing).
 *
 * Handles:
 * - Room creation & membership
 * - Shareable HTTPS App Link invitation generation (`https://nyxora.app/connect?room=...&inv=...`)
 * - Cryptographic HMAC-SHA256 frame signing & verification
 * - Explicit server confirmation (`JOIN_ACCEPTED`) before reporting `CONNECTED` / `SYNCED`
 * - Capped exponential-backoff reconnection with bounded retry exhaustion that cleanly transitions to `DISCONNECTED`
 * - Secret-redacted diagnostic logging (`diagnosticLogs`)
 * - Explicit user consent gating before sharing local browsing activity
 * - Bidirectional URL & navigation synchronization across connected devices
 * - Heartbeats (`HEARTBEAT_PING` / `HEARTBEAT_PONG`)
 * - Strict Incognito isolation (never emits or applies sync events while in Private Mode)
 */
class NyxoraConnectSessionManager(
    private val scope: CoroutineScope,
    val localDeviceId: String = UUID.randomUUID().toString(),
    initialDeviceName: String = defaultDeviceDisplayName(),
    private val sharedRelayServer: NyxoraWebSocketRelayServer = defaultSharedRelayServer,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val baseReconnectDelayMs: Long = DEFAULT_BASE_RECONNECT_DELAY_MS,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val configuredEndpointProvider: () -> String = { NyxoraConnectSecurity.configuredPublicWssEndpoint() }
) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
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
    private var handshakeTimeoutJob: Job? = null

    @Volatile
    private var reconnectAttempt: Int = 0

    @Volatile
    private var hasConfirmedServerSession: Boolean = false

    @Volatile
    private var activeAccessKey: String = ""

    @Volatile
    private var activeInviteToken: String = ""

    @Volatile
    private var lastGeneratedInviteToken: String = ""

    @Volatile
    private var lastAppliedSequence: Long = 0L

    @Volatile
    private var lastPublishedNavUrl: String = ""

    @Volatile
    private var lastPublishedNavType: RealtimeEventType? = null

    @Volatile
    private var lastPublishedNavAtMillis: Long = 0L

    @Volatile
    private var lastBeaconPositionSec: Double = -1.0

    @Volatile
    private var lastBeaconPlayingState: Boolean = false

    @Volatile
    private var lastBeaconSentAtMillis: Long = 0L

    private val _deviceDisplayName = MutableStateFlow(initialDeviceName)
    val deviceDisplayName: StateFlow<String> = _deviceDisplayName.asStateFlow()

    private val _roomState = MutableStateFlow<NyxoraConnectRoomState?>(null)
    val roomState: StateFlow<NyxoraConnectRoomState?> = _roomState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(SyncConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<SyncConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isFollowingPaused = MutableStateFlow(false)
    val isFollowingPaused: StateFlow<Boolean> = _isFollowingPaused.asStateFlow()

    private val _hasUserConsentedToShare = MutableStateFlow(true)
    val hasUserConsentedToShare: StateFlow<Boolean> = _hasUserConsentedToShare.asStateFlow()

    private val _currentShareableInviteUrl = MutableStateFlow("")
    val currentShareableInviteUrl: StateFlow<String> = _currentShareableInviteUrl.asStateFlow()

    private val _videoCompatibility = MutableStateFlow(VideoCompatibilityState.UNKNOWN)
    val videoCompatibility: StateFlow<VideoCompatibilityState> = _videoCompatibility.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    private val _lastHeartbeatRttMs = MutableStateFlow(0L)
    val lastHeartbeatRttMs: StateFlow<Long> = _lastHeartbeatRttMs.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<RoomChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<RoomChatMessage>> = _chatMessages.asStateFlow()

    private val _unreadChatCount = MutableStateFlow(0)
    val unreadChatCount: StateFlow<Int> = _unreadChatCount.asStateFlow()

    private val _isChatOverlayExpanded = MutableStateFlow(false)
    val isChatOverlayExpanded: StateFlow<Boolean> = _isChatOverlayExpanded.asStateFlow()

    private val _isChatBubbleDismissed = MutableStateFlow(false)
    val isChatBubbleDismissed: StateFlow<Boolean> = _isChatBubbleDismissed.asStateFlow()

    private val _participantControlsDisabledNotice = MutableStateFlow<String?>(null)
    val participantControlsDisabledNotice: StateFlow<String?> = _participantControlsDisabledNotice.asStateFlow()

    private val _remoteSyncCommands = MutableSharedFlow<IncomingRemoteSyncCommand>(extraBufferCapacity = 16)
    val remoteSyncCommands: SharedFlow<IncomingRemoteSyncCommand> = _remoteSyncCommands.asSharedFlow()

    val isInRoom: Boolean
        get() = _roomState.value != null && _connectionStatus.value != SyncConnectionStatus.DISCONNECTED

    val isCurrentDeviceHost: Boolean
        get() = _roomState.value?.hostDeviceId == localDeviceId

    val configuredBackendEndpoint: String
        get() = resolveConfiguredProductionEndpoint()

    private fun resolveConfiguredProductionEndpoint(): String {
        val candidate = configuredEndpointProvider().trim()
        return when {
            candidate.isBlank() ||
                candidate.equals(NyxoraConnectSecurity.LEGACY_PRODUCTION_WSS_ENDPOINT, ignoreCase = true) -> {
                NyxoraConnectSecurity.configuredPublicWssEndpoint()
            }
            candidate.startsWith("wss://", ignoreCase = true) ||
                candidate.startsWith("ws://", ignoreCase = true) -> candidate
            else -> NyxoraConnectSecurity.configuredPublicWssEndpoint()
        }
    }

    private fun shouldUseLocalRelayForInProcessTest(): Boolean {
        return sharedRelayServer !== defaultSharedRelayServer || isRobolectricOrJvmTestRuntime()
    }

    fun recordDiagnosticLog(rawEvent: String) {
        val redacted = NyxoraConnectSecurity.redactSecretsForLog(rawEvent)
        _diagnosticLogs.update { existing ->
            (existing + redacted).takeLast(MAX_DIAGNOSTIC_LOG_ENTRIES)
        }
        runCatching { Log.d(LOG_TAG, redacted) }
    }

    fun updateDeviceDisplayName(name: String) {
        val cleaned = name.trim().take(36).ifBlank { defaultDeviceDisplayName() }
        _deviceDisplayName.value = cleaned
    }

    fun setUserShareConsent(consented: Boolean) {
        _hasUserConsentedToShare.value = consented
    }

    fun clearSyncError() {
        _lastSyncError.value = null
    }

    /**
     * Generates a fresh HTTPS room invitation link (`https://nyxora.app/connect?room=...&inv=...`)
     * using a signed, time-bound token and never exposing raw keys or internal LAN IPs.
     */
    fun generateShareableInviteLink(
        singleUse: Boolean = false,
        ttlMillis: Long = NyxoraConnectSecurity.DEFAULT_INVITE_TTL_MS
    ): String {
        val current = _roomState.value ?: return ""
        val key = activeAccessKey.ifBlank { current.accessKey }
        if (key.isBlank()) return ""
        val token = NyxoraConnectSecurity.issueRoomInvitationToken(
            roomId = current.roomId,
            accessKey = key,
            ttlMillis = ttlMillis,
            singleUse = singleUse
        )
        lastGeneratedInviteToken = token
        val url = NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = current.roomId,
            accessKey = key,
            endpointUrl = current.endpointUrl,
            roomName = current.roomName,
            singleUse = singleUse,
            ttlMillis = ttlMillis,
            explicitInviteToken = token
        )
        _currentShareableInviteUrl.value = url
        recordDiagnosticLog("INVITE_GENERATED room=${current.roomId} url=$url")
        return url
    }

    /**
     * Revokes the current invitation token and issues a new HTTPS invitation link.
     */
    fun revokeCurrentInviteAndRotate(singleUse: Boolean = false): String {
        val current = _roomState.value ?: return ""
        val tokenToRevoke = lastGeneratedInviteToken.ifBlank { activeInviteToken }
        if (tokenToRevoke.isNotBlank()) {
            sharedRelayServer.revokeInvitationToken(current.roomId, tokenToRevoke)
            recordDiagnosticLog("INVITE_REVOKED room=${current.roomId} token=$tokenToRevoke")
        }
        return generateShareableInviteLink(singleUse = singleUse)
    }

    /**
     * Creates a new NYXORA Connect room and connects the Host over WebSockets.
     */
    fun createRoom(
        roomName: String = "NYXORA Sync Room",
        mode: RoomSyncMode = RoomSyncMode.BROWSE_TOGETHER,
        permissions: RoomPermissionsConfig = RoomPermissionsConfig(),
        initialUrl: String = "",
        initialTitle: String = "",
        isPrivateMode: Boolean = false,
        customRelayEndpoint: String = "",
        userConsentedToShare: Boolean = true
    ): NyxoraConnectRoomState {
        leaveOrCloseRoom(notifyServer = false)
        _lastSyncError.value = null
        _hasUserConsentedToShare.value = userConsentedToShare
        intentionalDisconnect.set(false)
        hasConfirmedServerSession = false
        reconnectAttempt = 0
        deduplicator.clear()
        lastAppliedSequence = 0L

        val roomId = NyxoraConnectSecurity.generateSecureRoomId()
        // Derive deterministic room handshake key from the Room Code so participants can join
        // directly using only the Room Code without manually entering an Access Key or URL.
        val accessKey = NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(roomId)
        activeAccessKey = accessKey
        activeInviteToken = ""
        lastGeneratedInviteToken = ""

        val cleanCustomEndpoint = customRelayEndpoint.trim().takeIf {
            it.startsWith("wss://", ignoreCase = true) || it.startsWith("ws://", ignoreCase = true)
        }
        val configuredEndpoint = resolveConfiguredProductionEndpoint()
        val publicEndpoint = cleanCustomEndpoint?.let { NyxoraConnectSecurity.sanitizePublicEndpointForInvite(it) } ?: ""
        val endpointUrl = publicEndpoint.ifBlank { configuredEndpoint }

        val safeInitialUrl = if (userConsentedToShare) {
            NyxoraConnectSecurity.sanitizeUrlForSync(
                rawUrl = initialUrl,
                isPrivateTab = isPrivateMode,
                isPrivateMode = isPrivateMode
            ) ?: ""
        } else {
            ""
        }

        val now = System.currentTimeMillis()
        val hostMember = ConnectedDeviceMember(
            deviceId = localDeviceId,
            deviceName = _deviceDisplayName.value,
            role = RoomRole.HOST,
            status = SyncConnectionStatus.CONNECTING,
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

        val targetEndpoint = cleanCustomEndpoint ?: configuredEndpoint
        val isDefaultProdEndpoint = targetEndpoint.equals(
            NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT,
            ignoreCase = true
        ) || targetEndpoint.equals(
            NyxoraConnectSecurity.LEGACY_PRODUCTION_WSS_ENDPOINT,
            ignoreCase = true
        )

        val connectUrl = if (isDefaultProdEndpoint && shouldUseLocalRelayForInProcessTest()) {
            val port = sharedRelayServer.startIfNeeded()
            sharedRelayServer.registerNewRoom(initialRoomState)
            "ws://127.0.0.1:$port/nyxora"
        } else {
            if (sharedRelayServer.listeningPort > 0 &&
                targetEndpoint.contains(":${sharedRelayServer.listeningPort}/")
            ) {
                sharedRelayServer.registerNewRoom(initialRoomState)
            }
            targetEndpoint
        }

        _roomState.value = initialRoomState
        _isFollowingPaused.value = false
        _connectionStatus.value = SyncConnectionStatus.CONNECTING
        generateShareableInviteLink(singleUse = false)

        recordDiagnosticLog("CREATE_ROOM room=$roomId mode=${mode.name} endpoint=$connectUrl")
        openWebSocketConnection(
            connectEndpoint = connectUrl,
            roomId = roomId,
            signingKey = accessKey,
            inviteToken = ""
        )
        return initialRoomState
    }

    /**
     * Joins an existing room directly using a Room Code (`NYX-XXXX-XXXX`, `XXXX-XXXX`, or `XXXXXXXX`)
     * or a standard HTTPS / deep-link Invitation URL.
     */
    fun joinRoom(
        rawRoomOrInvite: String,
        explicitAccessKey: String = "",
        explicitEndpointUrl: String = "",
        userConsentedToShare: Boolean = _hasUserConsentedToShare.value
    ): Boolean {
        val parsed = NyxoraConnectSecurity.parseRoomInviteInput(rawRoomOrInvite)
        if (parsed == null) {
            _lastSyncError.value = "Invalid Room Code format (expected NYX-XXXX-XXXX)"
            recordDiagnosticLog("JOIN_REJECTED_LOCAL reason=INVALID_FORMAT input=$rawRoomOrInvite")
            return false
        }

        if (parsed.validationStatus != InvitationValidationStatus.VALID && explicitAccessKey.isBlank()) {
            _lastSyncError.value = parsed.validationStatus.userMessage
            recordDiagnosticLog("JOIN_REJECTED_LOCAL reason=${parsed.validationStatus.errorCode} room=${parsed.roomId}")
            return false
        }

        val cleanExplicitKey = explicitAccessKey.trim().uppercase()
        val resolvedInviteToken = if (cleanExplicitKey.isBlank()) parsed.inviteToken else ""
        val handshakeSigningSecret = when {
            cleanExplicitKey.isNotBlank() -> cleanExplicitKey
            parsed.handshakeSigningSecret.isNotBlank() -> parsed.handshakeSigningSecret
            else -> NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(parsed.roomId)
        }
        val resolvedKey = cleanExplicitKey.ifBlank {
            parsed.accessKey.ifBlank { NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(parsed.roomId) }
        }

        leaveOrCloseRoom(notifyServer = false)
        _lastSyncError.value = null
        _hasUserConsentedToShare.value = userConsentedToShare
        intentionalDisconnect.set(false)
        hasConfirmedServerSession = false
        reconnectAttempt = 0
        deduplicator.clear()
        lastAppliedSequence = 0L
        activeAccessKey = handshakeSigningSecret
        activeInviteToken = resolvedInviteToken

        val configuredEndpoint = resolveConfiguredProductionEndpoint()
        val isHostedOnSharedRelay = sharedRelayServer.getRoomState(parsed.roomId) != null
        val candidateEndpoint = explicitEndpointUrl.trim().ifBlank { parsed.endpointUrl }
        val effectiveEndpoint = candidateEndpoint.ifBlank { configuredEndpoint }
        val isDefaultProdEndpoint = effectiveEndpoint.equals(
            NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT,
            ignoreCase = true
        ) || effectiveEndpoint.equals(
            NyxoraConnectSecurity.LEGACY_PRODUCTION_WSS_ENDPOINT,
            ignoreCase = true
        )

        val targetConnectEndpoint = when {
            sharedRelayServer.listeningPort > 0 &&
                effectiveEndpoint.contains(":${sharedRelayServer.listeningPort}/") -> {
                "ws://127.0.0.1:${sharedRelayServer.listeningPort}/nyxora"
            }
            isHostedOnSharedRelay &&
                (isDefaultProdEndpoint ||
                    (NyxoraConnectSecurity.isInternalOrPrivateEndpoint(effectiveEndpoint) &&
                        !effectiveEndpoint.startsWith("ws://127.0.0.1:", ignoreCase = true))) -> {
                val fallbackPort = sharedRelayServer.startIfNeeded()
                "ws://127.0.0.1:$fallbackPort/nyxora"
            }
            !isDefaultProdEndpoint -> effectiveEndpoint
            shouldUseLocalRelayForInProcessTest() -> {
                val fallbackPort = sharedRelayServer.startIfNeeded()
                "ws://127.0.0.1:$fallbackPort/nyxora"
            }
            else -> configuredEndpoint
        }

        val displayEndpoint = candidateEndpoint
            .takeIf { it.isNotBlank() && !NyxoraConnectSecurity.isInternalOrPrivateEndpoint(it) }
            ?: configuredEndpoint

        _connectionStatus.value = SyncConnectionStatus.CONNECTING
        _isFollowingPaused.value = false
        _roomState.value = NyxoraConnectRoomState(
            roomId = parsed.roomId,
            roomName = parsed.roomName.ifBlank { "Room ${parsed.roomId}" },
            accessKey = resolvedKey,
            endpointUrl = displayEndpoint,
            hostDeviceId = "",
            mode = RoomSyncMode.BROWSE_TOGETHER,
            isSyncActive = true
        )
        if (rawRoomOrInvite.startsWith("https://", ignoreCase = true)) {
            _currentShareableInviteUrl.value = rawRoomOrInvite.trim()
        }

        recordDiagnosticLog("JOIN_ROOM room=${parsed.roomId} endpoint=$targetConnectEndpoint")
        openWebSocketConnection(
            connectEndpoint = targetConnectEndpoint,
            roomId = parsed.roomId,
            signingKey = handshakeSigningSecret,
            inviteToken = resolvedInviteToken
        )
        return true
    }

    /**
     * Leaves the room (if Participant) or closes the room for all participants (if Host).
     * Immediately stops all synchronization and clears room credentials.
     */
    fun leaveOrCloseRoom(notifyServer: Boolean = true) {
        intentionalDisconnect.set(true)
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null

        val current = _roomState.value
        val ws = activeWebSocket
        if (notifyServer && current != null && ws != null && activeAccessKey.isNotBlank()) {
            val eventType = if (current.hostDeviceId == localDeviceId) {
                RealtimeEventType.ROOM_CLOSED
            } else {
                RealtimeEventType.LEAVE_ROOM
            }
            sendSignedEventOnSocket(
                webSocket = ws,
                roomId = current.roomId,
                accessKey = activeAccessKey,
                type = eventType,
                payloadJson = JSONObject().put("deviceId", localDeviceId).toString(),
                authorityEpoch = current.authorityEpoch
            )
            recordDiagnosticLog("${eventType.name} room=${current.roomId}")
        }

        activeWebSocket = null
        runCatching { ws?.close(1000, "Session ended") }
        activeAccessKey = ""
        activeInviteToken = ""
        lastGeneratedInviteToken = ""
        hasConfirmedServerSession = false
        reconnectAttempt = 0
        _currentShareableInviteUrl.value = ""
        lastAppliedSequence = 0L
        lastPublishedNavUrl = ""
        lastPublishedNavType = null
        lastPublishedNavAtMillis = 0L
        lastBeaconPositionSec = -1.0
        lastBeaconSentAtMillis = 0L
        deduplicator.clear()
        outgoingRateLimiter.clear()
        _roomState.value = null
        _isFollowingPaused.value = false
        _videoCompatibility.value = VideoCompatibilityState.UNKNOWN
        _chatMessages.value = emptyList()
        _unreadChatCount.value = 0
        _isChatOverlayExpanded.value = false
        _isChatBubbleDismissed.value = false
        _participantControlsDisabledNotice.value = null
        _connectionStatus.value = SyncConnectionStatus.DISCONNECTED
    }

    fun setAllowParticipantControls(allowed: Boolean) {
        val current = _roomState.value ?: return
        if (current.hostDeviceId != localDeviceId) return
        val updatedPerms = current.permissions.withParticipantControls(allowed)
        _roomState.value = current.copy(permissions = updatedPerms)
        if (allowed) {
            _participantControlsDisabledNotice.value = null
        }
        updateRoomConfiguration(permissions = updatedPerms)
        recordDiagnosticLog("HOST_SET_PARTICIPANT_CONTROLS room=${current.roomId} allowed=$allowed")
    }

    fun setChatOverlayExpanded(expanded: Boolean) {
        if (!isInRoom) {
            _isChatOverlayExpanded.value = false
            return
        }
        _isChatOverlayExpanded.value = expanded
        if (expanded) {
            _isChatBubbleDismissed.value = false
            _unreadChatCount.value = 0
        }
    }

    fun setChatBubbleDismissed(dismissed: Boolean) {
        _isChatBubbleDismissed.value = dismissed
        if (dismissed) {
            _isChatOverlayExpanded.value = false
        }
    }

    fun reopenChatOverlay() {
        if (!isInRoom) return
        _isChatBubbleDismissed.value = false
        _isChatOverlayExpanded.value = true
        _unreadChatCount.value = 0
    }

    fun sendChatMessage(rawText: String): Boolean {
        val current = _roomState.value ?: return false
        if (!hasConfirmedServerSession || _connectionStatus.value == SyncConnectionStatus.DISCONNECTED) {
            return false
        }
        val isConfirmedMember = current.members.any { it.deviceId == localDeviceId }
        if (!isConfirmedMember) {
            return false
        }
        val sanitizedText = NyxoraConnectSecurity.sanitizeChatMessageForSync(
            rawMessage = rawText,
            roomAccessKey = activeAccessKey.ifBlank { current.accessKey }
        ) ?: return false

        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val role = if (current.hostDeviceId == localDeviceId) RoomRole.HOST else RoomRole.PARTICIPANT
        val chatMessage = RoomChatMessage(
            messageId = messageId,
            roomId = current.roomId,
            senderDeviceId = localDeviceId,
            senderName = _deviceDisplayName.value,
            senderRole = role,
            text = sanitizedText,
            timestampMillis = now
        )
        val payload = JSONObject().apply {
            put("messageId", messageId)
            put("senderName", _deviceDisplayName.value)
            put("text", sanitizedText)
            put("timestampMillis", now)
        }
        val sent = sendSignedEvent(
            type = RealtimeEventType.CHAT_MESSAGE,
            payloadJson = payload.toString()
        )
        if (sent) {
            appendChatMessageInternal(chatMessage, isIncomingRemote = false)
            recordDiagnosticLog("CHAT_SENT room=${current.roomId} msgId=$messageId text=$sanitizedText")
        }
        return sent
    }

    private fun appendChatMessageInternal(message: RoomChatMessage, isIncomingRemote: Boolean) {
        var wasAdded = false
        _chatMessages.update { existing ->
            if (existing.any { it.messageId == message.messageId }) {
                existing
            } else {
                wasAdded = true
                (existing + message).takeLast(RoomChatMessage.MAX_CHAT_HISTORY)
            }
        }
        if (wasAdded) {
            _roomState.update { state ->
                state?.copy(chatMessages = _chatMessages.value)
            }
            if (isIncomingRemote && !_isChatOverlayExpanded.value) {
                _unreadChatCount.update { it + 1 }
            }
        }
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
        } else if (hasConfirmedServerSession) {
            SyncConnectionStatus.SYNCED
        } else {
            _connectionStatus.value
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
        if (hasConfirmedServerSession) {
            _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
        }
        sendSignedEvent(
            type = RealtimeEventType.REQUEST_RESYNC,
            payloadJson = JSONObject().put("requestedBy", localDeviceId).toString(),
            bypassRateLimit = true
        )
        if (current.currentSyncedUrl.isNotBlank()) {
            _remoteSyncCommands.tryEmit(
                IncomingRemoteSyncCommand.NavigateToUrl(
                    url = current.currentSyncedUrl,
                    title = current.currentSyncedTitle
                )
            )
        }
        val pb = current.playbackState
        if (pb != null) {
            _remoteSyncCommands.tryEmit(
                IncomingRemoteSyncCommand.ApplyPlaybackState(
                    state = pb,
                    forceManualResync = true
                )
            )
        }
    }

    // --- BROWSE TOGETHER OUTGOING EVENTS (BIDIRECTIONAL WHEN PERMITTED & CONSENTED) ---

    fun publishUrlNavigation(
        url: String,
        title: String,
        isPrivateTab: Boolean,
        isPrivateMode: Boolean,
        eventType: RealtimeEventType = RealtimeEventType.NAVIGATE_URL
    ): Boolean {
        if (!_hasUserConsentedToShare.value) return false
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || _isFollowingPaused.value) return false

        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost && (!current.permissions.allowParticipantControls || !current.permissions.allowParticipantNavigationSync)) {
            _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
            return false
        }

        if (!current.permissions.syncUrlNavigation && eventType == RealtimeEventType.NAVIGATE_URL) return false
        if (!current.permissions.syncTabSelection && eventType == RealtimeEventType.SELECT_TAB_NAVIGATION) return false
        if (!current.permissions.syncNewlyOpenedPages && eventType == RealtimeEventType.NEW_PAGE_OPENED) return false

        val sanitized = NyxoraConnectSecurity.sanitizeUrlForSync(
            rawUrl = url,
            isPrivateTab = isPrivateTab,
            isPrivateMode = isPrivateMode
        ) ?: return false

        val now = System.currentTimeMillis()
        if (sanitized == lastPublishedNavUrl &&
            eventType == lastPublishedNavType &&
            now - lastPublishedNavAtMillis in 0L..180L
        ) {
            return true
        }
        lastPublishedNavUrl = sanitized
        lastPublishedNavType = eventType
        lastPublishedNavAtMillis = now

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
        if (!_hasUserConsentedToShare.value || isPrivateTab || isPrivateMode) return false
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || _isFollowingPaused.value) return false

        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost && (!current.permissions.allowParticipantControls || !current.permissions.allowParticipantNavigationSync)) {
            _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
            return false
        }
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
        if (!_hasUserConsentedToShare.value || isPrivateTab || isPrivateMode) return false
        val compat = _videoCompatibility.value
        if (compat == VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED ||
            compat == VideoCompatibilityState.IFRAME_OR_EMBED_RESTRICTED ||
            compat == VideoCompatibilityState.NO_VIDEO_DETECTED
        ) {
            return false
        }
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || _isFollowingPaused.value) return false

        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost && (!current.permissions.allowParticipantControls || !current.permissions.allowParticipantPlaybackControl || isBeacon)) {
            if (!isBeacon && (!current.permissions.allowParticipantControls || !current.permissions.allowParticipantPlaybackControl)) {
                _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
            }
            return false
        }

        val candidateUrl = pageUrl.ifBlank { current.playbackState?.pageUrl ?: current.currentSyncedUrl }
        val safeUrl = NyxoraConnectSecurity.sanitizeUrlForSync(candidateUrl, false, false) ?: return false
        val safeDur = durationSeconds.coerceAtLeast(0.0)
        val rawPos = positionSeconds.coerceAtLeast(0.0)
        val safePos = if (safeDur > 0.0) rawPos.coerceAtMost(safeDur) else rawPos
        val safeRate = playbackRate.coerceIn(0.25, 2.0)
        val now = System.currentTimeMillis()
        if (isBeacon) {
            if (isPlaying == lastBeaconPlayingState &&
                kotlin.math.abs(safePos - lastBeaconPositionSec) < 0.15 &&
                now - lastBeaconSentAtMillis in 0L..800L
            ) {
                return true
            }
            lastBeaconPositionSec = safePos
            lastBeaconPlayingState = isPlaying
            lastBeaconSentAtMillis = now
        }
        val payload = JSONObject().apply {
            put("pageUrl", safeUrl)
            put("isPlaying", isPlaying)
            put("positionSeconds", safePos)
            put("durationSeconds", safeDur)
            put("playbackRate", safeRate)
        }
        val eventType = if (isBeacon) RealtimeEventType.PLAYBACK_BEACON else RealtimeEventType.PLAYBACK_ACTION
        val sent = sendSignedEvent(
            type = eventType,
            payloadJson = payload.toString()
        )
        if (sent) {
            val optimisticPb = WatchTogetherPlaybackState(
                pageUrl = safeUrl,
                isPlaying = isPlaying,
                positionSeconds = safePos,
                durationSeconds = safeDur,
                playbackRate = safeRate,
                updatedAtMillis = now,
                updatedByDeviceId = localDeviceId,
                sequenceNumber = current.lastSequenceNumber + 1L
            )
            _roomState.update { state ->
                state?.copy(
                    currentSyncedUrl = safeUrl.ifBlank { state.currentSyncedUrl },
                    playbackState = optimisticPb
                )
            }
        }
        return sent
    }

    /**
     * Simulates a temporary network drop for testing reconnection & room-state restoration.
     */
    fun simulateNetworkInterruptionForReconnect() {
        if (_roomState.value == null) return
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        recordDiagnosticLog("SIMULATE_NETWORK_DROP room=${_roomState.value?.roomId}")
        runCatching { activeWebSocket?.cancel() }
    }

    // --- INTERNAL WEBSOCKET & RECONNECTION ENGINE ---

    private fun openWebSocketConnection(
        connectEndpoint: String,
        roomId: String,
        signingKey: String,
        inviteToken: String
    ) {
        // Close any prior socket or pending reconnect job before opening a new one to prevent duplicate client sessions
        reconnectJob?.cancel()
        reconnectJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        val previousSocket = activeWebSocket
        activeWebSocket = null
        runCatching { previousSocket?.close(1000, "Replacing socket") }

        val request = runCatching {
            Request.Builder()
                .url(connectEndpoint)
                .header("X-Nyxora-Room", roomId)
                .header("X-Nyxora-Device", localDeviceId)
                .build()
        }.getOrElse { err ->
            recordDiagnosticLog("WS_INVALID_ENDPOINT endpoint=$connectEndpoint error=${err.message}")
            _lastSyncError.value = "Invalid relay server endpoint URL: $connectEndpoint"
            leaveOrCloseRoom(notifyServer = false)
            return
        }

        // Stay in CONNECTING (initial) or RECONNECTING (retry) until JOIN_ACCEPTED is confirmed by server
        _connectionStatus.value = if (reconnectAttempt > 0) {
            SyncConnectionStatus.RECONNECTING
        } else {
            SyncConnectionStatus.CONNECTING
        }

        val socket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (intentionalDisconnect.get() || activeWebSocket !== webSocket) {
                        runCatching { webSocket.close(1000, "Stale socket") }
                        return
                    }
                    recordDiagnosticLog("WS_OPEN_HANDSHAKE_SENT room=$roomId endpoint=$connectEndpoint")
                    val currentSnapshot = _roomState.value
                    val joinPayload = JSONObject().apply {
                        put("deviceName", _deviceDisplayName.value)
                        if (inviteToken.isNotBlank()) {
                            put("inviteToken", inviteToken)
                        }
                        if (currentSnapshot != null && currentSnapshot.hostDeviceId == localDeviceId) {
                            put("hostRoomSnapshot", currentSnapshot.toSnapshotJson(includeAccessKey = true))
                        }
                    }.toString()
                    sendSignedEventOnSocket(
                        webSocket = webSocket,
                        roomId = roomId,
                        accessKey = signingKey,
                        type = RealtimeEventType.JOIN_REQUEST,
                        payloadJson = joinPayload
                    )
                    startHandshakeConfirmationTimer(webSocket, connectEndpoint, roomId)
                    startHeartbeatLoop(webSocket, roomId)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (intentionalDisconnect.get() || activeWebSocket !== webSocket) return
                    handleIncomingSocketFrame(text, connectEndpoint)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (activeWebSocket === webSocket && !intentionalDisconnect.get() && code != 1000 && code != 4003) {
                        scheduleReconnect(connectEndpoint, roomId, "closed($code:$reason)")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (activeWebSocket === webSocket && !intentionalDisconnect.get()) {
                        scheduleReconnect(connectEndpoint, roomId, t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        )
        activeWebSocket = socket
    }

    private fun startHandshakeConfirmationTimer(
        webSocket: WebSocket,
        connectEndpoint: String,
        roomId: String
    ) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(handshakeTimeoutMs)
            if (isActive &&
                !intentionalDisconnect.get() &&
                activeWebSocket === webSocket &&
                _connectionStatus.value != SyncConnectionStatus.CONNECTED &&
                _connectionStatus.value != SyncConnectionStatus.SYNCED &&
                _connectionStatus.value != SyncConnectionStatus.PAUSED_FOLLOWING
            ) {
                recordDiagnosticLog("WS_HANDSHAKE_TIMEOUT room=$roomId endpoint=$connectEndpoint")
                runCatching { webSocket.cancel() }
                scheduleReconnect(connectEndpoint, roomId, "handshake_timeout")
            }
        }
    }

    private fun startHeartbeatLoop(webSocket: WebSocket, roomId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !intentionalDisconnect.get() && activeWebSocket === webSocket) {
                delay(10_000L)
                val key = activeAccessKey
                if (key.isNotBlank()) {
                    sendSignedEventOnSocket(
                        webSocket = webSocket,
                        roomId = roomId,
                        accessKey = key,
                        type = RealtimeEventType.HEARTBEAT_PING,
                        payloadJson = JSONObject().put("clientTimeMillis", System.currentTimeMillis()).toString()
                    )
                }
            }
        }
    }

    private fun scheduleReconnect(
        connectEndpoint: String,
        roomId: String,
        failureDetail: String = ""
    ) {
        if (intentionalDisconnect.get() || _roomState.value == null) return
        heartbeatJob?.cancel()
        handshakeTimeoutJob?.cancel()

        // If the session has never been confirmed by the server, fail fast after 2 retries so an
        // offline/undeployed external relay does not trap the UI in "Reconnecting" indefinitely.
        val effectiveMaxRetries = if (hasConfirmedServerSession) {
            maxReconnectAttempts
        } else {
            minOf(maxReconnectAttempts, 2)
        }

        if (reconnectAttempt >= effectiveMaxRetries) {
            com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketConnectionFailure(
                failureDetail.ifBlank { "reconnect_exhausted" }
            )
            recordDiagnosticLog(
                "RECONNECT_EXHAUSTED_DISCONNECTED room=$roomId attempts=$reconnectAttempt endpoint=$connectEndpoint reason=$failureDetail"
            )
            _lastSyncError.value = if (hasConfirmedServerSession) {
                "Connection to room $roomId was lost after $reconnectAttempt reconnect attempts."
            } else {
                "Unable to reach NYXORA Connect server ($connectEndpoint). Verify internet connection or relay server deployment."
            }
            leaveOrCloseRoom(notifyServer = false)
            return
        }

        val currentAttemptIndex = reconnectAttempt
        reconnectAttempt++
        com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketConnectionFailure(
            failureDetail.ifBlank { "socket_closed" }
        )
        com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketReconnect(reconnectAttempt)
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        val backoffMs = (baseReconnectDelayMs * (1L shl currentAttemptIndex.coerceAtMost(5))).coerceAtMost(8_000L)
        recordDiagnosticLog(
            "RECONNECT_SCHEDULED room=$roomId attempt=$reconnectAttempt/$effectiveMaxRetries delayMs=$backoffMs reason=$failureDetail"
        )

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!intentionalDisconnect.get() && _roomState.value != null) {
                openWebSocketConnection(
                    connectEndpoint = connectEndpoint,
                    roomId = roomId,
                    signingKey = activeAccessKey,
                    inviteToken = activeInviteToken
                )
            }
        }
    }

    private fun handleIncomingSocketFrame(rawText: String, connectEndpoint: String) {
        if (intentionalDisconnect.get() || _roomState.value == null) return
        val envelope = RealtimeEventEnvelope.fromJsonString(rawText) ?: return
        if (!deduplicator.markIfNew(envelope.eventId)) return

        if (envelope.type == RealtimeEventType.JOIN_REJECTED) {
            handshakeTimeoutJob?.cancel()
            val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull()
            val code = payload?.optString("code", "")?.ifBlank {
                payload.optString("errorCode", "REJECTED")
            } ?: "REJECTED"
            val message = payload?.optString("message", "Unable to join room") ?: "Unable to join room"
            recordDiagnosticLog("JOIN_REJECTED room=${envelope.roomId} code=$code message=$message")
            _lastSyncError.value = message
            leaveOrCloseRoom(notifyServer = false)
            return
        }

        val currentSigningKey = activeAccessKey
        val derivedRoomKey = NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(envelope.roomId)
        val verifiedWithCurrent = NyxoraConnectSecurity.verifyEventHmac(currentSigningKey, envelope) ||
            (derivedRoomKey.isNotBlank() && NyxoraConnectSecurity.verifyEventHmac(derivedRoomKey, envelope))
        if (!verifiedWithCurrent) {
            recordDiagnosticLog("FRAME_HMAC_MISMATCH room=${envelope.roomId} type=${envelope.type.name}")
            return
        }

        when (envelope.type) {
            RealtimeEventType.JOIN_ACCEPTED,
            RealtimeEventType.ROOM_STATE_SNAPSHOT -> {
                if (envelope.sequenceNumber < lastAppliedSequence && envelope.type == RealtimeEventType.ROOM_STATE_SNAPSHOT) {
                    return
                }
                val wasAlreadyConfirmedSession = hasConfirmedServerSession
                handshakeTimeoutJob?.cancel()
                handshakeTimeoutJob = null
                hasConfirmedServerSession = true
                reconnectAttempt = 0
                if (_lastSyncError.value != null) {
                    _lastSyncError.value = null
                }

                lastAppliedSequence = maxOf(lastAppliedSequence, envelope.sequenceNumber)
                val snapshotJson = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val previousUrl = _roomState.value?.currentSyncedUrl ?: ""
                val updatedState = NyxoraConnectRoomState.fromSnapshotJson(
                    obj = snapshotJson,
                    existingAccessKey = currentSigningKey,
                    fallbackEndpoint = _roomState.value?.endpointUrl ?: connectEndpoint
                )
                // Upgrade activeAccessKey from handshake tokenHmac to the authenticated room session key on JOIN_ACCEPTED
                // and clear activeInviteToken so future reconnections authenticate directly with activeAccessKey.
                if (updatedState.accessKey.isNotBlank()) {
                    activeAccessKey = updatedState.accessKey
                    activeInviteToken = ""
                } else if (envelope.type == RealtimeEventType.JOIN_ACCEPTED) {
                    activeInviteToken = ""
                }
                val mergedChats = (_chatMessages.value + updatedState.chatMessages)
                    .distinctBy { it.messageId }
                    .sortedBy { it.timestampMillis }
                    .takeLast(RoomChatMessage.MAX_CHAT_HISTORY)
                _chatMessages.value = mergedChats
                _roomState.value = updatedState.copy(chatMessages = mergedChats)
                if (_currentShareableInviteUrl.value.isBlank() && activeAccessKey.isNotBlank()) {
                    generateShareableInviteLink(singleUse = false)
                }
                _connectionStatus.value = if (_isFollowingPaused.value) {
                    SyncConnectionStatus.PAUSED_FOLLOWING
                } else {
                    SyncConnectionStatus.SYNCED
                }
                recordDiagnosticLog(
                    "${envelope.type.name} room=${updatedState.roomId} members=${updatedState.members.size} status=${_connectionStatus.value.name}"
                )

                val isParticipant = updatedState.hostDeviceId != localDeviceId
                if (!isParticipant || updatedState.permissions.allowParticipantControls) {
                    _participantControlsDisabledNotice.value = null
                } else {
                    _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                }
                val isInitialParticipantJoinConfirmation =
                    isParticipant && !wasAlreadyConfirmedSession && envelope.type == RealtimeEventType.JOIN_ACCEPTED

                if (isInitialParticipantJoinConfirmation) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.ParticipantJoinConfirmed(
                            roomId = updatedState.roomId,
                            syncedUrl = updatedState.currentSyncedUrl,
                            syncedTitle = updatedState.currentSyncedTitle,
                            playbackState = updatedState.playbackState
                        )
                    )
                }

                if (isParticipant &&
                    updatedState.isSyncActive &&
                    !_isFollowingPaused.value &&
                    updatedState.currentSyncedUrl.isNotBlank() &&
                    updatedState.currentSyncedUrl != previousUrl
                ) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.NavigateToUrl(
                            url = updatedState.currentSyncedUrl,
                            title = updatedState.currentSyncedTitle,
                            isInitialJoinSnapshot = isInitialParticipantJoinConfirmation
                        )
                    )
                }
                val pb = updatedState.playbackState
                if (isParticipant &&
                    updatedState.isSyncActive &&
                    !_isFollowingPaused.value &&
                    pb != null &&
                    envelope.type == RealtimeEventType.JOIN_ACCEPTED
                ) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.ApplyPlaybackState(
                            state = pb,
                            forceManualResync = true,
                            isInitialJoinSnapshot = isInitialParticipantJoinConfirmation
                        )
                    )
                }
            }
            RealtimeEventType.ROOM_CLOSED -> {
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull()
                val reason = payload?.optString("reason", "Room was closed by the host") ?: "Room closed"
                recordDiagnosticLog("ROOM_CLOSED_BY_HOST room=${envelope.roomId} reason=$reason")
                if (_roomState.value?.hostDeviceId != localDeviceId) {
                    _lastSyncError.value = reason
                }
                leaveOrCloseRoom(notifyServer = false)
            }
            RealtimeEventType.HEARTBEAT_PONG -> {
                val clientSentMs = runCatching {
                    JSONObject(envelope.payloadJson).optLong("clientTimeMillis", 0L)
                }.getOrDefault(0L)
                if (clientSentMs > 0L) {
                    val rtt = (System.currentTimeMillis() - clientSentMs).coerceAtLeast(0L)
                    _lastHeartbeatRttMs.value = rtt
                    com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketHeartbeatRtt(rtt)
                }
                if (hasConfirmedServerSession &&
                    (_connectionStatus.value == SyncConnectionStatus.CONNECTED ||
                        _connectionStatus.value == SyncConnectionStatus.RECONNECTING)
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
                val initiatorId = payload.optString("initiatedBy", "").ifBlank { envelope.senderDeviceId }

                _roomState.value = _roomState.value?.copy(
                    currentSyncedUrl = safeUrl,
                    currentSyncedTitle = title,
                    lastSequenceNumber = envelope.sequenceNumber
                )

                if (initiatorId != localDeviceId && !_isFollowingPaused.value) {
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
                val initiatorId = runCatching {
                    JSONObject(envelope.payloadJson).optString("initiatedBy", "")
                }.getOrDefault("").ifBlank { envelope.senderDeviceId }
                if (initiatorId != localDeviceId && !_isFollowingPaused.value) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateBack)
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                }
            }
            RealtimeEventType.NAVIGATE_FORWARD -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                val initiatorId = runCatching {
                    JSONObject(envelope.payloadJson).optString("initiatedBy", "")
                }.getOrDefault("").ifBlank { envelope.senderDeviceId }
                if (initiatorId != localDeviceId && !_isFollowingPaused.value) {
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
                    currentSyncedUrl = pbState.pageUrl.ifBlank { _roomState.value?.currentSyncedUrl ?: "" },
                    playbackState = pbState,
                    lastSequenceNumber = envelope.sequenceNumber
                )
                val initiatorId = pbState.updatedByDeviceId.ifBlank { envelope.senderDeviceId }
                if (initiatorId != localDeviceId && !_isFollowingPaused.value) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.ApplyPlaybackState(
                            state = pbState,
                            forceManualResync = envelope.type == RealtimeEventType.PLAYBACK_ACTION
                        )
                    )
                }
            }
            RealtimeEventType.CHAT_MESSAGE -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val incomingMsg = RoomChatMessage.fromJson(payload)?.let {
                    if (it.roomId.isBlank()) it.copy(roomId = envelope.roomId) else it
                } ?: return
                val safeText = NyxoraConnectSecurity.sanitizeChatMessageForSync(
                    rawMessage = incomingMsg.text,
                    roomAccessKey = activeAccessKey
                ) ?: return
                val sanitizedMsg = incomingMsg.copy(text = safeText)
                appendChatMessageInternal(
                    message = sanitizedMsg,
                    isIncomingRemote = sanitizedMsg.senderDeviceId != localDeviceId
                )
                recordDiagnosticLog(
                    "CHAT_RECEIVED room=${envelope.roomId} from=${sanitizedMsg.senderDeviceId} msgId=${sanitizedMsg.messageId}"
                )
            }
            RealtimeEventType.PERMISSION_DENIED -> {
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull()
                val code = payload?.optString("code", "PERMISSION_DENIED") ?: "PERMISSION_DENIED"
                val message = payload?.optString(
                    "message",
                    PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                ) ?: PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                _participantControlsDisabledNotice.value = message
                if (code == "PARTICIPANT_CONTROLS_DISABLED") {
                    _roomState.update { state ->
                        state?.copy(permissions = state.permissions.withParticipantControls(false))
                    }
                }
                recordDiagnosticLog("PERMISSION_DENIED room=${envelope.roomId} code=$code message=$message")
            }
            else -> Unit
        }
    }

    internal fun sendControlFrameBypassingClientCheckForTest(
        type: RealtimeEventType,
        payloadJson: String
    ): Boolean {
        return sendSignedEvent(
            type = type,
            payloadJson = payloadJson,
            bypassRateLimit = true
        )
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
        private const val LOG_TAG = "NyxoraConnect"
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 5
        const val DEFAULT_BASE_RECONNECT_DELAY_MS = 400L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 6_000L
        private const val MAX_DIAGNOSTIC_LOG_ENTRIES = 50
        const val PARTICIPANT_CONTROLS_DISABLED_MESSAGE =
            "Shared controls are disabled by the host — only the host can control shared navigation and playback."

        val defaultSharedRelayServer = NyxoraWebSocketRelayServer()

        fun isRobolectricOrJvmTestRuntime(): Boolean {
            return Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true ||
                System.getProperty("robolectric.logging") != null ||
                runCatching {
                    Class.forName("org.robolectric.Robolectric")
                    true
                }.getOrDefault(false)
        }

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
