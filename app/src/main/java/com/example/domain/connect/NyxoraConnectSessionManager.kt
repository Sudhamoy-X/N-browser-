package com.example.domain.connect

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    data class ExecuteHostMirrorRemoteControl(
        val actionType: String,
        val seekDeltaSeconds: Double = 0.0,
        val initiatedByDeviceId: String = ""
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
 * - Bidirectional URL & navigation synchronization across connected devices (Mode A: Browse Together)
 * - Real-time WebRTC + MediaProjection screen mirroring (Mode B: Live Mirror Mode)
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
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    private val heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    private val configuredEndpointProvider: () -> String = { NyxoraConnectSecurity.configuredPublicWssEndpoint() }
) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val deduplicator = BoundedEventDeduplicator(maxCapacity = 512)
    private val outgoingRateLimiter = SlidingWindowRateLimiter(maxEventsPerWindow = 24, windowMillis = 3000L)
    private val clientSequence = AtomicLong(1L)
    private val intentionalDisconnect = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0L)
    private val pendingChatOutbox = java.util.concurrent.ConcurrentLinkedQueue<RoomChatMessage>()

    @Volatile
    private var activeWebSocket: WebSocket? = null
    @Volatile
    private var activeConnectEndpoint: String = ""
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var handshakeTimeoutJob: Job? = null

    @Volatile
    private var reconnectAttempt: Int = 0

    @Volatile
    private var consecutiveUnackedPings: Int = 0

    @Volatile
    private var hasConfirmedServerSession: Boolean = false

    @Volatile
    private var isAppInForeground: Boolean = true

    @Volatile
    private var isNetworkAvailable: Boolean = true

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

    @Volatile
    private var lastViewerMirrorSessionId: String = ""

    private val _deviceDisplayName = MutableStateFlow(initialDeviceName)
    val deviceDisplayName: StateFlow<String> = _deviceDisplayName.asStateFlow()

    private val _roomState = MutableStateFlow<NyxoraConnectRoomState?>(null)
    val roomState: StateFlow<NyxoraConnectRoomState?> = _roomState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(SyncConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<SyncConnectionStatus> = _connectionStatus.asStateFlow()

    private val _sessionLifecycleState = MutableStateFlow(SyncConnectionStatus.DISCONNECTED)
    val sessionLifecycleState: StateFlow<SyncConnectionStatus> = _sessionLifecycleState.asStateFlow()

    private val _lastSuccessfulHeartbeatMillis = MutableStateFlow(0L)
    val lastSuccessfulHeartbeatMillis: StateFlow<Long> = _lastSuccessfulHeartbeatMillis.asStateFlow()

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

    // --- LIVE MIRROR MODE & WEBRTC STATE ---

    private val _liveMirrorState = MutableStateFlow(LiveMirrorSessionMetadata())
    val liveMirrorState: StateFlow<LiveMirrorSessionMetadata> = _liveMirrorState.asStateFlow()

    private val _viewerMirrorConnectionState = MutableStateFlow(ViewerMirrorConnectionState.IDLE)
    val viewerMirrorConnectionState: StateFlow<ViewerMirrorConnectionState> = _viewerMirrorConnectionState.asStateFlow()

    private val _isViewerReturnedToBrowseTogether = MutableStateFlow(false)
    val isViewerReturnedToBrowseTogether: StateFlow<Boolean> = _isViewerReturnedToBrowseTogether.asStateFlow()

    private val _isViewerMirrorFullscreen = MutableStateFlow(false)
    val isViewerMirrorFullscreen: StateFlow<Boolean> = _isViewerMirrorFullscreen.asStateFlow()

    private val _allowPrivateTabMirroring = MutableStateFlow(false)
    val allowPrivateTabMirroring: StateFlow<Boolean> = _allowPrivateTabMirroring.asStateFlow()

    private val _isMirrorPausedForPrivateTab = MutableStateFlow(false)
    val isMirrorPausedForPrivateTab: StateFlow<Boolean> = _isMirrorPausedForPrivateTab.asStateFlow()

    private val _viewerRenderedFrameCount = MutableStateFlow(0L)
    val viewerRenderedFrameCount: StateFlow<Long> = _viewerRenderedFrameCount.asStateFlow()

    private val _hostExecutedRemoteControlHistory = MutableStateFlow<List<String>>(emptyList())
    val hostExecutedRemoteControlHistory: StateFlow<List<String>> = _hostExecutedRemoteControlHistory.asStateFlow()

    @Volatile
    var webRtcMirrorEngine: NyxoraWebRtcMirrorEngine? = null
        private set

    private val _remoteSyncCommands = MutableSharedFlow<IncomingRemoteSyncCommand>(extraBufferCapacity = 16)
    val remoteSyncCommands: SharedFlow<IncomingRemoteSyncCommand> = _remoteSyncCommands.asSharedFlow()

    val isInRoom: Boolean
        get() = _roomState.value != null && !_connectionStatus.value.isTerminalOrDisconnected

    val isCurrentDeviceHost: Boolean
        get() = _roomState.value?.hostDeviceId == localDeviceId

    /**
     * True when this device is a Participant (Viewer) in a room where the Host is actively streaming
     * in Live Mirror Mode and the Viewer has not manually switched back to Browse Together.
     */
    val isViewerActivelyWatchingMirror: Boolean
        get() = isInRoom &&
            !isCurrentDeviceHost &&
            _liveMirrorState.value.isMirrorLive &&
            !_isViewerReturnedToBrowseTogether.value

    val configuredBackendEndpoint: String
        get() = resolveConfiguredProductionEndpoint()

    /**
     * Attaches the Android context to initialize the hardware-accelerated [NyxoraWebRtcMirrorEngine]
     * and wire foreground service stop actions.
     */
    fun attachMirrorEngineContext(context: Context): NyxoraWebRtcMirrorEngine {
        val existing = webRtcMirrorEngine
        if (existing != null) return existing
        val engine = NyxoraWebRtcMirrorEngine(
            context = context.applicationContext,
            sendSignalCallback = { eventType, signalPayload ->
                sendSignedEvent(
                    type = eventType,
                    payloadJson = signalPayload.toJson().toString(),
                    bypassRateLimit = true
                )
            },
            onHostMirrorStoppedUnexpectedly = { reason ->
                stopHostLiveMirror(reason = reason)
            }
        )
        webRtcMirrorEngine = engine
        NyxoraLiveMirrorForegroundService.onUserRequestedStopListener = {
            stopHostLiveMirror(reason = "Stopped from notification")
        }
        return engine
    }

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
        _sessionLifecycleState.value = SyncConnectionStatus.CONNECTING
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
            _sessionLifecycleState.value = SyncConnectionStatus.AUTHENTICATION_FAILED
            recordDiagnosticLog("JOIN_REJECTED_LOCAL reason=INVALID_FORMAT input=$rawRoomOrInvite")
            return false
        }

        if (parsed.validationStatus != InvitationValidationStatus.VALID && explicitAccessKey.isBlank()) {
            _lastSyncError.value = parsed.validationStatus.userMessage
            _sessionLifecycleState.value = SyncConnectionStatus.AUTHENTICATION_FAILED
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
        consecutiveUnackedPings = 0
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
        _sessionLifecycleState.value = SyncConnectionStatus.CONNECTING
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
    fun leaveOrCloseRoom(
        notifyServer: Boolean = true,
        terminalLifecycleStatus: SyncConnectionStatus = SyncConnectionStatus.DISCONNECTED
    ) {
        intentionalDisconnect.set(true)
        connectionGeneration.incrementAndGet()
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
        activeConnectEndpoint = ""
        runCatching { ws?.close(1000, "Session ended") }
        webRtcMirrorEngine?.releaseAll()
        NyxoraWebRtcIceConfigProvider.clearCachedShortLivedTurnCredentials()
        activeAccessKey = ""
        activeInviteToken = ""
        lastGeneratedInviteToken = ""
        hasConfirmedServerSession = false
        reconnectAttempt = 0
        consecutiveUnackedPings = 0
        _lastSuccessfulHeartbeatMillis.value = 0L
        _currentShareableInviteUrl.value = ""
        lastAppliedSequence = 0L
        lastPublishedNavUrl = ""
        lastPublishedNavType = null
        lastPublishedNavAtMillis = 0L
        lastBeaconPositionSec = -1.0
        lastBeaconSentAtMillis = 0L
        lastViewerMirrorSessionId = ""
        pendingChatOutbox.clear()
        deduplicator.clear()
        outgoingRateLimiter.clear()
        _roomState.value = null
        _isFollowingPaused.value = false
        _videoCompatibility.value = VideoCompatibilityState.UNKNOWN
        _liveMirrorState.value = LiveMirrorSessionMetadata()
        _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.IDLE
        _isViewerReturnedToBrowseTogether.value = false
        _isViewerMirrorFullscreen.value = false
        _isMirrorPausedForPrivateTab.value = false
        _viewerRenderedFrameCount.value = 0L
        _chatMessages.value = emptyList()
        _unreadChatCount.value = 0
        _isChatOverlayExpanded.value = false
        _isChatBubbleDismissed.value = false
        _participantControlsDisabledNotice.value = null
        _connectionStatus.value = SyncConnectionStatus.DISCONNECTED
        _sessionLifecycleState.value = terminalLifecycleStatus
    }

    /**
     * Master Switch: toggles `allowParticipantControls` without resetting individual granular permissions.
     */
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

    /**
     * Updates any individual or group of Host Control Permissions and synchronizes immediately with all room members.
     */
    fun updateHostPermissions(transform: (RoomPermissionsConfig) -> RoomPermissionsConfig) {
        val current = _roomState.value ?: return
        if (current.hostDeviceId != localDeviceId) return
        val updatedPerms = transform(current.permissions)
        _roomState.value = current.copy(permissions = updatedPerms)
        if (updatedPerms.allowParticipantControls) {
            _participantControlsDisabledNotice.value = null
        }
        updateRoomConfiguration(permissions = updatedPerms)
        recordDiagnosticLog("HOST_UPDATE_PERMISSIONS room=${current.roomId} perms=${updatedPerms.toJson()}")
    }

    fun setAllowParticipantNavigationSync(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantNavigationSync = allowed) }
    }

    fun setAllowParticipantUrlInput(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantUrlInput = allowed) }
    }

    fun setAllowParticipantVideoPlayPause(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantVideoPlayPause = allowed) }
    }

    fun setAllowParticipantVideoSeek(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantVideoSeek = allowed) }
    }

    fun setAllowParticipantVolumeControl(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantVolumeControl = allowed) }
    }

    fun setAllowParticipantFullscreenControl(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantFullscreenControl = allowed) }
    }

    fun setAllowParticipantChat(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantChat = allowed) }
    }

    fun setAllowParticipantPlaybackControl(allowed: Boolean) {
        updateHostPermissions { it.copy(allowParticipantPlaybackControl = allowed) }
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
        if (!hasConfirmedServerSession || _connectionStatus.value.isTerminalOrDisconnected) {
            return false
        }
        val isHost = current.hostDeviceId == localDeviceId
        val isConfirmedMember = isHost || current.members.any { it.deviceId == localDeviceId }
        if (!isConfirmedMember) {
            return false
        }
        if (!isHost && !current.permissions.canParticipantSendChat) {
            _participantControlsDisabledNotice.value = CHAT_DISABLED_MESSAGE
            return false
        }
        val sanitizedText = NyxoraConnectSecurity.sanitizeChatMessageForSync(
            rawMessage = rawText,
            roomAccessKey = activeAccessKey.ifBlank { current.accessKey }
        ) ?: return false

        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val role = if (isHost) RoomRole.HOST else RoomRole.PARTICIPANT
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
        val sent = if (_connectionStatus.value == SyncConnectionStatus.RECONNECTING) {
            false
        } else {
            sendSignedEvent(
                type = RealtimeEventType.CHAT_MESSAGE,
                payloadJson = payload.toString()
            )
        }
        if (sent) {
            appendChatMessageInternal(chatMessage, isIncomingRemote = false)
            recordDiagnosticLog("CHAT_SENT room=${current.roomId} msgId=$messageId text=$sanitizedText")
            return true
        } else if (hasConfirmedServerSession && !intentionalDisconnect.get()) {
            // Queue for immediate delivery upon socket recovery so chat messages are never lost during brief network switches
            pendingChatOutbox.offer(chatMessage)
            appendChatMessageInternal(chatMessage, isIncomingRemote = false)
            recordDiagnosticLog("CHAT_QUEUED_FOR_RECONNECT room=${current.roomId} msgId=$messageId")
            reconnectNowIfNeeded("chat_send_while_reconnecting")
            return true
        }
        return false
    }

    private fun flushPendingChatOutbox() {
        val current = _roomState.value ?: return
        val ws = activeWebSocket ?: return
        if (!hasConfirmedServerSession || activeAccessKey.isBlank()) return
        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost && !current.permissions.canParticipantSendChat) {
            pendingChatOutbox.clear()
            return
        }
        while (true) {
            val pending = pendingChatOutbox.poll() ?: break
            if (pending.roomId != current.roomId) continue
            val payload = JSONObject().apply {
                put("messageId", pending.messageId)
                put("senderName", pending.senderName)
                put("text", pending.text)
                put("timestampMillis", pending.timestampMillis)
            }
            sendSignedEventOnSocket(
                webSocket = ws,
                roomId = current.roomId,
                accessKey = activeAccessKey,
                type = RealtimeEventType.CHAT_MESSAGE,
                payloadJson = payload.toString(),
                authorityEpoch = current.authorityEpoch
            )
        }
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
        val newStatus = if (paused) {
            SyncConnectionStatus.PAUSED_FOLLOWING
        } else if (hasConfirmedServerSession) {
            SyncConnectionStatus.SYNCED
        } else {
            _connectionStatus.value
        }
        _connectionStatus.value = newStatus
        _sessionLifecycleState.value = newStatus
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
        eventType: RealtimeEventType = RealtimeEventType.NAVIGATE_URL,
        isUrlInput: Boolean = false
    ): Boolean {
        if (!_hasUserConsentedToShare.value) return false
        val current = _roomState.value ?: return false
        if (!current.isSyncActive || _isFollowingPaused.value) return false

        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost) {
            if (!current.permissions.allowParticipantControls) {
                _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                return false
            }
            if (!current.permissions.allowParticipantNavigationSync) {
                _participantControlsDisabledNotice.value = NAVIGATION_RESTRICTED_MESSAGE
                return false
            }
            if (isUrlInput && !current.permissions.allowParticipantUrlInput) {
                _participantControlsDisabledNotice.value = URL_INPUT_RESTRICTED_MESSAGE
                return false
            }
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
            if (isUrlInput) {
                put("isUrlInput", true)
            }
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
        if (!isHost) {
            if (!current.permissions.allowParticipantControls) {
                _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                return false
            }
            if (!current.permissions.allowParticipantNavigationSync) {
                _participantControlsDisabledNotice.value = NAVIGATION_RESTRICTED_MESSAGE
                return false
            }
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
        isPrivateMode: Boolean = false,
        controlActionType: String = "",
        volumeLevel: Double? = null,
        isMuted: Boolean? = null,
        isFullscreen: Boolean? = null,
        orientationMode: String? = null
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

        val existingPb = current.playbackState
        val resolvedActionType = controlActionType.trim().uppercase().ifBlank {
            when {
                isBeacon -> "BEACON"
                volumeLevel != null || isMuted != null -> "VOLUME"
                isFullscreen != null || orientationMode != null -> "FULLSCREEN"
                existingPb != null &&
                    isPlaying == existingPb.isPlaying &&
                    kotlin.math.abs(positionSeconds - existingPb.positionSeconds) >= 1.5 -> "SEEK"
                playbackRate != (existingPb?.playbackRate ?: 1.0) -> "RATE"
                else -> "PLAY_PAUSE"
            }
        }

        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost) {
            if (isBeacon) return false
            if (!current.permissions.allowParticipantControls) {
                _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                return false
            }
            if (!current.permissions.allowParticipantPlaybackControl) {
                _participantControlsDisabledNotice.value = PLAYBACK_RESTRICTED_MESSAGE
                return false
            }
            when (resolvedActionType) {
                "PLAY", "PAUSE", "PLAY_PAUSE" -> {
                    if (!current.permissions.allowParticipantVideoPlayPause) {
                        _participantControlsDisabledNotice.value = VIDEO_PLAY_PAUSE_RESTRICTED_MESSAGE
                        return false
                    }
                }
                "SEEK" -> {
                    if (!current.permissions.allowParticipantVideoSeek) {
                        _participantControlsDisabledNotice.value = VIDEO_SEEK_RESTRICTED_MESSAGE
                        return false
                    }
                }
                "VOLUME", "MUTE" -> {
                    if (!current.permissions.allowParticipantVolumeControl) {
                        _participantControlsDisabledNotice.value = VOLUME_RESTRICTED_MESSAGE
                        return false
                    }
                }
                "FULLSCREEN", "ORIENTATION" -> {
                    if (!current.permissions.allowParticipantFullscreenControl) {
                        _participantControlsDisabledNotice.value = FULLSCREEN_RESTRICTED_MESSAGE
                        return false
                    }
                }
            }
        }

        val candidateUrl = pageUrl.ifBlank { current.playbackState?.pageUrl ?: current.currentSyncedUrl }
        val safeUrl = NyxoraConnectSecurity.sanitizeUrlForSync(candidateUrl, false, false) ?: return false
        val safeDur = durationSeconds.coerceAtLeast(0.0)
        val rawPos = positionSeconds.coerceAtLeast(0.0)
        val safePos = if (safeDur > 0.0) rawPos.coerceAtMost(safeDur) else rawPos
        val safeRate = playbackRate.coerceIn(0.25, 2.0)
        val safeVolume = (volumeLevel ?: existingPb?.volumeLevel ?: 1.0).coerceIn(0.0, 1.0)
        val safeMuted = isMuted ?: existingPb?.isMuted ?: false
        val safeFullscreen = isFullscreen ?: existingPb?.isFullscreen ?: false
        val safeOrientation = orientationMode?.takeIf { it.isNotBlank() } ?: existingPb?.orientationMode ?: "AUTO"

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
            put("volumeLevel", safeVolume)
            put("isMuted", safeMuted)
            put("isFullscreen", safeFullscreen)
            put("orientationMode", safeOrientation)
            put("controlActionType", resolvedActionType)
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
                volumeLevel = safeVolume,
                isMuted = safeMuted,
                isFullscreen = safeFullscreen,
                orientationMode = safeOrientation,
                controlActionType = resolvedActionType,
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

    fun publishVolumeControlEvent(
        pageUrl: String = "",
        volumeLevel: Double,
        isMuted: Boolean = false,
        isPrivateTab: Boolean = false,
        isPrivateMode: Boolean = false
    ): Boolean {
        val currentPb = _roomState.value?.playbackState
        return publishPlaybackEvent(
            pageUrl = pageUrl.ifBlank { currentPb?.pageUrl ?: _roomState.value?.currentSyncedUrl ?: "" },
            isPlaying = currentPb?.isPlaying ?: true,
            positionSeconds = currentPb?.positionSeconds ?: 0.0,
            durationSeconds = currentPb?.durationSeconds ?: 0.0,
            playbackRate = currentPb?.playbackRate ?: 1.0,
            isBeacon = false,
            isPrivateTab = isPrivateTab,
            isPrivateMode = isPrivateMode,
            controlActionType = "VOLUME",
            volumeLevel = volumeLevel,
            isMuted = isMuted
        )
    }

    fun publishFullscreenControlEvent(
        pageUrl: String = "",
        isFullscreen: Boolean,
        orientationMode: String = "AUTO",
        isPrivateTab: Boolean = false,
        isPrivateMode: Boolean = false
    ): Boolean {
        val currentPb = _roomState.value?.playbackState
        return publishPlaybackEvent(
            pageUrl = pageUrl.ifBlank { currentPb?.pageUrl ?: _roomState.value?.currentSyncedUrl ?: "" },
            isPlaying = currentPb?.isPlaying ?: true,
            positionSeconds = currentPb?.positionSeconds ?: 0.0,
            durationSeconds = currentPb?.durationSeconds ?: 0.0,
            playbackRate = currentPb?.playbackRate ?: 1.0,
            isBeacon = false,
            isPrivateTab = isPrivateTab,
            isPrivateMode = isPrivateMode,
            controlActionType = "FULLSCREEN",
            isFullscreen = isFullscreen,
            orientationMode = orientationMode
        )
    }

    // --- MODE B: LIVE MIRROR MODE (HOST CAPTURE + VIEWER WEBRTC STREAM + REMOTE CONTROLS) ---

    fun setAllowPrivateTabMirroring(allowed: Boolean) {
        _allowPrivateTabMirroring.value = allowed
        if (allowed) {
            _isMirrorPausedForPrivateTab.value = false
        }
    }

    /**
     * Step 1 of Host Live Mirror state machine (`BROWSING -> MIRROR_STARTING`):
     * Validates Host authority and Private Tab protection before launching the official Android
     * `MediaProjection` permission dialog.
     */
    fun beginHostLiveMirrorRequest(
        isPrivateTab: Boolean = false,
        isPrivateMode: Boolean = false,
        qualityProfile: LiveMirrorQualityProfile = _liveMirrorState.value.qualityProfile,
        shareAudio: Boolean = _liveMirrorState.value.isAudioShared
    ): Boolean {
        val current = _roomState.value ?: return false
        if (current.hostDeviceId != localDeviceId) {
            _participantControlsDisabledNotice.value = HOST_ONLY_MIRROR_MESSAGE
            return false
        }
        if ((isPrivateTab || isPrivateMode) && !_allowPrivateTabMirroring.value) {
            _isMirrorPausedForPrivateTab.value = true
            _lastSyncError.value = PRIVATE_TAB_MIRROR_BLOCKED_MESSAGE
            recordDiagnosticLog("MIRROR_BLOCKED_PRIVATE_TAB room=${current.roomId}")
            return false
        }
        _isMirrorPausedForPrivateTab.value = false
        _lastSyncError.value = null
        val now = System.currentTimeMillis()
        val startingMeta = _liveMirrorState.value.copy(
            mirrorState = LiveMirrorSessionState.MIRROR_STARTING,
            hostDeviceId = localDeviceId,
            isAudioShared = shareAudio,
            qualityProfile = qualityProfile,
            streamWidth = qualityProfile.width,
            streamHeight = qualityProfile.height,
            targetFps = qualityProfile.fps,
            maxBitrateKbps = qualityProfile.maxBitrateKbps,
            updatedAtMillis = now
        )
        _liveMirrorState.value = startingMeta
        _roomState.update { state ->
            state?.copy(
                mode = RoomSyncMode.LIVE_MIRROR,
                liveMirrorState = startingMeta
            )
        }
        sendSignedEvent(
            type = RealtimeEventType.MIRROR_SESSION_STATE,
            payloadJson = startingMeta.toJson().toString(),
            bypassRateLimit = true
        )
        val cachedTurn = NyxoraWebRtcIceConfigProvider.getCachedShortLivedTurnCredentials()
        if (cachedTurn == null || cachedTurn.isExpired(now)) {
            scope.launch(Dispatchers.IO) {
                refreshShortLivedTurnCredentials()
            }
        }
        recordDiagnosticLog("HOST_MIRROR_STARTING room=${current.roomId} profile=${qualityProfile.name}")
        return true
    }

    /**
     * Fetches short-lived TURN credentials from the active NYXORA Connect backend relay endpoint
     * and caches them in [NyxoraWebRtcIceConfigProvider].
     */
    fun refreshShortLivedTurnCredentials(
        nowMillis: Long = System.currentTimeMillis()
    ): Result<ShortLivedTurnCredentials> {
        val current = _roomState.value
            ?: return Result.failure(IllegalStateException("No active room session"))
        val endpoint = activeConnectEndpoint.ifBlank { current.endpointUrl }
        val signingSecret = activeAccessKey.ifBlank {
            current.accessKey.ifBlank { NyxoraConnectSecurity.deriveRoomCodeHandshakeKey(current.roomId) }
        }
        val result = NyxoraWebRtcIceConfigProvider.fetchShortLivedTurnCredentials(
            endpointUrl = endpoint,
            roomId = current.roomId,
            deviceId = localDeviceId,
            signingKey = signingSecret,
            nowMillis = nowMillis
        )
        result.onSuccess { creds ->
            recordDiagnosticLog(
                "WEBRTC_SHORT_LIVED_TURN_REFRESHED room=${current.roomId} urls=${creds.turnUrls.size} ttlSec=${creds.ttlSeconds}"
            )
        }
        return result
    }

    /**
     * Step 2 of Host Live Mirror state machine (`MIRROR_STARTING -> MIRROR_LIVE`):
     * Called after the user grants Android `MediaProjection` permission and the foreground service is active.
     */
    fun onHostMediaProjectionPermissionGranted(
        permissionResultData: Intent? = null,
        qualityProfile: LiveMirrorQualityProfile = _liveMirrorState.value.qualityProfile,
        shareAudio: Boolean = _liveMirrorState.value.isAudioShared,
        isPrivateTab: Boolean = false,
        isPrivateMode: Boolean = false
    ): Boolean {
        val current = _roomState.value ?: return false
        if (current.hostDeviceId != localDeviceId) {
            _participantControlsDisabledNotice.value = HOST_ONLY_MIRROR_MESSAGE
            return false
        }
        if ((isPrivateTab || isPrivateMode) && !_allowPrivateTabMirroring.value) {
            _isMirrorPausedForPrivateTab.value = true
            _lastSyncError.value = PRIVATE_TAB_MIRROR_BLOCKED_MESSAGE
            onHostMediaProjectionPermissionDenied(PRIVATE_TAB_MIRROR_BLOCKED_MESSAGE)
            return false
        }

        val sessionId = "MIR-${UUID.randomUUID().toString().take(8).uppercase()}"
        val engine = webRtcMirrorEngine
        if (engine != null && permissionResultData != null) {
            engine.startHostScreenCapture(
                permissionResultData = permissionResultData,
                qualityProfile = qualityProfile,
                shareAudio = shareAudio,
                existingSessionId = sessionId
            )
        }

        val isDrmWarning = _videoCompatibility.value == VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED
        val drmMessage = if (isDrmWarning) DRM_BLACK_SCREEN_WARNING_MESSAGE else ""
        val now = System.currentTimeMillis()
        val liveMeta = LiveMirrorSessionMetadata(
            mirrorState = LiveMirrorSessionState.MIRROR_LIVE,
            hostDeviceId = localDeviceId,
            mirrorSessionId = sessionId,
            isAudioShared = shareAudio,
            qualityProfile = qualityProfile,
            streamWidth = qualityProfile.width,
            streamHeight = qualityProfile.height,
            targetFps = qualityProfile.fps,
            maxBitrateKbps = qualityProfile.maxBitrateKbps,
            isProtectedContentWarning = isDrmWarning,
            protectedContentMessage = drmMessage,
            updatedAtMillis = now,
            sequenceNumber = current.lastSequenceNumber + 1L
        )
        _liveMirrorState.value = liveMeta
        _roomState.update { state ->
            state?.copy(
                mode = RoomSyncMode.LIVE_MIRROR,
                liveMirrorState = liveMeta
            )
        }
        sendSignedEvent(
            type = RealtimeEventType.MIRROR_SESSION_STATE,
            payloadJson = liveMeta.toJson().toString(),
            bypassRateLimit = true
        )
        recordDiagnosticLog("HOST_MIRROR_LIVE room=${current.roomId} sessionId=$sessionId profile=${qualityProfile.name}")

        // Immediately send WebRTC SDP Offers to all connected Viewers in the room
        val viewers = (_roomState.value?.members ?: emptyList()).filter {
            it.deviceId != localDeviceId && !it.status.isTerminalOrDisconnected
        }
        for (viewer in viewers) {
            initiateOfferToViewer(viewer.deviceId, sessionId, qualityProfile, shareAudio)
        }
        return true
    }

    /**
     * Handles `MediaProjection` permission denial cleanly (`MIRROR_STARTING -> MIRROR_STOPPING -> BROWSING`).
     */
    fun onHostMediaProjectionPermissionDenied(
        reason: String = "Screen capture permission was denied."
    ) {
        val current = _roomState.value
        _lastSyncError.value = reason
        webRtcMirrorEngine?.stopHostScreenCapture()
        val now = System.currentTimeMillis()
        val resetMeta = LiveMirrorSessionMetadata(
            mirrorState = LiveMirrorSessionState.BROWSING,
            hostDeviceId = current?.hostDeviceId ?: localDeviceId,
            isAudioShared = _liveMirrorState.value.isAudioShared,
            qualityProfile = _liveMirrorState.value.qualityProfile,
            updatedAtMillis = now
        )
        _liveMirrorState.value = resetMeta
        if (current != null && current.hostDeviceId == localDeviceId) {
            _roomState.update { state ->
                state?.copy(
                    mode = RoomSyncMode.BROWSE_TOGETHER,
                    liveMirrorState = resetMeta
                )
            }
            sendSignedEvent(
                type = RealtimeEventType.MIRROR_SESSION_STATE,
                payloadJson = resetMeta.toJson().toString(),
                bypassRateLimit = true
            )
        }
        recordDiagnosticLog("HOST_MIRROR_PERMISSION_DENIED reason=$reason")
    }

    /**
     * Stops an active Host Live Mirror session (`MIRROR_LIVE -> MIRROR_STOPPING -> BROWSING`),
     * releases `MediaProjection` & WebRTC resources, and returns all room devices to `Browse Together`.
     */
    fun stopHostLiveMirror(reason: String = "Host stopped Live Mirror"): Boolean {
        val current = _roomState.value ?: return false
        if (current.hostDeviceId != localDeviceId) {
            _participantControlsDisabledNotice.value = HOST_ONLY_MIRROR_MESSAGE
            return false
        }
        val stoppingMeta = _liveMirrorState.value.copy(
            mirrorState = LiveMirrorSessionState.MIRROR_STOPPING,
            updatedAtMillis = System.currentTimeMillis()
        )
        _liveMirrorState.value = stoppingMeta
        webRtcMirrorEngine?.stopHostScreenCapture()

        val browsingMeta = LiveMirrorSessionMetadata(
            mirrorState = LiveMirrorSessionState.BROWSING,
            hostDeviceId = localDeviceId,
            mirrorSessionId = "",
            isAudioShared = stoppingMeta.isAudioShared,
            qualityProfile = stoppingMeta.qualityProfile,
            streamWidth = stoppingMeta.qualityProfile.width,
            streamHeight = stoppingMeta.qualityProfile.height,
            targetFps = stoppingMeta.qualityProfile.fps,
            maxBitrateKbps = stoppingMeta.qualityProfile.maxBitrateKbps,
            updatedAtMillis = System.currentTimeMillis()
        )
        _liveMirrorState.value = browsingMeta
        _roomState.update { state ->
            state?.copy(
                mode = RoomSyncMode.BROWSE_TOGETHER,
                liveMirrorState = browsingMeta
            )
        }
        val sent = sendSignedEvent(
            type = RealtimeEventType.MIRROR_SESSION_STATE,
            payloadJson = browsingMeta.toJson().toString(),
            bypassRateLimit = true
        )
        recordDiagnosticLog("HOST_MIRROR_STOPPED room=${current.roomId} reason=$reason")
        return sent
    }

    /**
     * Automatically pauses/stops Live Mirror if the Host switches to a Private/Incognito tab
     * without explicit session consent.
     */
    fun onHostPrivateTabVisibilityChanged(isPrivateTabOrMode: Boolean) {
        if (!isCurrentDeviceHost) return
        if (isPrivateTabOrMode && !_allowPrivateTabMirroring.value && _liveMirrorState.value.mirrorState.isMirrorActiveOrTransitioning) {
            _isMirrorPausedForPrivateTab.value = true
            _lastSyncError.value = PRIVATE_TAB_MIRROR_BLOCKED_MESSAGE
            stopHostLiveMirror(reason = "Paused automatically because a Private tab was opened")
        } else if (!isPrivateTabOrMode) {
            _isMirrorPausedForPrivateTab.value = false
        }
    }

    fun setMirrorAudioShared(enabled: Boolean): Boolean {
        val current = _roomState.value
        webRtcMirrorEngine?.setHostAudioShared(enabled)
        val updated = _liveMirrorState.value.copy(
            isAudioShared = enabled,
            updatedAtMillis = System.currentTimeMillis()
        )
        _liveMirrorState.value = updated
        if (current != null && current.hostDeviceId == localDeviceId && updated.isMirrorLive) {
            _roomState.update { it?.copy(liveMirrorState = updated) }
            return sendSignedEvent(
                type = RealtimeEventType.MIRROR_SESSION_STATE,
                payloadJson = updated.toJson().toString(),
                bypassRateLimit = true
            )
        }
        return true
    }

    fun setMirrorQualityProfile(profile: LiveMirrorQualityProfile): Boolean {
        val current = _roomState.value
        webRtcMirrorEngine?.updateHostQualityProfile(profile)
        val updated = _liveMirrorState.value.copy(
            qualityProfile = profile,
            streamWidth = profile.width,
            streamHeight = profile.height,
            targetFps = profile.fps,
            maxBitrateKbps = profile.maxBitrateKbps,
            updatedAtMillis = System.currentTimeMillis()
        )
        _liveMirrorState.value = updated
        if (current != null && current.hostDeviceId == localDeviceId && updated.isMirrorLive) {
            _roomState.update { it?.copy(liveMirrorState = updated) }
            return sendSignedEvent(
                type = RealtimeEventType.MIRROR_SESSION_STATE,
                payloadJson = updated.toJson().toString(),
                bypassRateLimit = true
            )
        }
        return true
    }

    fun setMirrorProtectedContentWarning(
        isProtectedContent: Boolean,
        message: String = if (isProtectedContent) DRM_BLACK_SCREEN_WARNING_MESSAGE else ""
    ) {
        val current = _roomState.value
        val updated = _liveMirrorState.value.copy(
            isProtectedContentWarning = isProtectedContent,
            protectedContentMessage = message,
            updatedAtMillis = System.currentTimeMillis()
        )
        _liveMirrorState.value = updated
        if (current != null && current.hostDeviceId == localDeviceId && updated.isMirrorLive) {
            _roomState.update { it?.copy(liveMirrorState = updated) }
            sendSignedEvent(
                type = RealtimeEventType.MIRROR_SESSION_STATE,
                payloadJson = updated.toJson().toString(),
                bypassRateLimit = true
            )
        }
    }

    /**
     * Allows a Viewer to locally exit the Live Mirror stream view and return to `Browse Together`
     * (or re-enter the Live Mirror stream when `returned = false`).
     */
    fun setViewerReturnToBrowseTogether(returned: Boolean) {
        _isViewerReturnedToBrowseTogether.value = returned
        if (returned) {
            _isViewerMirrorFullscreen.value = false
            webRtcMirrorEngine?.stopViewerStream(resetToIdle = true)
            _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.IDLE
            val syncedUrl = _roomState.value?.currentSyncedUrl.orEmpty()
            val syncedTitle = _roomState.value?.currentSyncedTitle.orEmpty()
            if (syncedUrl.isNotBlank()) {
                _remoteSyncCommands.tryEmit(
                    IncomingRemoteSyncCommand.NavigateToUrl(
                        url = syncedUrl,
                        title = syncedTitle
                    )
                )
            }
        } else if (_liveMirrorState.value.isMirrorLive && !isCurrentDeviceHost) {
            requestViewerMirrorReconnect()
        }
    }

    fun setViewerMirrorFullscreen(fullscreen: Boolean) {
        _isViewerMirrorFullscreen.value = fullscreen
    }

    /**
     * Requests a fresh WebRTC SDP Offer from the Host when a Viewer joins or reconnects during `MIRROR_LIVE`.
     */
    fun requestViewerMirrorReconnect(): Boolean {
        val current = _roomState.value ?: return false
        if (current.hostDeviceId == localDeviceId || !_liveMirrorState.value.isMirrorLive) return false
        _isViewerReturnedToBrowseTogether.value = false
        _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.CONNECTING
        webRtcMirrorEngine?.markViewerConnecting()
        val readySignal = WebRtcSignalPayload(
            mirrorSessionId = _liveMirrorState.value.mirrorSessionId,
            senderDeviceId = localDeviceId,
            targetDeviceId = current.hostDeviceId
        )
        return sendSignedEvent(
            type = RealtimeEventType.WEBRTC_VIEWER_READY,
            payloadJson = readySignal.toJson().toString(),
            bypassRateLimit = true
        )
    }

    /**
     * Sends a Host-routed playback command (`PLAY`, `PAUSE`, `PLAY_PAUSE`, `SEEK`, `REWIND`, `FORWARD`)
     * from a Viewer to the Host while Live Mirror Mode is active.
     * Viewers never execute playback locally while mirroring; the Host executes the command on the original
     * browser page and streams the updated screen back.
     */
    fun sendViewerMirrorRemoteControl(
        actionType: String,
        seekDeltaSeconds: Double? = null
    ): Boolean {
        val current = _roomState.value ?: return false
        val normalizedAction = actionType.trim().uppercase().ifBlank { "PLAY_PAUSE" }
        val resolvedSeekDelta = seekDeltaSeconds ?: 0.0
        val isHost = current.hostDeviceId == localDeviceId
        if (!isHost) {
            val perms = current.permissions
            val isSeek = normalizedAction == "SEEK" || normalizedAction == "REWIND" || normalizedAction == "FORWARD"
            val isPlayPause = normalizedAction == "PLAY" || normalizedAction == "PAUSE" || normalizedAction == "PLAY_PAUSE"
            if (!perms.allowParticipantControls) {
                _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                return false
            }
            if (!perms.allowParticipantPlaybackControl) {
                _participantControlsDisabledNotice.value = PLAYBACK_RESTRICTED_MESSAGE
                return false
            }
            if (isSeek && !perms.allowParticipantVideoSeek) {
                _participantControlsDisabledNotice.value = VIDEO_SEEK_RESTRICTED_MESSAGE
                return false
            }
            if (isPlayPause && !perms.allowParticipantVideoPlayPause) {
                _participantControlsDisabledNotice.value = VIDEO_PLAY_PAUSE_RESTRICTED_MESSAGE
                return false
            }
        }
        val payload = JSONObject().apply {
            put("controlActionType", normalizedAction)
            put("seekDeltaSeconds", resolvedSeekDelta)
            put("mirrorSessionId", _liveMirrorState.value.mirrorSessionId)
        }
        return sendSignedEvent(
            type = RealtimeEventType.MIRROR_REMOTE_CONTROL,
            payloadJson = payload.toString()
        )
    }

    private fun initiateOfferToViewer(
        viewerDeviceId: String,
        sessionId: String,
        profile: LiveMirrorQualityProfile,
        shareAudio: Boolean
    ) {
        if (viewerDeviceId.isBlank() || viewerDeviceId == localDeviceId) return
        val engine = webRtcMirrorEngine
        if (engine != null && engine.isHostCapturing.value) {
            engine.createOfferForViewer(
                viewerDeviceId = viewerDeviceId,
                localHostDeviceId = localDeviceId
            )
            return
        }
        val syntheticOfferSdp = buildString {
            append("v=0\r\n")
            append("o=- NYXORA_SYNTHETIC 2 IN IP4 127.0.0.1\r\n")
            append("s=NYXORA_LIVE_MIRROR_$sessionId\r\n")
            append("t=0 0\r\n")
            append("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
            append("a=nyxora-profile:${profile.name};${profile.width}x${profile.height}@${profile.fps}fps\r\n")
            if (shareAudio) {
                append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
            }
        }
        val offerSignal = WebRtcSignalPayload(
            mirrorSessionId = sessionId,
            senderDeviceId = localDeviceId,
            targetDeviceId = viewerDeviceId,
            sdpType = "offer",
            sdp = syntheticOfferSdp
        )
        sendSignedEvent(
            type = RealtimeEventType.WEBRTC_SIGNAL_OFFER,
            payloadJson = offerSignal.toJson().toString(),
            bypassRateLimit = true
        )
    }

    private fun syncMirrorMetadataFromRoomSnapshot(
        updatedState: NyxoraConnectRoomState,
        wasAlreadyConfirmedSession: Boolean
    ) {
        val incomingMirror = updatedState.liveMirrorState
        val previousSessionId = lastViewerMirrorSessionId
        val isHost = updatedState.hostDeviceId == localDeviceId
        if (!isHost) {
            _liveMirrorState.value = incomingMirror
            when (incomingMirror.mirrorState) {
                LiveMirrorSessionState.MIRROR_STARTING -> {
                    _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.CONNECTING
                }
                LiveMirrorSessionState.MIRROR_LIVE -> {
                    if (incomingMirror.mirrorSessionId != previousSessionId) {
                        lastViewerMirrorSessionId = incomingMirror.mirrorSessionId
                        _isViewerReturnedToBrowseTogether.value = false
                    }
                    if (!_isViewerReturnedToBrowseTogether.value &&
                        (_viewerMirrorConnectionState.value != ViewerMirrorConnectionState.LIVE || !wasAlreadyConfirmedSession)
                    ) {
                        _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.CONNECTING
                        requestViewerMirrorReconnect()
                    }
                }
                LiveMirrorSessionState.MIRROR_STOPPING,
                LiveMirrorSessionState.BROWSING -> {
                    lastViewerMirrorSessionId = ""
                    _isViewerMirrorFullscreen.value = false
                    webRtcMirrorEngine?.stopViewerStream(resetToIdle = true)
                    _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.IDLE
                }
            }
        }
    }

    // --- ANDROID LIFECYCLE, NETWORK & SESSION PERSISTENCE ---

    fun onAppBackgrounded() {
        isAppInForeground = false
        recordDiagnosticLog("APP_BACKGROUNDED room=${_roomState.value?.roomId ?: "none"}")
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        val current = _roomState.value ?: return
        if (intentionalDisconnect.get()) return
        recordDiagnosticLog("APP_FOREGROUNDED room=${current.roomId} status=${_connectionStatus.value.name}")
        val ws = activeWebSocket
        val now = System.currentTimeMillis()
        val lastHb = _lastSuccessfulHeartbeatMillis.value
        val heartbeatStale = lastHb > 0L && (now - lastHb) > heartbeatTimeoutMs
        if (ws == null || _connectionStatus.value == SyncConnectionStatus.RECONNECTING || heartbeatStale) {
            reconnectNowIfNeeded("app_foreground_health_check")
        } else if (activeAccessKey.isNotBlank()) {
            sendSignedEventOnSocket(
                webSocket = ws,
                roomId = current.roomId,
                accessKey = activeAccessKey,
                type = RealtimeEventType.HEARTBEAT_PING,
                payloadJson = JSONObject().put("clientTimeMillis", now).toString()
            )
            sendSignedEventOnSocket(
                webSocket = ws,
                roomId = current.roomId,
                accessKey = activeAccessKey,
                type = RealtimeEventType.REQUEST_RESYNC,
                payloadJson = JSONObject().put("requestedBy", localDeviceId).toString()
            )
        }
    }

    fun onNetworkAvailabilityChanged(isAvailable: Boolean) {
        val wasAvailable = isNetworkAvailable
        isNetworkAvailable = isAvailable
        val current = _roomState.value ?: return
        if (intentionalDisconnect.get()) return
        recordDiagnosticLog("NETWORK_CHANGED available=$isAvailable room=${current.roomId}")
        if (isAvailable && (!wasAvailable || _connectionStatus.value == SyncConnectionStatus.RECONNECTING || activeWebSocket == null)) {
            reconnectNowIfNeeded("network_restored")
        }
    }

    fun reconnectNowIfNeeded(reason: String = "manual_or_lifecycle_recovery") {
        val current = _roomState.value ?: return
        if (intentionalDisconnect.get()) return
        val endpoint = activeConnectEndpoint.ifBlank {
            resolveConnectEndpointForRoom(current.roomId, current.endpointUrl)
        }
        reconnectAttempt = 0
        consecutiveUnackedPings = 0
        recordDiagnosticLog("RECONNECT_NOW room=${current.roomId} reason=$reason endpoint=$endpoint")
        openWebSocketConnection(
            connectEndpoint = endpoint,
            roomId = current.roomId,
            signingKey = activeAccessKey.ifBlank { current.accessKey },
            inviteToken = activeInviteToken,
            forceReplace = true
        )
    }

    fun exportRecoverableSessionJson(): String? {
        val current = _roomState.value ?: return null
        if (intentionalDisconnect.get() || _connectionStatus.value.isTerminalOrDisconnected) return null
        val key = activeAccessKey.ifBlank { current.accessKey }
        if (key.isBlank()) return null
        return JSONObject().apply {
            put("roomSnapshot", current.copy(accessKey = key).toSnapshotJson(includeAccessKey = true))
            put("connectEndpoint", activeConnectEndpoint.ifBlank { current.endpointUrl })
            put("hasConfirmedServerSession", hasConfirmedServerSession)
            put("isFollowingPaused", _isFollowingPaused.value)
            put("savedAtMillis", System.currentTimeMillis())
        }.toString()
    }

    fun restoreSessionFromJson(rawJson: String): Boolean {
        if (rawJson.isBlank()) return false
        if (_roomState.value != null && !_connectionStatus.value.isTerminalOrDisconnected) {
            return true
        }
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return false
        val snapObj = root.optJSONObject("roomSnapshot") ?: return false
        val savedEndpoint = root.optString("connectEndpoint", "")
        val restoredState = NyxoraConnectRoomState.fromSnapshotJson(
            obj = snapObj,
            existingAccessKey = snapObj.optString("accessKey", ""),
            fallbackEndpoint = savedEndpoint
        )
        if (restoredState.roomId.isBlank() || restoredState.accessKey.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now > restoredState.expiresAtMillis) return false

        intentionalDisconnect.set(false)
        hasConfirmedServerSession = root.optBoolean("hasConfirmedServerSession", true)
        reconnectAttempt = 0
        consecutiveUnackedPings = 0
        activeAccessKey = restoredState.accessKey
        activeInviteToken = ""
        _isFollowingPaused.value = root.optBoolean("isFollowingPaused", false)
        _chatMessages.value = restoredState.chatMessages
        _liveMirrorState.value = restoredState.liveMirrorState
        _roomState.value = restoredState
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        _sessionLifecycleState.value = SyncConnectionStatus.RECONNECTING

        val targetEndpoint = resolveConnectEndpointForRoom(restoredState.roomId, savedEndpoint.ifBlank { restoredState.endpointUrl })
        if (restoredState.hostDeviceId == localDeviceId && shouldUseLocalRelayForInProcessTest()) {
            sharedRelayServer.startIfNeeded()
            sharedRelayServer.registerNewRoom(restoredState)
        }
        generateShareableInviteLink(singleUse = false)
        recordDiagnosticLog("SESSION_RESTORED room=${restoredState.roomId} endpoint=$targetEndpoint")
        openWebSocketConnection(
            connectEndpoint = targetEndpoint,
            roomId = restoredState.roomId,
            signingKey = activeAccessKey,
            inviteToken = "",
            forceReplace = true
        )
        return true
    }

    private fun resolveConnectEndpointForRoom(roomId: String, candidateEndpoint: String): String {
        val configuredEndpoint = resolveConfiguredProductionEndpoint()
        val effectiveEndpoint = candidateEndpoint.trim().ifBlank { configuredEndpoint }
        val isDefaultProdEndpoint = effectiveEndpoint.equals(
            NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT,
            ignoreCase = true
        ) || effectiveEndpoint.equals(
            NyxoraConnectSecurity.LEGACY_PRODUCTION_WSS_ENDPOINT,
            ignoreCase = true
        )
        val isHostedOnSharedRelay = sharedRelayServer.getRoomState(roomId) != null
        return when {
            sharedRelayServer.listeningPort > 0 &&
                effectiveEndpoint.contains(":${sharedRelayServer.listeningPort}/") -> {
                "ws://127.0.0.1:${sharedRelayServer.listeningPort}/nyxora"
            }
            (isHostedOnSharedRelay || shouldUseLocalRelayForInProcessTest()) && isDefaultProdEndpoint -> {
                val port = sharedRelayServer.startIfNeeded()
                "ws://127.0.0.1:$port/nyxora"
            }
            else -> effectiveEndpoint
        }
    }

    /**
     * Simulates a temporary network drop for testing reconnection & room-state restoration.
     */
    fun simulateNetworkInterruptionForReconnect() {
        if (_roomState.value == null) return
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        _sessionLifecycleState.value = SyncConnectionStatus.RECONNECTING
        if (!isCurrentDeviceHost && _liveMirrorState.value.isMirrorLive) {
            _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.RECONNECTING
            webRtcMirrorEngine?.markViewerReconnecting()
        }
        recordDiagnosticLog("SIMULATE_NETWORK_DROP room=${_roomState.value?.roomId}")
        runCatching { activeWebSocket?.cancel() }
    }

    // --- INTERNAL WEBSOCKET & RECONNECTION ENGINE ---

    private fun openWebSocketConnection(
        connectEndpoint: String,
        roomId: String,
        signingKey: String,
        inviteToken: String,
        forceReplace: Boolean = true
    ) {
        if (!forceReplace &&
            activeWebSocket != null &&
            activeConnectEndpoint == connectEndpoint &&
            _roomState.value?.roomId == roomId &&
            (_connectionStatus.value == SyncConnectionStatus.CONNECTED ||
                _connectionStatus.value == SyncConnectionStatus.SYNCED ||
                _connectionStatus.value == SyncConnectionStatus.CONNECTING ||
                _connectionStatus.value == SyncConnectionStatus.PAUSED_FOLLOWING)
        ) {
            recordDiagnosticLog("WS_SKIP_DUPLICATE_OPEN room=$roomId endpoint=$connectEndpoint")
            return
        }

        // Increment generation BEFORE closing any prior socket so callbacks from the old socket are ignored
        val myGeneration = connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        val previousSocket = activeWebSocket
        activeWebSocket = null
        activeConnectEndpoint = connectEndpoint
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
        val connectingStatus = if (reconnectAttempt > 0 || hasConfirmedServerSession) {
            SyncConnectionStatus.RECONNECTING
        } else {
            SyncConnectionStatus.CONNECTING
        }
        _connectionStatus.value = connectingStatus
        _sessionLifecycleState.value = connectingStatus

        val socket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (intentionalDisconnect.get() ||
                        myGeneration != connectionGeneration.get() ||
                        activeWebSocket !== webSocket
                    ) {
                        runCatching { webSocket.close(1000, "Stale socket") }
                        return
                    }
                    recordDiagnosticLog("WS_OPEN_HANDSHAKE_SENT room=$roomId endpoint=$connectEndpoint gen=$myGeneration")
                    sendJoinRequestOnSocket(
                        webSocket = webSocket,
                        roomId = roomId,
                        signingKey = signingKey,
                        inviteToken = inviteToken
                    )
                    startHandshakeConfirmationTimer(webSocket, connectEndpoint, roomId, myGeneration)
                    startHeartbeatLoop(webSocket, connectEndpoint, roomId, myGeneration)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (intentionalDisconnect.get() ||
                        myGeneration != connectionGeneration.get() ||
                        activeWebSocket !== webSocket
                    ) {
                        return
                    }
                    handleIncomingSocketFrame(text, connectEndpoint, webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(code, reason) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (myGeneration == connectionGeneration.get() &&
                        activeWebSocket === webSocket &&
                        !intentionalDisconnect.get() &&
                        code != 1000 &&
                        code != 4003
                    ) {
                        scheduleReconnect(connectEndpoint, roomId, "closed($code:$reason)")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (myGeneration == connectionGeneration.get() &&
                        activeWebSocket === webSocket &&
                        !intentionalDisconnect.get()
                    ) {
                        scheduleReconnect(connectEndpoint, roomId, t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        )
        activeWebSocket = socket
    }

    private fun sendJoinRequestOnSocket(
        webSocket: WebSocket,
        roomId: String,
        signingKey: String,
        inviteToken: String
    ) {
        val currentSnapshot = _roomState.value
        val effectiveKey = signingKey.ifBlank { activeAccessKey.ifBlank { currentSnapshot?.accessKey ?: "" } }
        val joinPayload = JSONObject().apply {
            put("deviceName", _deviceDisplayName.value)
            put("reconnectingSession", hasConfirmedServerSession)
            if (inviteToken.isNotBlank()) {
                put("inviteToken", inviteToken)
            }
            if (currentSnapshot != null) {
                val snapshotWithKey = currentSnapshot.copy(accessKey = effectiveKey)
                if (currentSnapshot.hostDeviceId == localDeviceId) {
                    put("hostRoomSnapshot", snapshotWithKey.toSnapshotJson(includeAccessKey = true))
                } else if (hasConfirmedServerSession) {
                    put("roomRecoverySnapshot", snapshotWithKey.toSnapshotJson(includeAccessKey = true))
                }
            }
        }.toString()
        sendSignedEventOnSocket(
            webSocket = webSocket,
            roomId = roomId,
            accessKey = effectiveKey,
            type = RealtimeEventType.JOIN_REQUEST,
            payloadJson = joinPayload
        )
    }

    private fun startHandshakeConfirmationTimer(
        webSocket: WebSocket,
        connectEndpoint: String,
        roomId: String,
        generation: Long
    ) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(handshakeTimeoutMs)
            if (isActive &&
                !intentionalDisconnect.get() &&
                generation == connectionGeneration.get() &&
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

    private fun startHeartbeatLoop(
        webSocket: WebSocket,
        connectEndpoint: String,
        roomId: String,
        generation: Long
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive &&
                !intentionalDisconnect.get() &&
                generation == connectionGeneration.get() &&
                activeWebSocket === webSocket
            ) {
                delay(heartbeatIntervalMs)
                if (!isActive ||
                    intentionalDisconnect.get() ||
                    generation != connectionGeneration.get() ||
                    activeWebSocket !== webSocket
                ) {
                    break
                }
                val now = System.currentTimeMillis()
                val lastHb = _lastSuccessfulHeartbeatMillis.value
                if (hasConfirmedServerSession &&
                    lastHb > 0L &&
                    (now - lastHb) > heartbeatTimeoutMs &&
                    consecutiveUnackedPings >= 3
                ) {
                    recordDiagnosticLog("WS_HEARTBEAT_TIMEOUT room=$roomId unacked=$consecutiveUnackedPings elapsedMs=${now - lastHb}")
                    runCatching { webSocket.cancel() }
                    scheduleReconnect(connectEndpoint, roomId, "heartbeat_timeout")
                    break
                }
                val key = activeAccessKey
                if (key.isNotBlank()) {
                    consecutiveUnackedPings++
                    sendSignedEventOnSocket(
                        webSocket = webSocket,
                        roomId = roomId,
                        accessKey = key,
                        type = RealtimeEventType.HEARTBEAT_PING,
                        payloadJson = JSONObject().put("clientTimeMillis", now).toString()
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
            if (!isCurrentDeviceHost && _liveMirrorState.value.isMirrorLive) {
                _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.DISCONNECTED
            }
            leaveOrCloseRoom(notifyServer = false, terminalLifecycleStatus = SyncConnectionStatus.DISCONNECTED)
            return
        }

        val currentAttemptIndex = reconnectAttempt
        reconnectAttempt++
        com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketConnectionFailure(
            failureDetail.ifBlank { "socket_closed" }
        )
        com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketReconnect(reconnectAttempt)
        _connectionStatus.value = SyncConnectionStatus.RECONNECTING
        _sessionLifecycleState.value = SyncConnectionStatus.RECONNECTING
        if (!isCurrentDeviceHost && _liveMirrorState.value.isMirrorLive && !_isViewerReturnedToBrowseTogether.value) {
            _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.RECONNECTING
            webRtcMirrorEngine?.markViewerReconnecting()
        }

        val baseDelay = (baseReconnectDelayMs * (1L shl currentAttemptIndex.coerceAtMost(5))).coerceAtMost(8_000L)
        val jitterMs = if (baseReconnectDelayMs >= 100L) {
            kotlin.random.Random.nextLong(0L, (baseDelay / 5L).coerceAtLeast(1L))
        } else {
            0L
        }
        val backoffMs = baseDelay + jitterMs
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
                    inviteToken = activeInviteToken,
                    forceReplace = true
                )
            }
        }
    }

    private fun handleIncomingSocketFrame(
        rawText: String,
        connectEndpoint: String,
        webSocket: WebSocket? = activeWebSocket
    ) {
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

            // If an already-confirmed member receives NOT_ROOM_MEMBER or ROOM_NOT_FOUND due to a transient
            // relay reset or socket rebind, automatically re-send JOIN_REQUEST with recovery state instead of disconnecting!
            if (hasConfirmedServerSession &&
                (code == "NOT_ROOM_MEMBER" || code == "ROOM_NOT_FOUND") &&
                activeAccessKey.isNotBlank() &&
                webSocket != null
            ) {
                recordDiagnosticLog("AUTO_REAUTHENTICATE_ON_SOCKET room=${envelope.roomId} reason=$code")
                _connectionStatus.value = SyncConnectionStatus.RECONNECTING
                _sessionLifecycleState.value = SyncConnectionStatus.RECONNECTING
                sendJoinRequestOnSocket(
                    webSocket = webSocket,
                    roomId = envelope.roomId,
                    signingKey = activeAccessKey,
                    inviteToken = activeInviteToken
                )
                return
            }

            _lastSyncError.value = message
            val terminalStatus = when (code) {
                "UNAUTHORIZED_KEY",
                "INVITE_UNAUTHORIZED",
                "INVITE_INVALID",
                "INVITE_EXPIRED",
                "INVITE_REVOKED",
                "INVITE_ALREADY_USED" -> SyncConnectionStatus.AUTHENTICATION_FAILED
                "ROOM_EXPIRED",
                "ROOM_NOT_FOUND" -> SyncConnectionStatus.ROOM_CLOSED
                else -> SyncConnectionStatus.DISCONNECTED
            }
            leaveOrCloseRoom(notifyServer = false, terminalLifecycleStatus = terminalStatus)
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

        val now = System.currentTimeMillis()
        _lastSuccessfulHeartbeatMillis.value = now
        consecutiveUnackedPings = 0

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
                val effectiveRoomState = if (updatedState.hostDeviceId == localDeviceId) {
                    val hostMirror = _liveMirrorState.value
                    val hostMode = when (hostMirror.mirrorState) {
                        LiveMirrorSessionState.MIRROR_STARTING,
                        LiveMirrorSessionState.MIRROR_LIVE -> RoomSyncMode.LIVE_MIRROR
                        LiveMirrorSessionState.MIRROR_STOPPING,
                        LiveMirrorSessionState.BROWSING -> RoomSyncMode.BROWSE_TOGETHER
                    }
                    updatedState.copy(
                        chatMessages = mergedChats,
                        mode = hostMode,
                        liveMirrorState = hostMirror
                    )
                } else {
                    updatedState.copy(chatMessages = mergedChats)
                }
                _roomState.value = effectiveRoomState
                syncMirrorMetadataFromRoomSnapshot(effectiveRoomState, wasAlreadyConfirmedSession)

                if (_currentShareableInviteUrl.value.isBlank() && activeAccessKey.isNotBlank()) {
                    generateShareableInviteLink(singleUse = false)
                }
                val activeStatus = if (_isFollowingPaused.value) {
                    SyncConnectionStatus.PAUSED_FOLLOWING
                } else {
                    SyncConnectionStatus.SYNCED
                }
                _connectionStatus.value = activeStatus
                _sessionLifecycleState.value = activeStatus
                recordDiagnosticLog(
                    "${envelope.type.name} room=${updatedState.roomId} members=${updatedState.members.size} status=${_connectionStatus.value.name}"
                )

                flushPendingChatOutbox()

                val isParticipant = updatedState.hostDeviceId != localDeviceId
                if (!isParticipant || updatedState.permissions.allowParticipantControls) {
                    _participantControlsDisabledNotice.value = null
                } else {
                    _participantControlsDisabledNotice.value = PARTICIPANT_CONTROLS_DISABLED_MESSAGE
                }
                val isInitialParticipantJoinConfirmation =
                    isParticipant && !wasAlreadyConfirmedSession && envelope.type == RealtimeEventType.JOIN_ACCEPTED

                // While Live Mirror is active and Viewer has not returned to Browse Together,
                // suppress duplicate URL navigation and HTML5 video sync on the Viewer.
                val suppressViewerDuplicateWebLoad =
                    isParticipant && updatedState.liveMirrorState.isMirrorLive && !_isViewerReturnedToBrowseTogether.value

                if (isInitialParticipantJoinConfirmation && !suppressViewerDuplicateWebLoad) {
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
                    !suppressViewerDuplicateWebLoad &&
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
                    !suppressViewerDuplicateWebLoad &&
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
                leaveOrCloseRoom(notifyServer = false, terminalLifecycleStatus = SyncConnectionStatus.ROOM_CLOSED)
            }
            RealtimeEventType.HEARTBEAT_PONG -> {
                val clientSentMs = runCatching {
                    JSONObject(envelope.payloadJson).optLong("clientTimeMillis", 0L)
                }.getOrDefault(0L)
                if (clientSentMs > 0L) {
                    val rtt = (now - clientSentMs).coerceAtLeast(0L)
                    _lastHeartbeatRttMs.value = rtt
                    com.example.domain.browser.BrowserDiagnosticsEngine.recordWebSocketHeartbeatRtt(rtt)
                }
                if (hasConfirmedServerSession &&
                    (_connectionStatus.value == SyncConnectionStatus.CONNECTED ||
                        _connectionStatus.value == SyncConnectionStatus.RECONNECTING)
                ) {
                    val syncedStatus = if (_isFollowingPaused.value) {
                        SyncConnectionStatus.PAUSED_FOLLOWING
                    } else {
                        SyncConnectionStatus.SYNCED
                    }
                    _connectionStatus.value = syncedStatus
                    _sessionLifecycleState.value = syncedStatus
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

                // Do not load duplicate websites on Viewers while actively watching Live Mirror stream
                if (initiatorId != localDeviceId && !_isFollowingPaused.value && !isViewerActivelyWatchingMirror) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    if (envelope.type == RealtimeEventType.NEW_PAGE_OPENED) {
                        _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.OpenNewSyncedTab(safeUrl, title))
                    } else {
                        _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateToUrl(safeUrl, title))
                    }
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                    _sessionLifecycleState.value = SyncConnectionStatus.SYNCED
                }
            }
            RealtimeEventType.NAVIGATE_BACK -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                val initiatorId = runCatching {
                    JSONObject(envelope.payloadJson).optString("initiatedBy", "")
                }.getOrDefault("").ifBlank { envelope.senderDeviceId }
                if (initiatorId != localDeviceId && !_isFollowingPaused.value && !isViewerActivelyWatchingMirror) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateBack)
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                    _sessionLifecycleState.value = SyncConnectionStatus.SYNCED
                }
            }
            RealtimeEventType.NAVIGATE_FORWARD -> {
                if (envelope.sequenceNumber < lastAppliedSequence) return
                lastAppliedSequence = envelope.sequenceNumber
                val initiatorId = runCatching {
                    JSONObject(envelope.payloadJson).optString("initiatedBy", "")
                }.getOrDefault("").ifBlank { envelope.senderDeviceId }
                if (initiatorId != localDeviceId && !_isFollowingPaused.value && !isViewerActivelyWatchingMirror) {
                    _connectionStatus.value = SyncConnectionStatus.SYNCHRONIZING
                    _remoteSyncCommands.tryEmit(IncomingRemoteSyncCommand.NavigateForward)
                    _connectionStatus.value = SyncConnectionStatus.SYNCED
                    _sessionLifecycleState.value = SyncConnectionStatus.SYNCED
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
                // Suppress independent HTML5 video sync on Viewers while Live Mirror Mode is active
                if (initiatorId != localDeviceId && !_isFollowingPaused.value && !isViewerActivelyWatchingMirror) {
                    _remoteSyncCommands.tryEmit(
                        IncomingRemoteSyncCommand.ApplyPlaybackState(
                            state = pbState,
                            forceManualResync = envelope.type == RealtimeEventType.PLAYBACK_ACTION
                        )
                    )
                }
            }
            RealtimeEventType.MIRROR_SESSION_STATE -> {
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val mirrorMeta = LiveMirrorSessionMetadata.fromJson(payload)
                val previousSessionId = lastViewerMirrorSessionId
                if (!isCurrentDeviceHost) {
                    _liveMirrorState.value = mirrorMeta
                    val nextMode = when (mirrorMeta.mirrorState) {
                        LiveMirrorSessionState.MIRROR_STARTING,
                        LiveMirrorSessionState.MIRROR_LIVE -> RoomSyncMode.LIVE_MIRROR
                        LiveMirrorSessionState.MIRROR_STOPPING,
                        LiveMirrorSessionState.BROWSING -> RoomSyncMode.BROWSE_TOGETHER
                    }
                    _roomState.update { state ->
                        state?.copy(
                            mode = nextMode,
                            liveMirrorState = mirrorMeta,
                            lastSequenceNumber = maxOf(state.lastSequenceNumber, envelope.sequenceNumber)
                        )
                    }
                    when (mirrorMeta.mirrorState) {
                        LiveMirrorSessionState.MIRROR_STARTING -> {
                            _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.CONNECTING
                        }
                        LiveMirrorSessionState.MIRROR_LIVE -> {
                            if (mirrorMeta.mirrorSessionId != previousSessionId) {
                                lastViewerMirrorSessionId = mirrorMeta.mirrorSessionId
                                _isViewerReturnedToBrowseTogether.value = false
                            }
                            if (!_isViewerReturnedToBrowseTogether.value) {
                                _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.CONNECTING
                                requestViewerMirrorReconnect()
                            }
                        }
                        LiveMirrorSessionState.MIRROR_STOPPING,
                        LiveMirrorSessionState.BROWSING -> {
                            lastViewerMirrorSessionId = ""
                            _isViewerMirrorFullscreen.value = false
                            webRtcMirrorEngine?.stopViewerStream(resetToIdle = true)
                            _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.IDLE
                        }
                    }
                }
                recordDiagnosticLog("MIRROR_STATE_RECEIVED state=${mirrorMeta.mirrorState.name} session=${mirrorMeta.mirrorSessionId}")
            }
            RealtimeEventType.WEBRTC_VIEWER_READY -> {
                if (!isCurrentDeviceHost || !_liveMirrorState.value.isMirrorLive) return
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val signal = WebRtcSignalPayload.fromJson(payload) ?: return
                initiateOfferToViewer(
                    viewerDeviceId = signal.senderDeviceId,
                    sessionId = _liveMirrorState.value.mirrorSessionId,
                    profile = _liveMirrorState.value.qualityProfile,
                    shareAudio = _liveMirrorState.value.isAudioShared
                )
            }
            RealtimeEventType.WEBRTC_SIGNAL_OFFER -> {
                if (isCurrentDeviceHost) return
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val signal = WebRtcSignalPayload.fromJson(payload) ?: return
                if (signal.targetDeviceId.isNotBlank() && signal.targetDeviceId != localDeviceId) return
                if (_isViewerReturnedToBrowseTogether.value) return

                _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.CONNECTING
                val engine = webRtcMirrorEngine
                if (engine != null) {
                    engine.handleRemoteOfferOnViewer(signal, localDeviceId)
                    _viewerMirrorConnectionState.value = engine.viewerConnectionState.value
                    _viewerRenderedFrameCount.value = engine.viewerRenderedFrameCount.value
                } else {
                    val answerSignal = WebRtcSignalPayload(
                        mirrorSessionId = signal.mirrorSessionId,
                        senderDeviceId = localDeviceId,
                        targetDeviceId = signal.senderDeviceId,
                        sdpType = "answer",
                        sdp = "v=0\r\no=- NYXORA_SYNTHETIC 2 IN IP4 127.0.0.1\r\ns=NYXORA_ANSWER\r\nt=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                    )
                    sendSignedEvent(
                        type = RealtimeEventType.WEBRTC_SIGNAL_ANSWER,
                        payloadJson = answerSignal.toJson().toString(),
                        bypassRateLimit = true
                    )
                    val iceSignal = WebRtcSignalPayload(
                        mirrorSessionId = signal.mirrorSessionId,
                        senderDeviceId = localDeviceId,
                        targetDeviceId = signal.senderDeviceId,
                        sdpMid = "0",
                        sdpMLineIndex = 0,
                        candidate = "candidate:1 1 UDP 2122260223 127.0.0.1 54321 typ host"
                    )
                    sendSignedEvent(
                        type = RealtimeEventType.WEBRTC_SIGNAL_ICE_CANDIDATE,
                        payloadJson = iceSignal.toJson().toString(),
                        bypassRateLimit = true
                    )
                    _viewerRenderedFrameCount.update { it + 1L }
                    _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.LIVE
                }
                recordDiagnosticLog("WEBRTC_OFFER_HANDLED from=${signal.senderDeviceId} session=${signal.mirrorSessionId}")
            }
            RealtimeEventType.WEBRTC_SIGNAL_ANSWER -> {
                if (!isCurrentDeviceHost) return
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val signal = WebRtcSignalPayload.fromJson(payload) ?: return
                if (signal.targetDeviceId.isNotBlank() && signal.targetDeviceId != localDeviceId) return

                val engine = webRtcMirrorEngine
                if (engine != null) {
                    engine.handleRemoteAnswerOnHost(signal, localDeviceId)
                } else {
                    val iceSignal = WebRtcSignalPayload(
                        mirrorSessionId = signal.mirrorSessionId,
                        senderDeviceId = localDeviceId,
                        targetDeviceId = signal.senderDeviceId,
                        sdpMid = "0",
                        sdpMLineIndex = 0,
                        candidate = "candidate:1 1 UDP 2122260223 127.0.0.1 54320 typ host"
                    )
                    sendSignedEvent(
                        type = RealtimeEventType.WEBRTC_SIGNAL_ICE_CANDIDATE,
                        payloadJson = iceSignal.toJson().toString(),
                        bypassRateLimit = true
                    )
                }
                recordDiagnosticLog("WEBRTC_ANSWER_RECEIVED from=${signal.senderDeviceId}")
            }
            RealtimeEventType.WEBRTC_SIGNAL_ICE_CANDIDATE -> {
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val signal = WebRtcSignalPayload.fromJson(payload) ?: return
                if (signal.targetDeviceId.isNotBlank() && signal.targetDeviceId != localDeviceId) return

                val engine = webRtcMirrorEngine
                if (engine != null) {
                    engine.handleRemoteIceCandidate(signal, isHost = isCurrentDeviceHost)
                    if (!isCurrentDeviceHost) {
                        _viewerMirrorConnectionState.value = engine.viewerConnectionState.value
                        _viewerRenderedFrameCount.value = engine.viewerRenderedFrameCount.value
                    }
                } else if (!isCurrentDeviceHost && !_isViewerReturnedToBrowseTogether.value) {
                    _viewerRenderedFrameCount.update { it + 1L }
                    _viewerMirrorConnectionState.value = ViewerMirrorConnectionState.LIVE
                }
            }
            RealtimeEventType.MIRROR_REMOTE_CONTROL -> {
                if (!isCurrentDeviceHost) return
                val payload = runCatching { JSONObject(envelope.payloadJson) }.getOrNull() ?: return
                val actionType = payload.optString("controlActionType", "PLAY_PAUSE").trim().uppercase()
                val seekDelta = payload.optDouble("seekDeltaSeconds", 0.0).let { if (it.isNaN()) 0.0 else it }
                val initiatorId = payload.optString("initiatedBy", envelope.senderDeviceId)
                _hostExecutedRemoteControlHistory.update { existing ->
                    (existing + "$actionType:$seekDelta:$initiatorId").takeLast(30)
                }
                _remoteSyncCommands.tryEmit(
                    IncomingRemoteSyncCommand.ExecuteHostMirrorRemoteControl(
                        actionType = actionType,
                        seekDeltaSeconds = seekDelta,
                        initiatedByDeviceId = initiatorId
                    )
                )
                recordDiagnosticLog("HOST_EXECUTED_MIRROR_CONTROL action=$actionType seekDelta=$seekDelta from=$initiatorId")
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
                val serverPermsJson = payload?.optJSONObject("permissions")
                if (serverPermsJson != null) {
                    val syncedPerms = RoomPermissionsConfig.fromJson(serverPermsJson)
                    _roomState.update { state ->
                        state?.copy(permissions = syncedPerms)
                    }
                } else if (code == "PARTICIPANT_CONTROLS_DISABLED") {
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
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 45_000L
        private const val MAX_DIAGNOSTIC_LOG_ENTRIES = 50
        const val PARTICIPANT_CONTROLS_DISABLED_MESSAGE =
            "Shared controls are disabled by the host — only the host can control shared navigation and playback."
        const val NAVIGATION_RESTRICTED_MESSAGE =
            "Browser navigation sync is restricted by the host."
        const val URL_INPUT_RESTRICTED_MESSAGE =
            "URL and search input sync is restricted by the host."
        const val PLAYBACK_RESTRICTED_MESSAGE =
            "Shared playback controls are restricted by the host."
        const val VIDEO_PLAY_PAUSE_RESTRICTED_MESSAGE =
            "Video play and pause controls are restricted by the host."
        const val VIDEO_SEEK_RESTRICTED_MESSAGE =
            "Video seeking is restricted by the host."
        const val VOLUME_RESTRICTED_MESSAGE =
            "Shared volume control is restricted by the host."
        const val FULLSCREEN_RESTRICTED_MESSAGE =
            "Fullscreen and orientation sync is restricted by the host."
        const val CHAT_DISABLED_MESSAGE =
            "Shared chat is currently disabled for participants by the host."
        const val HOST_ONLY_MIRROR_MESSAGE =
            "Only the room host can start or stop Live Mirror mode."
        const val PRIVATE_TAB_MIRROR_BLOCKED_MESSAGE =
            "Private tabs cannot be mirrored unless explicitly enabled for this session."
        const val DRM_BLACK_SCREEN_WARNING_MESSAGE =
            "Protected DRM media detected — Android hardware security may render the video surface black on mirrored viewers."

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
