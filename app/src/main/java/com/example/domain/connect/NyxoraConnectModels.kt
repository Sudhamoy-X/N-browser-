package com.example.domain.connect

import org.json.JSONArray
import org.json.JSONObject

enum class RoomSyncMode(val label: String, val subtitle: String) {
    BROWSE_TOGETHER(
        "Browse Together",
        "Synchronize URL navigation, Back/Forward, and optional tab selection"
    ),
    WATCH_TOGETHER(
        "Watch Together",
        "Synchronize compatible HTML5 video Play, Pause, and playback position"
    )
}

enum class RoomRole(val label: String) {
    HOST("Host"),
    PARTICIPANT("Participant")
}

enum class SyncConnectionStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    SYNCHRONIZING("Connected"),
    SYNCED("Connected"),
    RECONNECTING("Reconnecting"),
    PAUSED_FOLLOWING("Paused"),
    ROOM_CLOSED("Room Closed"),
    AUTHENTICATION_FAILED("Authentication Failed");

    val isConnectedOrSynced: Boolean
        get() = this == CONNECTED || this == SYNCED || this == SYNCHRONIZING

    val isTerminalOrDisconnected: Boolean
        get() = this == DISCONNECTED || this == ROOM_CLOSED || this == AUTHENTICATION_FAILED
}

enum class VideoCompatibilityState(val label: String, val supportsDirectSync: Boolean) {
    UNKNOWN("Inspecting Page Media…", false),
    COMPATIBLE_HTML5("Compatible HTML5 Video Player", true),
    DRM_PROTECTED_UNSUPPORTED(
        "DRM / Encrypted Media Detected — Direct player sync disabled; using URL Sync fallback",
        false
    ),
    IFRAME_OR_EMBED_RESTRICTED(
        "Cross-Origin Restricted Player — Direct player sync unavailable; using URL Sync fallback",
        false
    ),
    NO_VIDEO_DETECTED("No HTML5 Video Element Detected on Page", false)
}

data class RoomPermissionsConfig(
    val maxParticipants: Int = 5,
    val isRoomLocked: Boolean = false,
    val syncUrlNavigation: Boolean = true,
    val syncBackForward: Boolean = true,
    val syncTabSelection: Boolean = false,
    val syncNewlyOpenedPages: Boolean = true,
    val allowParticipantPlaybackControl: Boolean = true,
    val allowParticipantNavigationSync: Boolean = true,
    val allowParticipantUrlInput: Boolean = true,
    val allowParticipantVideoPlayPause: Boolean = true,
    val allowParticipantVideoSeek: Boolean = true,
    val allowParticipantVolumeControl: Boolean = true,
    val allowParticipantFullscreenControl: Boolean = true,
    val allowParticipantChat: Boolean = true,
    val allowParticipantControls: Boolean = true
) {
    val canParticipantNavigateBrowser: Boolean
        get() = allowParticipantControls && allowParticipantNavigationSync

    val canParticipantInputUrl: Boolean
        get() = allowParticipantControls && allowParticipantNavigationSync && allowParticipantUrlInput

    val canParticipantPlayPauseVideo: Boolean
        get() = allowParticipantControls && allowParticipantPlaybackControl && allowParticipantVideoPlayPause

    val canParticipantSeekVideo: Boolean
        get() = allowParticipantControls && allowParticipantPlaybackControl && allowParticipantVideoSeek

    val canParticipantControlVolume: Boolean
        get() = allowParticipantControls && allowParticipantPlaybackControl && allowParticipantVolumeControl

    val canParticipantControlFullscreen: Boolean
        get() = allowParticipantControls && allowParticipantPlaybackControl && allowParticipantFullscreenControl

    val canParticipantControlOtherPlayback: Boolean
        get() = allowParticipantControls && allowParticipantPlaybackControl

    val canParticipantSendChat: Boolean
        get() = allowParticipantChat

    fun withParticipantControls(allowed: Boolean): RoomPermissionsConfig {
        return copy(allowParticipantControls = allowed)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("maxParticipants", maxParticipants.coerceIn(2, 10))
        put("isRoomLocked", isRoomLocked)
        put("syncUrlNavigation", syncUrlNavigation)
        put("syncBackForward", syncBackForward)
        put("syncTabSelection", syncTabSelection)
        put("syncNewlyOpenedPages", syncNewlyOpenedPages)
        put("allowParticipantPlaybackControl", allowParticipantPlaybackControl)
        put("allowParticipantNavigationSync", allowParticipantNavigationSync)
        put("allowParticipantUrlInput", allowParticipantUrlInput)
        put("allowParticipantVideoPlayPause", allowParticipantVideoPlayPause)
        put("allowParticipantVideoSeek", allowParticipantVideoSeek)
        put("allowParticipantVolumeControl", allowParticipantVolumeControl)
        put("allowParticipantFullscreenControl", allowParticipantFullscreenControl)
        put("allowParticipantChat", allowParticipantChat)
        put("allowParticipantControls", allowParticipantControls)
    }

    companion object {
        fun fromJson(obj: JSONObject?): RoomPermissionsConfig {
            if (obj == null) return RoomPermissionsConfig()
            val masterAllowed = obj.optBoolean("allowParticipantControls", true)
            val pbAllowed = obj.optBoolean("allowParticipantPlaybackControl", true)
            val navAllowed = obj.optBoolean("allowParticipantNavigationSync", true)
            val urlInputAllowed = obj.optBoolean("allowParticipantUrlInput", true)
            val playPauseAllowed = obj.optBoolean("allowParticipantVideoPlayPause", true)
            val seekAllowed = obj.optBoolean("allowParticipantVideoSeek", true)
            val volumeAllowed = obj.optBoolean("allowParticipantVolumeControl", true)
            val fullscreenAllowed = obj.optBoolean("allowParticipantFullscreenControl", true)
            val chatAllowed = obj.optBoolean("allowParticipantChat", true)
            return RoomPermissionsConfig(
                maxParticipants = obj.optInt("maxParticipants", 5).coerceIn(2, 10),
                isRoomLocked = obj.optBoolean("isRoomLocked", false),
                syncUrlNavigation = obj.optBoolean("syncUrlNavigation", true),
                syncBackForward = obj.optBoolean("syncBackForward", true),
                syncTabSelection = obj.optBoolean("syncTabSelection", false),
                syncNewlyOpenedPages = obj.optBoolean("syncNewlyOpenedPages", true),
                allowParticipantPlaybackControl = pbAllowed,
                allowParticipantNavigationSync = navAllowed,
                allowParticipantUrlInput = urlInputAllowed,
                allowParticipantVideoPlayPause = playPauseAllowed,
                allowParticipantVideoSeek = seekAllowed,
                allowParticipantVolumeControl = volumeAllowed,
                allowParticipantFullscreenControl = fullscreenAllowed,
                allowParticipantChat = chatAllowed,
                allowParticipantControls = masterAllowed
            )
        }
    }
}

data class RoomChatMessage(
    val messageId: String,
    val roomId: String,
    val senderDeviceId: String,
    val senderName: String,
    val senderRole: RoomRole,
    val text: String,
    val timestampMillis: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("messageId", messageId)
        put("roomId", roomId)
        put("senderDeviceId", senderDeviceId)
        put("senderName", senderName)
        put("senderRole", senderRole.name)
        put("text", text)
        put("timestampMillis", timestampMillis)
    }

    companion object {
        const val MAX_CHAT_HISTORY = 100

        fun fromJson(obj: JSONObject?): RoomChatMessage? {
            if (obj == null) return null
            val id = obj.optString("messageId", "").trim()
            val text = obj.optString("text", "").trim()
            if (id.isEmpty() || text.isEmpty()) return null
            val roleStr = obj.optString("senderRole", RoomRole.PARTICIPANT.name)
            val role = runCatching { RoomRole.valueOf(roleStr) }.getOrDefault(RoomRole.PARTICIPANT)
            return RoomChatMessage(
                messageId = id,
                roomId = obj.optString("roomId", ""),
                senderDeviceId = obj.optString("senderDeviceId", ""),
                senderName = obj.optString("senderName", "Participant").trim().ifBlank { "Participant" },
                senderRole = role,
                text = text,
                timestampMillis = obj.optLong("timestampMillis", 0L)
            )
        }
    }
}

data class ConnectedDeviceMember(
    val deviceId: String,
    val deviceName: String,
    val role: RoomRole,
    val status: SyncConnectionStatus,
    val joinedAtMillis: Long,
    val lastHeartbeatMillis: Long,
    val isFollowingPaused: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("role", role.name)
        put("status", status.name)
        put("joinedAtMillis", joinedAtMillis)
        put("lastHeartbeatMillis", lastHeartbeatMillis)
        put("isFollowingPaused", isFollowingPaused)
    }

    companion object {
        fun fromJson(obj: JSONObject): ConnectedDeviceMember {
            return ConnectedDeviceMember(
                deviceId = obj.optString("deviceId", ""),
                deviceName = obj.optString("deviceName", "Android Device"),
                role = runCatching { RoomRole.valueOf(obj.optString("role", RoomRole.PARTICIPANT.name)) }
                    .getOrDefault(RoomRole.PARTICIPANT),
                status = runCatching { SyncConnectionStatus.valueOf(obj.optString("status", SyncConnectionStatus.CONNECTED.name)) }
                    .getOrDefault(SyncConnectionStatus.CONNECTED),
                joinedAtMillis = obj.optLong("joinedAtMillis", 0L),
                lastHeartbeatMillis = obj.optLong("lastHeartbeatMillis", 0L),
                isFollowingPaused = obj.optBoolean("isFollowingPaused", false)
            )
        }
    }
}

data class WatchTogetherPlaybackState(
    val pageUrl: String,
    val isPlaying: Boolean,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val playbackRate: Double = 1.0,
    val volumeLevel: Double = 1.0,
    val isMuted: Boolean = false,
    val isFullscreen: Boolean = false,
    val orientationMode: String = "AUTO",
    val controlActionType: String = "",
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
    val sequenceNumber: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pageUrl", pageUrl)
        put("isPlaying", isPlaying)
        put("positionSeconds", positionSeconds)
        put("durationSeconds", durationSeconds)
        put("playbackRate", playbackRate)
        put("volumeLevel", volumeLevel.coerceIn(0.0, 1.0))
        put("isMuted", isMuted)
        put("isFullscreen", isFullscreen)
        put("orientationMode", orientationMode)
        if (controlActionType.isNotBlank()) {
            put("controlActionType", controlActionType)
        }
        put("updatedAtMillis", updatedAtMillis)
        put("updatedByDeviceId", updatedByDeviceId)
        put("sequenceNumber", sequenceNumber)
    }

    companion object {
        fun fromJson(obj: JSONObject?): WatchTogetherPlaybackState? {
            if (obj == null) return null
            val pageUrl = obj.optString("pageUrl", "")
            if (pageUrl.isBlank()) return null
            return WatchTogetherPlaybackState(
                pageUrl = pageUrl,
                isPlaying = obj.optBoolean("isPlaying", false),
                positionSeconds = obj.optDouble("positionSeconds", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it },
                durationSeconds = obj.optDouble("durationSeconds", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it },
                playbackRate = obj.optDouble("playbackRate", 1.0).let { if (it.isNaN()) 1.0 else it.coerceIn(0.25, 2.0) },
                volumeLevel = obj.optDouble("volumeLevel", 1.0).let { if (it.isNaN()) 1.0 else it.coerceIn(0.0, 1.0) },
                isMuted = obj.optBoolean("isMuted", false),
                isFullscreen = obj.optBoolean("isFullscreen", false),
                orientationMode = obj.optString("orientationMode", "AUTO").ifBlank { "AUTO" },
                controlActionType = obj.optString("controlActionType", "").trim().uppercase(),
                updatedAtMillis = obj.optLong("updatedAtMillis", 0L),
                updatedByDeviceId = obj.optString("updatedByDeviceId", ""),
                sequenceNumber = obj.optLong("sequenceNumber", 0L)
            )
        }
    }
}

data class NyxoraConnectRoomState(
    val roomId: String,
    val roomName: String,
    val accessKey: String,
    val endpointUrl: String,
    val hostDeviceId: String,
    val authorityEpoch: Long = 1L,
    val mode: RoomSyncMode = RoomSyncMode.BROWSE_TOGETHER,
    val isSyncActive: Boolean = true,
    val permissions: RoomPermissionsConfig = RoomPermissionsConfig(),
    val members: List<ConnectedDeviceMember> = emptyList(),
    val currentSyncedUrl: String = "",
    val currentSyncedTitle: String = "",
    val playbackState: WatchTogetherPlaybackState? = null,
    val chatMessages: List<RoomChatMessage> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = System.currentTimeMillis() + DEFAULT_ROOM_TTL_MS,
    val lastSequenceNumber: Long = 0L
) {
    fun toSnapshotJson(includeAccessKey: Boolean = false): JSONObject = JSONObject().apply {
        put("roomId", roomId)
        put("roomName", roomName)
        if (includeAccessKey) {
            put("accessKey", accessKey)
        }
        put("endpointUrl", endpointUrl)
        put("hostDeviceId", hostDeviceId)
        put("authorityEpoch", authorityEpoch)
        put("mode", mode.name)
        put("isSyncActive", isSyncActive)
        put("permissions", permissions.toJson())
        val membersArr = JSONArray()
        for (m in members) {
            membersArr.put(m.toJson())
        }
        put("members", membersArr)
        put("currentSyncedUrl", currentSyncedUrl)
        put("currentSyncedTitle", currentSyncedTitle)
        if (playbackState != null) {
            put("playbackState", playbackState.toJson())
        }
        val chatArr = JSONArray()
        for (msg in chatMessages.takeLast(RoomChatMessage.MAX_CHAT_HISTORY)) {
            chatArr.put(msg.toJson())
        }
        put("chatMessages", chatArr)
        put("createdAtMillis", createdAtMillis)
        put("expiresAtMillis", expiresAtMillis)
        put("lastSequenceNumber", lastSequenceNumber)
    }

    companion object {
        const val DEFAULT_ROOM_TTL_MS = 2 * 60 * 60 * 1000L // 2 hours

        fun fromSnapshotJson(obj: JSONObject, existingAccessKey: String, fallbackEndpoint: String): NyxoraConnectRoomState {
            val membersArr = obj.optJSONArray("members")
            val membersList = mutableListOf<ConnectedDeviceMember>()
            if (membersArr != null) {
                for (i in 0 until membersArr.length()) {
                    val item = membersArr.optJSONObject(i)
                    if (item != null) {
                        membersList.add(ConnectedDeviceMember.fromJson(item))
                    }
                }
            }
            val chatArr = obj.optJSONArray("chatMessages")
            val chatList = mutableListOf<RoomChatMessage>()
            if (chatArr != null) {
                for (i in 0 until chatArr.length()) {
                    val item = chatArr.optJSONObject(i)
                    if (item != null) {
                        RoomChatMessage.fromJson(item)?.let { chatList.add(it) }
                    }
                }
            }
            return NyxoraConnectRoomState(
                roomId = obj.optString("roomId", ""),
                roomName = obj.optString("roomName", "NYXORA Room"),
                accessKey = obj.optString("accessKey", existingAccessKey).ifBlank { existingAccessKey },
                endpointUrl = obj.optString("endpointUrl", fallbackEndpoint).ifBlank { fallbackEndpoint },
                hostDeviceId = obj.optString("hostDeviceId", ""),
                authorityEpoch = obj.optLong("authorityEpoch", 1L),
                mode = runCatching { RoomSyncMode.valueOf(obj.optString("mode", RoomSyncMode.BROWSE_TOGETHER.name)) }
                    .getOrDefault(RoomSyncMode.BROWSE_TOGETHER),
                isSyncActive = obj.optBoolean("isSyncActive", true),
                permissions = RoomPermissionsConfig.fromJson(obj.optJSONObject("permissions")),
                members = membersList,
                currentSyncedUrl = obj.optString("currentSyncedUrl", ""),
                currentSyncedTitle = obj.optString("currentSyncedTitle", ""),
                playbackState = WatchTogetherPlaybackState.fromJson(obj.optJSONObject("playbackState")),
                chatMessages = chatList.takeLast(RoomChatMessage.MAX_CHAT_HISTORY),
                createdAtMillis = obj.optLong("createdAtMillis", 0L),
                expiresAtMillis = obj.optLong("expiresAtMillis", 0L),
                lastSequenceNumber = obj.optLong("lastSequenceNumber", 0L)
            )
        }
    }
}

enum class RealtimeEventType {
    JOIN_REQUEST,
    JOIN_ACCEPTED,
    JOIN_REJECTED,
    LEAVE_ROOM,
    ROOM_CLOSED,
    HEARTBEAT_PING,
    HEARTBEAT_PONG,
    ROOM_STATE_SNAPSHOT,
    UPDATE_ROOM_CONFIG,
    TRANSFER_HOST,
    REMOVE_PARTICIPANT,
    PARTICIPANT_FOLLOW_STATE,
    NAVIGATE_URL,
    NAVIGATE_BACK,
    NAVIGATE_FORWARD,
    SELECT_TAB_NAVIGATION,
    NEW_PAGE_OPENED,
    PLAYBACK_ACTION,
    PLAYBACK_BEACON,
    REQUEST_RESYNC,
    CHAT_MESSAGE,
    PERMISSION_DENIED,
    ERROR_EVENT
}

data class RealtimeEventEnvelope(
    val eventId: String,
    val roomId: String,
    val type: RealtimeEventType,
    val senderDeviceId: String,
    val senderRole: RoomRole,
    val authorityEpoch: Long,
    val sequenceNumber: Long,
    val timestampMillis: Long,
    val payloadJson: String,
    val hmacSignature: String
) {
    fun toJsonString(): String = JSONObject().apply {
        put("eventId", eventId)
        put("roomId", roomId)
        put("type", type.name)
        put("senderDeviceId", senderDeviceId)
        put("senderRole", senderRole.name)
        put("authorityEpoch", authorityEpoch)
        put("sequenceNumber", sequenceNumber)
        put("timestampMillis", timestampMillis)
        put("payloadJson", payloadJson)
        put("hmacSignature", hmacSignature)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): RealtimeEventEnvelope? {
            if (raw.isBlank() || raw.length > 32768) return null
            return runCatching {
                val obj = JSONObject(raw)
                val typeStr = obj.optString("type", "")
                val type = RealtimeEventType.valueOf(typeStr)
                val roleStr = obj.optString("senderRole", RoomRole.PARTICIPANT.name)
                val role = runCatching { RoomRole.valueOf(roleStr) }.getOrDefault(RoomRole.PARTICIPANT)
                RealtimeEventEnvelope(
                    eventId = obj.optString("eventId", ""),
                    roomId = obj.optString("roomId", ""),
                    type = type,
                    senderDeviceId = obj.optString("senderDeviceId", ""),
                    senderRole = role,
                    authorityEpoch = obj.optLong("authorityEpoch", 1L),
                    sequenceNumber = obj.optLong("sequenceNumber", 0L),
                    timestampMillis = obj.optLong("timestampMillis", 0L),
                    payloadJson = obj.optString("payloadJson", "{}"),
                    hmacSignature = obj.optString("hmacSignature", "")
                )
            }.getOrNull()
        }
    }
}
