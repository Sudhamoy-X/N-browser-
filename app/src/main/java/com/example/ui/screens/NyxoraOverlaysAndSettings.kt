package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FullscreenOrientationMode
import com.example.data.local.NyxoraSettingsState
import com.example.data.local.SearchEngine
import com.example.data.local.ThemeMode
import com.example.data.local.VideoAspectMode
import com.example.data.model.BookmarkItem
import com.example.data.model.CustomFilterRule
import com.example.data.model.DownloadRecord
import com.example.data.model.HistoryItem
import com.example.data.model.SiteSettingItem
import com.example.domain.browser.DownloadEngine
import com.example.domain.browser.EngineCapabilityMatrix
import com.example.domain.browser.ReaderArticle
import kotlin.math.roundToInt

// --- BOOKMARKS SHEET ---

@Composable
fun NyxoraBookmarksSheet(
    bookmarks: List<BookmarkItem>,
    onSelectUrl: (String) -> Unit,
    onAddBookmark: (title: String, url: String, folder: String, isShortcut: Boolean) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }

    val folders = remember(bookmarks) {
        listOf("All") + bookmarks.map { it.folder.ifBlank { "General" } }.distinct()
    }

    val filtered = remember(bookmarks, searchQuery, selectedFolder) {
        bookmarks.filter { bm ->
            val matchesFolder = selectedFolder == "All" || bm.folder.equals(selectedFolder, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() ||
                bm.title.contains(searchQuery, ignoreCase = true) ||
                bm.url.contains(searchQuery, ignoreCase = true)
            matchesFolder && matchesQuery
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
                }
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search bookmarks…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (folder in folders.take(5)) {
                    FilterChip(
                        selected = selectedFolder == folder,
                        onClick = { selectedFolder = folder },
                        label = { Text(folder) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No bookmarks match your filter.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { bm ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectUrl(bm.url)
                                    onDismiss()
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkBorder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bm.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${bm.folder} • ${bm.url}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete Bookmark")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var folder by remember { mutableStateOf("General") }
        var pinShortcut by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Bookmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Pin to Homepage Shortcuts")
                        Switch(checked = pinShortcut, onCheckedChange = { pinShortcut = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            onAddBookmark(title, url, folder, pinShortcut)
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// --- HISTORY SHEET ---

@Composable
fun NyxoraHistorySheet(
    history: List<HistoryItem>,
    onSelectUrl: (String) -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onClearBrowsingData: (clearHistory: Boolean, clearCookies: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var searchQuery by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    val filtered = remember(history, searchQuery) {
        if (searchQuery.isBlank()) history else history.filter {
            it.title.contains(searchQuery, ignoreCase = true) || it.url.contains(searchQuery, ignoreCase = true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Text("Browsing History", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Data")
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search history…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No browsing history recorded.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectUrl(item.url)
                                    onDismiss()
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onDeleteHistoryItem(item.id) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Remove History Entry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        var clearHist by remember { mutableStateOf(true) }
        var clearCookies by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Browsing Data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Browsing History")
                        Switch(checked = clearHist, onCheckedChange = { clearHist = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cookies, Cache & Site Storage")
                        Switch(checked = clearCookies, onCheckedChange = { clearCookies = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearBrowsingData(clearHist, clearCookies)
                        showClearDialog = false
                    }
                ) {
                    Text("Clear Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// --- DOWNLOADS SHEET ---

@Composable
fun NyxoraDownloadsSheet(
    downloads: List<DownloadRecord>,
    downloadEngine: DownloadEngine,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Text("Downloads", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(12.dp))
            if (downloads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No downloads yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(downloads, key = { it.id }) { dl ->
                        DownloadItemCard(
                            record = dl,
                            onPause = { downloadEngine.pauseDownload(dl.id) },
                            onResumeOrRetry = { downloadEngine.resumeOrRetryDownload(dl.id) },
                            onCancelOrDelete = { downloadEngine.deleteDownloadRecord(dl.id, true) },
                            onOpen = {
                                val intent = downloadEngine.buildOpenFileIntent(dl)
                                if (intent != null) {
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { onToast("No app installed to open ${dl.mimeType}") }
                                } else {
                                    onToast("File no longer exists on disk")
                                }
                            },
                            onShare = {
                                val intent = downloadEngine.buildShareFileIntent(dl)
                                if (intent != null) {
                                    runCatching { context.startActivity(intent) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    record: DownloadRecord,
    onPause: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onCancelOrDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sizeInfo = if (record.totalBytes > 0) {
                        "${formatBytes(record.downloadedBytes)} / ${formatBytes(record.totalBytes)}"
                    } else {
                        formatBytes(record.downloadedBytes)
                    }
                    val speedInfo = if (record.status == "RUNNING" && record.speedBytesPerSec > 0) {
                        " • ${formatBytes(record.speedBytesPerSec)}/s"
                    } else ""
                    Text(
                        text = "${record.status} • $sizeInfo$speedInfo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    when (record.status) {
                        "RUNNING" -> {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Outlined.Pause, contentDescription = "Pause Download")
                            }
                        }
                        "PAUSED", "FAILED" -> {
                            IconButton(onClick = onResumeOrRetry) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume or Retry Download")
                            }
                        }
                        "COMPLETED" -> {
                            IconButton(onClick = onOpen) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = "Open File")
                            }
                            IconButton(onClick = onShare) {
                                Icon(Icons.Outlined.Share, contentDescription = "Share File")
                            }
                        }
                    }
                    IconButton(onClick = onCancelOrDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete Download")
                    }
                }
            }

            if (record.status == "RUNNING" || record.status == "PAUSED") {
                if (record.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (record.downloadedBytes.toFloat() / record.totalBytes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}

// --- PER-SITE SETTINGS SHEET ---

@Composable
fun NyxoraSiteSettingsSheet(
    currentHost: String,
    siteSettings: List<SiteSettingItem>,
    currentUserId: String,
    onSaveSiteSetting: (SiteSettingItem) -> Unit,
    onDeleteSiteSetting: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val activeHost = currentHost.takeIf { it.isNotBlank() && it != "nyxora.home" } ?: "example.com"
    var selectedHost by remember(activeHost) { mutableStateOf(activeHost) }

    val currentSetting = remember(selectedHost, siteSettings) {
        siteSettings.firstOrNull { it.host.equals(selectedHost, ignoreCase = true) }
            ?: SiteSettingItem(userId = currentUserId, host = selectedHost)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Column {
                        Text("Site Settings & Exceptions", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Host: $selectedHost",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Content Blocking Exceptions for $selectedHost", style = MaterialTheme.typography.titleMedium)

                        SettingToggleItem(
                            title = "Allow Ads & Trackers on this site",
                            subtitle = "Disable subresource ad filtering for $selectedHost",
                            checked = currentSetting.allowAds,
                            onCheckedChange = { onSaveSiteSetting(currentSetting.copy(allowAds = it)) }
                        )
                        SettingToggleItem(
                            title = "Allow Pop-ups on this site",
                            subtitle = "Permit window.open() without gesture check on $selectedHost",
                            checked = currentSetting.allowPopups,
                            onCheckedChange = { onSaveSiteSetting(currentSetting.copy(allowPopups = it)) }
                        )
                        SettingToggleItem(
                            title = "Allow Auto-Redirects on this site",
                            subtitle = "Permit cross-domain script redirects on $selectedHost",
                            checked = currentSetting.allowRedirects,
                            onCheckedChange = { onSaveSiteSetting(currentSetting.copy(allowRedirects = it)) }
                        )
                    }
                }
            }

            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Display & Layout for $selectedHost", style = MaterialTheme.typography.titleMedium)

                        SettingToggleItem(
                            title = "Desktop Site Mode",
                            subtitle = "Always request desktop layout on $selectedHost",
                            checked = currentSetting.desktopMode,
                            onCheckedChange = { onSaveSiteSetting(currentSetting.copy(desktopMode = it)) }
                        )

                        Column {
                            Text(
                                text = "Site Text Zoom: ${currentSetting.textZoom}%",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Slider(
                                value = currentSetting.textZoom.toFloat(),
                                onValueChange = {
                                    val stepped = ((it / 5f).roundToInt() * 5).coerceIn(50, 250)
                                    onSaveSiteSetting(currentSetting.copy(textZoom = stepped))
                                },
                                valueRange = 50f..200f
                            )
                        }
                    }
                }
            }

            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Hardware Permissions for $selectedHost", style = MaterialTheme.typography.titleMedium)

                        PermissionStateSelector(
                            label = "Camera",
                            currentState = currentSetting.cameraPermission,
                            onStateChange = { onSaveSiteSetting(currentSetting.copy(cameraPermission = it)) }
                        )
                        PermissionStateSelector(
                            label = "Microphone",
                            currentState = currentSetting.micPermission,
                            onStateChange = { onSaveSiteSetting(currentSetting.copy(micPermission = it)) }
                        )
                        PermissionStateSelector(
                            label = "Location",
                            currentState = currentSetting.locationPermission,
                            onStateChange = { onSaveSiteSetting(currentSetting.copy(locationPermission = it)) }
                        )
                        PermissionStateSelector(
                            label = "Notifications",
                            currentState = currentSetting.notificationPermission,
                            onStateChange = { onSaveSiteSetting(currentSetting.copy(notificationPermission = it)) }
                        )
                    }
                }
            }

            if (siteSettings.isNotEmpty()) {
                item {
                    Text("Configured Websites", style = MaterialTheme.typography.titleMedium)
                }
                items(siteSettings, key = { it.id.ifBlank { it.host } }) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedHost = item.host },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.host, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Zoom ${item.textZoom}% • Ads ${if (item.allowAds) "Allowed" else "Blocked"} • Camera ${item.cameraPermission}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteSiteSetting(item.host) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Reset site rules")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStateSelector(
    label: String,
    currentState: String,
    onStateChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in listOf("ASK", "ALLOW", "DENY")) {
                FilterChip(
                    selected = currentState == option,
                    onClick = { onStateChange(option) },
                    label = { Text(option) }
                )
            }
        }
    }
}

// --- READER MODE SHEET (TIER 3 SIGNATURE FEATURE) ---

@Composable
fun NyxoraReaderModeSheet(
    article: ReaderArticle,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var fontSizeSp by remember { mutableIntStateOf(17) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close Reader View")
                    }
                    Text("Reader View • ${article.estimatedReadMinutes} min read", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { fontSizeSp = (fontSizeSp - 2).coerceAtLeast(13) }) {
                        Text("A-")
                    }
                    OutlinedButton(onClick = { fontSizeSp = (fontSizeSp + 2).coerceAtMost(28) }) {
                        Text("A+")
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (article.byline.isNotBlank() || article.siteName.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = listOf(article.siteName, article.byline).filter { it.isNotBlank() }.joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                items(article.paragraphs) { para ->
                    Text(
                        text = para,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * 1.55f).sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

// --- COMPLETE SEARCHABLE SETTINGS SCREEN (SECTION 16) ---

@Composable
fun NyxoraSettingsScreen(
    settings: NyxoraSettingsState,
    customFilterRules: List<CustomFilterRule>,
    onUpdateSettings: ((NyxoraSettingsState) -> NyxoraSettingsState) -> Unit,
    onRefreshFilterList: () -> Unit,
    onAddCustomFilterRule: (String, Boolean) -> Unit,
    onRemoveCustomFilterRule: (String) -> Unit,
    onOpenSiteSettings: () -> Unit,
    onClearBrowsingData: (Boolean, Boolean) -> Unit,
    onSetPrivatePin: (String) -> Unit,
    onExportEncryptedBackup: (String, (Result<String>) -> Unit) -> Unit,
    onImportEncryptedBackup: (String, String, (Result<Int>) -> Unit) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var advancedExpanded by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showCustomRuleDialog by remember { mutableStateOf(false) }
    var showCapabilityMatrixDialog by remember { mutableStateOf(false) }

    fun matchesSearch(vararg keywords: String): Boolean {
        if (searchQuery.isBlank()) return true
        return keywords.any { it.contains(searchQuery, ignoreCase = true) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 700.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search settings (e.g. theme, ad block, video, zoom)…") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_search_input")
                )
            }

            // 1. APPEARANCE
            if (matchesSearch("Appearance", "Theme", "Light", "Dark", "Homepage", "Shortcuts", "Frequently visited", "Recent activity", "Greeting", "Date", "Background", "Text size", "Toolbar", "Animation", "Reduced motion")) {
                item {
                    SettingsSectionCard(title = "Appearance") {
                        Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (mode in ThemeMode.entries) {
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                    label = { Text(mode.name) }
                                )
                            }
                        }

                        Column {
                            Text("Global Text Size: ${settings.globalTextZoom}%", style = MaterialTheme.typography.bodyLarge)
                            Text("Default text scaling applied across websites", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = settings.globalTextZoom.toFloat(),
                                onValueChange = { v ->
                                    val stepped = ((v / 5f).roundToInt() * 5).coerceIn(50, 250)
                                    onUpdateSettings { it.copy(globalTextZoom = stepped) }
                                },
                                valueRange = 50f..200f
                            )
                        }

                        SettingToggleItem(
                            title = "Compact Toolbar Mode",
                            subtitle = "Use condensed toolbar padding for maximum vertical viewport",
                            checked = settings.compactBottomToolbar,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(compactBottomToolbar = v) } }
                        )
                        SettingToggleItem(
                            title = "Reduced Motion",
                            subtitle = "Minimize splash, tab, and menu transitions for instant response",
                            checked = settings.reducedMotion,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(reducedMotion = v) } }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Homepage",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Optional homepage sections (all off by default for a clean, minimal homepage)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingToggleItem(
                            title = "Shortcuts",
                            subtitle = "Show pinned shortcuts on homepage",
                            checked = settings.showHomeShortcuts,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(showHomeShortcuts = v) } }
                        )
                        SettingToggleItem(
                            title = "Frequently visited",
                            subtitle = "Show top visited domains on homepage",
                            checked = settings.showHomeFreqVisited,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(showHomeFreqVisited = v) } }
                        )
                        SettingToggleItem(
                            title = "Recent activity",
                            subtitle = "Show recent browsing history on homepage",
                            checked = settings.showHomeRecentActivity,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(showHomeRecentActivity = v) } }
                        )
                        SettingToggleItem(
                            title = "Greeting and date",
                            subtitle = "Show time-of-day greeting and current date on homepage",
                            checked = settings.showHomeGreeting,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(showHomeGreeting = v) } }
                        )
                        SettingToggleItem(
                            title = "Background",
                            subtitle = "Show subtle ambient background on homepage",
                            checked = settings.showAmbientHomeBackground,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(showAmbientHomeBackground = v) } }
                        )
                    }
                }
            }

            // 2. SEARCH
            if (matchesSearch("Search", "Engine", "DuckDuckGo", "Google", "Brave", "Startpage", "Ecosia", "Suggestions")) {
                item {
                    SettingsSectionCard(title = "Search") {
                        Text("Default Search Engine", style = MaterialTheme.typography.bodyLarge)
                        Text("Used when entering queries in the address bar", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (engine in SearchEngine.entries.take(3)) {
                                FilterChip(
                                    selected = settings.searchEngine == engine,
                                    onClick = { onUpdateSettings { it.copy(searchEngine = engine) } },
                                    label = { Text(engine.label) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (engine in SearchEngine.entries.drop(3)) {
                                FilterChip(
                                    selected = settings.searchEngine == engine,
                                    onClick = { onUpdateSettings { it.copy(searchEngine = engine) } },
                                    label = { Text(engine.label) }
                                )
                            }
                        }
                        SettingToggleItem(
                            title = "Address Bar Suggestions",
                            subtitle = "Show history and bookmark autocomplete matches while typing",
                            checked = settings.searchSuggestionsEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(searchSuggestionsEnabled = v) } }
                        )
                    }
                }
            }

            // 3. PRIVACY AND SECURITY
            if (matchesSearch("Privacy", "Security", "Ad", "Tracker", "Pop-up", "Redirect", "HTTPS", "Cookie", "Incognito", "Clear", "Backup", "Sync")) {
                item {
                    SettingsSectionCard(title = "Privacy and Security") {
                        SettingToggleItem(
                            title = "Ad & Tracker Blocking",
                            subtitle = "Filter known ad/analytics subresources (${settings.totalAdsBlockedCount} blocked • ${settings.filterRuleCount} active rules)",
                            checked = settings.adBlockingEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(adBlockingEnabled = v) } }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRefreshFilterList) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Update Filter List")
                            }
                            OutlinedButton(onClick = { showCustomRuleDialog = true }) {
                                Text("Custom Rules (${customFilterRules.size})")
                            }
                        }

                        SettingToggleItem(
                            title = "Block Pop-ups",
                            subtitle = "Block window.open and new-tab requests without a real user gesture (${settings.totalPopupsBlockedCount} blocked)",
                            checked = settings.popupBlockingEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(popupBlockingEnabled = v) } }
                        )
                        SettingToggleItem(
                            title = "Block Auto-Redirects",
                            subtitle = "Block non-gesture top-level redirects and intent:// app launches (${settings.totalRedirectsBlockedCount} blocked)",
                            checked = settings.redirectBlockingEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(redirectBlockingEnabled = v) } }
                        )
                        SettingToggleItem(
                            title = "Show Blocked Action Messages",
                            subtitle = "Display brief 'Allow once' snackbar when a pop-up or redirect is blocked",
                            checked = settings.showBlockedMessages,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(showBlockedMessages = v) } }
                        )
                        SettingToggleItem(
                            title = "HTTPS-Only Mode",
                            subtitle = "Automatically upgrade http:// navigations to secure https://",
                            checked = settings.httpsOnlyMode,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(httpsOnlyMode = v) } }
                        )
                        SettingToggleItem(
                            title = "Restrict Third-Party Cookies",
                            subtitle = "Block cross-site tracking cookies in WebView sessions",
                            checked = settings.blockThirdPartyCookies,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(blockThirdPartyCookies = v) } }
                        )
                        SettingToggleItem(
                            title = "Block Screenshots in Incognito",
                            subtitle = "Apply FLAG_SECURE to hide private tabs from screenshots and Recents preview",
                            checked = settings.incognitoBlockScreenshots,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(incognitoBlockScreenshots = v) } }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showPinDialog = true }) {
                                Text(if (settings.privateTabsLockEnabled) "Change Private PIN" else "Set Private Tabs PIN")
                            }
                            OutlinedButton(onClick = { onClearBrowsingData(true, true) }) {
                                Text("Clear All Browsing Data")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showBackupDialog = true }) {
                                Text("Encrypted Backup Export / Import")
                            }
                        }
                    }
                }
            }

            // 4. DOWNLOADS
            if (matchesSearch("Downloads", "Risky", "APK", "Notifications", "Location")) {
                item {
                    SettingsSectionCard(title = "Downloads") {
                        Text(
                            text = "Storage Location: App-Scoped Downloads (/files/downloads) via FileProvider",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingToggleItem(
                            title = "Confirm Risky File Types",
                            subtitle = "Warn before downloading executable or package files (.apk, .exe, .sh, .jar)",
                            checked = settings.downloadConfirmRiskyFiles,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(downloadConfirmRiskyFiles = v) } }
                        )
                        SettingToggleItem(
                            title = "Download Status Notifications",
                            subtitle = "Show progress and completion alerts for active downloads",
                            checked = settings.downloadNotificationsEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(downloadNotificationsEnabled = v) } }
                        )
                    }
                }
            }

            // 5. GESTURES
            if (matchesSearch("Gestures", "Swipe", "Back", "Forward", "Pull to refresh")) {
                item {
                    SettingsSectionCard(title = "Gestures") {
                        SettingToggleItem(
                            title = "Edge Swipe Back & Forward",
                            subtitle = "Swipe from left or right screen edges to navigate page history",
                            checked = settings.edgeSwipeBackForward,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(edgeSwipeBackForward = v) } }
                        )
                        SettingToggleItem(
                            title = "Quick Reload Action",
                            subtitle = "Enable one-tap reload and stop controls in the navigation bar",
                            checked = settings.pullToRefreshEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(pullToRefreshEnabled = v) } }
                        )
                    }
                }
            }

            // 6. VIDEO
            if (matchesSearch("Video", "Fullscreen", "Orientation", "Aspect", "Picture-in-Picture", "PiP", "Audio")) {
                item {
                    SettingsSectionCard(title = "Video") {
                        Text("Fullscreen Orientation", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = settings.fullscreenOrientationMode.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (mode in FullscreenOrientationMode.entries) {
                                FilterChip(
                                    selected = settings.fullscreenOrientationMode == mode,
                                    onClick = { onUpdateSettings { it.copy(fullscreenOrientationMode = mode) } },
                                    label = { Text(mode.name) }
                                )
                            }
                        }

                        Text("Default Video Aspect Mode", style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (aspect in VideoAspectMode.entries) {
                                FilterChip(
                                    selected = settings.defaultVideoAspectMode == aspect,
                                    onClick = { onUpdateSettings { it.copy(defaultVideoAspectMode = aspect) } },
                                    label = { Text(aspect.label) }
                                )
                            }
                        }

                        SettingToggleItem(
                            title = "Picture-in-Picture (PiP)",
                            subtitle = "Enter floating PiP window when leaving the app during fullscreen video",
                            checked = settings.pipEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(pipEnabled = v) } }
                        )
                        SettingToggleItem(
                            title = "Keep Screen On While Playing",
                            subtitle = "Prevent device screen timeout during fullscreen video playback",
                            checked = settings.keepScreenOnWhilePlaying,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(keepScreenOnWhilePlaying = v) } }
                        )
                        SettingToggleItem(
                            title = "Background Audio Playback",
                            subtitle = "Allow web media audio to continue when switching tabs where supported",
                            checked = settings.backgroundAudioEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(backgroundAudioEnabled = v) } }
                        )
                    }
                }
            }

            // 7. WEBSITES
            if (matchesSearch("Websites", "Site settings", "Permissions", "Camera", "Microphone", "Location", "Exceptions")) {
                item {
                    SettingsSectionCard(title = "Websites") {
                        Text(
                            text = "Manage per-site permissions (Camera, Microphone, Location, Notifications) and per-site exceptions for Ads, Pop-ups, Auto-Redirects, Desktop Mode, and Zoom.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onOpenSiteSettings) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open Site Settings & Exceptions")
                        }
                    }
                }
            }

            // 8. ADVANCED (Collapsed by default)
            if (matchesSearch("Advanced", "Cache", "Desktop", "Data saver", "Force dark", "Cookie banner")) {
                item {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { advancedExpanded = !advancedExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Advanced", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("Cache, Desktop defaults, Data Saver, Force-Dark, Cookie banners", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = if (advancedExpanded || searchQuery.isNotBlank()) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = "Expand Advanced Settings"
                                )
                            }

                            AnimatedVisibility(visible = advancedExpanded || searchQuery.isNotBlank()) {
                                Column(
                                    modifier = Modifier.padding(top = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    SettingToggleItem(
                                        title = "Default to Desktop Site",
                                        subtitle = "Request desktop user-agent by default for all new tabs",
                                        checked = settings.defaultDesktopSite,
                                        onCheckedChange = { v -> onUpdateSettings { it.copy(defaultDesktopSite = v) } }
                                    )
                                    SettingToggleItem(
                                        title = "Data Saver Mode (Block Images)",
                                        subtitle = "Prevent WebView from automatically loading network images",
                                        checked = settings.dataSaverBlockImages,
                                        onCheckedChange = { v -> onUpdateSettings { it.copy(dataSaverBlockImages = v) } }
                                    )
                                    SettingToggleItem(
                                        title = "Force-Dark Web Pages",
                                        subtitle = "Apply dark charcoal CSS theme overrides to bright web pages",
                                        checked = settings.forceDarkWebPages,
                                        onCheckedChange = { v -> onUpdateSettings { it.copy(forceDarkWebPages = v) } }
                                    )
                                    SettingToggleItem(
                                        title = "Hide Cookie-Consent Banners",
                                        subtitle = "Inject cosmetic CSS filter rules hiding OneTrust, Cookiebot, and GDPR overlays",
                                        checked = settings.hideCookieConsentBanners,
                                        onCheckedChange = { v -> onUpdateSettings { it.copy(hideCookieConsentBanners = v) } }
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.cacheDir.listFiles()?.forEach { file ->
                                                    if (file.name.contains("WebView", ignoreCase = true) ||
                                                        file.name.contains("org.chromium", ignoreCase = true)
                                                    ) {
                                                        file.deleteRecursively()
                                                    }
                                                }
                                            }
                                            onToast("WebView disk cache cleared")
                                        }
                                    ) {
                                        Text("Purge WebView Disk Cache")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 8. ABOUT NYXORA & WEBVIEW LIMITATIONS
            if (matchesSearch("About", "NYXORA", "Version", "Engine", "WebView", "Extensions", "Notifications", "DRM", "Capability", "Licenses")) {
                item {
                    SettingsSectionCard(title = "About NYXORA & Engine Scope") {
                        Text("NYXORA Browser v1.0.0", style = MaterialTheme.typography.titleMedium)
                        Text("Tagline: Browse Beyond Ordinary.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Engine: ${EngineCapabilityMatrix.ENGINE_NAME}. 100% local storage (Room + DataStore) with zero sign-in, zero account requirement, and zero telemetry.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "What Android System WebView Cannot Do:\n" +
                                "• Browser Extensions: WebExtension (.xpi / .crx) APIs are not supported by Android System WebView; NYXORA provides native filter-list ad blocking, Reader Mode, and cosmetic CSS rules instead.\n" +
                                "• Background Web Push Notifications: Service Worker Push API background delivery when the app is closed is not supported by System WebView.\n" +
                                "• DRM Depth: Hardware-backed Widevine L1 EME playback depth depends on OEM System WebView provisioning and falls back to L3 or standard playback on unsupported devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { showCapabilityMatrixDialog = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("View Full Engine Capability Matrix")
                        }
                    }
                }
            }
        }
    }

    if (showCapabilityMatrixDialog) {
        AlertDialog(
            onDismissRequest = { showCapabilityMatrixDialog = false },
            title = { Text("Engine Capability Matrix") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            text = EngineCapabilityMatrix.DECISION_REASON,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(EngineCapabilityMatrix.capabilities) { cap ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("${cap.feature} [${cap.supportLevel}]", style = MaterialTheme.typography.titleMedium)
                                Text(cap.engineMechanism, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(cap.limitationOrAlternative, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCapabilityMatrixDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showPinDialog) {
        var newPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Private Tabs PIN Lock") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a PIN to lock access to Private Tabs (leave empty to disable lock):")
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it },
                        label = { Text("PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSetPrivatePin(newPin)
                        showPinDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCustomRuleDialog) {
        var patternInput by remember { mutableStateOf("") }
        var isAllow by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCustomRuleDialog = false },
            title = { Text("Custom Ad/Tracker Filter Rules") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = patternInput,
                        onValueChange = { patternInput = it },
                        label = { Text("Domain (e.g. ads.example.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Allow Rule (Exception)")
                        Switch(checked = isAllow, onCheckedChange = { isAllow = it })
                    }
                    Button(
                        onClick = {
                            if (patternInput.isNotBlank()) {
                                onAddCustomFilterRule(patternInput, isAllow)
                                patternInput = ""
                            }
                        }
                    ) {
                        Text("Add Rule")
                    }
                    LazyColumn(modifier = Modifier.height(140.dp)) {
                        items(customFilterRules, key = { it.pattern }) { r ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${if (r.isAllowRule) "@@" else "||"}${r.pattern}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onRemoveCustomFilterRule(r.pattern) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Delete rule", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomRuleDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showBackupDialog) {
        var passphrase by remember { mutableStateOf("") }
        var cipherTextBlob by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("AES-256-GCM Encrypted Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Export or import your bookmarks, site permissions, and browser settings using PBKDF2 + AES-256-GCM local encryption.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Encryption Passphrase (min 4 chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cipherTextBlob,
                        onValueChange = { cipherTextBlob = it },
                        label = { Text("Encrypted Payload (NYXORA_ENC_V1:...)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onExportEncryptedBackup(passphrase) { res ->
                                    res.onSuccess { encrypted ->
                                        cipherTextBlob = encrypted
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("NYXORA Backup", encrypted))
                                        onToast("Encrypted backup generated & copied to clipboard")
                                    }.onFailure { err ->
                                        onToast(err.message ?: "Export failed")
                                    }
                                }
                            }
                        ) {
                            Text("Encrypt & Copy")
                        }
                        OutlinedButton(
                            onClick = {
                                onImportEncryptedBackup(cipherTextBlob, passphrase) { res ->
                                    res.onSuccess { count ->
                                        onToast("Restored $count items from encrypted backup")
                                        showBackupDialog = false
                                    }.onFailure { err ->
                                        onToast("Decryption failed: ${err.message}")
                                    }
                                }
                            }
                        ) {
                            Text("Decrypt & Restore")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
