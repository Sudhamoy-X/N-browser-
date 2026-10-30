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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
    fun `builds and validates standard HTTPS room invitations without exposing secret keys or internal IPs and handles expired revoked and single-use tokens`() {
        val roomId = NyxoraConnectSecurity.generateSecureRoomId()
        val accessKey = NyxoraConnectSecurity.generateRoomAccessKey()
        val internalLanEndpoint = "ws://192.0.0.2:38207/nyxora"
        val now = 1_710_000_000_000L

        val inviteToken = NyxoraConnectSecurity.issueRoomInvitationToken(
            roomId = roomId,
            accessKey = accessKey,
            nowMillis = now,
            ttlMillis = 3_600_000L,
            singleUse = true
        )
        val httpsLink = NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = roomId,
            accessKey = accessKey,
            endpointUrl = internalLanEndpoint,
            roomName = "Cinema Room",
            explicitInviteToken = inviteToken,
            nowMillis = now
        )

        // 1. Standard clickable HTTPS URL on registered domain; never exposes raw accessKey or internal LAN IP
        assertTrue("Expected https:// URL, got $httpsLink", httpsLink.startsWith("https://nyxora.app/connect?"))
        assertFalse("Public HTTPS invite must never expose raw accessKey", httpsLink.contains(accessKey))
        assertFalse("Public HTTPS invite must never expose 192.0.0.2 internal IP", httpsLink.contains("192.0.0.2"))
        assertTrue(NyxoraConnectSecurity.isNyxoraConnectInvitationUrl(httpsLink))

        // 2. Valid HTTPS invitation parses and validates against the room's accessKey
        val parsed = NyxoraConnectSecurity.parseRoomInviteInput(httpsLink, nowMillis = now + 1000L)
        assertNotNull(parsed)
        assertEquals(roomId, parsed?.roomId)
        assertEquals("Cinema Room", parsed?.roomName)
        assertEquals(InvitationValidationStatus.VALID, parsed?.validationStatus)
        assertEquals(
            InvitationValidationStatus.VALID,
            NyxoraConnectSecurity.verifyInvitationTokenAgainstRoom(
                parsed = parsed!!,
                roomId = roomId,
                roomAccessKey = accessKey,
                requestingDeviceId = "device-joiner-1",
                nowMillis = now + 1000L
            )
        )

        // 3. Single-use invitation marked consumed by device-joiner-1 -> another device ("device-joiner-2") gets ALREADY_USED
        assertTrue(NyxoraConnectSecurity.markSingleUseInvitationConsumed(parsed.tokenId, "device-joiner-1"))
        assertEquals(
            InvitationValidationStatus.ALREADY_USED,
            NyxoraConnectSecurity.verifyInvitationTokenAgainstRoom(
                parsed = parsed,
                roomId = roomId,
                roomAccessKey = accessKey,
                requestingDeviceId = "device-joiner-2",
                nowMillis = now + 2000L
            )
        )

        // 4. Expired invitation token returns EXPIRED
        val parsedExpired = NyxoraConnectSecurity.parseRoomInviteInput(httpsLink, nowMillis = now + 7_200_000L)
        assertEquals(InvitationValidationStatus.EXPIRED, parsedExpired?.validationStatus)

        // 5. Revoked invitation token returns REVOKED
        val revocableToken = NyxoraConnectSecurity.issueRoomInvitationToken(
            roomId = roomId,
            accessKey = accessKey,
            nowMillis = now,
            singleUse = false
        )
        val revocableLink = NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = roomId,
            accessKey = accessKey,
            endpointUrl = internalLanEndpoint,
            roomName = "Cinema Room",
            explicitInviteToken = revocableToken,
            nowMillis = now
        )
        NyxoraConnectSecurity.revokeInvitationToken(revocableToken)
        val parsedRevoked = NyxoraConnectSecurity.parseRoomInviteInput(revocableLink, nowMillis = now + 500L)
        assertEquals(InvitationValidationStatus.REVOKED, parsedRevoked?.validationStatus)

        // 6. Tampered invitation token signature returns UNAUTHORIZED_SIGNATURE
        val freshToken = NyxoraConnectSecurity.issueRoomInvitationToken(
            roomId = roomId,
            accessKey = accessKey,
            nowMillis = now,
            singleUse = false
        )
        val tamperedToken = freshToken.substringBeforeLast(".") + ".0000000000000000000000000000000000000000000000000000000000000000"
        val tamperedLink = NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = roomId,
            accessKey = accessKey,
            endpointUrl = internalLanEndpoint,
            roomName = "Cinema Room",
            explicitInviteToken = tamperedToken,
            nowMillis = now
        )
        val parsedTampered = NyxoraConnectSecurity.parseRoomInviteInput(tamperedLink, nowMillis = now + 500L)
        assertNotNull(parsedTampered)
        assertEquals(
            InvitationValidationStatus.UNAUTHORIZED_SIGNATURE,
            NyxoraConnectSecurity.verifyInvitationTokenAgainstRoom(
                parsed = parsedTampered!!,
                roomId = roomId,
                roomAccessKey = accessKey,
                requestingDeviceId = "device-joiner-1",
                nowMillis = now + 500L
            )
        )

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
    fun `multi-device bidirectional URL sync reconnect deduplication consent gating and immediate stop on leave`() {
        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "device-host-1",
            initialDeviceName = "Host Phone",
            sharedRelayServer = relayServer
        )
        val participantA = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "device-part-a",
            initialDeviceName = "Participant A",
            sharedRelayServer = relayServer
        )
        val participantB = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "device-part-b",
            initialDeviceName = "Participant B",
            sharedRelayServer = relayServer
        )

        val hostCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val partACommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val partBCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()

        val jobHost = testScope.launch { hostManager.remoteSyncCommands.collect { hostCommands.add(it) } }
        val jobA = testScope.launch { participantA.remoteSyncCommands.collect { partACommands.add(it) } }
        val jobB = testScope.launch { participantB.remoteSyncCommands.collect { partBCommands.add(it) } }

        try {
            // 1. Host creates room and generates HTTPS shareable invitation link (no raw key or LAN IP)
            hostManager.createRoom(
                roomName = "Multi-Device Sync Room",
                mode = RoomSyncMode.BROWSE_TOGETHER,
                initialUrl = "https://example.com",
                initialTitle = "Example"
            )
            assertTrue(awaitCondition { hostManager.connectionStatus.value == SyncConnectionStatus.SYNCED })
            val httpsInvite = hostManager.currentShareableInviteUrl.value
            assertTrue(httpsInvite.startsWith("https://nyxora.app/connect?"))
            assertFalse(httpsInvite.contains("192.0.0.2"))

            // 2. Both Participant A and Participant B join using only the HTTPS invitation link
            assertTrue(participantA.joinRoom(httpsInvite))
            assertTrue(participantB.joinRoom(httpsInvite))
            assertTrue(
                "Expected all 3 devices connected and synced in the same room",
                awaitCondition {
                    participantA.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        participantB.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        hostManager.roomState.value?.members?.size == 3
                }
            )

            // 3. Direction 1 (Host -> Participants): Host navigates to kotlinlang.org -> both A and B receive it
            partACommands.clear()
            partBCommands.clear()
            assertTrue(
                hostManager.publishUrlNavigation(
                    url = "https://kotlinlang.org",
                    title = "Kotlin",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                "Both Participant A and B must receive Host URL change",
                awaitCondition {
                    partACommands.any { it is IncomingRemoteSyncCommand.NavigateToUrl && it.url.trimEnd('/') == "https://kotlinlang.org" } &&
                        partBCommands.any { it is IncomingRemoteSyncCommand.NavigateToUrl && it.url.trimEnd('/') == "https://kotlinlang.org" }
                }
            )

            // 4. Direction 2 (Participant -> Host & other Participants): Participant A navigates to wikipedia.org -> Host and B receive it
            hostCommands.clear()
            partBCommands.clear()
            assertTrue(
                participantA.publishUrlNavigation(
                    url = "https://www.wikipedia.org",
                    title = "Wikipedia",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                "Both Host and Participant B must receive Participant A's URL change (bidirectional sync)",
                awaitCondition {
                    hostCommands.any { it is IncomingRemoteSyncCommand.NavigateToUrl && it.url.trimEnd('/') == "https://www.wikipedia.org" } &&
                        partBCommands.any { it is IncomingRemoteSyncCommand.NavigateToUrl && it.url.trimEnd('/') == "https://www.wikipedia.org" }
                }
            )

            // 5. User consent check: When Participant A disables sharing consent, outgoing URL sync is blocked
            participantA.setUserShareConsent(false)
            assertFalse(
                participantA.publishUrlNavigation(
                    url = "https://unshared-local-page.org",
                    title = "Unshared",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            participantA.setUserShareConsent(true)

            // 6. Disconnect and reconnect Participant A -> verify no duplicate member entries (members.size stays 3)
            participantA.simulateNetworkInterruptionForReconnect()
            assertTrue(
                awaitCondition(timeoutMs = 6000L) {
                    participantA.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        hostManager.roomState.value?.members?.size == 3
                }
            )
            val distinctIds = hostManager.roomState.value!!.members.map { it.deviceId }.distinct()
            assertEquals("Reconnecting must never create duplicate device entries", 3, distinctIds.size)

            // 7. Participant B leaves the room -> immediately stops receiving any further synchronization
            partBCommands.clear()
            participantB.leaveOrCloseRoom(notifyServer = true)
            assertEquals(SyncConnectionStatus.DISCONNECTED, participantB.connectionStatus.value)
            assertNull(participantB.roomState.value)
            assertTrue(
                awaitCondition { hostManager.roomState.value?.members?.size == 2 }
            )

            partACommands.clear()
            assertTrue(
                hostManager.publishUrlNavigation(
                    url = "https://developer.android.com/jetpack/compose",
                    title = "Jetpack Compose",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                awaitCondition {
                    partACommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://developer.android.com/jetpack/compose"
                    }
                }
            )
            assertTrue("Departed Participant B must receive zero sync events after leaving", partBCommands.isEmpty())
        } finally {
            jobHost.cancel()
            jobA.cancel()
            jobB.cancel()
            participantB.leaveOrCloseRoom()
            participantA.leaveOrCloseRoom()
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

    @Test
    fun `unreachable endpoint transitions cleanly from Reconnecting to Disconnected and remote relay bootstraps host room`() {
        // Part A: Unreachable endpoint must never report CONNECTED and must exit RECONNECTING -> DISCONNECTED
        val failingManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "device-unreachable-test",
            initialDeviceName = "Offline Test Phone",
            sharedRelayServer = relayServer,
            maxReconnectAttempts = 2,
            baseReconnectDelayMs = 30L,
            handshakeTimeoutMs = 200L
        )

        val validInvite = NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = "NYX-9B8C-7D6E",
            accessKey = "ABCD-EFGH-JKLM-NPQR",
            roomName = "Unreachable Room"
        )

        try {
            assertTrue(
                failingManager.joinRoom(
                    rawRoomOrInvite = validInvite,
                    explicitEndpointUrl = "ws://127.0.0.1:1/nyxora"
                )
            )
            // Must never prematurely claim CONNECTED or SYNCED without server confirmation
            assertNotEquals(SyncConnectionStatus.CONNECTED, failingManager.connectionStatus.value)
            assertNotEquals(SyncConnectionStatus.SYNCED, failingManager.connectionStatus.value)

            // Wait for bounded exponential backoff retries to exhaust and transition to DISCONNECTED
            assertTrue(
                "Unreachable endpoint must transition cleanly to DISCONNECTED instead of looping in RECONNECTING forever",
                awaitCondition(timeoutMs = 5000L) {
                    failingManager.connectionStatus.value == SyncConnectionStatus.DISCONNECTED &&
                        failingManager.roomState.value == null
                }
            )
            assertNotNull("Expected actionable error message after reconnect exhaustion", failingManager.lastSyncError.value)
            assertTrue(
                failingManager.lastSyncError.value!!.contains("Unable to reach NYXORA Connect server", ignoreCase = true)
            )
        } finally {
            failingManager.leaveOrCloseRoom()
        }

        // Part B: Remote relay server (separate instance without local in-memory registerNewRoom) bootstraps room from Host JOIN_REQUEST
        val remoteCloudRelay = NyxoraWebSocketRelayServer()
        val remotePort = remoteCloudRelay.startIfNeeded()
        val remoteEndpoint = "ws://127.0.0.1:$remotePort/nyxora"

        val hostLocalRelay = NyxoraWebSocketRelayServer()
        val guestLocalRelay = NyxoraWebSocketRelayServer()

        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "remote-host-1",
            initialDeviceName = "Host Device",
            sharedRelayServer = hostLocalRelay
        )
        val guestManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "remote-guest-2",
            initialDeviceName = "Guest Device",
            sharedRelayServer = guestLocalRelay
        )

        try {
            val created = hostManager.createRoom(
                roomName = "Remote Cloud Room",
                mode = RoomSyncMode.BROWSE_TOGETHER,
                initialUrl = "https://kotlinlang.org",
                initialTitle = "Kotlin",
                customRelayEndpoint = remoteEndpoint
            )
            assertTrue(
                "Host failed to bootstrap room on remote relay server",
                awaitCondition { hostManager.connectionStatus.value == SyncConnectionStatus.SYNCED }
            )

            val inviteUrl = hostManager.currentShareableInviteUrl.value
            assertTrue(
                guestManager.joinRoom(
                    rawRoomOrInvite = inviteUrl,
                    explicitEndpointUrl = remoteEndpoint
                )
            )
            assertTrue(
                "Guest failed to join remote relay room",
                awaitCondition {
                    guestManager.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        hostManager.roomState.value?.members?.size == 2
                }
            )

            // Verify diagnostic logs never leak raw accessKey or inviteToken HMAC
            val logsJoined = hostManager.diagnosticLogs.value.joinToString("\n")
            assertFalse("Diagnostic logs must redact room accessKey", logsJoined.contains(created.accessKey))
            assertTrue("Diagnostic logs must contain redacted placeholders", logsJoined.contains("[REDACTED]"))
        } finally {
            guestManager.leaveOrCloseRoom()
            hostManager.leaveOrCloseRoom()
            remoteCloudRelay.stopServer()
            hostLocalRelay.stopServer()
            guestLocalRelay.stopServer()
        }
    }

    @Test
    fun `permanent backend configuration supports host room creation code-only participant joining non-existent room error and URL sync`() {
        // 1. Verify permanent production WSS endpoint is configured as wss://n-browser.onrender.com/nyxora
        assertEquals(
            "wss://n-browser.onrender.com/nyxora",
            NyxoraConnectSecurity.DEFAULT_PRODUCTION_WSS_ENDPOINT
        )
        assertEquals(
            "wss://n-browser.onrender.com/nyxora",
            NyxoraConnectSecurity.configuredPublicWssEndpoint()
        )

        val remoteRelay = NyxoraWebSocketRelayServer()
        val remotePort = remoteRelay.startIfNeeded()
        val remoteEndpoint = "ws://127.0.0.1:$remotePort/nyxora"

        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "code-host-device",
            initialDeviceName = "Host Pixel 9",
            sharedRelayServer = NyxoraWebSocketRelayServer(),
            configuredEndpointProvider = { remoteEndpoint }
        )
        val participantManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "code-participant-device",
            initialDeviceName = "Participant Galaxy S24",
            sharedRelayServer = NyxoraWebSocketRelayServer(),
            configuredEndpointProvider = { remoteEndpoint }
        )

        val participantCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val collectJob = testScope.launch {
            participantManager.remoteSyncCommands.collect { participantCommands.add(it) }
        }

        try {
            // 2. Invalid room code rejected immediately on client
            assertFalse(participantManager.joinRoom("INVALID-CODE"))
            assertTrue(
                participantManager.lastSyncError.value?.contains("Invalid Room Code", ignoreCase = true) == true
            )

            // 3. Valid format Room Code for a room that does not exist on the server -> rejected with clear error
            assertTrue(participantManager.joinRoom("NYX-9B8C-7D6E"))
            assertTrue(
                "Non-existent room code must transition to DISCONNECTED with clear error",
                awaitCondition {
                    participantManager.connectionStatus.value == SyncConnectionStatus.DISCONNECTED &&
                        participantManager.lastSyncError.value?.contains("does not exist", ignoreCase = true) == true
                }
            )

            // 4. Host taps Create Room -> connects to configured backend and confirms room creation
            val createdRoom = hostManager.createRoom(
                mode = RoomSyncMode.BROWSE_TOGETHER,
                initialUrl = "https://kotlinlang.org",
                initialTitle = "Kotlin"
            )
            assertTrue(
                "Host must transition to Connected only after server confirms room creation",
                awaitCondition {
                    hostManager.connectionStatus.value.isConnectedOrSynced &&
                        hostManager.connectionStatus.value.label == "Connected"
                }
            )

            // 5. Participant joins using ONLY the Room Code (or shared text containing the Room Code)
            val sharedCodeMessage = "Join my NYXORA Room: ${createdRoom.roomId}"
            assertTrue(participantManager.joinRoom(sharedCodeMessage))
            assertTrue(
                "Participant must join room using only the Room Code and display Connected",
                awaitCondition {
                    participantManager.connectionStatus.value.isConnectedOrSynced &&
                        participantManager.connectionStatus.value.label == "Connected" &&
                        hostManager.roomState.value?.members?.size == 2
                }
            )

            // 6. Unified mode: Both video playback and URL navigation sync in the same room
            participantCommands.clear()
            assertTrue(
                hostManager.publishPlaybackEvent(
                    pageUrl = "https://kotlinlang.org",
                    isPlaying = true,
                    positionSeconds = 30.0,
                    durationSeconds = 120.0
                )
            )
            assertTrue(
                "Participant must receive Host playback sync in unified room mode",
                awaitCondition {
                    participantCommands.any {
                        it is IncomingRemoteSyncCommand.ApplyPlaybackState &&
                            it.state.isPlaying &&
                            it.state.positionSeconds >= 30.0
                    }
                }
            )

            // 7. URL synchronization works across code-joined devices
            participantCommands.clear()
            assertTrue(
                hostManager.publishUrlNavigation(
                    url = "https://developer.android.com/compose",
                    title = "Jetpack Compose",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                "Participant joined via Room Code must receive Host URL navigation",
                awaitCondition {
                    participantCommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://developer.android.com/compose"
                    }
                }
            )

            // 8. Reconnection after invite generation and network drop never triggers INVITE_UNAUTHORIZED
            hostManager.generateShareableInviteLink()
            hostManager.simulateNetworkInterruptionForReconnect()
            participantManager.simulateNetworkInterruptionForReconnect()
            assertTrue(
                "Host and Participant must reconnect and restore Connected state without INVITE_UNAUTHORIZED",
                awaitCondition(timeoutMs = 6000L) {
                    hostManager.connectionStatus.value.isConnectedOrSynced &&
                        participantManager.connectionStatus.value.isConnectedOrSynced &&
                        hostManager.lastSyncError.value == null &&
                        participantManager.lastSyncError.value == null &&
                        hostManager.roomState.value?.members?.size == 2
                }
            )
        } finally {
            collectJob.cancel()
            participantManager.leaveOrCloseRoom()
            hostManager.leaveOrCloseRoom()
            remoteRelay.stopServer()
        }
    }

    @Test
    fun `watch together playback controls synchronize play pause and 10s seeks and handle unsupported media and single join confirmation`() {
        // 1. Verify WatchTogetherMediaBridge control calculations & JS result parsing
        val noMediaAttempt = WatchTogetherMediaBridge.computeControlledPlaybackState(
            currentRoomPlayback = null,
            currentCompatibility = VideoCompatibilityState.NO_VIDEO_DETECTED,
            shouldPlay = true,
            seekDeltaSeconds = null
        )
        assertFalse("Play control must fail when no supported media is detected", noMediaAttempt.succeeded)
        assertEquals(VideoCompatibilityState.NO_VIDEO_DETECTED, noMediaAttempt.compatibility)

        val drmAttempt = WatchTogetherMediaBridge.computeControlledPlaybackState(
            currentRoomPlayback = WatchTogetherPlaybackState(
                pageUrl = "https://drm.example.com/movie",
                isPlaying = true,
                positionSeconds = 50.0,
                durationSeconds = 300.0,
                updatedAtMillis = 1000L,
                updatedByDeviceId = "host-1",
                sequenceNumber = 1L
            ),
            currentCompatibility = VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED,
            shouldPlay = false,
            seekDeltaSeconds = 10.0
        )
        assertFalse("Control must fail gracefully on DRM-protected media", drmAttempt.succeeded)
        assertEquals(VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED, drmAttempt.compatibility)

        val activePb = WatchTogetherPlaybackState(
            pageUrl = "https://videos.example.org/watch",
            isPlaying = true,
            positionSeconds = 45.0,
            durationSeconds = 120.0,
            updatedAtMillis = 1000L,
            updatedByDeviceId = "host-1",
            sequenceNumber = 2L
        )
        val forward10 = WatchTogetherMediaBridge.computeControlledPlaybackState(
            currentRoomPlayback = activePb,
            currentCompatibility = VideoCompatibilityState.COMPATIBLE_HTML5,
            shouldPlay = true,
            seekDeltaSeconds = 10.0
        )
        assertTrue(forward10.succeeded)
        assertEquals(55.0, forward10.positionSeconds, 0.01)

        val rewind10 = WatchTogetherMediaBridge.computeControlledPlaybackState(
            currentRoomPlayback = activePb.copy(positionSeconds = 55.0),
            currentCompatibility = VideoCompatibilityState.COMPATIBLE_HTML5,
            shouldPlay = true,
            seekDeltaSeconds = -10.0
        )
        assertTrue(rewind10.succeeded)
        assertEquals(45.0, rewind10.positionSeconds, 0.01)

        val rewindClampZero = WatchTogetherMediaBridge.computeControlledPlaybackState(
            currentRoomPlayback = activePb.copy(positionSeconds = 4.0),
            currentCompatibility = VideoCompatibilityState.COMPATIBLE_HTML5,
            shouldPlay = true,
            seekDeltaSeconds = -10.0
        )
        assertTrue(rewindClampZero.succeeded)
        assertEquals(0.0, rewindClampZero.positionSeconds, 0.01)

        val parsedJsOk = WatchTogetherMediaBridge.parseControlActionResult(
            """{"ok":true,"compat":"COMPATIBLE_HTML5","currentTime":62.5,"duration":180.0,"paused":false,"playbackRate":1.0}"""
        )
        assertNotNull(parsedJsOk)
        assertTrue(parsedJsOk!!.succeeded)
        assertTrue(parsedJsOk.isPlaying)
        assertEquals(62.5, parsedJsOk.positionSeconds, 0.01)

        val parsedJsDrm = WatchTogetherMediaBridge.parseControlActionResult(
            """{"ok":false,"compat":"DRM_PROTECTED_UNSUPPORTED","reason":"DRM-protected video cannot be controlled"}"""
        )
        assertNotNull(parsedJsDrm)
        assertFalse(parsedJsDrm!!.succeeded)
        assertEquals(VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED, parsedJsDrm.compatibility)

        // 2. Verify end-to-end WebSocket synchronization for ParticipantJoinConfirmed and Watch Together controls
        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "wt-host-1",
            initialDeviceName = "Host Device",
            sharedRelayServer = relayServer
        )
        val participantManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "wt-part-2",
            initialDeviceName = "Participant Device",
            sharedRelayServer = relayServer
        )

        val hostCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val partCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val hostJob = testScope.launch {
            hostManager.remoteSyncCommands.collect { hostCommands.add(it) }
        }
        val partJob = testScope.launch {
            participantManager.remoteSyncCommands.collect { partCommands.add(it) }
        }

        try {
            val room = hostManager.createRoom(
                roomName = "Watch Together Room",
                mode = RoomSyncMode.BROWSE_TOGETHER
            )
            assertTrue(awaitCondition { hostManager.connectionStatus.value == SyncConnectionStatus.SYNCED })

            // Host initially has no supported video detected -> publishPlaybackEvent must return false
            hostManager.updateVideoCompatibility(VideoCompatibilityState.NO_VIDEO_DETECTED)
            assertFalse(
                "publishPlaybackEvent must fail when no supported video is detected",
                hostManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/article",
                    isPlaying = true,
                    positionSeconds = 10.0,
                    durationSeconds = 0.0
                )
            )

            // Host detects DRM-protected media -> publishPlaybackEvent must return false
            hostManager.updateVideoCompatibility(VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED)
            assertFalse(
                "publishPlaybackEvent must fail on DRM-protected media",
                hostManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/drm",
                    isPlaying = true,
                    positionSeconds = 10.0,
                    durationSeconds = 100.0
                )
            )

            // Participant joins room -> receives ParticipantJoinConfirmed strictly once on JOIN_ACCEPTED
            assertTrue(participantManager.joinRoom(room.roomId))
            assertTrue(
                awaitCondition {
                    participantManager.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        partCommands.count { it is IncomingRemoteSyncCommand.ParticipantJoinConfirmed } == 1
                }
            )
            val joinConfirmed = partCommands.filterIsInstance<IncomingRemoteSyncCommand.ParticipantJoinConfirmed>().single()
            assertEquals(room.roomId, joinConfirmed.roomId)
            assertNull("Host is not playing media yet, so initial playbackState is null", joinConfirmed.playbackState)

            // Host starts HTML5 video playback at 25.0s -> Participant receives playback state
            hostManager.updateVideoCompatibility(VideoCompatibilityState.COMPATIBLE_HTML5)
            partCommands.clear()
            assertTrue(
                hostManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/video.mp4",
                    isPlaying = true,
                    positionSeconds = 25.0,
                    durationSeconds = 200.0
                )
            )
            assertTrue(
                awaitCondition {
                    partCommands.any {
                        it is IncomingRemoteSyncCommand.ApplyPlaybackState &&
                            it.state.isPlaying &&
                            kotlin.math.abs(it.state.positionSeconds - 25.0) < 0.1
                    }
                }
            )

            // Participant skips forward 10 seconds (25.0s -> 35.0s) -> Host receives synchronized seek position
            participantManager.updateVideoCompatibility(VideoCompatibilityState.COMPATIBLE_HTML5)
            hostCommands.clear()
            assertTrue(
                participantManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/video.mp4",
                    isPlaying = true,
                    positionSeconds = 35.0,
                    durationSeconds = 200.0
                )
            )
            assertTrue(
                "Host must receive Participant's +10s seek position",
                awaitCondition {
                    hostCommands.any {
                        it is IncomingRemoteSyncCommand.ApplyPlaybackState &&
                            it.state.isPlaying &&
                            kotlin.math.abs(it.state.positionSeconds - 35.0) < 0.1
                    } && kotlin.math.abs((hostManager.roomState.value?.playbackState?.positionSeconds ?: 0.0) - 35.0) < 0.1
                }
            )

            // Host skips backward 10 seconds (35.0s -> 25.0s) and pauses -> Participant receives updated state
            partCommands.clear()
            assertTrue(
                hostManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/video.mp4",
                    isPlaying = false,
                    positionSeconds = 25.0,
                    durationSeconds = 200.0
                )
            )
            assertTrue(
                "Participant must receive Host's -10s seek and Pause action",
                awaitCondition {
                    partCommands.any {
                        it is IncomingRemoteSyncCommand.ApplyPlaybackState &&
                            !it.state.isPlaying &&
                            kotlin.math.abs(it.state.positionSeconds - 25.0) < 0.1
                    }
                }
            )

            // Simulate Participant network drop and reconnect -> verify NO duplicate ParticipantJoinConfirmed is emitted
            partCommands.clear()
            participantManager.simulateNetworkInterruptionForReconnect()
            assertTrue(
                awaitCondition(timeoutMs = 6000L) {
                    participantManager.connectionStatus.value == SyncConnectionStatus.SYNCED
                }
            )
            assertEquals(
                "Reconnect must never emit duplicate ParticipantJoinConfirmed events",
                0,
                partCommands.count { it is IncomingRemoteSyncCommand.ParticipantJoinConfirmed }
            )
        } finally {
            hostJob.cancel()
            partJob.cancel()
            participantManager.leaveOrCloseRoom()
            hostManager.leaveOrCloseRoom()
        }
    }

    @Test
    fun `realtime chat synchronization secret redaction equal participant controls and host permission switch`() {
        val hostManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "chat-host-1",
            initialDeviceName = "Host Device",
            sharedRelayServer = relayServer
        )
        val participantManager = NyxoraConnectSessionManager(
            scope = testScope,
            localDeviceId = "chat-part-2",
            initialDeviceName = "Participant Device",
            sharedRelayServer = relayServer
        )

        val hostCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val partCommands = CopyOnWriteArrayList<IncomingRemoteSyncCommand>()
        val hostJob = testScope.launch {
            hostManager.remoteSyncCommands.collect { hostCommands.add(it) }
        }
        val partJob = testScope.launch {
            participantManager.remoteSyncCommands.collect { partCommands.add(it) }
        }

        try {
            val room = hostManager.createRoom(
                roomName = "Equal Controls & Chat Room",
                mode = RoomSyncMode.BROWSE_TOGETHER
            )
            assertTrue(awaitCondition { hostManager.connectionStatus.value == SyncConnectionStatus.SYNCED })
            assertTrue(
                "Allow Participant Controls must be enabled by default when a room is created",
                hostManager.roomState.value?.permissions?.allowParticipantControls == true
            )

            assertTrue(participantManager.joinRoom(room.roomId))
            assertTrue(
                awaitCondition {
                    participantManager.connectionStatus.value == SyncConnectionStatus.SYNCED &&
                        (hostManager.roomState.value?.members?.size ?: 0) == 2
                }
            )
            assertTrue(
                "Participant must see Allow Participant Controls enabled by default",
                participantManager.roomState.value?.permissions?.allowParticipantControls == true
            )
            assertNull(participantManager.participantControlsDisabledNotice.value)

            // 1. Real-time Chat between Host and Participant + unread count + secret redaction
            val inviteLink = hostManager.generateShareableInviteLink()
            assertTrue(inviteLink.isNotBlank())
            val secretRoomKey = room.accessKey
            assertTrue(
                participantManager.sendChatMessage(
                    "Hello from Participant! Key=$secretRoomKey link=$inviteLink"
                )
            )
            assertTrue(
                awaitCondition {
                    hostManager.chatMessages.value.size == 1 &&
                        participantManager.chatMessages.value.size == 1
                }
            )
            val hostReceivedChat = hostManager.chatMessages.value.single()
            assertEquals("Participant Device", hostReceivedChat.senderName)
            assertTrue(hostReceivedChat.text.contains("Hello from Participant!"))
            assertFalse(
                "Chat messages must never expose room access keys",
                hostReceivedChat.text.contains(secretRoomKey)
            )
            assertFalse(
                "Chat messages must never expose invitation tokens",
                hostReceivedChat.text.contains("inv=INV")
            )
            assertEquals(
                "Unread chat count must increment when chat overlay is collapsed",
                1,
                hostManager.unreadChatCount.value
            )

            // Opening the chat overlay clears unread count
            hostManager.setChatOverlayExpanded(true)
            assertEquals(0, hostManager.unreadChatCount.value)
            assertTrue(hostManager.isChatOverlayExpanded.value)

            // Host replies in real time
            assertTrue(hostManager.sendChatMessage("Welcome to the room!"))
            assertTrue(
                awaitCondition {
                    participantManager.chatMessages.value.size == 2 &&
                        hostManager.chatMessages.value.size == 2
                }
            )
            assertEquals("Welcome to the room!", participantManager.chatMessages.value.last().text)

            // Verify diagnostic logs never expose raw access keys or invite tokens
            val combinedLogs = (hostManager.diagnosticLogs.value + participantManager.diagnosticLogs.value).joinToString("\n")
            assertFalse("Logs must never expose raw room access key", combinedLogs.contains(secretRoomKey))

            // 2. Equal Controls by Default: Participant can search/navigate, open new pages, and control playback
            hostCommands.clear()
            assertTrue(
                participantManager.publishUrlNavigation(
                    url = "https://developer.android.com/jetpack/compose",
                    title = "Jetpack Compose",
                    isPrivateTab = false,
                    isPrivateMode = false,
                    eventType = RealtimeEventType.NAVIGATE_URL
                )
            )
            assertTrue(
                awaitCondition {
                    hostCommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://developer.android.com/jetpack/compose"
                    }
                }
            )

            hostCommands.clear()
            assertTrue(
                participantManager.publishUrlNavigation(
                    url = "https://kotlinlang.org/docs/home.html",
                    title = "Kotlin Docs",
                    isPrivateTab = false,
                    isPrivateMode = false,
                    eventType = RealtimeEventType.NEW_PAGE_OPENED
                )
            )
            assertTrue(
                "Newly opened pages by Participant must synchronize to Host when shared controls are enabled",
                awaitCondition {
                    hostCommands.any {
                        it is IncomingRemoteSyncCommand.OpenNewSyncedTab &&
                            it.url == "https://kotlinlang.org/docs/home.html"
                    }
                }
            )

            // 3. Host disables Allow Participant Controls -> enforced immediately on client and server
            hostManager.setAllowParticipantControls(false)
            assertTrue(
                awaitCondition {
                    participantManager.roomState.value?.permissions?.allowParticipantControls == false &&
                        !participantManager.participantControlsDisabledNotice.value.isNullOrBlank()
                }
            )

            // Participant attempts navigation and playback control -> blocked on client with clear notice
            hostCommands.clear()
            assertFalse(
                "Participant navigation must be blocked when Host disables Allow Participant Controls",
                participantManager.publishUrlNavigation(
                    url = "https://unauthorized.example.com",
                    title = "Unauthorized",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            participantManager.updateVideoCompatibility(VideoCompatibilityState.COMPATIBLE_HTML5)
            assertFalse(
                "Participant playback control must be blocked when Host disables Allow Participant Controls",
                participantManager.publishPlaybackEvent(
                    pageUrl = "https://example.com/video.mp4",
                    isPlaying = true,
                    positionSeconds = 50.0,
                    durationSeconds = 120.0
                )
            )
            assertNotNull(participantManager.participantControlsDisabledNotice.value)

            // Even if a participant client bypasses local checks and sends a signed NAVIGATE_URL frame directly,
            // the server enforces Allow Participant Controls and responds with PERMISSION_DENIED.
            assertTrue(
                participantManager.sendControlFrameBypassingClientCheckForTest(
                    type = RealtimeEventType.NAVIGATE_URL,
                    payloadJson = """{"url":"https://bypass-attempt.example.com","title":"Bypass"}"""
                )
            )
            assertTrue(
                awaitCondition {
                    participantManager.diagnosticLogs.value.any { it.contains("PERMISSION_DENIED") }
                }
            )
            assertNotEquals(
                "Server must reject unauthorized participant URL change",
                "https://bypass-attempt.example.com",
                hostManager.roomState.value?.currentSyncedUrl
            )

            // Meanwhile, Host can still control shared navigation and playback
            partCommands.clear()
            assertTrue(
                hostManager.publishUrlNavigation(
                    url = "https://host-controlled.example.com/page",
                    title = "Host Controlled",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                awaitCondition {
                    partCommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://host-controlled.example.com/page"
                    }
                }
            )

            // 4. Host re-enables Allow Participant Controls -> restored immediately without reconnecting
            hostManager.setAllowParticipantControls(true)
            assertTrue(
                awaitCondition {
                    participantManager.roomState.value?.permissions?.allowParticipantControls == true &&
                        participantManager.participantControlsDisabledNotice.value == null
                }
            )

            hostCommands.clear()
            assertTrue(
                "Participant controls must work immediately after Host re-enables Allow Participant Controls without reconnecting",
                participantManager.publishUrlNavigation(
                    url = "https://restored-participant.example.com/page",
                    title = "Restored Participant",
                    isPrivateTab = false,
                    isPrivateMode = false
                )
            )
            assertTrue(
                awaitCondition {
                    hostCommands.any {
                        it is IncomingRemoteSyncCommand.NavigateToUrl &&
                            it.url == "https://restored-participant.example.com/page"
                    }
                }
            )

            // 5. Leaving the room automatically clears chat overlay state and messages
            participantManager.setChatOverlayExpanded(true)
            participantManager.leaveOrCloseRoom()
            assertFalse(participantManager.isInRoom)
            assertFalse(participantManager.isChatOverlayExpanded.value)
            assertTrue(participantManager.chatMessages.value.isEmpty())
            assertEquals(0, participantManager.unreadChatCount.value)
        } finally {
            hostJob.cancel()
            partJob.cancel()
            participantManager.leaveOrCloseRoom()
            hostManager.leaveOrCloseRoom()
        }
    }
}


