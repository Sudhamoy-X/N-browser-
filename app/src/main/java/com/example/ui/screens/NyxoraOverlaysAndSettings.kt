package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FullscreenOrientationMode
import com.example.data.local.NyxoraSettingsState
import com.example.domain.browser.InputClassification
import com.example.domain.browser.UrlAndSearchHandler
import com.example.data.local.SearchEngine
import com.example.data.local.ThemeMode
import com.example.data.local.VideoAspectMode
import com.example.data.model.BookmarkItem
import com.example.data.model.CustomFilterRule
import com.example.data.model.DownloadRecord
import com.example.data.model.HistoryItem
import com.example.data.model.SiteSettingItem
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.domain.browser.BrowserDiagnosticsEngine
import com.example.domain.browser.DownloadEngine
import com.example.domain.browser.EngineCapabilityMatrix
import com.example.domain.browser.ReaderArticle
import com.example.domain.update.ApkUpdateUiState
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        ) {
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
                        text = "No bookmarks saved yet.",
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
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelectUrl(bm.url)
                                    onDismiss()
                                },
                            shape = RoundedCornerShape(12.dp),
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

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nyxora_history_sheet"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("history_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Text("History", style = MaterialTheme.typography.titleLarge)
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
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelectUrl(item.url)
                                    onDismiss()
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
        ) {
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
                                runCatching {
                                    val intent = downloadEngine.buildOpenFileIntent(dl)
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        onToast("File no longer exists on disk")
                                    }
                                }.onFailure {
                                    onToast("No app installed to open ${dl.fileName}")
                                }
                            },
                            onShare = {
                                runCatching {
                                    val intent = downloadEngine.buildShareFileIntent(dl)
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        onToast("File no longer exists on disk")
                                    }
                                }.onFailure {
                                    onToast("Unable to share ${dl.fileName}")
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
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
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

// --- GENERAL -> SITE SETTINGS SHEET (CONTENT, PERMISSIONS, SITE MANAGEMENT) ---

@Composable
fun NyxoraSiteSettingsSheet(
    currentHost: String,
    siteSettings: List<SiteSettingItem>,
    currentUserId: String,
    settings: NyxoraSettingsState = NyxoraSettingsState(),
    onUpdateSettings: ((NyxoraSettingsState) -> NyxoraSettingsState) -> Unit = {},
    onSaveSiteSetting: (SiteSettingItem) -> Unit,
    onDeleteSiteSetting: (String) -> Unit,
    onClearAllSiteData: () -> Unit = {},
    onResetSitePermissions: () -> Unit = {},
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val activeHost = currentHost
        .trim()
        .lowercase()
        .removePrefix("www.")
        .removePrefix("m.")
        .removePrefix("mobile.")
        .takeIf { it.isNotBlank() && it != "nyxora.home" }
        ?: "example.com"
    var selectedHost by remember(activeHost) { mutableStateOf(activeHost) }

    val currentSetting = remember(selectedHost, siteSettings) {
        findSiteSettingForHost(siteSettings, selectedHost)
            ?: SiteSettingItem(userId = currentUserId, host = selectedHost)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nyxora_site_settings_sheet"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .widthIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("site_settings_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text("Site Settings", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Global defaults & overrides for $selectedHost",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1. CONTENT
            SettingsSectionCard(title = "Content") {
                Column {
                    Text(
                        text = "Default Text Size: ${settings.globalTextZoom}%",
                        style = MaterialTheme.typography.bodyLarge
                    )
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
                    title = "Desktop Site Preference",
                    subtitle = "Request desktop websites by default",
                    checked = settings.defaultDesktopSite,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(defaultDesktopSite = v) } }
                )
                SettingToggleItem(
                    title = "JavaScript",
                    subtitle = "Allow websites to run JavaScript",
                    checked = settings.javascriptEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(javascriptEnabled = v) } }
                )
                SettingToggleItem(
                    title = "Images",
                    subtitle = "Load webpage images automatically",
                    checked = !settings.dataSaverBlockImages,
                    onCheckedChange = { loadImages -> onUpdateSettings { it.copy(dataSaverBlockImages = !loadImages) } }
                )
                SettingToggleItem(
                    title = "Cookies",
                    subtitle = "Allow websites to save and read cookie data",
                    checked = settings.cookiesEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(cookiesEnabled = v) } }
                )
                SettingToggleItem(
                    title = "Block Pop-ups and Redirects",
                    subtitle = "Prevent sites from opening unrequested windows or cross-domain redirects",
                    checked = settings.popupBlockingEnabled && settings.redirectBlockingEnabled,
                    onCheckedChange = { v ->
                        onUpdateSettings {
                            it.copy(popupBlockingEnabled = v, redirectBlockingEnabled = v)
                        }
                    }
                )
            }

            // 2. PERMISSIONS
            SettingsSectionCard(title = "Permissions") {
                PermissionStateSelector(
                    label = "Location",
                    currentState = currentSetting.locationPermission,
                    onStateChange = {
                        onSaveSiteSetting(currentSetting.copy(locationPermission = it))
                        onUpdateSettings { s -> s.copy(defaultLocationPermission = it) }
                    }
                )
                PermissionStateSelector(
                    label = "Camera",
                    currentState = currentSetting.cameraPermission,
                    onStateChange = {
                        onSaveSiteSetting(currentSetting.copy(cameraPermission = it))
                        onUpdateSettings { s -> s.copy(defaultCameraPermission = it) }
                    }
                )
                PermissionStateSelector(
                    label = "Microphone",
                    currentState = currentSetting.micPermission,
                    onStateChange = {
                        onSaveSiteSetting(currentSetting.copy(micPermission = it))
                        onUpdateSettings { s -> s.copy(defaultMicPermission = it) }
                    }
                )
                PermissionStateSelector(
                    label = "Notifications",
                    currentState = currentSetting.notificationPermission,
                    onStateChange = {
                        onSaveSiteSetting(currentSetting.copy(notificationPermission = it))
                        onUpdateSettings { s -> s.copy(defaultNotificationPermission = it) }
                    }
                )
                SettingToggleItem(
                    title = "Clipboard Access",
                    subtitle = "Allow websites to read or write clipboard content on user gesture",
                    checked = settings.clipboardAccessEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(clipboardAccessEnabled = v) } }
                )
                Text(
                    text = "File Access: Restricted to user-selected files via Android system document picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 3. INDIVIDUAL WEBSITE SETTINGS FOR SELECTED HOST
            SettingsSectionCard(title = "Website Override: $selectedHost") {
                SettingToggleItem(
                    title = "Desktop Site for $selectedHost",
                    subtitle = "Always load desktop version for this host",
                    checked = currentSetting.desktopMode,
                    onCheckedChange = { onSaveSiteSetting(currentSetting.copy(desktopMode = it)) }
                )
                SettingToggleItem(
                    title = "Allow Ads on $selectedHost",
                    subtitle = "Disable ad filtering on this website",
                    checked = currentSetting.allowAds,
                    onCheckedChange = { onSaveSiteSetting(currentSetting.copy(allowAds = it)) }
                )
                SettingToggleItem(
                    title = "Allow Pop-ups on $selectedHost",
                    subtitle = "Allow pop-up windows on this website",
                    checked = currentSetting.allowPopups,
                    onCheckedChange = { onSaveSiteSetting(currentSetting.copy(allowPopups = it)) }
                )
                SettingToggleItem(
                    title = "Allow Redirects on $selectedHost",
                    subtitle = "Allow automatic redirects on this website",
                    checked = currentSetting.allowRedirects,
                    onCheckedChange = { onSaveSiteSetting(currentSetting.copy(allowRedirects = it)) }
                )
                Column {
                    Text(
                        text = "Site Text Size ($selectedHost): ${currentSetting.textZoom}%",
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

            // 4. SITE MANAGEMENT
            SettingsSectionCard(title = "Site Management") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onClearAllSiteData,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("site_settings_clear_data_button")
                    ) {
                        Text("Clear Site Data")
                    }
                    OutlinedButton(
                        onClick = onResetSitePermissions,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("site_settings_reset_permissions_button")
                    ) {
                        Text("Reset Site Permissions")
                    }
                }

                if (siteSettings.isEmpty()) {
                    Text(
                        text = "No custom per-site rules saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "All Sites (${siteSettings.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (siteSettings.isNotEmpty()) {
                for (item in siteSettings) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clickable { selectedHost = item.host },
                        shape = RoundedCornerShape(12.dp),
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

// --- READER MODE SHEET ---

@Composable
fun NyxoraReaderModeSheet(
    article: ReaderArticle,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var fontSizeSp by remember { mutableIntStateOf(17) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
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

// --- PROFESSIONAL 7-CATEGORY SETTINGS SCREEN (NYXORA 2.0) ---

enum class SettingsCategory(val title: String, val subtitle: String) {
    GENERAL(
        title = "General",
        subtitle = "Homepage, Default Search Engine, Site Settings, Startup, Default Browser"
    ),
    CUSTOMIZATION(
        title = "Appearance",
        subtitle = "Theme, Minimal Wallpaper, Homepage Shortcuts, Launch Animation, Toolbar, Text Size"
    ),
    PRIVACY(
        title = "Privacy & Security",
        subtitle = "Ad & Tracker Blocking, Pop-ups, Incognito PIN, Cookies, HTTPS-Only, Clear Data"
    ),
    DOWNLOADS(
        title = "Downloads",
        subtitle = "Download Manager, Risky File Warnings, Download Notifications, Storage"
    ),
    MEDIA_VIDEO_ASSISTANT(
        title = "Media & Video Assistant",
        subtitle = "Video Assistant, 500% Max Zoom, Fullscreen Orientation, Aspect Ratio, PiP"
    ),
    ADVANCED(
        title = "Advanced",
        subtitle = "Gesture Navigation, Accessibility, Cache, Reset Settings, Diagnostics"
    ),
    ABOUT(
        title = "About NYXORA",
        subtitle = "NYXORA Browser 2.5, Automatic APK Updates, Share App, Engine Capabilities"
    )
}

private val SUPPORTED_LANGUAGES = listOf(
    "System Default",
    "English (United States)",
    "Español",
    "Français",
    "Deutsch",
    "हिन्दी",
    "日本語"
)

private val WALLPAPER_STYLES = listOf(
    "AURORA_BLUE" to "Aurora Blue",
    "OBSIDIAN_GLOW" to "Obsidian Glow",
    "MIDNIGHT_SLATE" to "Midnight Slate"
)

@Composable
fun NyxoraSettingsScreen(
    settings: NyxoraSettingsState,
    customFilterRules: List<CustomFilterRule>,
    apkUpdateState: ApkUpdateUiState = ApkUpdateUiState.Idle,
    onCheckForUpdates: (Boolean) -> Unit = {},
    onDownloadUpdateNow: () -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onInstallUpdateNow: (Context) -> Unit = {},
    onUpdateSettings: ((NyxoraSettingsState) -> NyxoraSettingsState) -> Unit,
    onRefreshFilterList: () -> Unit,
    onAddCustomFilterRule: (String, Boolean) -> Unit,
    onRemoveCustomFilterRule: (String) -> Unit,
    onOpenSiteSettings: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenNyxoraConnect: () -> Unit = {},
    onClearBrowsingData: (Boolean, Boolean) -> Unit,
    onClearCache: () -> Unit = {},
    onResetBrowserSettings: () -> Unit = {},
    onSetPrivatePin: (String) -> Unit,
    onExportEncryptedBackup: (String, (Result<String>) -> Unit) -> Unit,
    onImportEncryptedBackup: (String, String, (Result<Int>) -> Unit) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val settingsScrollState = rememberScrollState()

    // Automatically check for new releases when the user opens Settings > About NYXORA
    LaunchedEffect(selectedCategory) {
        settingsScrollState.scrollTo(0)
        if (selectedCategory == SettingsCategory.ABOUT) {
            onCheckForUpdates(false)
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showCustomRuleDialog by remember { mutableStateOf(false) }
    var showCapabilityMatrixDialog by remember { mutableStateOf(false) }
    var showOpenSourceLicensesDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showHomepageDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            onDismiss()
        }
    }

    val isSearching = searchQuery.isNotBlank()
    fun matchesSearch(vararg keywords: String): Boolean {
        if (!isSearching) return false
        return keywords.any { it.contains(searchQuery, ignoreCase = true) }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nyxora_settings_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .widthIn(max = 700.dp)
                .verticalScroll(settingsScrollState)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (selectedCategory != null) {
                            selectedCategory = null
                        } else {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.testTag("settings_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = selectedCategory?.title ?: "Settings",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Search input on Settings Home
            if (selectedCategory == null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search settings…") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_search_input")
                )
            }

            // Settings Home: 7 clean categories when not searching
            if (selectedCategory == null && !isSearching) {
                SettingsCategoryRow(
                    icon = Icons.Outlined.Settings,
                    category = SettingsCategory.GENERAL,
                    testTag = "settings_category_general",
                    onClick = { selectedCategory = SettingsCategory.GENERAL }
                )
                SettingsCategoryRow(
                    icon = Icons.Outlined.Palette,
                    category = SettingsCategory.CUSTOMIZATION,
                    testTag = "settings_category_customization",
                    onClick = { selectedCategory = SettingsCategory.CUSTOMIZATION }
                )
                SettingsCategoryRow(
                    icon = Icons.Outlined.Shield,
                    category = SettingsCategory.PRIVACY,
                    testTag = "settings_category_privacy",
                    onClick = { selectedCategory = SettingsCategory.PRIVACY }
                )
                SettingsCategoryRow(
                    icon = Icons.Outlined.Download,
                    category = SettingsCategory.DOWNLOADS,
                    testTag = "settings_category_downloads",
                    onClick = { selectedCategory = SettingsCategory.DOWNLOADS }
                )
                SettingsCategoryRow(
                    icon = Icons.Outlined.PlayArrow,
                    category = SettingsCategory.MEDIA_VIDEO_ASSISTANT,
                    testTag = "settings_category_media_video",
                    onClick = { selectedCategory = SettingsCategory.MEDIA_VIDEO_ASSISTANT }
                )
                SettingsCategoryRow(
                    icon = Icons.Outlined.Tune,
                    category = SettingsCategory.ADVANCED,
                    testTag = "settings_category_advanced",
                    onClick = { selectedCategory = SettingsCategory.ADVANCED }
                )
                SettingsCategoryRow(
                    icon = Icons.Outlined.Info,
                    category = SettingsCategory.ABOUT,
                    testTag = "settings_category_about",
                    badgeText = if (apkUpdateState.hasUpdateIndicator) "Update available" else null,
                    badgeTestTag = "settings_about_update_badge",
                    onClick = { selectedCategory = SettingsCategory.ABOUT }
                )
            }

            // 1. GENERAL
            if (selectedCategory == SettingsCategory.GENERAL ||
                matchesSearch("General", "Homepage", "Search", "Engine", "Google", "DuckDuckGo", "Brave", "Site Settings", "Downloads", "Restore", "Startup", "Default Browser")
            ) {
                SettingsSectionCard(title = "General") {
                    // Homepage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showHomepageDialog = true }
                            .padding(vertical = 4.dp)
                            .testTag("settings_homepage_option"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Homepage", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (settings.homepageUrl == "nyxora://home" || settings.homepageUrl.isBlank()) {
                                    "NYXORA Homepage"
                                } else {
                                    settings.homepageUrl
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showHomepageDialog = true }) {
                            Text("Edit")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Site Settings
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenSiteSettings)
                            .padding(vertical = 6.dp)
                            .testTag("settings_open_site_settings_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Site Settings", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Content, permissions (Location, Camera, Mic), and per-site rules",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Default Search Engine
                    Text("Default Search Engine", style = MaterialTheme.typography.bodyLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (engine in SearchEngine.entries) {
                            FilterChip(
                                selected = settings.searchEngine == engine,
                                onClick = { onUpdateSettings { it.copy(searchEngine = engine) } },
                                label = { Text(engine.label) }
                            )
                        }
                    }
                    SettingToggleItem(
                        title = "Search & Address Suggestions",
                        subtitle = "Show matching bookmarks and history while typing",
                        checked = settings.searchSuggestionsEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(searchSuggestionsEnabled = v) } }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Restore Tabs on Startup
                    SettingToggleItem(
                        title = "Restore Tabs on Startup",
                        subtitle = "Reopen your previous normal tabs when launching NYXORA",
                        checked = settings.restoreTabsOnStartup,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(restoreTabsOnStartup = v) } }
                    )

                    if (selectedCategory == SettingsCategory.GENERAL) {
                        OutlinedButton(
                            onClick = onOpenDownloads,
                            modifier = Modifier.testTag("settings_open_downloads_button")
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Manage Downloads")
                        }
                    }

                    // Set as Default Browser
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallback)
                                }.onFailure {
                                    onToast("Open Android Settings > Apps > Default apps to set NYXORA as default browser")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_default_browser_button")
                    ) {
                        Text("Set as Default Browser")
                    }
                }
            }

            // 2. APPEARANCE (CUSTOMIZATION)
            if (selectedCategory == SettingsCategory.CUSTOMIZATION ||
                matchesSearch("Customization", "Appearance", "Theme", "Light", "Dark", "System", "Shortcuts", "Wallpaper", "Animation", "Launch", "News", "Promo", "Cards", "Toolbar", "Text size", "Language")
            ) {
                SettingsSectionCard(title = "Appearance") {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mode in ThemeMode.entries) {
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.DARK -> "Dark"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.SYSTEM -> "System"
                                        }
                                    )
                                }
                            )
                        }
                    }

                    SettingToggleItem(
                        title = "Opening Launch Animation",
                        subtitle = "Play the 2-second glowing NYXORA opening animation on subsequent launches",
                        checked = settings.launchAnimationEnabled,
                        onCheckedChange = { v ->
                            onUpdateSettings { it.copy(launchAnimationEnabled = v) }
                        },
                        testTag = "setting_launch_animation_toggle"
                    )

                    SettingToggleItem(
                        title = "Home Screen Logo Animation",
                        subtitle = "Subtle breathing blue-violet glow around the NYXORA Home Screen logo",
                        checked = settings.homeLogoAnimationEnabled,
                        onCheckedChange = { v ->
                            onUpdateSettings { it.copy(homeLogoAnimationEnabled = v) }
                        },
                        testTag = "setting_home_logo_animation_toggle"
                    )

                    SettingToggleItem(
                        title = "Homepage Shortcuts",
                        subtitle = "Display rounded website shortcuts with cached favicons on the homepage",
                        checked = settings.homepageShortcutsEnabled,
                        onCheckedChange = { v ->
                            onUpdateSettings {
                                it.copy(
                                    homepageShortcutsEnabled = v,
                                    showHomeShortcuts = v,
                                    showHomeFreqVisited = false
                                )
                            }
                        },
                        testTag = "setting_home_shortcuts_toggle"
                    )

                    SettingToggleItem(
                        title = "Homepage News Feed",
                        subtitle = "Display curated web & technology feed cards on the homepage",
                        checked = settings.showHomeNewsFeed,
                        onCheckedChange = { v ->
                            onUpdateSettings {
                                it.copy(showHomeNewsFeed = v)
                            }
                        },
                        testTag = "setting_home_news_feed_toggle"
                    )

                    SettingToggleItem(
                        title = "Minimal Homepage Wallpaper",
                        subtitle = "Show subtle atmospheric glow wallpaper on the NYXORA homepage",
                        checked = settings.showAmbientHomeBackground,
                        onCheckedChange = { v ->
                            onUpdateSettings { it.copy(showAmbientHomeBackground = v) }
                        },
                        testTag = "setting_home_wallpaper_toggle"
                    )

                    if (settings.showAmbientHomeBackground) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .testTag("setting_home_wallpaper_style_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((styleKey, styleLabel) in WALLPAPER_STYLES) {
                                FilterChip(
                                    selected = settings.homeWallpaperStyle == styleKey,
                                    onClick = {
                                        onUpdateSettings { it.copy(homeWallpaperStyle = styleKey) }
                                    },
                                    label = { Text(styleLabel) }
                                )
                            }
                        }
                    }

                    SettingToggleItem(
                        title = "Disable News Feeds & Extra Homepage Cards",
                        subtitle = "Keep the homepage distraction-free without news feeds, discovery cards, or activity clutter",
                        checked = !settings.showHomeNewsFeed && !settings.showHomeFreqVisited && !settings.showHomeRecentActivity,
                        onCheckedChange = { disableExtras ->
                            onUpdateSettings {
                                it.copy(
                                    showHomeNewsFeed = !disableExtras,
                                    showHomeFreqVisited = false,
                                    showHomeRecentActivity = !disableExtras,
                                    showHomeGreeting = false
                                )
                            }
                        },
                        testTag = "setting_disable_news_feed_toggle"
                    )

                    SettingToggleItem(
                        title = "Disable Promotional & Discovery Cards",
                        subtitle = "Prevent promotional banners and sponsored cards from appearing on the homepage",
                        checked = !settings.showHomePromotionalCards,
                        onCheckedChange = { disablePromo ->
                            onUpdateSettings { it.copy(showHomePromotionalCards = !disablePromo) }
                        },
                        testTag = "setting_disable_promo_cards_toggle"
                    )

                    SettingToggleItem(
                        title = "Compact Toolbar Layout",
                        subtitle = "Use compact bottom navigation bar height for more vertical screen space",
                        checked = settings.compactBottomToolbar,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(compactBottomToolbar = v) } }
                    )

                    Column {
                        Text(
                            text = "Default Text Size: ${settings.globalTextZoom}%",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Scale text size across all websites",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = settings.globalTextZoom.toFloat(),
                            onValueChange = { v ->
                                val stepped = ((v / 5f).roundToInt() * 5).coerceIn(50, 250)
                                onUpdateSettings { it.copy(globalTextZoom = stepped) }
                            },
                            valueRange = 50f..200f
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Language", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (lang in SUPPORTED_LANGUAGES) {
                            FilterChip(
                                selected = settings.preferredLanguage == lang,
                                onClick = {
                                    onUpdateSettings { it.copy(preferredLanguage = lang) }
                                    onToast("Language preference: $lang")
                                },
                                label = { Text(lang) }
                            )
                        }
                    }
                }
            }

            // 3. PRIVACY & SECURITY
            if (selectedCategory == SettingsCategory.PRIVACY ||
                matchesSearch("Privacy", "Security", "Ad", "Pop-up", "Incognito", "Clear", "Cookie", "Do Not Track", "HTTPS", "Backup")
            ) {
                SettingsSectionCard(title = "Privacy & Security") {
                    SettingToggleItem(
                        title = "Ad & Tracker Blocking",
                        subtitle = "Block intrusive ads and trackers (${settings.totalAdsBlockedCount} blocked)",
                        checked = settings.adBlockingEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(adBlockingEnabled = v) } }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshFilterList) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Update Filters")
                        }
                        OutlinedButton(onClick = { showCustomRuleDialog = true }) {
                            Text("Custom Rules (${customFilterRules.size})")
                        }
                    }

                    SettingToggleItem(
                        title = "Pop-up Blocking",
                        subtitle = "Block unrequested pop-up windows (${settings.totalPopupsBlockedCount} blocked)",
                        checked = settings.popupBlockingEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(popupBlockingEnabled = v) } }
                    )
                    SettingToggleItem(
                        title = "Auto-Redirect Protection",
                        subtitle = "Block automatic cross-site redirects without user interaction",
                        checked = settings.redirectBlockingEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(redirectBlockingEnabled = v) } }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    SettingToggleItem(
                        title = "Incognito Screenshot Protection",
                        subtitle = "Hide incognito tabs from screenshots and recent apps switcher",
                        checked = settings.incognitoBlockScreenshots,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(incognitoBlockScreenshots = v) } }
                    )
                    OutlinedButton(onClick = { showPinDialog = true }) {
                        Text(if (settings.privateTabsLockEnabled) "Change Incognito PIN" else "Set Incognito PIN Lock")
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    SettingToggleItem(
                        title = "Allow Cookies",
                        subtitle = "Enable first-party website cookies for sign-in sessions",
                        checked = settings.cookiesEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(cookiesEnabled = v) } }
                    )
                    SettingToggleItem(
                        title = "Block Third-Party Cookies",
                        subtitle = "Prevent cross-site tracking cookies across websites",
                        checked = settings.blockThirdPartyCookies,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(blockThirdPartyCookies = v) } }
                    )
                    SettingToggleItem(
                        title = "Hide Cookie Consent Banners",
                        subtitle = "Automatically hide common cookie consent pop-ups",
                        checked = settings.hideCookieConsentBanners,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(hideCookieConsentBanners = v) } }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    SettingToggleItem(
                        title = "Send 'Do Not Track' Signal",
                        subtitle = "Include DNT and Global Privacy Control signals with requests",
                        checked = settings.doNotTrackEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(doNotTrackEnabled = v) } }
                    )
                    SettingToggleItem(
                        title = "HTTPS-Only Mode",
                        subtitle = "Upgrade connections to HTTPS whenever possible",
                        checked = settings.httpsOnlyMode,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(httpsOnlyMode = v) } }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onClearBrowsingData(true, true)
                                onToast("Browsing history, cookies, and site data cleared")
                            },
                            modifier = Modifier.testTag("settings_clear_browsing_data_button")
                        ) {
                            Text("Clear Browsing Data")
                        }
                        OutlinedButton(onClick = { showBackupDialog = true }) {
                            Text("Encrypted Backup")
                        }
                    }
                }
            }

            // 4. DOWNLOADS
            if (selectedCategory == SettingsCategory.DOWNLOADS ||
                matchesSearch("Downloads", "Risky", "File", "Download Notifications")
            ) {
                SettingsSectionCard(title = "Downloads") {
                    SettingToggleItem(
                        title = "Confirm Risky Downloads",
                        subtitle = "Warn before downloading APK or executable files",
                        checked = settings.downloadConfirmRiskyFiles,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(downloadConfirmRiskyFiles = v) } },
                        testTag = "setting_confirm_risky_downloads_toggle"
                    )
                    SettingToggleItem(
                        title = "Download Notifications",
                        subtitle = "Show progress and completion notifications in the system tray",
                        checked = settings.downloadNotificationsEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(downloadNotificationsEnabled = v) } },
                        testTag = "setting_download_notifications_toggle"
                    )
                    Text(
                        text = "Storage Location: Android Public Downloads folder (scoped storage)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onOpenDownloads,
                        modifier = Modifier.testTag("settings_open_downloads_button")
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Download Manager")
                    }
                }
            }

            // 5. MEDIA & VIDEO ASSISTANT
            if (selectedCategory == SettingsCategory.MEDIA_VIDEO_ASSISTANT ||
                matchesSearch("Media", "Video", "Video Assistant", "Zoom", "500%", "Fullscreen", "Orientation", "Aspect", "PiP", "Picture-in-Picture", "Keep Screen On", "Background Audio")
            ) {
                SettingsSectionCard(title = "Media & Video Assistant") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_video_assistant_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingToggleItem(
                            title = "Video Assistant",
                            subtitle = "Enable fullscreen video assistant toolbar with orientation, fit/fill, zoom-out/reset, and up to 500% zoom",
                            checked = settings.videoAssistantEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(videoAssistantEnabled = v) } },
                            testTag = "setting_video_assistant_toggle"
                        )
                        Text(
                            text = "Maximum Video Zoom: 500% (with Zoom-Out & 1-Tap Reset)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("video_assistant_max_zoom_label")
                        )
                        Text(
                            text = "Fullscreen Orientation: ${settings.fullscreenOrientationMode.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (mode in FullscreenOrientationMode.entries) {
                                FilterChip(
                                    selected = settings.fullscreenOrientationMode == mode,
                                    onClick = { onUpdateSettings { it.copy(fullscreenOrientationMode = mode) } },
                                    label = { Text(mode.name) }
                                )
                            }
                        }
                        Text(
                            text = "Default Video Aspect Ratio: ${settings.defaultVideoAspectMode.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
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
                            subtitle = "Allow supported fullscreen videos to continue in a floating PiP window",
                            checked = settings.pipEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(pipEnabled = v) } }
                        )
                        SettingToggleItem(
                            title = "Keep Screen On During Video",
                            subtitle = "Prevent screen sleep while fullscreen video is playing",
                            checked = settings.keepScreenOnWhilePlaying,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(keepScreenOnWhilePlaying = v) } }
                        )
                        SettingToggleItem(
                            title = "Background Audio Playback",
                            subtitle = "Allow supported HTML5 media audio to continue when tab is in the background",
                            checked = settings.backgroundAudioEnabled,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(backgroundAudioEnabled = v) } }
                        )
                    }
                }
            }

            // 6. ADVANCED
            if (selectedCategory == SettingsCategory.ADVANCED ||
                matchesSearch("Advanced", "Gesture", "Swipe", "Accessibility", "Cache", "Reset", "Diagnostics", "Memory", "CPU", "Crash")
            ) {
                SettingsSectionCard(title = "Advanced") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                onClearCache()
                                onToast("Browser cache cleared")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_clear_cache_button")
                        ) {
                            Text("Clear Cache")
                        }
                        OutlinedButton(
                            onClick = { showResetConfirmDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_reset_browser_button")
                        ) {
                            Text("Reset Settings")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text("Gesture Navigation", style = MaterialTheme.typography.labelLarge)
                    SettingToggleItem(
                        title = "Edge Swipe Back & Forward",
                        subtitle = "Swipe from left or right screen edges to navigate history",
                        checked = settings.edgeSwipeBackForward,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(edgeSwipeBackForward = v) } },
                        testTag = "setting_edge_swipe_toggle"
                    )
                    SettingToggleItem(
                        title = "Pull-to-Refresh",
                        subtitle = "Pull down from the top of a webpage to reload",
                        checked = settings.pullToRefreshEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(pullToRefreshEnabled = v) } }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    if (selectedCategory == SettingsCategory.ADVANCED) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_video_assistant_section"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Video Assistant & Playback", style = MaterialTheme.typography.labelLarge)
                            SettingToggleItem(
                                title = "Video Assistant",
                                subtitle = "Enable fullscreen video assistant toolbar with orientation, fit/fill, zoom-out/reset, and up to 500% zoom",
                                checked = settings.videoAssistantEnabled,
                                onCheckedChange = { v -> onUpdateSettings { it.copy(videoAssistantEnabled = v) } },
                                testTag = "setting_video_assistant_toggle"
                            )
                            Text(
                                text = "Maximum Video Zoom: 500% (with Zoom-Out & 1-Tap Reset)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("video_assistant_max_zoom_label")
                            )
                            Text(
                                text = "Fullscreen Orientation: ${settings.fullscreenOrientationMode.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (mode in FullscreenOrientationMode.entries) {
                                    FilterChip(
                                        selected = settings.fullscreenOrientationMode == mode,
                                        onClick = { onUpdateSettings { it.copy(fullscreenOrientationMode = mode) } },
                                        label = { Text(mode.name) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }

                    Text("Accessibility", style = MaterialTheme.typography.labelLarge)
                    SettingToggleItem(
                        title = "Reduced Motion",
                        subtitle = "Minimize UI transitions and animations",
                        checked = settings.reducedMotion,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(reducedMotion = v) } }
                    )
                    SettingToggleItem(
                        title = "Force Dark Web Pages",
                        subtitle = "Apply dark theme styling to light websites",
                        checked = settings.forceDarkWebPages,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(forceDarkWebPages = v) } }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    val diagSnapshot by BrowserDiagnosticsEngine.snapshotFlow.collectAsStateWithLifecycle()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_diagnostics_card"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "System & Performance Diagnostics",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Memory: ${diagSnapshot.usedHeapMb} MB / ${diagSnapshot.maxHeapMb} MB (${diagSnapshot.memoryUsagePercent}% • ${diagSnapshot.memoryPressureLevel.label})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostics_memory_summary")
                        )
                        Text(
                            text = "CPU: ${diagSnapshot.cpuUsagePercent}% est • Process CPU: ${diagSnapshot.processCpuTimeMs} ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostics_cpu_summary")
                        )
                        Text(
                            text = "WebViews: ${diagSnapshot.activeWebViewCount} active • ${diagSnapshot.suspendedTabCount} tabs suspended • ${diagSnapshot.duplicateWebViewPreventedCount} pooled reuses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostics_webview_summary")
                        )
                        Text(
                            text = "Ad-Block Engine: ${diagSnapshot.subresourceBlockedCount}/${diagSnapshot.subresourceCheckCount} blocked (avg ${diagSnapshot.avgSubresourceCheckMicros} µs)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostics_filter_summary")
                        )
                        Text(
                            text = "Stability & Sync: ${diagSnapshot.rendererCrashCount} crashes • ${diagSnapshot.wsConnectionFailureCount} WS failures • ${diagSnapshot.lastWsHeartbeatRttMs} ms RTT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostics_stability_summary")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    BrowserDiagnosticsEngine.captureSnapshot()
                                    onToast("Diagnostics refreshed")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diagnostics_refresh_button")
                            ) {
                                Text("Refresh")
                            }
                            OutlinedButton(
                                onClick = {
                                    BrowserDiagnosticsEngine.resetDiagnostics()
                                    onToast("Diagnostics reset")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diagnostics_reset_button")
                            ) {
                                Text("Reset Metrics")
                            }
                        }
                    }
                }
            }

            // 7. ABOUT NYXORA
            if (selectedCategory == SettingsCategory.ABOUT ||
                matchesSearch("About", "NYXORA", "Sudhamoy", "Crafted", "Version", "2.5", "2.0", "Update", "APK", "Release", "Share", "Engine", "WebView", "Capability", "Licenses")
            ) {
                SettingsSectionCard(title = "About NYXORA") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NYXORA Browser 2.5",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.testTag("about_nyxora_title")
                            )
                            Text(
                                text = "Browse Beyond Ordinary.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("about_nyxora_tagline")
                            )
                        }
                        if (apkUpdateState.hasUpdateIndicator) {
                            val badgeVersion = apkUpdateState.activeReleaseInfo?.cleanVersion
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                        RoundedCornerShape(50)
                                    )
                                    .testTag("about_update_available_indicator")
                            ) {
                                Text(
                                    text = if (!badgeVersion.isNullOrBlank()) "Update • v$badgeVersion" else "Update Available",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) • Engine: ${EngineCapabilityMatrix.ENGINE_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("about_installed_version_text")
                    )
                    Text(
                        text = "Local-first storage (Room + DataStore) with zero account requirement, zero telemetry, and strict incognito isolation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Automatic APK Update System Section
                    NyxoraApkUpdateSection(
                        updateState = apkUpdateState,
                        onCheckForUpdates = { onCheckForUpdates(true) },
                        onDownloadUpdateNow = onDownloadUpdateNow,
                        onCancelDownload = onCancelUpdateDownload,
                        onInstallUpdateNow = { onInstallUpdateNow(context) }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    OutlinedButton(
                        onClick = {
                            val (shareMessage, hasPublishedApk) = com.example.domain.update.NyxoraApkUpdateManager.buildAppSharePayload(apkUpdateState)
                            if (!hasPublishedApk) {
                                onToast("Sharing GitHub Releases page — publish a release APK on GitHub first for direct APK downloads")
                            }
                            runCatching {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "NYXORA Browser ${BuildConfig.VERSION_NAME}")
                                    putExtra(Intent.EXTRA_TEXT, shareMessage)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Share NYXORA Browser").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("about_share_app_button")
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share NYXORA Browser")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showCapabilityMatrixDialog = true },
                            modifier = Modifier.testTag("about_engine_capabilities_button")
                        ) {
                            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Engine Capabilities")
                        }
                        OutlinedButton(
                            onClick = { showOpenSourceLicensesDialog = true },
                            modifier = Modifier.testTag("about_open_source_licenses_button")
                        ) {
                            Text("Open-Source Licenses")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    com.example.ui.components.NyxoraCreatorSignature(
                        textTestTag = "about_nyxora_creator_credit"
                    )
                }
            }
        }
    }

    if (showHomepageDialog) {
        var homepageInput by remember(settings.homepageUrl) {
            mutableStateOf(
                if (settings.homepageUrl == "nyxora://home") "" else settings.homepageUrl
            )
        }
        AlertDialog(
            onDismissRequest = { showHomepageDialog = false },
            title = { Text("Homepage Preference") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Leave blank to use the default NYXORA Homepage, or enter a custom website URL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = homepageInput,
                        onValueChange = { homepageInput = it },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = homepageInput.trim()
                        if (trimmed.isBlank() || trimmed.equals("nyxora://home", ignoreCase = true)) {
                            onUpdateSettings { it.copy(homepageUrl = "nyxora://home") }
                            showHomepageDialog = false
                        } else {
                            val normalized = UrlAndSearchHandler.normalizeUserInput(
                                rawInput = trimmed,
                                searchEngine = settings.searchEngine,
                                httpsOnlyMode = settings.httpsOnlyMode
                            )
                            if (normalized.classification == InputClassification.BLOCKED_DANGEROUS_SCHEME ||
                                normalized.classification == InputClassification.EXTERNAL_INTENT_OR_CUSTOM_SCHEME
                            ) {
                                onToast("Blocked unsafe homepage URL scheme")
                                onUpdateSettings { it.copy(homepageUrl = "nyxora://home") }
                            } else {
                                onUpdateSettings { it.copy(homepageUrl = normalized.finalUrl) }
                            }
                            showHomepageDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onUpdateSettings { it.copy(homepageUrl = "nyxora://home") }
                        showHomepageDialog = false
                    }
                ) {
                    Text("Use Default")
                }
            }
        )
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset Browser Settings?") },
            text = {
                Text("This will restore all NYXORA settings and site permissions to their clean defaults without deleting your bookmarks.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetBrowserSettings()
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showOpenSourceLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showOpenSourceLicensesDialog = false },
            title = { Text("Open-Source Licenses") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            text = "NYXORA Browser is built with the following open-source libraries under the Apache License 2.0:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(
                        listOf(
                            "Android Jetpack Compose & Material 3 — Apache License 2.0",
                            "AndroidX Room & DataStore — Apache License 2.0",
                            "AndroidX WebKit & Core KTX — Apache License 2.0",
                            "Kotlin Coroutines & Serialization — Apache License 2.0",
                            "OkHttp — Apache License 2.0"
                        )
                    ) { entry ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenSourceLicensesDialog = false }) {
                    Text("Close")
                }
            }
        )
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
            title = { Text("Incognito Tabs PIN Lock") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a PIN to lock access to Incognito Tabs (leave empty to disable lock):")
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
private fun NyxoraApkUpdateSection(
    updateState: ApkUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdateNow: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdateNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_apk_update_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Software Update",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        when (updateState) {
            is ApkUpdateUiState.Idle -> {
                Text(
                    text = "Automatic release checks verify signed APK updates from GitHub Releases while preserving your bookmarks, history, tabs, and settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    modifier = Modifier.testTag("about_check_updates_button")
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check for Updates")
                }
            }

            is ApkUpdateUiState.Checking -> {
                Text(
                    text = "Checking GitHub Releases for a newer stable NYXORA Browser version…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("about_update_checking_progress")
                )
            }

            is ApkUpdateUiState.UpToDate -> {
                Text(
                    text = updateState.statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    modifier = Modifier.testTag("about_check_updates_button")
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check for Updates")
                }
            }

            is ApkUpdateUiState.UpdateAvailable -> {
                val rel = updateState.release
                val sizeLabel = if (rel.assetSizeBytes > 0L) " (${formatBytes(rel.assetSizeBytes)})" else ""
                Text(
                    text = "New stable release v${rel.cleanVersion} is available$sizeLabel. Installed: v${updateState.installedVersion}.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                if (rel.releaseNotes.isNotBlank()) {
                    Text(
                        text = rel.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("about_update_release_notes")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDownloadUpdateNow,
                        modifier = Modifier.testTag("about_update_now_button")
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update Now")
                    }
                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        modifier = Modifier.testTag("about_check_updates_button")
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }
                }
            }

            is ApkUpdateUiState.Downloading -> {
                val rel = updateState.release
                val progressFraction = (updateState.progressPercent.coerceIn(0, 100) / 100f)
                val sizeInfo = if (updateState.totalBytes > 0L) {
                    "${formatBytes(updateState.bytesDownloaded)} / ${formatBytes(updateState.totalBytes)}"
                } else {
                    formatBytes(updateState.bytesDownloaded)
                }
                Text(
                    text = "Downloading NYXORA Browser v${rel.cleanVersion}… ${updateState.progressPercent}% ($sizeInfo)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("about_update_progress_text")
                )
                if (updateState.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("about_update_progress_bar")
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("about_update_progress_bar")
                    )
                }
                OutlinedButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.testTag("about_cancel_download_button")
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel Download")
                }
            }

            is ApkUpdateUiState.Verifying -> {
                Text(
                    text = "Verifying v${updateState.release.cleanVersion} APK package integrity and signature…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("about_update_progress_bar")
                )
            }

            is ApkUpdateUiState.ReadyToInstall -> {
                val rel = updateState.release
                Text(
                    text = "NYXORA Browser v${rel.cleanVersion} downloaded and verified. Your bookmarks, history, tabs, and settings will be preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                Button(
                    onClick = onInstallUpdateNow,
                    modifier = Modifier.testTag("about_install_update_button")
                ) {
                    Text("Install Update")
                }
            }

            is ApkUpdateUiState.PermissionRequired -> {
                val rel = updateState.release
                Text(
                    text = "Android requires permission to install updates from NYXORA Browser (v${rel.cleanVersion}). Tap Install Update to allow 'Install unknown apps' and open the official package installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("about_update_status_text")
                )
                Button(
                    onClick = onInstallUpdateNow,
                    modifier = Modifier.testTag("about_install_update_button")
                ) {
                    Text("Install Update")
                }
            }

            is ApkUpdateUiState.Error -> {
                Text(
                    text = updateState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("about_update_error_text")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (updateState.verifiedApkFile != null && updateState.verifiedApkFile.exists()) {
                        Button(
                            onClick = onInstallUpdateNow,
                            modifier = Modifier.testTag("about_install_update_button")
                        ) {
                            Text("Install Update")
                        }
                    } else if (updateState.recoverableRelease != null) {
                        Button(
                            onClick = onDownloadUpdateNow,
                            modifier = Modifier.testTag("about_update_now_button")
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Update Now")
                        }
                    }
                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        modifier = Modifier.testTag("about_check_updates_button")
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Check Again")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    category: SettingsCategory,
    testTag: String,
    badgeText: String? = null,
    badgeTestTag: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!badgeText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    RoundedCornerShape(50)
                                )
                                .let { base -> if (badgeTestTag != null) base.testTag(badgeTestTag) else base }
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
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
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}
