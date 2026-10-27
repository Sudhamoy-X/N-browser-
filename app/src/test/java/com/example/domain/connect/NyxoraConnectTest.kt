package com.example.domain.connect

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NyxoraConnectTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var relayServer: NyxoraWebSocketRelayServer

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        relayServer = NyxoraWebSocketRelayServer()
        relayServer.startIfNeeded()
    }

    @After
    fun tearDown() {
        relayServer.stopServer()
        testScope.cancel()
    }

    private fun awaitCondition(timeoutMs: Long = 5000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(35L)
        }
        return predicate()
    }

    @Test
    fun `generates secure Crockford Base32 Room IDs access keys and verifies HMAC-SHA256 signatures`() {
        val roomId = NyxoraConnectSecurity.generateSecureRoomId()
        val accessKey = NyxoraConnectSecurity.generateRoomAccessKey()

        assertTrue("Expected valid NYX-XXXX-XXXX Room ID, got $roomId", NyxoraConnectSecurity.validateRoomIdFormat(roomId))
        assertFalse(NyxoraConnectSecurity.validateRoomIdFormat("INVALID-ID"))
        assertFalse(NyxoraConnectSecurity.validateRoomIdFormat("NYX-0000-1111")) // 0 and 1 excluded in Crockford Base32
        assertEquals(roomId, NyxoraConnectSecurity.normalizeRoomId(roomId.removePrefix("NYX-").replace("-", "")))

        val sig = NyxoraConnectSecurity.computeEventHmac(
            accessKey = accessKey,
            roomId = roomId,
            eventId = "evt-101",
            type = RealtimeEventType.NAVIGATE_URL.name,
            senderDeviceId = "dev-host-1",
            authorityEpoch = 1L,
            sequenceNumber = 5L,
            timestampMillis = 1710000000000L,
            payloadJson = """{"url":"https://kotlinlang.org","title":"Kotlin"}"""
        )

        val validEnvelope = RealtimeEventEnvelope(
            eventId = "evt-101",
            roomId = roomId,
            type = RealtimeEventType.NAVIGATE_URL,
            senderDeviceId = "dev-host-1",
            senderRole = RoomRole.HOST,
            authorityEpoch = 1L,
            sequenceNumber = 5L,
            timestampMillis = 1710000000000L,
            payloadJson = """{"url":"https://kotlinlang.org","title":"Kotlin"}""",
            hmacSignature = sig
        )
        assertTrue(NyxoraConnectSecurity.verifyEventHmac(accessKey, validEnvelope))
        assertFalse(NyxoraConnectSecurity.verifyEventHmac("WRONG-ACCESS-KEY", validEnvelope))

        val tamperedEnvelope = validEnvelope.copy(payloadJson = """{"url":"https://evil.example","title":"Tampered"}""")
        assertFalse(NyxoraConnectSecurity.verifyEventHmac(accessKey, tamperedEnvelope))
    }

    @Test
    fun `sanitizes URLs by blocking private tabs stripping credentials and removing auth session query parameters`() {
        // 1. Private tab or Private Mode is strictly blocked from synchronization
        assertNull(
            NyxoraConnectSecurity.sanitizeUrlForSync(
                rawUrl = "https://developer.android.com",
                isPrivateTab = true,
                isPrivateMode = false
            )
        )
        assertNull(
            NyxoraConnectSecurity.sanitizeUrlForSync(
                rawUrl = "https://developer.android.com",
                isPrivateTab = false,
                isPrivateMode = true
            )
        )

        // 2. Non-HTTP/HTTPS schemes (javascript:, data:, file:) are blocked
        assertNull(NyxoraConnectSecurity.sanitizeUrlForSync("javascript:alert(1)", false, false))
        assertNull(NyxoraConnectSecurity.sanitizeUrlForSync("file:///sdcard/secret.html", false, false))

        // 3. Embedded user:password@ credentials and sensitive tokens are stripped while preserving safe params
        val rawWithSecrets =
            "https://admin:supersecret@video.example.com:8443/watch?v=abc123&access_token=eyJhbGci&session_id=s999&t=42s#access_token=fragsecret"
        val sanitized = NyxoraConnectSecurity.sanitizeUrlForSync(rawWithSecrets, isPrivateTab = false, isPrivateMode = false)
        assertNotNull(sanitized)
        assertEquals("https://video.example.com:8443/watch?v=abc123&t=42s", sanitized)
    }

    @Test
    fun `builds and parses nyxora connect deep-link invitations and enforces deduplication and rate limiting`() {
        val roomId = NyxoraConnectSecurity.generateSecureRoomId()
        val accessKey = NyxoraConnectSecurity.generateRoomAccessKey()
        val endpoint = "ws://192.168.1.50:41234/nyxora"
        val link = NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = roomId,
            accessKey = accessKey,
            endpointUrl = endpoint,
            roomName = "Cinema Room"
        )

        val parsed = NyxoraConnectSecurity.parseRoomInviteInput(link)
        assertNotNull(parsed)
        assertEquals(roomId, parsed?.roomId)
        assertEquals(accessKey, parsed?.accessKey)
        assertEquals(endpoint, parsed?.endpointUrl)
        assertEquals("Cinema Room", parsed?.roomName)

        // BoundedEventDeduplicator rejects duplicate event IDs
        val deduplicator = BoundedEventDeduplicator(maxCapacity = 8)
        assertTrue(deduplicator.markIfNew("evt-1"))
        assertFalse(deduplicator.markIfNew("evt-1"))

        // SlidingWindowRateLimiter blocks bursts above threshold
        val limiter = SlidingWindowRateLimiter(maxEventsPerWindow = 3, windowMillis = 5000L)
        assertTrue(limiter.tryAcquire("dev-1", 1000L))
        assertTrue(limiter.tryAcquire("dev-1", 1100L))
        assertTrue(limiter.tryAcquire("dev-1", 1200L))
        assertFalse(limiter.tryAcquire("dev-1", 1300L))
        assertTrue(limiter.tryAcquire("dev-1", 6500L))
    }

    @Test
    fun `Watch Together drift calculator distinguishes in-sync gentle rate nudge and direct seek`() {
        val now = 1_710_000_000_000L
        val hostPlayingState = WatchTogetherPlaybackState(
            pageUrl = "https://example.com/movie",
            isPlaying = true,
            positionSeconds = 120.0,
            durationSeconds = 600.0,
            playbackRate = 1.0,
            updatedByDeviceId = "host-1",
            updatedAtMillis = now,
            sequenceNumber = 1L
        )

        // 1. Small drift (< 0.85s) -> NONE_IN_SYNC (avoids disruptive constant seeking)
        val inSync = WatchTogetherMediaBridge.calculatePlaybackAdjustment(
            hostState = hostPlayingState,
            localPositionSeconds = 119.5,
            localIsPlaying = true,
            nowMillis = now,
            lastSeekAtMillis = now - 1000L
        )
        assertEquals(PlaybackCorrectionDecision.NONE_IN_SYNC, inSync.decision)
        assertEquals(SyncConnectionStatus.SYNCED, inSync.nextSyncStatus)

        // 2. Moderate drift (0.85s..2.2s) within seek cooldown -> GENTLE_RATE_ADJUST (1.05x catch-up)
        val gentleAdjust = WatchTogetherMediaBridge.calculatePlaybackAdjustment(
            hostState = hostPlayingState,
            localPositionSeconds = 118.9,
            localIsPlaying = true,
            nowMillis = now,
            lastSeekAtMillis = now - 1000L
        )
        assertEquals(PlaybackCorrectionDecision.GENTLE_RATE_ADJUST, gentleAdjust.decision)
        assertEquals(1.05, gentleAdjust.targetPlaybackRate, 0.01)

        // 3. Large drift (>= 2.2s) -> DIRECT_SEEK
        val hardSeek = WatchTogetherMediaBridge.calculatePlaybackAdjustment(
            hostState = hostPlayingState,
            localPositionSeconds = 112.0,
            localIsPlaying = true,
            nowMillis = now,
            lastSeekAtMillis = now - 500L
        )
        assertEquals(PlaybackCorrectionDecision.DIRECT_SEEK, hardSeek.decision)
        assertEquals(120.0, hardSeek.targetPositionSeconds, 0.01)

        // 4. Probe parser accurately identifies DRM_PROTECTED_UNSUPPORTED and COMPATIBLE_HTML5
        val drmProbe = WatchTogetherMediaBridge.parseProbeResult(
            """{"status":"DRM_PROTECTED_UNSUPPORTED","currentTime":0,"duration":0,"paused":true,"rate":1.0}"""
        )
        assertEquals(VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED, drmProbe.compatibility)

        val html5Probe = WatchTogetherMediaBridge.parseProbeResult(
            """{"status":"COMPATIBLE_HTML5","currentTime":45.5,"duration":180.0,"paused":false,"rate":1.25}"""
        )
        assertEquals(VideoCompatibilityState.COMPATIBLE_HTML5, html5Probe.compatibility)
        assertEquals(45.5, html5Probe.currentTimeSec, 0.01)
        assertFalse(html5Probe.isPaused)
    }

    @Test
    fun `real multi-instance WebSocket session syncs Browse Together Watch Together and host controls`() {
        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "device-host-pixel",
            initialDeviceName = "Host Pixel 8",
            sharedRelayServer = relayServer
        )
        val participantManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "device-participant-tab",
            initialDeviceName = "Participant Galaxy Tab",
            sharedRelayServer = relayServer
        )

        val participantCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val collectJob = testScope.launch {
            participantManager.remoteSyncCommands.collect { cmd ->
                participantCommands.add(cmd)
            }
        }

        try {
            // 1. Host creates a room
            val createdRoom = hostManager.createRoom(
                roomName = "Design Review Sync",
                mode = RoomSyncMode.BROWSE_TOGETHER,
                initialUrl = "https://developer.android.com",
                initialTitle = "Android Developers"
            )
            assertTrue(
                "Host failed to connect to WebSocket relay",
                awaitCondition { hostManager.connectionStatus.value == SyncConnectionStatus.SYNCED }
            )

            // 2. Participant joins using the invitation link
            val inviteLink = NyxoraConnectSecurity.buildRoomInviteLink(
                roomId = createdRoom.roomId,
                accessKey = createdRoom.accessKey,
                endpointUrl = createdRoom.endpointUrl,
                roomName = createdRoom.roomName
            )
            assertTrue(participantManager.joinRoom(inviteLink))
            assertTrue(
                "Participant failed to join room over WebSocket",
                awaitCondition {
                    participantManager.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        (hostManager.roomState.value?.members?.size ?: 0) == 2
                }
            )

            // Initial room snapshot delivers the Host's initial URL to Participant
            assertTrue(
                awaitCondition {
                    participantCommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://developer.android.com/"
                    }
                }
            )

            // 3. Host navigates to a new URL in Browse Together mode -> Participant receives it in realtime
            participantCommands.clear()
            assertTrue(
                hostManager.publishUrlNavigation(
                    url = "https://kotlinlang.org/docs/coroutines-overview.html?utm_source=test&access_token=secret123",
                    title = "Coroutines Guide",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                "Participant did not receive sanitized NavigateToUrl command",
                awaitCondition {
                    participantCommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://kotlinlang.org/docs/coroutines-overview.html?utm_source=test" &&
                            it.title == "Coroutines Guide"
                    }
                }
            )

            // 4. Host triggers Back navigation -> Participant receives NavigateBack
            assertTrue(hostManager.publishBackForwardAction(isBack = true, isPrivateTab = false, isPrivateMode = false))
            assertTrue(
                "Participant did not receive NavigateBack command",
                awaitCondition { participantCommands.any { it is IncomingRemoteSyncCommand.NavigateBack } }
            )

            // 5. Switch to Watch Together mode and synchronize Play/Pause/Seek state
            hostManager.updateRoomConfiguration(mode = RoomSyncMode.WATCH_TOGETHER)
            assertTrue(
                awaitCondition { participantManager.roomState.value?.mode == RoomSyncMode.WATCH_TOGETHER }
            )

            participantCommands.clear()
            assertTrue(
                hostManager.publishPlaybackEvent(
                    pageUrl = "https://example.org/video-demo",
                    isPlaying = true,
                    positionSeconds = 74.5,
                    durationSeconds = 300.0,
                    playbackRate = 1.0
                )
            )
            assertTrue(
                "Participant did not receive Watch Together ApplyPlaybackState",
                awaitCondition {
                    participantCommands.any {
                        it is IncomingRemoteSyncCommand.ApplyPlaybackState &&
                            it.state.isPlaying &&
                            kotlin.math.abs(it.state.positionSeconds - 74.5) < 0.01
                    }
                }
            )

            // 6. Simulate temporary network drop on Participant and verify automatic reconnection & state restoration
            participantManager.simulateNetworkInterruptionForReconnect()
            assertTrue(
                "Participant did not automatically reconnect and restore SYNCED status",
                awaitCondition(timeoutMs = 6000L) {
                    participantManager.connectionStatus.value == SyncConnectionStatus.SYNCED
                }
            )
            assertEquals(RoomSyncMode.WATCH_TOGETHER, participantManager.roomState.value?.mode)

            // 7. Transfer Host role to Participant, then close room
            hostManager.transferHostRole("device-participant-tab")
            assertTrue(
                "Host transfer did not propagate to both devices",
                awaitCondition {
                    participantManager.roomState.value?.hostDeviceId == "device-participant-tab" &&
                        hostManager.roomState.value?.hostDeviceId == "device-participant-tab"
                }
            )
        } finally {
            collectJob.cancel()
            participantManager.leaveOrCloseRoom()
            hostManager.leaveOrCloseRoom()
        }
    }

    @Test
    fun `rejects invalid Room ID wrong access key expired room and participant overflow`() {
        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "host-sec-1",
            initialDeviceName = "Host Device",
            sharedRelayServer = relayServer
        )
        val intruderManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "intruder-1",
            initialDeviceName = "Intruder Device",
            sharedRelayServer = relayServer
        )
        val guest1 = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "guest-1",
            initialDeviceName = "Guest 1",
            sharedRelayServer = relayServer
        )
        val guest2 = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "guest-2",
            initialDeviceName = "Guest 2",
            sharedRelayServer = relayServer
        )

        try {
            // Create room with maxParticipants = 2
            val room = hostManager.createRoom(
                roomName = "Restricted Room",
                mode = RoomSyncMode.BROWSE_TOGETHER,
                permissions = RoomPermissionsConfig(maxParticipants = 2)
            )
            assertTrue(awaitCondition { hostManager.connectionStatus.value == SyncConnectionStatus.SYNCED })

            // 1. Invalid Room ID format rejected immediately on client
            assertFalse(intruderManager.joinRoom("BAD-ROOM-ID", explicitAccessKey = room.accessKey))
            assertNotNull(intruderManager.lastSyncError.value)

            // 2. Wrong Access Key rejected by WebSocket server with UNAUTHORIZED
            assertTrue(
                intruderManager.joinRoom(
                    rawRoomOrInvite = room.roomId,
                    explicitAccessKey = "ZZZZ-ZZZZ-ZZZZ-ZZZZ",
                    explicitEndpointUrl = room.endpointUrl
                )
            )
            assertTrue(
                "Intruder with wrong access key should be disconnected",
                awaitCondition {
                    intruderManager.connectionStatus.value == SyncConnectionStatus.DISCONNECTED &&
                        intruderManager.lastSyncError.value?.contains("Invalid Room Access Key", ignoreCase = true) == true
                }
            )

            // 3. Valid Guest 1 joins (fills room to 2/2)
            assertTrue(
                guest1.joinRoom(
                    rawRoomOrInvite = room.roomId,
                    explicitAccessKey = room.accessKey,
                    explicitEndpointUrl = room.endpointUrl
                )
            )
            assertTrue(awaitCondition { guest1.connectionStatus.value == SyncConnectionStatus.SYNCED })

            // 4. Guest 2 attempts to join when room is at capacity (2/2) -> rejected with ROOM_FULL
            assertTrue(
                guest2.joinRoom(
                    rawRoomOrInvite = room.roomId,
                    explicitAccessKey = room.accessKey,
                    explicitEndpointUrl = room.endpointUrl
                )
            )
            assertTrue(
                "Guest 2 should be rejected because room is full (2/2)",
                awaitCondition {
                    guest2.connectionStatus.value == SyncConnectionStatus.DISCONNECTED &&
                        guest2.lastSyncError.value?.contains("limit", ignoreCase = true) == true
                }
            )

            // 5. Expire room on server and verify join attempt is rejected
            relayServer.expireRoomForTesting(room.roomId)
            assertTrue(
                guest2.joinRoom(
                    rawRoomOrInvite = room.roomId,
                    explicitAccessKey = room.accessKey,
                    explicitEndpointUrl = room.endpointUrl
                )
            )
            assertTrue(
                "Expired room should reject join attempts",
                awaitCondition {
                    guest2.connectionStatus.value == SyncConnectionStatus.DISCONNECTED &&
                        guest2.lastSyncError.value?.contains("expired", ignoreCase = true) == true
                }
            )
        } finally {
            guest2.leaveOrCloseRoom()
            guest1.leaveOrCloseRoom()
            intruderManager.leaveOrCloseRoom()
            hostManager.leaveOrCloseRoom()
        }
    }
}
