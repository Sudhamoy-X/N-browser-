package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.connect.ConnectedDeviceMember
import com.example.domain.connect.NyxoraConnectRoomState
import com.example.domain.connect.NyxoraConnectSecurity
import com.example.domain.connect.RoomPermissionsConfig
import com.example.domain.connect.RoomRole
import com.example.domain.connect.RoomSyncMode
import com.example.domain.connect.SyncConnectionStatus
import com.example.domain.connect.VideoCompatibilityState
import com.example.ui.theme.NyxoraDangerRed
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.theme.NyxoraSuccessGreen
import com.example.ui.theme.NyxoraVibrantViolet
import com.example.ui.theme.NyxoraWarningAmber
import kotlin.math.roundToInt

/**
 * Compact status indicator bar displayed beneath the Top Address Bar whenever a NYXORA Connect
 * session is active. Clearly indicates whether URL or playback synchronization is active,
 * displays connection status (Connected, Synchronizing, Synced, Reconnecting), provides a
 * manual resync action, and allows stopping/leaving immediately.
 */
@Composable
fun NyxoraConnectStatusBanner(
    roomState: NyxoraConnectRoomState,
    localDeviceId: String,
    connectionStatus: SyncConnectionStatus,
    isFollowingPaused: Boolean,
    isPrivateMode: Boolean,
    onOpenConnectSheet: () -> Unit,
    onManualResync: () -> Unit,
    onTogglePauseOrSync: () -> Unit,
    onStopOrLeaveImmediate: () -> Unit
) {
    val isHost = roomState.hostDeviceId == localDeviceId
    val statusColor = when {
        isPrivateMode -> NyxoraVibrantViolet
        !roomState.isSyncActive || isFollowingPaused -> NyxoraWarningAmber
        connectionStatus == SyncConnectionStatus.SYNCED || connectionStatus == SyncConnectionStatus.CONNECTED -> NyxoraSuccessGreen
        connectionStatus == SyncConnectionStatus.SYNCHRONIZING -> NyxoraElectricBlue
        connectionStatus == SyncConnectionStatus.RECONNECTING || connectionStatus == SyncConnectionStatus.CONNECTING -> NyxoraWarningAmber
        else -> NyxoraDangerRed
    }

    val statusLabel = when {
        isPrivateMode -> "Incognito Isolated (Sync Paused)"
        !roomState.isSyncActive -> "Sharing Paused by Host"
        isFollowingPaused -> "Following Paused"
        else -> connectionStatus.label
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenConnectSheet)
            .testTag("nyxora_connect_status_banner"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${roomState.mode.label} • ${if (isHost) "Host" else "Participant"}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(${roomState.members.size}/${roomState.permissions.maxParticipants})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "$statusLabel • ${roomState.roomId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("connect_banner_status_text")
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isHost) {
                    TextButton(
                        onClick = onManualResync,
                        modifier = Modifier.testTag("connect_banner_resync_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = "Manual Resync",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Resync", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    TextButton(
                        onClick = onTogglePauseOrSync,
                        modifier = Modifier.testTag("connect_banner_pause_toggle")
                    ) {
                        Text(
                            text = if (roomState.isSyncActive) "Pause" else "Resume",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                TextButton(
                    onClick = onStopOrLeaveImmediate,
                    colors = ButtonDefaults.textButtonColors(contentColor = NyxoraDangerRed),
                    modifier = Modifier.testTag("connect_banner_stop_button")
                ) {
                    Text(
                        text = if (isHost) "Close" else "Leave",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/**
 * Full-screen NYXORA Connect overlay sheet for creating rooms, joining rooms, managing connected
 * devices, switching between Browse Together and Watch Together, and configuring room permissions.
 */
@Composable
fun NyxoraConnectSheet(
    roomState: NyxoraConnectRoomState?,
    localDeviceId: String,
    deviceDisplayName: String,
    connectionStatus: SyncConnectionStatus,
    isFollowingPaused: Boolean,
    videoCompatibility: VideoCompatibilityState,
    lastSyncError: String?,
    isPrivateMode: Boolean,
    activePageUrl: String,
    onUpdateDeviceName: (String) -> Unit,
    onClearError: () -> Unit,
    onCreateRoom: (
        roomName: String,
        mode: RoomSyncMode,
        permissions: RoomPermissionsConfig,
        customEndpoint: String
    ) -> Unit,
    onJoinRoom: (roomIdOrInvite: String, accessKey: String, customEndpoint: String) -> Unit,
    onLeaveOrCloseRoom: () -> Unit,
    onUpdateRoomConfig: (
        roomName: String?,
        mode: RoomSyncMode?,
        isSyncActive: Boolean?,
        permissions: RoomPermissionsConfig?
    ) -> Unit,
    onSetFollowingPaused: (Boolean) -> Unit,
    onManualResync: () -> Unit,
    onBroadcastCurrentUrlNow: () -> Unit,
    onHostPlaybackControl: (play: Boolean, seekDeltaSec: Double?) -> Unit,
    onRemoveParticipant: (String) -> Unit,
    onTransferHost: (String) -> Unit,
    onSimulateNetworkDrop: () -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nyxora_connect_sheet"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("connect_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Icon(
                        imageVector = Icons.Outlined.CastConnected,
                        contentDescription = null,
                        tint = NyxoraElectricBlue,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "NYXORA Connect",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Realtime Multi-Device Room Sync & Watch Together",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                StatusPillBadge(status = connectionStatus)
            }

            Spacer(Modifier.height(12.dp))

            // Private Mode Isolation Warning
            if (isPrivateMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = NyxoraVibrantViolet.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = NyxoraVibrantViolet
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Incognito Isolation Active: Private tabs, cookies, and private history are never transmitted or synchronized with any room.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Error Banner if any
            if (!lastSyncError.isNullOrBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .testTag("connect_error_banner"),
                    shape = MaterialTheme.shapes.medium,
                    color = NyxoraDangerRed.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = NyxoraDangerRed
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = lastSyncError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onClearError) {
                            Icon(Icons.Outlined.Close, contentDescription = "Dismiss error")
                        }
                    }
                }
            }

            if (roomState == null) {
                NyxoraConnectLobbyContent(
                    deviceDisplayName = deviceDisplayName,
                    onUpdateDeviceName = onUpdateDeviceName,
                    onCreateRoom = onCreateRoom,
                    onJoinRoom = onJoinRoom
                )
            } else {
                NyxoraConnectActiveRoomContent(
                    context = context,
                    room = roomState,
                    localDeviceId = localDeviceId,
                    connectionStatus = connectionStatus,
                    isFollowingPaused = isFollowingPaused,
                    videoCompatibility = videoCompatibility,
                    activePageUrl = activePageUrl,
                    onUpdateRoomConfig = onUpdateRoomConfig,
                    onSetFollowingPaused = onSetFollowingPaused,
                    onManualResync = onManualResync,
                    onBroadcastCurrentUrlNow = onBroadcastCurrentUrlNow,
                    onHostPlaybackControl = onHostPlaybackControl,
                    onRemoveParticipant = onRemoveParticipant,
                    onTransferHost = onTransferHost,
                    onSimulateNetworkDrop = onSimulateNetworkDrop,
                    onLeaveOrCloseRoom = onLeaveOrCloseRoom,
                    onToast = onToast
                )
            }
        }
    }
}

@Composable
private fun StatusPillBadge(status: SyncConnectionStatus) {
    val badgeColor = when (status) {
        SyncConnectionStatus.SYNCED, SyncConnectionStatus.CONNECTED -> NyxoraSuccessGreen
        SyncConnectionStatus.SYNCHRONIZING, SyncConnectionStatus.CONNECTING -> NyxoraElectricBlue
        SyncConnectionStatus.RECONNECTING, SyncConnectionStatus.PAUSED_FOLLOWING -> NyxoraWarningAmber
        SyncConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = badgeColor.copy(alpha = 0.16f),
        modifier = Modifier.testTag("connect_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = badgeColor
            )
        }
    }
}

@Composable
private fun NyxoraConnectLobbyContent(
    deviceDisplayName: String,
    onUpdateDeviceName: (String) -> Unit,
    onCreateRoom: (String, RoomSyncMode, RoomPermissionsConfig, String) -> Unit,
    onJoinRoom: (String, String, String) -> Unit
) {
    var editedDeviceName by rememberSaveable(deviceDisplayName) { mutableStateOf(deviceDisplayName) }
    var roomNameInput by rememberSaveable { mutableStateOf("NYXORA Sync Room") }
    var selectedMode by rememberSaveable { mutableStateOf(RoomSyncMode.BROWSE_TOGETHER) }
    var maxParticipants by rememberSaveable { mutableIntStateOf(5) }
    var syncUrls by rememberSaveable { mutableStateOf(true) }
    var syncBackForward by rememberSaveable { mutableStateOf(true) }
    var syncTabSelect by rememberSaveable { mutableStateOf(false) }
    var syncNewPages by rememberSaveable { mutableStateOf(false) }
    var allowParticipantPlayback by rememberSaveable { mutableStateOf(false) }
    var customRelayEndpoint by rememberSaveable { mutableStateOf("") }

    var joinRoomInput by rememberSaveable { mutableStateOf("") }
    var joinAccessKeyInput by rememberSaveable { mutableStateOf("") }
    var joinEndpointInput by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("connect_lobby_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Create Room Card (Primary Action)
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Create a Room",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = roomNameInput,
                        onValueChange = { roomNameInput = it },
                        label = { Text("Room Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_room_name_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mode in RoomSyncMode.entries) {
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("connect_mode_chip_${mode.name.lowercase()}")
                            )
                        }
                    }
                    Text(
                        text = selectedMode.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            onCreateRoom(
                                roomNameInput,
                                selectedMode,
                                RoomPermissionsConfig(
                                    maxParticipants = maxParticipants,
                                    isRoomLocked = false,
                                    syncUrlNavigation = syncUrls,
                                    syncBackForward = syncBackForward,
                                    syncTabSelection = syncTabSelect,
                                    syncNewlyOpenedPages = syncNewPages,
                                    allowParticipantPlaybackControl = allowParticipantPlayback
                                ),
                                customRelayEndpoint
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_create_room_button")
                    ) {
                        Icon(Icons.Outlined.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create ${selectedMode.label} Room")
                    }

                    Column {
                        Text(
                            text = "Participant Limit: $maxParticipants devices (Default: 5)",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = maxParticipants.toFloat(),
                            onValueChange = { maxParticipants = it.roundToInt().coerceIn(2, 10) },
                            valueRange = 2f..10f,
                            steps = 7
                        )
                    }

                    ConnectOptionToggleRow(
                        title = "Synchronize URL Navigation",
                        subtitle = "Share current URL and page transitions with participants",
                        checked = syncUrls,
                        onCheckedChange = { syncUrls = it }
                    )
                    ConnectOptionToggleRow(
                        title = "Synchronize Back & Forward",
                        subtitle = "Mirror host Back and Forward navigation actions",
                        checked = syncBackForward,
                        onCheckedChange = { syncBackForward = it }
                    )
                    ConnectOptionToggleRow(
                        title = "Synchronize Selected-Tab Navigation",
                        subtitle = "Navigate participants when host switches active normal tabs",
                        checked = syncTabSelect,
                        onCheckedChange = { syncTabSelect = it }
                    )
                    ConnectOptionToggleRow(
                        title = "Auto-Sync Newly Opened Pages",
                        subtitle = "Automatically synchronize newly opened tabs/pages",
                        checked = syncNewPages,
                        onCheckedChange = { syncNewPages = it }
                    )
                    if (selectedMode == RoomSyncMode.WATCH_TOGETHER) {
                        ConnectOptionToggleRow(
                            title = "Allow Participant Playback Controls",
                            subtitle = "Permit participants to trigger Play, Pause, and Seek",
                            checked = allowParticipantPlayback,
                            onCheckedChange = { allowParticipantPlayback = it }
                        )
                    }

                    OutlinedTextField(
                        value = customRelayEndpoint,
                        onValueChange = { customRelayEndpoint = it },
                        label = { Text("Optional Cloud WSS Relay URL (leave empty for Direct LAN/Local WS)") },
                        placeholder = { Text("wss://relay.example.com/nyxora") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 3. Join Room Card
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Join an Existing Room",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = joinRoomInput,
                        onValueChange = { input ->
                            joinRoomInput = input
                            // Auto-populate key & endpoint if user pasted a full invitation link
                            val parsed = NyxoraConnectSecurity.parseRoomInviteInput(input)
                            if (parsed != null) {
                                if (parsed.accessKey.isNotBlank()) {
                                    joinAccessKeyInput = parsed.accessKey
                                }
                                if (parsed.endpointUrl.isNotBlank()) {
                                    joinEndpointInput = parsed.endpointUrl
                                }
                            }
                        },
                        label = { Text("Room ID (NYX-XXXX-XXXX) or Invitation Link") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_join_room_input")
                    )

                    OutlinedTextField(
                        value = joinAccessKeyInput,
                        onValueChange = { joinAccessKeyInput = it },
                        label = { Text("Room Access Key (auto-filled from invite link)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_join_key_input")
                    )

                    OutlinedTextField(
                        value = joinEndpointInput,
                        onValueChange = { joinEndpointInput = it },
                        label = { Text("Host WebSocket Endpoint (auto-filled from invite link)") },
                        placeholder = { Text("ws://192.168.1.x:port/nyxora") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            onJoinRoom(joinRoomInput, joinAccessKeyInput, joinEndpointInput)
                        },
                        enabled = joinRoomInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_join_room_button")
                    ) {
                        Icon(Icons.Outlined.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Join Room")
                    }
                }
            }
        }

        // 3. Device Identity Card
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Your Device Name",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = editedDeviceName,
                        onValueChange = {
                            editedDeviceName = it
                            onUpdateDeviceName(it)
                        },
                        label = { Text("Visible to room members") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_device_name_input")
                    )
                }
            }
        }

        // 4. Security & Compatibility Scope Card
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = NyxoraElectricBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Privacy, Security & Compatibility Scope",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "• Zero Credential Leakage: Passwords, cookies, form inputs, auth tokens, and Private/Incognito tabs are never transmitted.\n" +
                            "• Cryptographic Room Auth: Every WebSocket frame is signed with HMAC-SHA256 using the room's ephemeral Access Key.\n" +
                            "• Watch Together Scope: Synchronizes standard HTML5 <video> players where allowed by the website. DRM-protected streams (Widevine/EME) and cross-origin restricted embeds are never bypassed and automatically offer Synchronized URL Navigation as a fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NyxoraConnectActiveRoomContent(
    context: Context,
    room: NyxoraConnectRoomState,
    localDeviceId: String,
    connectionStatus: SyncConnectionStatus,
    isFollowingPaused: Boolean,
    videoCompatibility: VideoCompatibilityState,
    activePageUrl: String,
    onUpdateRoomConfig: (String?, RoomSyncMode?, Boolean?, RoomPermissionsConfig?) -> Unit,
    onSetFollowingPaused: (Boolean) -> Unit,
    onManualResync: () -> Unit,
    onBroadcastCurrentUrlNow: () -> Unit,
    onHostPlaybackControl: (Boolean, Double?) -> Unit,
    onRemoveParticipant: (String) -> Unit,
    onTransferHost: (String) -> Unit,
    onSimulateNetworkDrop: () -> Unit,
    onLeaveOrCloseRoom: () -> Unit,
    onToast: (String) -> Unit
) {
    val isHost = room.hostDeviceId == localDeviceId
    val inviteLink = remember(room.roomId, room.accessKey, room.endpointUrl, room.roomName) {
        NyxoraConnectSecurity.buildRoomInviteLink(
            roomId = room.roomId,
            accessKey = room.accessKey,
            endpointUrl = room.endpointUrl,
            roomName = room.roomName
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Room Header & Invitation Controls
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = room.roomName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isHost) "Role: Room Host (Source of Truth)" else "Role: Participant",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isHost) NyxoraElectricBlue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (room.permissions.isRoomLocked) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "Room Locked",
                                tint = NyxoraWarningAmber
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Room ID: ${room.roomId}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.testTag("connect_active_room_id")
                            )
                            Text(
                                text = "Access Key: ${room.accessKey}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Endpoint: ${room.endpointUrl}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NYXORA Room Invite", inviteLink))
                                onToast("Room invitation link copied to clipboard")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("connect_copy_invite_button")
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Invite")
                        }

                        OutlinedButton(
                            onClick = {
                                val shareText = "Join my NYXORA Connect room (${room.roomName})\n" +
                                    "Room ID: ${room.roomId}\n" +
                                    "Access Key: ${room.accessKey}\n" +
                                    "Invite Link: $inviteLink"
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "NYXORA Connect Room Invite")
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "Share Room Invitation")
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("connect_share_invite_button")
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }

                        OutlinedButton(
                            onClick = onLeaveOrCloseRoom,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NyxoraDangerRed),
                            modifier = Modifier.testTag("connect_header_leave_button")
                        ) {
                            Text(if (isHost) "Close" else "Leave")
                        }
                    }
                }
            }
        }

        // 2. Active Mode & Live Synchronization Control Card
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Synchronization Mode & Controls",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mode in RoomSyncMode.entries) {
                            FilterChip(
                                selected = room.mode == mode,
                                enabled = isHost,
                                onClick = {
                                    if (isHost) {
                                        onUpdateRoomConfig(null, mode, null, null)
                                    }
                                },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("connect_active_mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    if (room.currentSyncedUrl.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = NyxoraElectricBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Synchronized URL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = room.currentSyncedUrl,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("connect_synced_url_text")
                                    )
                                }
                            }
                        }
                    }

                    if (isHost) {
                        ConnectOptionToggleRow(
                            title = "Host Synchronization Active",
                            subtitle = if (room.isSyncActive) {
                                "Broadcasting ${room.mode.label} events to all connected devices"
                            } else {
                                "Synchronization is paused; participants stay in room without updates"
                            },
                            checked = room.isSyncActive,
                            onCheckedChange = { active ->
                                onUpdateRoomConfig(null, null, active, null)
                            }
                        )

                        OutlinedButton(
                            onClick = onBroadcastCurrentUrlNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_broadcast_url_button")
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sync Current Active Tab URL Now")
                        }
                    } else {
                        ConnectOptionToggleRow(
                            title = "Follow Host Synchronization",
                            subtitle = if (!isFollowingPaused) {
                                "Automatically following host navigation and playback"
                            } else {
                                "Following paused — browse independently without leaving the room"
                            },
                            checked = !isFollowingPaused,
                            onCheckedChange = { follow ->
                                onSetFollowingPaused(!follow)
                            }
                        )

                        Button(
                            onClick = onManualResync,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_manual_resync_button")
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Manual Resync with Host")
                        }
                    }

                    // Watch Together Dedicated Controls & Player Compatibility Indicator
                    if (room.mode == RoomSyncMode.WATCH_TOGETHER) {
                        HorizontalDivider()
                        Text(
                            text = "Watch Together Media Player Status",
                            style = MaterialTheme.typography.titleSmall
                        )
                        val compatColor = if (videoCompatibility.supportsDirectSync) {
                            NyxoraSuccessGreen
                        } else {
                            NyxoraWarningAmber
                        }
                        Text(
                            text = videoCompatibility.label,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = compatColor,
                            modifier = Modifier.testTag("connect_video_compat_text")
                        )

                        val pb = room.playbackState
                        if (pb != null) {
                            Text(
                                text = "Host Playback: ${if (pb.isPlaying) "Playing" else "Paused"} at ${formatSeconds(pb.positionSeconds)} / ${formatSeconds(pb.durationSeconds)} (${pb.playbackRate}x)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("connect_playback_state_text")
                            )
                        }

                        val canControlPlayback = isHost || room.permissions.allowParticipantPlaybackControl
                        if (canControlPlayback) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onHostPlaybackControl(pb?.isPlaying ?: true, -10.0) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Replay10, contentDescription = "Rewind 10s", modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("-10s")
                                }
                                Button(
                                    onClick = {
                                        val nextPlay = !(pb?.isPlaying ?: false)
                                        onHostPlaybackControl(nextPlay, null)
                                    },
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .testTag("connect_watch_play_pause_button")
                                ) {
                                    val isPlaying = pb?.isPlaying == true
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isPlaying) "Pause All" else "Play All")
                                }
                                OutlinedButton(
                                    onClick = { onHostPlaybackControl(pb?.isPlaying ?: true, 10.0) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Forward10, contentDescription = "Forward 10s", modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("+10s")
                                }
                            }
                        }

                        if (!videoCompatibility.supportsDirectSync) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = NyxoraWarningAmber.copy(alpha = 0.14f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Why Direct Player Sync Is Unavailable Here:",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = NyxoraWarningAmber
                                    )
                                    Text(
                                        text = "This page either uses DRM-protected media (Widevine/EME), a cross-origin restricted iframe embed, or has no active HTML5 <video> element. NYXORA respects DRM and website access controls. Use Synchronized URL Navigation below so all devices open the same video URL together.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (isHost && activePageUrl.startsWith("http")) {
                                        OutlinedButton(
                                            onClick = onBroadcastCurrentUrlNow,
                                            modifier = Modifier.testTag("connect_fallback_url_sync_button")
                                        ) {
                                            Text("Use Synchronized URL Navigation Fallback")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Connected Devices List
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_devices_list")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Connected Devices (${room.members.size}/${room.permissions.maxParticipants})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    for (member in room.members) {
                        ConnectedDeviceRow(
                            member = member,
                            isCurrentDevice = member.deviceId == localDeviceId,
                            canManageMember = isHost && member.deviceId != localDeviceId,
                            onTransferHost = { onTransferHost(member.deviceId) },
                            onRemoveParticipant = { onRemoveParticipant(member.deviceId) }
                        )
                    }
                }
            }
        }

        // 4. Room Permissions & Settings (Host Editable, Participant Read-Only)
        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isHost) "Room Settings & Access Controls" else "Room Permissions (Managed by Host)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val perms = room.permissions
                    ConnectOptionToggleRow(
                        title = "Lock Room",
                        subtitle = "Prevent any additional devices from joining this room",
                        checked = perms.isRoomLocked,
                        enabled = isHost,
                        onCheckedChange = { locked ->
                            onUpdateRoomConfig(null, null, null, perms.copy(isRoomLocked = locked))
                        }
                    )
                    ConnectOptionToggleRow(
                        title = "Synchronize URL Navigation",
                        subtitle = "Navigate participants when host loads a new URL",
                        checked = perms.syncUrlNavigation,
                        enabled = isHost,
                        onCheckedChange = { v ->
                            onUpdateRoomConfig(null, null, null, perms.copy(syncUrlNavigation = v))
                        }
                    )
                    ConnectOptionToggleRow(
                        title = "Synchronize Back & Forward",
                        subtitle = "Mirror host Back and Forward actions",
                        checked = perms.syncBackForward,
                        enabled = isHost,
                        onCheckedChange = { v ->
                            onUpdateRoomConfig(null, null, null, perms.copy(syncBackForward = v))
                        }
                    )
                    ConnectOptionToggleRow(
                        title = "Synchronize Selected-Tab Navigation",
                        subtitle = "Sync URL when host switches between open normal tabs",
                        checked = perms.syncTabSelection,
                        enabled = isHost,
                        onCheckedChange = { v ->
                            onUpdateRoomConfig(null, null, null, perms.copy(syncTabSelection = v))
                        }
                    )
                    ConnectOptionToggleRow(
                        title = "Auto-Sync Newly Opened Pages",
                        subtitle = "Open synchronized tabs when host opens a new page",
                        checked = perms.syncNewlyOpenedPages,
                        enabled = isHost,
                        onCheckedChange = { v ->
                            onUpdateRoomConfig(null, null, null, perms.copy(syncNewlyOpenedPages = v))
                        }
                    )
                    ConnectOptionToggleRow(
                        title = "Allow Participant Playback Controls",
                        subtitle = "Allow participants to Play, Pause, and Seek in Watch Together",
                        checked = perms.allowParticipantPlaybackControl,
                        enabled = isHost,
                        onCheckedChange = { v ->
                            onUpdateRoomConfig(null, null, null, perms.copy(allowParticipantPlaybackControl = v))
                        }
                    )

                    OutlinedButton(
                        onClick = onSimulateNetworkDrop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_test_reconnect_button")
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test Reconnection & State Recovery")
                    }
                }
            }
        }

        // 5. Leave / Close Room Button
        item {
            Button(
                onClick = onLeaveOrCloseRoom,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NyxoraDangerRed,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_leave_or_close_button")
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isHost) "Close Room for All Devices" else "Leave Room")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ConnectedDeviceRow(
    member: ConnectedDeviceMember,
    isCurrentDevice: Boolean,
    canManageMember: Boolean,
    onTransferHost: () -> Unit,
    onRemoveParticipant: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (member.role == RoomRole.HOST) 1.dp else 0.dp,
                color = if (member.role == RoomRole.HOST) NyxoraElectricBlue.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isCurrentDevice) "${member.deviceName} (You)" else member.deviceName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = member.role.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (member.role == RoomRole.HOST) NyxoraElectricBlue else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Status: ${member.status.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (canManageMember) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onTransferHost,
                        modifier = Modifier.testTag("connect_transfer_host_${member.deviceId}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SwapHoriz,
                            contentDescription = "Transfer Host Role",
                            tint = NyxoraElectricBlue
                        )
                    }
                    IconButton(
                        onClick = onRemoveParticipant,
                        modifier = Modifier.testTag("connect_remove_member_${member.deviceId}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PersonRemove,
                            contentDescription = "Remove Participant",
                            tint = NyxoraDangerRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectOptionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun formatSeconds(sec: Double): String {
    if (sec.isNaN() || sec <= 0.0) return "0:00"
    val total = sec.roundToInt()
    val mins = total / 60
    val rem = total % 60
    return "%d:%02d".format(mins, rem)
}
