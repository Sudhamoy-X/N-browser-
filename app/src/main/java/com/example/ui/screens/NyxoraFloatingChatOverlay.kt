package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.domain.connect.NyxoraConnectRoomState
import com.example.domain.connect.RoomChatMessage
import com.example.domain.connect.RoomRole
import com.example.domain.connect.SyncConnectionStatus
import com.example.ui.theme.NyxoraDangerRed
import com.example.ui.theme.NyxoraElectricBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Floating Chat Overlay for NYXORA Connect (Requirement 6).
 *
 * - Renders only while connected to a room (`roomState != null && connectionStatus != DISCONNECTED`).
 * - Positioned at the screen edge so it does not obstruct webpage browsing or video playback.
 * - Movable via drag gestures, dismissible to a minimal edge tab or status banner, and easy to reopen.
 * - Automatically collapses the expanded chat panel when entering fullscreen video playback while
 *   keeping the compact edge indicator accessible.
 */
@Composable
fun NyxoraFloatingChatOverlay(
    roomState: NyxoraConnectRoomState?,
    localDeviceId: String,
    connectionStatus: SyncConnectionStatus,
    chatMessages: List<RoomChatMessage>,
    unreadChatCount: Int,
    isExpanded: Boolean,
    isBubbleDismissed: Boolean,
    isFullscreenVideo: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDismissBubbleChange: (Boolean) -> Unit,
    onSendMessage: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    if (roomState == null || connectionStatus.isTerminalOrDisconnected) {
        return
    }

    val isHost = roomState.hostDeviceId == localDeviceId
    val canSendChat = isHost || roomState.permissions.canParticipantSendChat

    // Automatically hide expanded chat overlay when entering fullscreen video while keeping indicator accessible
    LaunchedEffect(isFullscreenVideo) {
        if (isFullscreenVideo && isExpanded) {
            onExpandedChange(false)
        }
    }

    var offsetX by rememberSaveable(roomState.roomId) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(roomState.roomId) { mutableFloatStateOf(0f) }
    var draftMessage by rememberSaveable(roomState.roomId) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size, isExpanded) {
        if (isExpanded && chatMessages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(chatMessages.lastIndex) }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .displayCutoutPadding()
            .testTag("nyxora_floating_chat_container")
    ) {
        val density = LocalDensity.current
        val maxDragX = with(density) { (maxWidth - 72.dp).toPx().coerceAtLeast(0f) }
        val maxDragY = with(density) { (maxHeight - 180.dp).toPx().coerceAtLeast(0f) }

        if (isBubbleDismissed && !isExpanded) {
            // Minimal edge reopen tab when the floating chat bubble has been dismissed
            Surface(
                onClick = {
                    onDismissBubbleChange(false)
                    onExpandedChange(true)
                },
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, NyxoraElectricBlue.copy(alpha = 0.6f)),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(0, offsetY.roundToInt()) }
                    .semantics { contentDescription = "Reopen Room Chat" }
                    .testTag("nyxora_floating_chat_reopen_tab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        tint = NyxoraElectricBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    if (unreadChatCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = unreadChatCount.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = NyxoraElectricBlue
                        )
                    }
                }
            }
        } else {
            // Edge-docked movable container for the floating chat indicator and compact chat overlay
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .offset {
                        IntOffset(
                            x = offsetX.coerceIn(-maxDragX, 0f).roundToInt(),
                            y = offsetY.coerceIn(-maxDragY / 2f, maxDragY / 2f).roundToInt()
                        )
                    }
            ) {
                if (isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                        border = BorderStroke(1.dp, NyxoraElectricBlue.copy(alpha = 0.45f)),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .widthIn(min = 250.dp, max = 300.dp)
                            .heightIn(min = 220.dp, max = 330.dp)
                            .testTag("nyxora_chat_panel")
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Movable header bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            offsetX = (offsetX + dragAmount.x).coerceIn(-maxDragX, 0f)
                                            offsetY = (offsetY + dragAmount.y).coerceIn(-maxDragY / 2f, maxDragY / 2f)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("chat_panel_drag_handle"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DragIndicator,
                                        contentDescription = "Move chat overlay",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                                        contentDescription = null,
                                        tint = NyxoraElectricBlue,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = "Room Chat (${roomState.members.size})",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val chatStatusText = when {
                                            connectionStatus == SyncConnectionStatus.RECONNECTING ||
                                                connectionStatus == SyncConnectionStatus.CONNECTING -> "Reconnecting…"
                                            !canSendChat -> "Chat disabled by host"
                                            else -> "Live"
                                        }
                                        val chatStatusColor = when {
                                            connectionStatus == SyncConnectionStatus.RECONNECTING ||
                                                connectionStatus == SyncConnectionStatus.CONNECTING -> Color(0xFFFFB300)
                                            !canSendChat -> NyxoraDangerRed
                                            else -> Color(0xFF00E676)
                                        }
                                        Text(
                                            text = chatStatusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = chatStatusColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.testTag("chat_panel_connection_status")
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onExpandedChange(false) },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("chat_panel_minimize_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Remove,
                                            contentDescription = "Minimize chat panel",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onExpandedChange(false)
                                            onDismissBubbleChange(true)
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("chat_panel_close_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Dismiss chat overlay",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Messages list
                            if (chatMessages.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        .testTag("chat_empty_state"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No messages yet. Say hello to the room!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("chat_messages_list"),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(chatMessages, key = { it.messageId }) { msg ->
                                        val isLocalSender = msg.senderDeviceId == localDeviceId
                                        ChatMessageBubbleItem(
                                            message = msg,
                                            isLocalSender = isLocalSender
                                        )
                                    }
                                }
                            }

                            // Compact message input row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = draftMessage,
                                    onValueChange = { if (canSendChat) draftMessage = it.take(500) },
                                    enabled = canSendChat,
                                    placeholder = {
                                        Text(
                                            text = if (canSendChat) "Message room…" else "Chat disabled by host",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (canSendChat) {
                                                val trimmed = draftMessage.trim()
                                                if (trimmed.isNotEmpty() && onSendMessage(trimmed)) {
                                                    draftMessage = ""
                                                }
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("chat_message_input")
                                )

                                IconButton(
                                    onClick = {
                                        if (canSendChat) {
                                            val trimmed = draftMessage.trim()
                                            if (trimmed.isNotEmpty() && onSendMessage(trimmed)) {
                                                draftMessage = ""
                                            }
                                        }
                                    },
                                    enabled = canSendChat && draftMessage.isNotBlank(),
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (canSendChat && draftMessage.isNotBlank()) {
                                                NyxoraElectricBlue
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                        .semantics { contentDescription = "Send chat message" }
                                        .testTag("chat_send_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Send,
                                        contentDescription = null,
                                        tint = if (canSendChat && draftMessage.isNotBlank()) {
                                            Color.White
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Compact edge-positioned floating chat indicator pill
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (unreadChatCount > 0) NyxoraElectricBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    ),
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(-maxDragX, 0f)
                                offsetY = (offsetY + dragAmount.y).coerceIn(-maxDragY / 2f, maxDragY / 2f)
                            }
                        }
                        .clickable { onExpandedChange(!isExpanded) }
                        .semantics { contentDescription = "Toggle Room Chat Overlay" }
                        .testTag("nyxora_floating_chat_bubble")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Chat,
                            contentDescription = null,
                            tint = NyxoraElectricBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        if (unreadChatCount > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(NyxoraElectricBlue)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                    .testTag("nyxora_floating_chat_badge"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = unreadChatCount.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        } else if (!isFullscreenVideo) {
                            Text(
                                text = "Chat",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = { onDismissBubbleChange(true) },
                            modifier = Modifier
                                .size(22.dp)
                                .testTag("nyxora_floating_chat_dismiss")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss floating chat indicator",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubbleItem(
    message: RoomChatMessage,
    isLocalSender: Boolean
) {
    val timeLabel = remember(message.timestampMillis) {
        runCatching {
            SimpleDateFormat("HH:mm", Locale.US).format(Date(message.timestampMillis))
        }.getOrDefault("")
    }
    val roleSuffix = if (message.senderRole == RoomRole.HOST) " (Host)" else ""
    val headerLabel = if (isLocalSender) "You$roleSuffix" else "${message.senderName}$roleSuffix"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_message_item_${message.messageId}"),
        horizontalAlignment = if (isLocalSender) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = headerLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (isLocalSender) NyxoraElectricBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (timeLabel.isNotBlank()) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Surface(
            modifier = Modifier.wrapContentHeight(),
            shape = RoundedCornerShape(
                topStart = 10.dp,
                topEnd = 10.dp,
                bottomStart = if (isLocalSender) 10.dp else 2.dp,
                bottomEnd = if (isLocalSender) 2.dp else 10.dp
            ),
            color = if (isLocalSender) {
                NyxoraElectricBlue.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            border = BorderStroke(
                1.dp,
                if (isLocalSender) {
                    NyxoraElectricBlue.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                }
            )
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .wrapContentHeight()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}
