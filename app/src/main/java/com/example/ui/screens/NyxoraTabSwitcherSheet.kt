package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.BrowserTab

private val TAB_GROUPS = listOf("All", "Default", "Research", "Media", "Work")

@Composable
fun NyxoraTabSwitcherSheet(
    normalTabs: List<BrowserTab>,
    privateTabs: List<BrowserTab>,
    activeNormalTabId: String,
    activePrivateTabId: String?,
    isPrivateMode: Boolean,
    isPrivateUnlocked: Boolean,
    hasRecentlyClosed: Boolean,
    onSwitchMode: (Boolean) -> Unit,
    onUnlockPrivateWithPin: (String) -> Boolean,
    onSelectTab: (tabId: String, isPrivate: Boolean) -> Unit,
    onCreateNewTab: (Boolean, String) -> Unit,
    onDuplicateTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllTabs: (Boolean) -> Unit,
    onReopenClosedTab: () -> Unit,
    onAssignTabGroup: (String, String) -> Unit,
    onOpenInSplitView: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf("All") }
    var showCloseAllConfirm by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    val displayedTabs = remember(isPrivateMode, normalTabs, privateTabs, selectedGroup) {
        val base = if (isPrivateMode) privateTabs else normalTabs
        if (selectedGroup == "All") base else base.filter { it.groupName.equals(selectedGroup, ignoreCase = true) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Top Switcher Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isPrivateMode,
                        onClick = { onSwitchMode(false) },
                        label = { Text("Normal (${normalTabs.size})") },
                        modifier = Modifier.testTag("tab_switcher_normal_mode")
                    )
                    FilterChip(
                        selected = isPrivateMode,
                        onClick = { onSwitchMode(true) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("Private (${privateTabs.size})") },
                        modifier = Modifier.testTag("tab_switcher_private_mode")
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isPrivateMode && hasRecentlyClosed) {
                        IconButton(
                            onClick = onReopenClosedTab,
                            modifier = Modifier.testTag("reopen_closed_tab_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Restore,
                                contentDescription = "Reopen Recently Closed Tab"
                            )
                        }
                    }
                    IconButton(
                        onClick = { showCloseAllConfirm = true },
                        modifier = Modifier.testTag("close_all_tabs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Close All Tabs"
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_tab_switcher_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Tab Switcher"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tier 3 Signature Feature: Tab Group Filter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (group in TAB_GROUPS) {
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                        label = { Text(group) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isPrivateMode && !isPrivateUnlocked) {
                // Tier 3 Private Tabs PIN / Security Lock Screen
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Private Tabs Locked",
                                style = MaterialTheme.typography.titleLarge
                            )
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = {
                                    pinInput = it
                                    pinError = false
                                },
                                label = { Text("Enter Private PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                isError = pinError
                            )
                            if (pinError) {
                                Text(
                                    text = "Incorrect PIN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Button(
                                onClick = {
                                    if (!onUnlockPrivateWithPin(pinInput)) {
                                        pinError = true
                                    } else {
                                        pinInput = ""
                                    }
                                }
                            ) {
                                Text("Unlock Private Session")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 165.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayedTabs, key = { it.id }) { tab ->
                        val isSelected = if (isPrivateMode) {
                            tab.id == activePrivateTabId
                        } else {
                            tab.id == activeNormalTabId
                        }
                        TabCompactCard(
                            tab = tab,
                            isSelected = isSelected,
                            onSelect = {
                                onSelectTab(tab.id, tab.isPrivate)
                                onDismiss()
                            },
                            onDuplicate = { onDuplicateTab(tab.id) },
                            onClose = { onCloseTab(tab.id) },
                            onCycleGroup = {
                                val selectableGroups = TAB_GROUPS.drop(1)
                                val nextIdx = (selectableGroups.indexOf(tab.groupName) + 1) % selectableGroups.size
                                onAssignTabGroup(tab.id, selectableGroups[nextIdx])
                            },
                            onSplitView = {
                                onOpenInSplitView(tab.id)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            // Bottom New Tab Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val group = if (selectedGroup == "All") "Default" else selectedGroup
                        onCreateNewTab(isPrivateMode, group)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("new_tab_button"),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isPrivateMode) "New Private Tab" else "New Tab")
                }
            }
        }
    }

    if (showCloseAllConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseAllConfirm = false },
            title = { Text(if (isPrivateMode) "Wipe All Private Tabs?" else "Close All Normal Tabs?") },
            text = {
                Text(
                    if (isPrivateMode) {
                        "This will immediately close all private tabs and wipe session cookies and DOM storage."
                    } else {
                        "All open normal tabs will be closed. You can reopen recently closed tabs from the tab switcher."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloseAllConfirm = false
                        onCloseAllTabs(isPrivateMode)
                    }
                ) {
                    Text("Close All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TabCompactCard(
    tab: BrowserTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onClose: () -> Unit,
    onCycleGroup: () -> Unit,
    onSplitView: () -> Unit
) {
    val borderColor = when {
        isSelected && tab.isPrivate -> MaterialTheme.colorScheme.secondary
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Card Header: Favicon initial + Title + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (tab.isPrivate) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = tab.title.firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close tab ${tab.title}",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Middle Preview Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (tab.isHomePage) "nyxora://home" else tab.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Footer: Group Tag + Duplicate + Split View
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(onClick = onCycleGroup)
                ) {
                    Text(
                        text = tab.groupName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!tab.isPrivate) {
                        IconButton(
                            onClick = onSplitView,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VerticalSplit,
                                contentDescription = "Open in Split View",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onDuplicate,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Duplicate Tab",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
