package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.NyxoraSettingsState
import com.example.data.model.BookmarkItem
import com.example.data.model.HistoryItem
import com.example.domain.browser.BrowserNetworkClient
import com.example.domain.browser.FaviconCacheManager
import com.example.domain.browser.UrlAndSearchHandler
import com.example.ui.components.NyxoraGeometricLogo
import com.example.ui.theme.NyxoraElectricBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Clean, lightweight NYXORA Homepage:
 * - Balanced NYXORA logo and functional search/URL field in the center area.
 * - Compact shortcut grid displaying real cached website favicons (or a neutral globe fallback icon).
 * - Supports adding, removing, and reordering shortcuts.
 * - Clean, useful empty state for new users with zero fake placeholder websites and no "Frequently Visited" clutter.
 */
@Composable
fun NyxoraHomePage(
    settings: NyxoraSettingsState,
    isPrivateMode: Boolean,
    bookmarksFlow: StateFlow<List<BookmarkItem>>,
    historyFlow: StateFlow<List<HistoryItem>>,
    onNavigateToUrl: (String) -> Unit,
    onAddShortcut: (title: String, url: String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onMoveShortcut: (shortcutId: String, moveEarlier: Boolean) -> Unit = { _, _ -> },
    onOpenBookmarks: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER")
    onOpenHistory: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var isEditingShortcuts by remember { mutableStateOf(false) }

    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle()
    val history by historyFlow.collectAsStateWithLifecycle()
    val faviconCacheTick by FaviconCacheManager.cacheUpdateTick.collectAsStateWithLifecycle()

    val pinnedShortcuts = remember(bookmarks) {
        bookmarks.filter { it.isShortcut }
    }

    // Debounced DNS prefetch when the user types a domain in the homepage search box
    LaunchedEffect(searchQuery, isPrivateMode, settings.dataSaverBlockImages) {
        if (searchQuery.length >= 4 && !isPrivateMode && !settings.dataSaverBlockImages) {
            delay(220L)
            BrowserNetworkClient.prefetchDnsAndPreconnect(
                rawHostOrInput = searchQuery,
                isPrivateMode = isPrivateMode,
                dataSaverEnabled = settings.dataSaverBlockImages,
                preconnectSocket = false
            )
        }
    }

    val shortcutsAllowed = settings.homepageShortcutsEnabled || settings.showHomeShortcuts
    val hasShortcutsData = shortcutsAllowed && (pinnedShortcuts.isNotEmpty() || settings.showHomeShortcuts)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("nyxora_minimal_homepage")
    ) {
        if (settings.showAmbientHomeBackground) {
            NyxoraMinimalHomeWallpaper(style = settings.homeWallpaperStyle)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = if (hasShortcutsData || settings.showHomeNewsFeed) 20.dp else 72.dp,
                bottom = 24.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Center Area: Balanced NYXORA Logo
            item {
                MinimalHomeBrandHeader(isPrivateMode = isPrivateMode)
            }

            // 2. Clean Functional Search / URL Field below Logo + Easy Bookmark Access directly below
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MinimalHomeSearchBox(
                        query = searchQuery,
                        searchEngineLabel = settings.searchEngine.label,
                        isPrivateMode = isPrivateMode,
                        focusRequester = searchFocusRequester,
                        onQueryChange = { searchQuery = it },
                        onClear = { searchQuery = "" },
                        onSubmit = {
                            val submitted = searchQuery.trim()
                            if (submitted.isNotEmpty()) {
                                focusManager.clearFocus()
                                searchQuery = ""
                                onNavigateToUrl(submitted)
                            }
                        }
                    )

                    // Easy Bookmark Access directly below the search bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_below_search_actions_row"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(onClick = onOpenBookmarks)
                                .testTag("home_bookmarks_quick_access_button"),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkBorder,
                                    contentDescription = "Bookmarks",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (bookmarks.isNotEmpty()) {
                                        "Bookmarks (${bookmarks.size})"
                                    } else {
                                        "Bookmarks"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (!isPrivateMode) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable { showAddShortcutDialog = true }
                                    .testTag("home_customize_shortcuts_button"),
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = "Add Shortcut",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Add Shortcut",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Autocomplete Suggestions when typing
            if (searchQuery.isNotBlank() && settings.searchSuggestionsEnabled) {
                item {
                    LazyHomeSearchSuggestions(
                        query = searchQuery,
                        bookmarks = bookmarks,
                        history = history,
                        onSelectUrl = { url ->
                            focusManager.clearFocus()
                            searchQuery = ""
                            onNavigateToUrl(url)
                        }
                    )
                }
            }

            // 3. Shortcuts Grid (only shown when shortcuts exist or explicitly enabled)
            if (hasShortcutsData) {
                item {
                    HomeShortcutsGridSection(
                        shortcuts = pinnedShortcuts,
                        faviconCacheTick = faviconCacheTick,
                        isEditing = isEditingShortcuts,
                        onToggleEdit = { isEditingShortcuts = !isEditingShortcuts },
                        onOpenAddDialog = { showAddShortcutDialog = true },
                        onNavigateToUrl = onNavigateToUrl,
                        onDeleteShortcut = onDeleteBookmark,
                        onMoveShortcut = onMoveShortcut
                    )
                }
            } else if (shortcutsAllowed) {
                // 4. Clear, useful empty state for new users when no shortcut data exists yet
                item {
                    HomeCleanEmptyState(
                        isPrivateMode = isPrivateMode,
                        onAddShortcutClick = { showAddShortcutDialog = true }
                    )
                }
            }

            // 5. Understated Creator Branding: CRAFTED BY SUDHAMOY
            item {
                com.example.ui.components.NyxoraCreatorSignature(
                    textTestTag = "home_creator_credit"
                )
            }

            // 6. Optional Minimal News Feed (ON by default, independently controllable in Settings > Appearance)
            if (settings.showHomeNewsFeed && !isPrivateMode) {
                item {
                    HomeCuratedNewsFeedSection(
                        onNavigateToUrl = onNavigateToUrl
                    )
                }
            }
        }
    }

    if (showAddShortcutDialog) {
        var titleInput by remember { mutableStateOf("") }
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddShortcutDialog = false },
            title = { Text("Add Homepage Shortcut") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Website Title") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shortcut_dialog_title_input")
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL (e.g. https://developer.android.com)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shortcut_dialog_url_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onAddShortcut(titleInput.trim(), urlInput.trim())
                            showAddShortcutDialog = false
                        }
                    },
                    modifier = Modifier.testTag("shortcut_dialog_save_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddShortcutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MinimalHomeBrandHeader(isPrivateMode: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("home_logo_header")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NyxoraGeometricLogo(size = 32.dp)
            Text(
                text = "NYXORA",
                style = MaterialTheme.typography.headlineSmall.copy(
                    letterSpacing = 2.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            if (isPrivateMode) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = "Incognito Mode",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Incognito",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalHomeSearchBox(
    query: String,
    searchEngineLabel: String,
    isPrivateMode: Boolean,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable {
                runCatching { focusRequester.requestFocus() }
            }
            .testTag("home_search_bar_trigger"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(NyxoraElectricBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("home_search_box_input"),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = if (isPrivateMode) {
                                "Search $searchEngineLabel or enter address privately"
                            } else {
                                "Search $searchEngineLabel or enter address"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("home_search_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeCleanEmptyState(
    isPrivateMode: Boolean,
    onAddShortcutClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag("home_empty_state_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (isPrivateMode) {
                "Browsing history, cookies, and site data are isolated and cleared when you close all incognito tabs."
            } else {
                "Pinned shortcuts will appear here for quick access."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )
        if (!isPrivateMode) {
            TextButton(
                onClick = onAddShortcutClick,
                modifier = Modifier.testTag("add_shortcut_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add Shortcut",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Add shortcut",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun LazyHomeSearchSuggestions(
    query: String,
    bookmarks: List<BookmarkItem>,
    history: List<HistoryItem>,
    onSelectUrl: (String) -> Unit
) {
    val suggestions = remember(query, bookmarks, history) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            emptyList()
        } else {
            val bmMatches = bookmarks.filter {
                it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
            }.map { Triple(it.title, it.url, true) }
            val histMatches = history.filter {
                it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
            }.map { Triple(it.title, it.url, false) }
            (bmMatches + histMatches).distinctBy { it.second }.take(5)
        }
    }

    if (suggestions.isNotEmpty()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                for ((title, url, isBookmark) in suggestions) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectUrl(url) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isBookmark) Icons.Outlined.BookmarkBorder else Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeShortcutsGridSection(
    shortcuts: List<BookmarkItem>,
    faviconCacheTick: Long,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
    onOpenAddDialog: () -> Unit,
    onNavigateToUrl: (String) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onMoveShortcut: (shortcutId: String, moveEarlier: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_shortcuts_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Shortcuts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shortcuts.size > 1) {
                    TextButton(
                        onClick = onToggleEdit,
                        modifier = Modifier.testTag("reorder_shortcuts_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit shortcuts",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isEditing) "Done" else "Edit", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick = onOpenAddDialog,
                    modifier = Modifier.testTag("add_shortcut_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Add Shortcut",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (shortcuts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4
            ) {
                shortcuts.forEachIndexed { idx, shortcut ->
                    ShortcutCompactTile(
                        item = shortcut,
                        faviconCacheTick = faviconCacheTick,
                        isEditing = isEditing,
                        canMoveLeft = idx > 0,
                        canMoveRight = idx < shortcuts.lastIndex,
                        onClick = { onNavigateToUrl(shortcut.url) },
                        onRemove = { onDeleteShortcut(shortcut.id) },
                        onMoveLeft = { onMoveShortcut(shortcut.id, true) },
                        onMoveRight = { onMoveShortcut(shortcut.id, false) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutCompactTile(
    item: BookmarkItem,
    faviconCacheTick: Long,
    isEditing: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val host = remember(item.url) { UrlAndSearchHandler.extractHost(item.url).removePrefix("www.") }

    var faviconBitmap by remember(item.url, faviconCacheTick) {
        mutableStateOf<Bitmap?>(FaviconCacheManager.getCachedFavicon(context, item.url))
    }

    LaunchedEffect(item.url) {
        if (faviconBitmap == null || FaviconCacheManager.isFaviconMissingOrOutdated(context, item.url)) {
            val fetched = FaviconCacheManager.getOrFetchFavicon(context, item.url)
            if (fetched != null) {
                faviconBitmap = fetched
            }
        }
    }

    Surface(
        modifier = modifier
            .widthIn(min = 74.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRemove
            )
            .testTag("shortcut_tile_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val bmp = faviconBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "${item.title.ifBlank { host }} favicon",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .testTag("shortcut_favicon_image_${item.id}")
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = "${item.title.ifBlank { host }} website icon",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .testTag("shortcut_fallback_icon_${item.id}")
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.title.ifBlank { host },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isEditing) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onMoveLeft,
                        enabled = canMoveLeft,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("move_shortcut_left_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = "Move shortcut earlier",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("remove_shortcut_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Remove shortcut",
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveRight,
                        enabled = canMoveRight,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("move_shortcut_right_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "Move shortcut later",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NyxoraMinimalHomeWallpaper(style: String = "AURORA_BLUE") {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val (topColor, bottomColor) = when (style) {
        "OBSIDIAN_GLOW" -> Pair(
            primaryColor.copy(alpha = 0.09f),
            primaryColor.copy(alpha = 0.06f)
        )
        "MIDNIGHT_SLATE" -> Pair(
            secondaryColor.copy(alpha = 0.13f),
            primaryColor.copy(alpha = 0.07f)
        )
        else -> Pair(
            primaryColor.copy(alpha = 0.14f),
            secondaryColor.copy(alpha = 0.09f)
        )
    }
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_minimal_wallpaper_layer")
    ) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    topColor,
                    secondaryColor.copy(alpha = 0.04f),
                    androidx.compose.ui.graphics.Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.24f),
                radius = w * 0.72f
            ),
            radius = w * 0.72f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.24f)
        )
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    bottomColor,
                    androidx.compose.ui.graphics.Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.76f),
                radius = w * 0.55f
            ),
            radius = w * 0.55f,
            center = androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.76f)
        )
    }
}

private data class HomeNewsFeedEntry(
    val id: String,
    val source: String,
    val title: String,
    val url: String
)

private val CURATED_HOME_NEWS_ITEMS = listOf(
    HomeNewsFeedEntry(
        id = "android_dev",
        source = "Android Developers",
        title = "Modern Android Architecture, Jetpack Compose & WebView Engineering",
        url = "https://developer.android.com/jetpack/compose"
    ),
    HomeNewsFeedEntry(
        id = "mdn_web",
        source = "MDN Web Docs",
        title = "Web Standards, HTML5 Media APIs & Modern Browser Security",
        url = "https://developer.mozilla.org/en-US/docs/Web"
    ),
    HomeNewsFeedEntry(
        id = "kotlin_blog",
        source = "Kotlin Official",
        title = "Kotlin Coroutines, Structured Concurrency & Multiplatform Updates",
        url = "https://kotlinlang.org/docs/coroutines-overview.html"
    )
)

@Composable
private fun HomeCuratedNewsFeedSection(
    onNavigateToUrl: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_news_feed_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Web & Tech Feed",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (item in CURATED_HOME_NEWS_ITEMS) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onNavigateToUrl(item.url) }
                    .testTag("home_news_item_${item.id}"),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = item.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


