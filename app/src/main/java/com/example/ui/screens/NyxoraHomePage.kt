package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.example.data.local.NyxoraSettingsState
import com.example.data.model.BookmarkItem
import com.example.data.model.HistoryItem
import com.example.domain.browser.BrowserNetworkClient
import com.example.domain.browser.UrlAndSearchHandler
import com.example.ui.components.NyxoraGeometricLogo
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.theme.NyxoraVibrantViolet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Minimal, lightweight NYXORA Homepage:
 * - Default homepage: Small NYXORA logo and one search box. Nothing else.
 * - Optional sections (Shortcuts, Frequently visited, Recent activity, Greeting and date, Background)
 *   are off by default, controlled in Settings > Appearance > Homepage, and lazy-loaded only when enabled.
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
    onOpenHistory: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Cheap debounced DNS prefetch when the user types a domain in the homepage search box
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

    val hasAnyOptionalListSection = settings.showHomeShortcuts ||
        settings.showHomeFreqVisited ||
        settings.showHomeRecentActivity

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("nyxora_minimal_homepage")
    ) {
        // Optional Background section (off by default, lazy-rendered)
        if (settings.showAmbientHomeBackground) {
            LazyHomeAmbientBackground(isPrivateMode = isPrivateMode)
        }

        if (!hasAnyOptionalListSection && !settings.showHomeGreeting) {
            // Default minimal homepage: ONLY small NYXORA logo + one search box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                MinimalHomeBrandHeader(isPrivateMode = isPrivateMode)

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

                if (searchQuery.isNotBlank() && settings.searchSuggestionsEnabled) {
                    LazyHomeSearchSuggestions(
                        query = searchQuery,
                        bookmarksFlow = bookmarksFlow,
                        historyFlow = historyFlow,
                        onSelectUrl = { url ->
                            focusManager.clearFocus()
                            searchQuery = ""
                            onNavigateToUrl(url)
                        }
                    )
                }
            }
        } else {
            // Optional sections enabled in Settings > Appearance > Homepage (lazy-loaded)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MinimalHomeBrandHeader(isPrivateMode = isPrivateMode)
                        if (settings.showHomeGreeting) {
                            LazyHomeGreetingAndDate()
                        }
                    }
                }

                item {
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
                }

                if (searchQuery.isNotBlank() && settings.searchSuggestionsEnabled) {
                    item {
                        LazyHomeSearchSuggestions(
                            query = searchQuery,
                            bookmarksFlow = bookmarksFlow,
                            historyFlow = historyFlow,
                            onSelectUrl = { url ->
                                focusManager.clearFocus()
                                searchQuery = ""
                                onNavigateToUrl(url)
                            }
                        )
                    }
                }

                if (settings.showHomeShortcuts) {
                    item {
                        LazyHomeShortcutsSection(
                            bookmarksFlow = bookmarksFlow,
                            onNavigateToUrl = onNavigateToUrl,
                            onAddShortcut = onAddShortcut,
                            onDeleteBookmark = onDeleteBookmark
                        )
                    }
                }

                if (settings.showHomeFreqVisited && !isPrivateMode) {
                    item {
                        LazyHomeFrequentlyVisitedSection(
                            historyFlow = historyFlow,
                            onNavigateToUrl = onNavigateToUrl
                        )
                    }
                }

                if (settings.showHomeRecentActivity && !isPrivateMode) {
                    item {
                        LazyHomeRecentActivitySection(
                            historyFlow = historyFlow,
                            onNavigateToUrl = onNavigateToUrl,
                            onOpenHistory = onOpenHistory
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalHomeBrandHeader(isPrivateMode: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.testTag("home_logo_header")
    ) {
        NyxoraGeometricLogo(size = 28.dp)
        Text(
            text = "NYXORA",
            style = MaterialTheme.typography.titleLarge.copy(
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        if (isPrivateMode) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = "Private Mode",
                tint = NyxoraVibrantViolet,
                modifier = Modifier.size(16.dp)
            )
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
            .height(52.dp)
            .border(
                width = 1.dp,
                color = if (isPrivateMode) {
                    NyxoraVibrantViolet.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .clickable {
                runCatching { focusRequester.requestFocus() }
            }
            .testTag("home_search_bar_trigger"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Search",
                tint = if (isPrivateMode) NyxoraVibrantViolet else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(if (isPrivateMode) NyxoraVibrantViolet else NyxoraElectricBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("home_search_box_input"),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search $searchEngineLabel or enter address",
                            style = MaterialTheme.typography.bodyLarge,
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
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyHomeSearchSuggestions(
    query: String,
    bookmarksFlow: StateFlow<List<BookmarkItem>>,
    historyFlow: StateFlow<List<HistoryItem>>,
    onSelectUrl: (String) -> Unit
) {
    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle()
    val history by historyFlow.collectAsStateWithLifecycle()

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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
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
                            imageVector = if (isBookmark) Icons.Default.Bookmark else Icons.Default.History,
                            contentDescription = null,
                            tint = if (isBookmark) NyxoraElectricBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
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

@Composable
private fun LazyHomeAmbientBackground(isPrivateMode: Boolean) {
    val primaryColor = if (isPrivateMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.14f),
                radius = size.width * 0.6f
            ),
            center = Offset(size.width * 0.82f, size.height * 0.14f),
            radius = size.width * 0.6f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryColor.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(size.width * 0.16f, size.height * 0.78f),
                radius = size.width * 0.55f
            ),
            center = Offset(size.width * 0.16f, size.height * 0.78f),
            radius = size.width * 0.55f
        )
    }
}

@Composable
private fun LazyHomeGreetingAndDate() {
    val greetingText = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val dateFormatted = remember {
        SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date())
    }
    Text(
        text = "$greetingText • $dateFormatted",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("home_greeting_date_text")
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LazyHomeShortcutsSection(
    bookmarksFlow: StateFlow<List<BookmarkItem>>,
    onNavigateToUrl: (String) -> Unit,
    onAddShortcut: (title: String, url: String) -> Unit,
    onDeleteBookmark: (String) -> Unit
) {
    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle()
    var showAddShortcutDialog by remember { mutableStateOf(false) }

    val shortcuts = remember(bookmarks) {
        val pinned = bookmarks.filter { it.isShortcut }
        if (pinned.isNotEmpty()) pinned else bookmarks.take(8)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("home_shortcuts_section")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Shortcuts",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(
                onClick = { showAddShortcutDialog = true },
                modifier = Modifier.testTag("add_shortcut_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add Shortcut",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add")
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 4
        ) {
            for (shortcut in shortcuts) {
                ShortcutTile(
                    item = shortcut,
                    onClick = { onNavigateToUrl(shortcut.url) },
                    onRemove = { onDeleteBookmark(shortcut.id) },
                    modifier = Modifier.weight(1f)
                )
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
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL (e.g. https://kotlinlang.org)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                    }
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
private fun LazyHomeFrequentlyVisitedSection(
    historyFlow: StateFlow<List<HistoryItem>>,
    onNavigateToUrl: (String) -> Unit
) {
    val history by historyFlow.collectAsStateWithLifecycle()
    val frequentlyVisited = remember(history) {
        history
            .groupBy { UrlAndSearchHandler.extractHost(it.url).ifBlank { it.url } }
            .entries
            .sortedByDescending { it.value.size }
            .take(4)
            .map { it.value.first() }
    }

    if (frequentlyVisited.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.testTag("home_frequently_visited_section")
        ) {
            Text(
                text = "Frequently Visited",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (freq in frequentlyVisited) {
                    val host = UrlAndSearchHandler.extractHost(freq.url).ifBlank { freq.title }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToUrl(freq.url) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = freq.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = host,
                                style = MaterialTheme.typography.labelSmall,
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

@Composable
private fun LazyHomeRecentActivitySection(
    historyFlow: StateFlow<List<HistoryItem>>,
    onNavigateToUrl: (String) -> Unit,
    onOpenHistory: () -> Unit
) {
    val history by historyFlow.collectAsStateWithLifecycle()
    val recentHistory = remember(history) { history.take(5) }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.testTag("home_recent_activity_section")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (recentHistory.isNotEmpty()) {
                TextButton(onClick = onOpenHistory) {
                    Text("View All")
                }
            }
        }

        if (recentHistory.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Pages you visit in Normal tabs appear here for instant resumption.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            for (entry in recentHistory) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToUrl(entry.url) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.url,
                                style = MaterialTheme.typography.labelSmall,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(
    item: BookmarkItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = item.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
    Surface(
        modifier = modifier
            .widthIn(min = 74.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRemove
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
