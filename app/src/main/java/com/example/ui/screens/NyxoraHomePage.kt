package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.NyxoraSettingsState
import com.example.data.model.BookmarkItem
import com.example.data.model.HistoryItem
import com.example.domain.browser.FaviconCacheManager
import com.example.ui.components.NyxoraCreatorSignature
import com.example.ui.components.NyxoraHomeBrandLogo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private data class HomeNewsFeedItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val source: String,
    val readTime: String,
    val url: String
)

private val curatedHomeNewsItems = listOf(
    HomeNewsFeedItem(
        id = "news_featured_mobile_browsing",
        title = "The Future of Mobile Browsing",
        subtitle = "Privacy, Speed and AI",
        source = "Web Tech",
        readTime = "4 min read",
        url = "https://developer.chrome.com/blog"
    ),
    HomeNewsFeedItem(
        id = "news_w3c_standards",
        title = "Next-Gen Web Standards & Faster Rendering Engines",
        subtitle = "How modern mobile browsers reduce memory & battery usage",
        source = "W3C Weekly",
        readTime = "3 min read",
        url = "https://www.w3.org/news/"
    ),
    HomeNewsFeedItem(
        id = "news_privacy_sandbox",
        title = "Zero-Tracking Architecture on Mobile Web",
        subtitle = "Strict popup, redirect & tracker isolation explained",
        source = "Security Lab",
        readTime = "5 min read",
        url = "https://web.dev/articles"
    )
)

private val defaultReferenceShortcuts = listOf(
    BookmarkItem(
        id = "ref_google",
        title = "Google",
        url = "https://www.google.com",
        folder = "Shortcuts",
        isShortcut = true
    ),
    BookmarkItem(
        id = "ref_youtube",
        title = "YouTube",
        url = "https://www.youtube.com",
        folder = "Shortcuts",
        isShortcut = true
    ),
    BookmarkItem(
        id = "ref_x",
        title = "X",
        url = "https://x.com",
        folder = "Shortcuts",
        isShortcut = true
    ),
    BookmarkItem(
        id = "ref_instagram",
        title = "Instagram",
        url = "https://www.instagram.com",
        folder = "Shortcuts",
        isShortcut = true
    ),
    BookmarkItem(
        id = "ref_wikipedia",
        title = "Wikipedia",
        url = "https://www.wikipedia.org",
        folder = "Shortcuts",
        isShortcut = true
    ),
    BookmarkItem(
        id = "ref_github",
        title = "GitHub",
        url = "https://github.com",
        folder = "Shortcuts",
        isShortcut = true
    ),
    BookmarkItem(
        id = "ref_reddit",
        title = "Reddit",
        url = "https://www.reddit.com",
        folder = "Shortcuts",
        isShortcut = true
    )
)

/**
 * NYXORA Browser Home Screen – Recreated to match Reference Image 1 (Design B – Glass Minimal)
 * and Reference Image 2 (Official NYXORA App Icon):
 * 1. Top Header: Official NYXORA icon asset + "NYXORA" uppercase typography.
 * 2. Search Bar: Pill-shaped frosted glass search bar ("Search Google or enter address").
 * 3. Shortcuts Grid: 2x4 circular website shortcuts (Google, YouTube, X, Instagram,
 *    Wikipedia, GitHub, Reddit, + Add) with real favicon caching and brand icons.
 * 4. Action Row: Side-by-side "Bookmarks" and "News" pill buttons directly below shortcuts.
 * 5. Featured News Card: Snow mountain banner card ("The Future of Mobile Browsing / Privacy, Speed and AI",
 *    "Web Tech • 4 min read") directly below Bookmarks & News buttons.
 */
@Composable
fun NyxoraHomePage(
    settings: NyxoraSettingsState,
    isPrivateMode: Boolean,
    bookmarksFlow: StateFlow<List<BookmarkItem>>,
    historyFlow: StateFlow<List<HistoryItem>>,
    onNavigateToUrl: (String) -> Unit,
    onAddShortcut: (String, String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onMoveShortcut: (String, Boolean) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit = {}
) {
    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle()
    val userShortcuts = remember(bookmarks) {
        bookmarks.filter { it.isShortcut }
    }
    val effectiveShortcuts = remember(userShortcuts) {
        if (userShortcuts.isEmpty()) {
            defaultReferenceShortcuts
        } else {
            val userHosts = userShortcuts.map { FaviconCacheManager.normalizeHostKey(it.url) }.toSet()
            userShortcuts + defaultReferenceShortcuts.filterNot {
                FaviconCacheManager.normalizeHostKey(it.url) in userHosts
            }
        }
    }

    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var shortcutBeingEdited by remember { mutableStateOf<BookmarkItem?>(null) }
    var showExpandedNewsList by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val showShortcutsSection = settings.homepageShortcutsEnabled || settings.showHomeShortcuts

    val backgroundBrush = remember(settings.showAmbientHomeBackground, settings.homeWallpaperStyle, isPrivateMode) {
        if (isPrivateMode) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF090D1A),
                    Color(0xFF111A30),
                    Color(0xFF0A1020)
                )
            )
        } else {
            when (settings.homeWallpaperStyle) {
                "MIDNIGHT_GOLD" -> Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF070E1C),
                        Color(0xFF0F213C),
                        Color(0xFF13294B),
                        Color(0xFF071222)
                    )
                )
                "EMERALD_MIST" -> Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF05101E),
                        Color(0xFF0B243E),
                        Color(0xFF0E2D4D),
                        Color(0xFF061222)
                    )
                )
                else -> Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060E1B),
                        Color(0xFF0C1E36),
                        Color(0xFF102846),
                        Color(0xFF071222)
                    )
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .testTag("nyxora_home_page")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = 0.14f),
                        Color(0xFF1E40AF).copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.18f),
                    radius = size.width * 0.85f
                ),
                center = Offset(size.width * 0.5f, size.height * 0.18f),
                radius = size.width * 0.85f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2563EB).copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.72f),
                    radius = size.width * 0.75f
                ),
                center = Offset(size.width * 0.5f, size.height * 0.72f),
                radius = size.width * 0.75f
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Official NYXORA Logo & Branding Header
            item {
                ReferenceHomeBrandHeader(
                    isPrivateMode = isPrivateMode,
                    animateLogoGlow = settings.homeLogoAnimationEnabled,
                    reducedMotion = settings.reducedMotion
                )
            }

            // 2. Pill-shaped Glass Search Bar ("Search Google or enter address")
            item {
                ReferenceHomeSearchBar(
                    searchQuery = searchQuery,
                    onQueryChange = { searchQuery = it },
                    searchFocused = searchFocused,
                    onFocusChange = { searchFocused = it },
                    searchEngineLabel = settings.searchEngine.label,
                    onSubmit = {
                        val target = searchQuery.trim()
                        if (target.isNotEmpty()) {
                            focusManager.clearFocus()
                            onNavigateToUrl(target)
                            searchQuery = ""
                        }
                    }
                )
            }

            // 3. 2x4 Rounded Circular Website Shortcuts Grid
            if (showShortcutsSection) {
                item {
                    ReferenceHomeShortcutsGrid(
                        shortcuts = effectiveShortcuts,
                        onOpenShortcut = { onNavigateToUrl(it.url) },
                        onLongPressShortcut = { shortcutBeingEdited = it },
                        onAddClick = { showAddShortcutDialog = true }
                    )
                }
            }

            // 4. Side-by-side "Bookmarks" & "News" Pill Buttons
            item {
                ReferenceBookmarksAndNewsActionRow(
                    onOpenBookmarks = onOpenBookmarks,
                    onClickNews = {
                        showExpandedNewsList = !showExpandedNewsList
                        if (settings.showHomeNewsFeed) {
                            coroutineScope.launch {
                                val newsIndex = if (showShortcutsSection) 4 else 3
                                runCatching { listState.animateScrollToItem(newsIndex) }
                            }
                        }
                    }
                )
            }

            // 5. Featured News Card ("The Future of Mobile Browsing / Privacy, Speed and AI")
            if (settings.showHomeNewsFeed) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReferenceFeaturedNewsSection(
                            items = curatedHomeNewsItems,
                            showExpandedNewsList = showExpandedNewsList,
                            onOpenArticle = onNavigateToUrl
                        )
                        NyxoraCreatorSignature(
                            modifier = Modifier.testTag("home_creator_credit")
                        )
                    }
                }
            } else {
                item {
                    NyxoraCreatorSignature(
                        modifier = Modifier.testTag("home_creator_credit")
                    )
                }
            }

            // Optional Recent Activity when explicitly enabled in Settings
            if (settings.showHomeRecentActivity) {
                item {
                    val recentHistory by historyFlow.collectAsStateWithLifecycle()
                    val recentItems = remember(recentHistory) { recentHistory.take(4) }
                    if (recentItems.isNotEmpty()) {
                        ReferenceRecentActivitySection(
                            recentItems = recentItems,
                            onNavigateToUrl = onNavigateToUrl,
                            onOpenHistory = onOpenHistory
                        )
                    }
                }
            }
        }
    }

    if (showAddShortcutDialog) {
        AddShortcutDialog(
            onDismiss = { showAddShortcutDialog = false },
            onAdd = { title, url ->
                onAddShortcut(title, url)
                showAddShortcutDialog = false
            }
        )
    }

    shortcutBeingEdited?.let { selected ->
        val isUserSavedShortcut = userShortcuts.any { it.id == selected.id }
        val index = userShortcuts.indexOfFirst { it.id == selected.id }
        AlertDialog(
            onDismissRequest = { shortcutBeingEdited = null },
            title = { Text(selected.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = selected.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isUserSavedShortcut) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onMoveShortcut(selected.id, true)
                                    shortcutBeingEdited = null
                                },
                                enabled = index > 0,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("move_shortcut_left_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Move Left")
                            }
                            OutlinedButton(
                                onClick = {
                                    onMoveShortcut(selected.id, false)
                                    shortcutBeingEdited = null
                                },
                                enabled = index >= 0 && index < userShortcuts.lastIndex,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("move_shortcut_right_button")
                            ) {
                                Text("Move Right")
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isUserSavedShortcut) {
                    TextButton(
                        onClick = {
                            onDeleteBookmark(selected.id)
                            shortcutBeingEdited = null
                        },
                        modifier = Modifier.testTag("confirm_remove_shortcut_button")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(
                        onClick = {
                            onNavigateToUrl(selected.url)
                            shortcutBeingEdited = null
                        }
                    ) {
                        Text("Open")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { shortcutBeingEdited = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ReferenceHomeBrandHeader(
    isPrivateMode: Boolean,
    animateLogoGlow: Boolean,
    reducedMotion: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp)
            .testTag("home_logo_header"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            NyxoraHomeBrandLogo(
                animateGlow = animateLogoGlow,
                reducedMotion = reducedMotion,
                size = 30.dp,
                imageTestTag = "home_official_icon_image"
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "NYXORA",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.6.sp,
                    fontSize = 21.sp
                ),
                color = Color(0xFFF8FAFC)
            )
        }
        if (isPrivateMode) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.85f),
                border = BorderStroke(0.8.dp, Color(0xFF60A5FA).copy(alpha = 0.4f))
            ) {
                Text(
                    text = "Private Browsing",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ReferenceHomeSearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    searchFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    searchEngineLabel: String,
    onSubmit: () -> Unit
) {
    val searchBarBrush = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF234B7E).copy(alpha = 0.78f),
            Color(0xFF2B5B94).copy(alpha = 0.78f),
            Color(0xFF234B7E).copy(alpha = 0.78f)
        )
    )
    val placeholderText = if (searchEngineLabel.equals("Google", ignoreCase = true)) {
        "Search Google or enter address"
    } else {
        "Search $searchEngineLabel or enter address"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(searchBarBrush, RoundedCornerShape(26.dp))
            .border(
                BorderStroke(
                    width = if (searchFocused) 1.5.dp else 1.2.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF93C5FD).copy(alpha = if (searchFocused) 0.9f else 0.65f),
                            Color(0xFF60A5FA).copy(alpha = if (searchFocused) 0.75f else 0.48f),
                            Color(0xFF93C5FD).copy(alpha = if (searchFocused) 0.9f else 0.65f)
                        )
                    )
                ),
                RoundedCornerShape(26.dp)
            )
            .semantics { contentDescription = "Search or enter address" }
            .testTag("home_search_bar_trigger"),
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = Color(0xFFDCEAFE),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = placeholderText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = Color(0xFFE2EEFF).copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFFF8FAFC),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(Color(0xFF93C5FD)),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { onSubmit() },
                        onSearch = { onSubmit() },
                        onDone = { onSubmit() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocusChange(it.isFocused) }
                        .testTag("home_search_box_input")
                )
            }
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("home_search_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_input),
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceHomeShortcutsGrid(
    shortcuts: List<BookmarkItem>,
    onOpenShortcut: (BookmarkItem) -> Unit,
    onLongPressShortcut: (BookmarkItem) -> Unit,
    onAddClick: () -> Unit
) {
    val visibleShortcuts = shortcuts.take(7)
    val firstRowItems = visibleShortcuts.take(4)
    val secondRowShortcuts = if (visibleShortcuts.size > 4) visibleShortcuts.drop(4) else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_shortcuts_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (slotIndex in 0 until 4) {
                val shortcut = firstRowItems.getOrNull(slotIndex)
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (shortcut != null) {
                        ReferenceShortcutTile(
                            shortcut = shortcut,
                            onClick = { onOpenShortcut(shortcut) },
                            onLongClick = { onLongPressShortcut(shortcut) }
                        )
                    } else if (slotIndex == firstRowItems.size && secondRowShortcuts.isEmpty()) {
                        ReferenceAddShortcutTile(onClick = onAddClick)
                    } else {
                        Spacer(modifier = Modifier.size(44.dp))
                    }
                }
            }
        }

        if (visibleShortcuts.size >= 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (slotIndex in 0 until 4) {
                    val shortcut = secondRowShortcuts.getOrNull(slotIndex)
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (shortcut != null) {
                            ReferenceShortcutTile(
                                shortcut = shortcut,
                                onClick = { onOpenShortcut(shortcut) },
                                onLongClick = { onLongPressShortcut(shortcut) }
                            )
                        } else if (slotIndex == secondRowShortcuts.size) {
                            ReferenceAddShortcutTile(onClick = onAddClick)
                        } else {
                            Spacer(modifier = Modifier.size(44.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReferenceShortcutTile(
    shortcut: BookmarkItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cacheTick by FaviconCacheManager.cacheUpdateTick.collectAsState()
    var cachedBitmap by remember(shortcut.url, cacheTick) {
        mutableStateOf<Bitmap?>(FaviconCacheManager.getCachedFavicon(context, shortcut.url))
    }

    LaunchedEffect(shortcut.url) {
        val fetched = FaviconCacheManager.getOrFetchFavicon(context, shortcut.url)
        if (fetched != null) {
            cachedBitmap = fetched
        }
    }

    val knownBrand = remember(shortcut.id, shortcut.title, shortcut.url) {
        resolveReferenceShortcutBrand(shortcut)
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 1.dp, horizontal = 4.dp)
            .testTag("shortcut_tile_${shortcut.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = cachedBitmap
            if (bitmap != null && !bitmap.isRecycled && knownBrand == ReferenceShortcutBrand.CUSTOM) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = Color(0xFF152C4A),
                    border = BorderStroke(1.dp, Color(0xFF93C5FD).copy(alpha = 0.4f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "${shortcut.title} favicon",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .testTag("shortcut_favicon_image_${shortcut.id}")
                        )
                    }
                }
            } else if (knownBrand != ReferenceShortcutBrand.CUSTOM) {
                ReferenceBrandCircleIcon(
                    brand = knownBrand,
                    shortcutId = shortcut.id,
                    cachedBitmap = bitmap,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shortcut_fallback_icon_${shortcut.id}"),
                    shape = CircleShape,
                    color = Color(0xFF1A365D),
                    border = BorderStroke(1.1.dp, Color(0xFF93C5FD).copy(alpha = 0.42f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = shortcut.title,
                            tint = Color(0xFFDCEAFE),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = shortcut.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color(0xFFEBF3FF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReferenceAddShortcutTile(
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp, horizontal = 4.dp)
            .testTag("add_shortcut_button"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = Color(0xFF1E3A5F).copy(alpha = 0.72f),
            border = BorderStroke(1.2.dp, Color(0xFF7DD3FC).copy(alpha = 0.45f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add shortcut",
                    tint = Color(0xFFF0F7FF),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Add",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color(0xFFEBF3FF),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

private enum class ReferenceShortcutBrand {
    GOOGLE,
    YOUTUBE,
    X,
    INSTAGRAM,
    WIKIPEDIA,
    GITHUB,
    REDDIT,
    CUSTOM
}

private fun resolveReferenceShortcutBrand(shortcut: BookmarkItem): ReferenceShortcutBrand {
    val key = "${shortcut.id} ${shortcut.title} ${shortcut.url}".lowercase()
    return when {
        "google" in key -> ReferenceShortcutBrand.GOOGLE
        "youtube" in key || "youtu.be" in key -> ReferenceShortcutBrand.YOUTUBE
        shortcut.title.equals("X", ignoreCase = true) || "x.com" in key || "twitter.com" in key -> ReferenceShortcutBrand.X
        "instagram" in key -> ReferenceShortcutBrand.INSTAGRAM
        "wikipedia" in key -> ReferenceShortcutBrand.WIKIPEDIA
        "github" in key -> ReferenceShortcutBrand.GITHUB
        "reddit" in key -> ReferenceShortcutBrand.REDDIT
        else -> ReferenceShortcutBrand.CUSTOM
    }
}

@Composable
private fun ReferenceBrandCircleIcon(
    brand: ReferenceShortcutBrand,
    shortcutId: String,
    cachedBitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    val tagModifier = if (cachedBitmap != null && !cachedBitmap.isRecycled) {
        modifier.testTag("shortcut_favicon_image_$shortcutId")
    } else {
        modifier.testTag("shortcut_fallback_icon_$shortcutId")
    }

    when (brand) {
        ReferenceShortcutBrand.GOOGLE -> {
            Surface(
                modifier = tagModifier,
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 3.dp
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = size.minDimension * 0.16f
                    val arcDiameter = size.minDimension * 0.56f
                    val topLeft = Offset(
                        (size.width - arcDiameter) / 2f,
                        (size.height - arcDiameter) / 2f
                    )
                    val arcSize = Size(arcDiameter, arcDiameter)
                    drawArc(
                        color = Color(0xFFEA4335),
                        startAngle = 215f,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    drawArc(
                        color = Color(0xFFFBBC05),
                        startAngle = 135f,
                        sweepAngle = 80f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    drawArc(
                        color = Color(0xFF34A853),
                        startAngle = 42f,
                        sweepAngle = 93f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    drawArc(
                        color = Color(0xFF4285F4),
                        startAngle = -12f,
                        sweepAngle = 56f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    drawRect(
                        color = Color(0xFF4285F4),
                        topLeft = Offset(size.width * 0.50f, size.height * 0.43f),
                        size = Size(size.width * 0.30f, strokeWidth * 0.88f)
                    )
                }
            }
        }

        ReferenceShortcutBrand.YOUTUBE -> {
            Surface(
                modifier = tagModifier,
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 3.dp
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val badgeW = size.width * 0.62f
                    val badgeH = size.height * 0.44f
                    val badgeLeft = (size.width - badgeW) / 2f
                    val badgeTop = (size.height - badgeH) / 2f
                    drawRoundRect(
                        color = Color(0xFFFF0000),
                        topLeft = Offset(badgeLeft, badgeTop),
                        size = Size(badgeW, badgeH),
                        cornerRadius = CornerRadius(badgeH * 0.28f, badgeH * 0.28f)
                    )
                    val playPath = Path().apply {
                        moveTo(size.width * 0.45f, size.height * 0.39f)
                        lineTo(size.width * 0.59f, size.height * 0.50f)
                        lineTo(size.width * 0.45f, size.height * 0.61f)
                        close()
                    }
                    drawPath(playPath, color = Color.White)
                }
            }
        }

        ReferenceShortcutBrand.X -> {
            Surface(
                modifier = tagModifier,
                shape = CircleShape,
                color = Color(0xFF0A0E14),
                border = BorderStroke(1.2.dp, Color(0xFF94A3B8).copy(alpha = 0.55f)),
                shadowElevation = 3.dp
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = size.minDimension
                    drawLine(
                        color = Color.White,
                        start = Offset(s * 0.31f, s * 0.29f),
                        end = Offset(s * 0.69f, s * 0.71f),
                        strokeWidth = s * 0.085f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(s * 0.69f, s * 0.29f),
                        end = Offset(s * 0.31f, s * 0.71f),
                        strokeWidth = s * 0.075f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        ReferenceShortcutBrand.INSTAGRAM -> {
            Box(
                modifier = tagModifier
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFEDA75),
                                Color(0xFFFA7E1E),
                                Color(0xFFD62976),
                                Color(0xFF962FBF),
                                Color(0xFF4F5BD5)
                            ),
                            start = Offset(0f, 100f),
                            end = Offset(100f, 0f)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = size.minDimension
                    val frameSize = s * 0.54f
                    val frameOffset = (s - frameSize) / 2f
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(frameOffset, frameOffset),
                        size = Size(frameSize, frameSize),
                        cornerRadius = CornerRadius(frameSize * 0.30f, frameSize * 0.30f),
                        style = Stroke(width = s * 0.06f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.12f,
                        center = center,
                        style = Stroke(width = s * 0.06f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.032f,
                        center = Offset(s * 0.63f, s * 0.37f)
                    )
                }
            }
        }

        ReferenceShortcutBrand.WIKIPEDIA -> {
            Surface(
                modifier = tagModifier,
                shape = CircleShape,
                color = Color(0xFFF4F6F9),
                shadowElevation = 3.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "W",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color(0xFF111827)
                    )
                }
            }
        }

        ReferenceShortcutBrand.GITHUB -> {
            Surface(
                modifier = tagModifier,
                shape = CircleShape,
                color = Color(0xFF161B22),
                border = BorderStroke(1.2.dp, Color(0xFF94A3B8).copy(alpha = 0.5f)),
                shadowElevation = 3.dp
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = size.minDimension
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.23f,
                        center = Offset(s * 0.50f, s * 0.48f)
                    )
                    val leftEar = Path().apply {
                        moveTo(s * 0.31f, s * 0.38f)
                        lineTo(s * 0.35f, s * 0.22f)
                        lineTo(s * 0.45f, s * 0.31f)
                        close()
                    }
                    val rightEar = Path().apply {
                        moveTo(s * 0.69f, s * 0.38f)
                        lineTo(s * 0.65f, s * 0.22f)
                        lineTo(s * 0.55f, s * 0.31f)
                        close()
                    }
                    drawPath(leftEar, color = Color.White)
                    drawPath(rightEar, color = Color.White)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(s * 0.40f, s * 0.63f),
                        size = Size(s * 0.20f, s * 0.22f),
                        cornerRadius = CornerRadius(s * 0.08f, s * 0.08f)
                    )
                }
            }
        }

        ReferenceShortcutBrand.REDDIT -> {
            Surface(
                modifier = tagModifier,
                shape = CircleShape,
                color = Color(0xFFFF4500),
                shadowElevation = 3.dp
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = size.minDimension
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.065f,
                        center = Offset(s * 0.31f, s * 0.46f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.065f,
                        center = Offset(s * 0.69f, s * 0.46f)
                    )
                    drawOval(
                        color = Color.White,
                        topLeft = Offset(s * 0.28f, s * 0.37f),
                        size = Size(s * 0.44f, s * 0.34f)
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(s * 0.50f, s * 0.37f),
                        end = Offset(s * 0.57f, s * 0.24f),
                        strokeWidth = s * 0.045f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.045f,
                        center = Offset(s * 0.63f, s * 0.24f)
                    )
                    drawCircle(
                        color = Color(0xFFFF4500),
                        radius = s * 0.042f,
                        center = Offset(s * 0.41f, s * 0.52f)
                    )
                    drawCircle(
                        color = Color(0xFFFF4500),
                        radius = s * 0.042f,
                        center = Offset(s * 0.59f, s * 0.52f)
                    )
                    drawArc(
                        color = Color(0xFFFF4500),
                        startAngle = 20f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(s * 0.39f, s * 0.52f),
                        size = Size(s * 0.22f, s * 0.12f),
                        style = Stroke(width = s * 0.036f, cap = StrokeCap.Round)
                    )
                }
            }
        }

        ReferenceShortcutBrand.CUSTOM -> Unit
    }
}

@Composable
private fun ReferenceBookmarksAndNewsActionRow(
    onOpenBookmarks: () -> Unit,
    onClickNews: () -> Unit
) {
    val pillBrush = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF1B3A63).copy(alpha = 0.78f),
            Color(0xFF234B7E).copy(alpha = 0.78f)
        )
    )
    val pillBorder = BorderStroke(
        1.1.dp,
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF93C5FD).copy(alpha = 0.55f),
                Color(0xFF60A5FA).copy(alpha = 0.42f)
            )
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_bookmarks_section"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(pillBrush, RoundedCornerShape(22.dp))
                .clickable(onClick = onOpenBookmarks)
                .testTag("home_bookmarks_quick_access_button"),
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = pillBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    tint = Color(0xFFE2EEFF),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Bookmarks",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color(0xFFF0F7FF),
                    maxLines = 1
                )
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(pillBrush, RoundedCornerShape(22.dp))
                .clickable(onClick = onClickNews)
                .testTag("home_news_quick_access_button"),
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = pillBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = Color(0xFFE2EEFF),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "News",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color(0xFFF0F7FF),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ReferenceFeaturedNewsSection(
    items: List<HomeNewsFeedItem>,
    showExpandedNewsList: Boolean,
    onOpenArticle: (String) -> Unit
) {
    val featured = items.firstOrNull() ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_news_feed_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable { onOpenArticle(featured.url) }
                .testTag("home_featured_news_card"),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0B1D36),
            border = BorderStroke(
                1.1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF93C5FD).copy(alpha = 0.62f),
                        Color(0xFF60A5FA).copy(alpha = 0.35f)
                    )
                )
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SnowMountainBannerCanvas(modifier = Modifier.fillMaxSize())

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.42f to Color(0xFF08162B).copy(alpha = 0.35f),
                                    0.72f to Color(0xFF071326).copy(alpha = 0.88f),
                                    1.0f to Color(0xFF061020).copy(alpha = 0.97f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "${featured.title}\n${featured.subtitle}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFFF8FAFC)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${featured.source}  •  ${featured.readTime}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        if (showExpandedNewsList && items.size > 1) {
            items.drop(1).forEach { article ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenArticle(article.url) }
                        .testTag("home_news_item_${article.id}"),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF102542).copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.32f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = article.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFFF1F5F9),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${article.source}  •  ${article.readTime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnowMountainBannerCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1E56A0),
                    Color(0xFF3B82F6),
                    Color(0xFF93C5FD),
                    Color(0xFF1E3A5F)
                ),
                startY = 0f,
                endY = h * 0.68f
            )
        )

        drawOval(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(w * 0.04f, h * 0.20f),
            size = Size(w * 0.38f, h * 0.16f)
        )
        drawOval(
            color = Color.White.copy(alpha = 0.20f),
            topLeft = Offset(w * 0.62f, h * 0.24f),
            size = Size(w * 0.36f, h * 0.14f)
        )

        val distantRange = Path().apply {
            moveTo(0f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.36f)
            lineTo(w * 0.32f, h * 0.50f)
            lineTo(w * 0.56f, h * 0.14f)
            lineTo(w * 0.78f, h * 0.44f)
            lineTo(w * 0.90f, h * 0.34f)
            lineTo(w, h * 0.48f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = distantRange,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1E3A5F),
                    Color(0xFF0F2544),
                    Color(0xFF081426)
                ),
                startY = h * 0.14f,
                endY = h * 0.85f
            )
        )

        val sunlitSnowFace = Path().apply {
            moveTo(w * 0.56f, h * 0.14f)
            lineTo(w * 0.38f, h * 0.46f)
            lineTo(w * 0.47f, h * 0.49f)
            lineTo(w * 0.53f, h * 0.38f)
            lineTo(w * 0.57f, h * 0.45f)
            close()
        }
        drawPath(
            path = sunlitSnowFace,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFDCEAFE),
                    Color(0xFF93C5FD)
                ),
                start = Offset(w * 0.45f, h * 0.14f),
                end = Offset(w * 0.56f, h * 0.50f)
            )
        )

        val rightSnowRidge = Path().apply {
            moveTo(w * 0.56f, h * 0.14f)
            lineTo(w * 0.76f, h * 0.43f)
            lineTo(w * 0.66f, h * 0.47f)
            lineTo(w * 0.57f, h * 0.36f)
            close()
        }
        drawPath(
            path = rightSnowRidge,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE0F2FE),
                    Color(0xFF60A5FA),
                    Color(0xFF1E40AF)
                ),
                start = Offset(w * 0.56f, h * 0.14f),
                end = Offset(w * 0.76f, h * 0.47f)
            )
        )

        val leftSnowPeak = Path().apply {
            moveTo(w * 0.16f, h * 0.36f)
            lineTo(w * 0.06f, h * 0.52f)
            lineTo(w * 0.15f, h * 0.50f)
            lineTo(w * 0.23f, h * 0.44f)
            close()
        }
        drawPath(
            path = leftSnowPeak,
            color = Color(0xFFE0F2FE).copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun ReferenceRecentActivitySection(
    recentItems: List<HistoryItem>,
    onNavigateToUrl: (String) -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_recent_activity_section"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE2E8F0)
            )
            TextButton(onClick = onOpenHistory) {
                Text("History", color = Color(0xFF93C5FD))
            }
        }
        recentItems.forEach { item ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onNavigateToUrl(item.url) },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF102542).copy(alpha = 0.75f),
                border = BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = Color(0xFF93C5FD),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title.ifBlank { item.url },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF1F5F9),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddShortcutDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Website Shortcut", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (e.g. YouTube)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shortcut_title_input")
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (https://...)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shortcut_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && url.isNotBlank()) {
                        onAdd(title.trim(), url.trim())
                    }
                },
                enabled = title.isNotBlank() && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                modifier = Modifier.testTag("confirm_add_shortcut_button")
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
