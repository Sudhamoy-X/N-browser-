package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.connect.ConnectedDeviceMember
import com.example.domain.connect.NyxoraConnectRoomState
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
 * Compact, collapsible synchronization indicator displayed beneath the Top Address Bar whenever
 * a NYXORA Connect session is active.
 *
 * - Default compact mode shows a small status icon, connection dot, and room code with a compact
 *   menu for Pause/Resume, Resync, and Leave.
 * - Expanded mode reveals full host/participant counts and quick actions, and can be manually
 *   hidden or automatically collapsed when scrolling or entering fullscreen video.
 */
@Composable
fun NyxoraConnectStatusBanner(
    roomState: NyxoraConnectRoomState,
    localDeviceId: String,
    connectionStatus: SyncConnectionStatus,
    isFollowingPaused: Boolean,
    isPrivateMode: Boolean,
    isExpanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    unreadChatCount: Int = 0,
    onOpenChatOverlay: () -> Unit = {},
    onOpenConnectSheet: () -> Unit,
    onManualResync: () -> Unit,
    onTogglePauseOrSync: () -> Unit,
    onStopOrLeaveImmediate: () -> Unit
) {
    val isHost = roomState.hostDeviceId == localDeviceId
    var showCompactMenu by remember { mutableStateOf(false) }

    val statusColor = when {
        isPrivateMode -> NyxoraVibrantViolet
        !roomState.isSyncActive || isFollowingPaused -> NyxoraWarningAmber
        connectionStatus.isConnectedOrSynced -> NyxoraSuccessGreen
        connectionStatus == SyncConnectionStatus.RECONNECTING || connectionStatus == SyncConnectionStatus.CONNECTING -> NyxoraWarningAmber
        else -> NyxoraDangerRed
    }

    val statusLabel = when {
        isPrivateMode -> "Incognito Isolated (Sync Paused)"
        !roomState.isSyncActive -> "Sharing Paused by Host"
        isFollowingPaused -> "Following Paused"
        !isHost && !roomState.permissions.allowParticipantControls ->
            "Host Only Controls"
        !isHost && connectionStatus.isConnectedOrSynced && roomState.playbackState == null ->
            "Waiting for host media…"
        else -> connectionStatus.label
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nyxora_connect_status_banner"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!isExpanded) {
                // Compact single-line indicator: small status icon, room code, restore/expand button, and compact menu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onOpenConnectSheet)
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CastConnected,
                            contentDescription = "Room Sync Status",
                            tint = statusColor,
                            modifier = Modifier
                                .size(14.dp)
                                .testTag("connect_banner_status_icon")
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = roomState.roomId,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("connect_banner_room_code")
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "• $statusLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("connect_banner_status_text")
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = onOpenChatOverlay,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("connect_banner_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Chat,
                                contentDescription = "Open Room Chat",
                                tint = if (unreadChatCount > 0) NyxoraElectricBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        IconButton(
                            onClick = { onExpandedChange(true) },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("connect_banner_expand_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = "Expand sync status bar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showCompactMenu = true },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("connect_banner_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "Room Sync actions",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            ConnectBannerCompactDropdownMenu(
                                expanded = showCompactMenu,
                                isHost = isHost,
                                isSyncActive = roomState.isSyncActive,
                                isBannerExpanded = false,
                                onDismiss = { showCompactMenu = false },
                                onTogglePauseOrSync = onTogglePauseOrSync,
                                onManualResync = onManualResync,
                                onOpenChatOverlay = onOpenChatOverlay,
                                onOpenConnectSheet = onOpenConnectSheet,
                                onToggleExpand = { onExpandedChange(true) },
                                onStopOrLeaveImmediate = onStopOrLeaveImmediate
                            )
                        }
                    }
                }
            } else {
                // Expanded status bar: shows full details, quick actions, and a Hide button to collapse while browsing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenConnectSheet)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("connect_banner_expanded_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CastConnected,
                            contentDescription = "Room Sync Status",
                            tint = statusColor,
                            modifier = Modifier
                                .size(15.dp)
                                .testTag("connect_banner_status_icon")
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Room Sync • ${if (isHost) "Host" else "Participant"}",
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
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (!isHost) {
                            TextButton(
                                onClick = onManualResync,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("connect_banner_resync_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = "Manual Resync",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Resync", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            TextButton(
                                onClick = onTogglePauseOrSync,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("connect_banner_pause_toggle")
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
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(30.dp)
                                .testTag("connect_banner_stop_button")
                        ) {
                            Text(
                                text = "Leave",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        IconButton(
                            onClick = { onExpandedChange(false) },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("connect_banner_collapse_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExpandLess,
                                contentDescription = "Hide expanded sync status bar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showCompactMenu = true },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("connect_banner_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "Room Sync actions",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            ConnectBannerCompactDropdownMenu(
                                expanded = showCompactMenu,
                                isHost = isHost,
                                isSyncActive = roomState.isSyncActive,
                                isBannerExpanded = true,
                                onDismiss = { showCompactMenu = false },
                                onTogglePauseOrSync = onTogglePauseOrSync,
                                onManualResync = onManualResync,
                                onOpenChatOverlay = onOpenChatOverlay,
                                onOpenConnectSheet = onOpenConnectSheet,
                                onToggleExpand = { onExpandedChange(false) },
                                onStopOrLeaveImmediate = onStopOrLeaveImmediate
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectBannerCompactDropdownMenu(
    expanded: Boolean,
    isHost: Boolean,
    isSyncActive: Boolean,
    isBannerExpanded: Boolean,
    onDismiss: () -> Unit,
    onTogglePauseOrSync: () -> Unit,
    onManualResync: () -> Unit,
    onOpenChatOverlay: () -> Unit = {},
    onOpenConnectSheet: () -> Unit,
    onToggleExpand: () -> Unit,
    onStopOrLeaveImmediate: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("connect_banner_compact_menu")
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = "Room Chat",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onOpenChatOverlay()
            },
            modifier = Modifier.testTag("connect_banner_menu_chat_item")
        )
        if (isHost) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (isSyncActive) "Pause" else "Resume",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isSyncActive) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onDismiss()
                    onTogglePauseOrSync()
                },
                modifier = Modifier.testTag(
                    if (!isBannerExpanded) "connect_banner_pause_toggle" else "connect_banner_menu_pause_item"
                )
            )
        } else {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Resync",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onDismiss()
                    onManualResync()
                },
                modifier = Modifier.testTag(
                    if (!isBannerExpanded) "connect_banner_resync_button" else "connect_banner_menu_resync_item"
                )
            )
        }

        DropdownMenuItem(
            text = {
                Text(
                    text = if (isBannerExpanded) "Hide Status Details" else "Show Status Details",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (isBannerExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onToggleExpand()
            },
            modifier = Modifier.testTag("connect_banner_menu_toggle_expand")
        )

        DropdownMenuItem(
            text = {
                Text(
                    text = "Room Sync Panel",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.CastConnected,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onOpenConnectSheet()
            },
            modifier = Modifier.testTag("connect_banner_menu_open_sheet")
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = {
                Text(
                    text = "Leave",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NyxoraDangerRed
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = NyxoraDangerRed,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onStopOrLeaveImmediate()
            },
            modifier = Modifier.testTag(
                if (!isBannerExpanded) "connect_banner_stop_button" else "connect_banner_menu_leave_item"
            )
        )
    }
}

/**
 * Simple, compact NYXORA Connect screen.
 * One person creates a room as Host; other users join directly using a Room Code.
 * Uses the permanent configured WSS endpoint internally without manual endpoint or key fields.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
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
    shareableInviteUrl: String = "",
    hasUserConsentedToShare: Boolean = true,
    onSetUserShareConsent: (Boolean) -> Unit = {},
    onRotateInviteLink: (Boolean) -> String = { "" },
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
    onSetAllowParticipantControls: (Boolean) -> Unit = {},
    participantControlsDisabledNotice: String? = null,
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
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header: Back button, NYXORA Connect title, and Connection status pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("connect_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.CastConnected,
                            contentDescription = null,
                            tint = NyxoraElectricBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "NYXORA Connect",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("connect_title_text")
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    StatusPillBadge(status = connectionStatus)
                }

                Spacer(Modifier.height(12.dp))

                // Incognito Isolation Notice
                if (isPrivateMode) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = NyxoraVibrantViolet.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = NyxoraVibrantViolet,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Private tabs are isolated and never synchronized with any room.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Error Banner
                if (!lastSyncError.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .testTag("connect_error_banner"),
                        shape = RoundedCornerShape(10.dp),
                        color = NyxoraDangerRed.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    tint = NyxoraDangerRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = lastSyncError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = onClearError,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss error",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (roomState == null) {
                    NyxoraConnectSimpleLobby(
                        onCreateRoom = onCreateRoom,
                        onJoinRoom = onJoinRoom
                    )
                } else {
                    NyxoraConnectSimpleActiveRoom(
                        context = context,
                        room = roomState,
                        localDeviceId = localDeviceId,
                        connectionStatus = connectionStatus,
                        videoCompatibility = videoCompatibility,
                        participantControlsDisabledNotice = participantControlsDisabledNotice,
                        onSetAllowParticipantControls = onSetAllowParticipantControls,
                        onHostPlaybackControl = onHostPlaybackControl,
                        onLeaveOrCloseRoom = onLeaveOrCloseRoom,
                        onToast = onToast
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPillBadge(status: SyncConnectionStatus) {
    val badgeColor = when {
        status.isConnectedOrSynced -> NyxoraSuccessGreen
        status == SyncConnectionStatus.CONNECTING -> NyxoraElectricBlue
        status == SyncConnectionStatus.RECONNECTING || status == SyncConnectionStatus.PAUSED_FOLLOWING -> NyxoraWarningAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = badgeColor.copy(alpha = 0.14f),
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
                color = badgeColor,
                modifier = Modifier.testTag("connect_status_text")
            )
        }
    }
}

@Composable
private fun NyxoraConnectSimpleLobby(
    onCreateRoom: (String, RoomSyncMode, RoomPermissionsConfig, String) -> Unit,
    onJoinRoom: (String, String, String) -> Unit
) {
    var roomCodeInput by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connect_lobby_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Host Section: Create Room
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onCreateRoom(
                                "NYXORA Sync Room",
                                RoomSyncMode.BROWSE_TOGETHER,
                                RoomPermissionsConfig(),
                                ""
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NyxoraElectricBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("connect_create_room_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CastConnected,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Create Room",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }

        // 2. Participant Section: Room Code Input + Join Room Button
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { roomCodeInput = it.uppercase() },
                        label = { Text("Room Code") },
                        placeholder = { Text("NYX-XXXX-XXXX") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_join_room_input")
                    )

                    Button(
                        onClick = {
                            onJoinRoom(roomCodeInput.trim(), "", "")
                        },
                        enabled = roomCodeInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NyxoraElectricBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("connect_join_room_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Join Room",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NyxoraConnectSimpleActiveRoom(
    context: Context,
    room: NyxoraConnectRoomState,
    localDeviceId: String,
    connectionStatus: SyncConnectionStatus,
    videoCompatibility: VideoCompatibilityState,
    participantControlsDisabledNotice: String? = null,
    onSetAllowParticipantControls: (Boolean) -> Unit = {},
    onHostPlaybackControl: (Boolean, Double?) -> Unit,
    onLeaveOrCloseRoom: () -> Unit,
    onToast: (String) -> Unit
) {
    val isHost = room.hostDeviceId == localDeviceId
    val pb = room.playbackState
    val isUnsupportedOrProtected =
        videoCompatibility == VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED ||
            videoCompatibility == VideoCompatibilityState.IFRAME_OR_EMBED_RESTRICTED
    val hasSupportedMedia = !isUnsupportedOrProtected &&
        (videoCompatibility == VideoCompatibilityState.COMPATIBLE_HTML5 || pb != null)
    val canControlPlayback = hasSupportedMedia &&
        (isHost || (room.permissions.allowParticipantControls && room.permissions.allowParticipantPlaybackControl))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 1. Compact Active Room Card: Room Code, Connection Status, Copy Code, Share Code, Leave Room
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Room Code",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = room.roomId,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("connect_active_room_id")
                            )
                        }
                        val roleLabel = if (isHost) "Host" else "Participant"
                        val summaryLabel = if (connectionStatus.isConnectedOrSynced) {
                            "$roleLabel • ${connectionStatus.label}"
                        } else {
                            "$roleLabel • ${connectionStatus.label}"
                        }
                        Text(
                            text = summaryLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connectionStatus.isConnectedOrSynced) NyxoraElectricBlue else NyxoraWarningAmber
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_compact_action_row"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("NYXORA Room Code", room.roomId))
                                onToast("Room code ${room.roomId} copied")
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NyxoraElectricBlue.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = NyxoraElectricBlue.copy(alpha = 0.12f),
                                contentColor = NyxoraElectricBlue
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "Copy Room Code" }
                                .testTag("connect_copy_code_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Copy Code",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "NYXORA Room Code")
                                    putExtra(Intent.EXTRA_TEXT, room.roomId)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share Room Code").apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }.onFailure {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("NYXORA Room Code", room.roomId))
                                    onToast("Room code ${room.roomId} copied")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NyxoraElectricBlue.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = NyxoraElectricBlue.copy(alpha = 0.12f),
                                contentColor = NyxoraElectricBlue
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "Share Room Code" }
                                .testTag("connect_share_code_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Share Code",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = onLeaveOrCloseRoom,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, NyxoraDangerRed.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = NyxoraDangerRed.copy(alpha = 0.12f),
                                contentColor = NyxoraDangerRed
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "Leave Room" }
                                .testTag("connect_leave_or_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Leave Room",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isHost) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_allow_participant_controls_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Allow Participant Controls",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Let connected participants search, navigate, open pages, and control playback",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = room.permissions.allowParticipantControls,
                                onCheckedChange = { onSetAllowParticipantControls(it) },
                                modifier = Modifier
                                    .semantics { contentDescription = "Allow Participant Controls" }
                                    .testTag("connect_allow_participant_controls_switch")
                            )
                        }
                    } else if (!room.permissions.allowParticipantControls || !participantControlsDisabledNotice.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NyxoraWarningAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, NyxoraWarningAmber.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_participant_controls_disabled_notice")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = NyxoraWarningAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = participantControlsDisabledNotice
                                        ?: "Shared controls are disabled by the host — only the host can control shared navigation and playback.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Compact Watch Together Media Session & Controls
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_watch_together_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pb != null && !isUnsupportedOrProtected) {
                        Text(
                            text = "Playback: ${if (pb.isPlaying) "Playing" else "Paused"} at ${formatSeconds(pb.positionSeconds)} / ${formatSeconds(pb.durationSeconds)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("connect_playback_state_text")
                        )
                    } else if (isUnsupportedOrProtected) {
                        Text(
                            text = videoCompatibility.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = NyxoraWarningAmber,
                            modifier = Modifier.testTag("connect_video_compat_text")
                        )
                    } else {
                        Text(
                            text = "Waiting for host to start supported media playback…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("connect_waiting_for_media_text")
                        )
                        Text(
                            text = videoCompatibility.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("connect_video_compat_text")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onHostPlaybackControl(pb?.isPlaying ?: true, -10.0) },
                            enabled = canControlPlayback,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "Skip backward 10 seconds" }
                                .testTag("connect_watch_rewind_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Replay10,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("-10s")
                        }

                        Button(
                            onClick = {
                                val nextPlay = !(pb?.isPlaying ?: false)
                                onHostPlaybackControl(nextPlay, null)
                            },
                            enabled = canControlPlayback,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NyxoraElectricBlue,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = if (pb?.isPlaying == true) "Pause playback" else "Play playback"
                                }
                                .testTag("connect_watch_play_pause_button")
                        ) {
                            val isPlaying = pb?.isPlaying == true
                            Icon(
                                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isPlaying) "Pause" else "Play")
                        }

                        OutlinedButton(
                            onClick = { onHostPlaybackControl(pb?.isPlaying ?: true, 10.0) },
                            enabled = canControlPlayback,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "Skip forward 10 seconds" }
                                .testTag("connect_watch_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Forward10,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("+10s")
                        }
                    }
                }
            }
        }
    }
}

private fun formatSeconds(sec: Double): String {
    if (sec.isNaN() || sec <= 0.0) return "0:00"
    val total = sec.roundToInt()
    val mins = total / 60
    val rem = total % 60
    return "%d:%02d".format(mins, rem)
}
