package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.NyxoraSettingsState
import com.example.domain.browser.BrowserNetworkClient
import com.example.ui.theme.NyxoraDangerRed
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.theme.NyxoraSuccessGreen
import com.example.ui.theme.NyxoraVibrantViolet
import com.example.ui.theme.NyxoraWarningAmber
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.OverlaySheet
import com.example.ui.viewmodel.PendingExternalSchemePrompt
import com.example.ui.viewmodel.PendingRiskyDownload
import com.example.ui.viewmodel.PendingSitePermissionPrompt
import com.example.ui.viewmodel.PendingSslErrorPrompt
import kotlinx.coroutines.delay

/**
 * Minimalist NYXORA Top Bar for URL/search input and real-time thin progress indicator.
 */
@Composable
fun NyxoraTopAddressBar(
    activeTab: BrowserTab,
    settings: NyxoraSettingsState,
    isPrivateMode: Boolean,
    addressInput: String,
    isEditingAddress: Boolean,
    findInPageVisible: Boolean,
    findInPageQuery: String,
    findInPageMatches: Pair<Int, Int>,
    currentModeTabs: List<BrowserTab>,
    addressFocusRequester: FocusRequester,
    onAddressInputChange: (String) -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    onSubmitAddress: () -> Unit,
    onClearAddress: () -> Unit,
    onReloadOrStop: () -> Unit,
    onSelectTab: (String) -> Unit,
    onFindQueryChange: (String) -> Unit,
    onFindNext: (Boolean) -> Unit,
    onCloseFindInPage: () -> Unit
) {
    val showProgressBar = activeTab.isLoading &&
        activeTab.progress in 1..99 &&
        activeTab.pageErrorDescription == null &&
        !activeTab.hasCertificateError

    val animatedProgressScale by animateFloatAsState(
        targetValue = if (showProgressBar) (activeTab.progress.coerceIn(0, 100) / 100f) else 0f,
        animationSpec = tween(
            durationMillis = if (settings.reducedMotion) 0 else 160,
            easing = FastOutSlowInEasing
        ),
        label = "nyxora_top_progress_scale"
    )

    val animatedProgressAlpha by animateFloatAsState(
        targetValue = if (showProgressBar) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (settings.reducedMotion) 0 else 150,
            easing = FastOutSlowInEasing
        ),
        label = "nyxora_top_progress_alpha"
    )

    // Cheap debounced DNS prefetch + preconnect for typed host in address bar
    LaunchedEffect(isEditingAddress, addressInput, isPrivateMode, settings.dataSaverBlockImages) {
        if (isEditingAddress && addressInput.length >= 4 && !isPrivateMode && !settings.dataSaverBlockImages) {
            delay(220L)
            BrowserNetworkClient.prefetchDnsAndPreconnect(
                rawHostOrInput = addressInput,
                isPrivateMode = isPrivateMode,
                dataSaverEnabled = settings.dataSaverBlockImages,
                preconnectSocket = false
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nyxora_top_address_bar"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            val verticalPad = if (settings.compactBottomToolbar) 6.dp else 8.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = verticalPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                var horizontalSwipeDelta by remember { mutableFloatStateOf(0f) }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (settings.compactBottomToolbar) 40.dp else 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = when {
                                isEditingAddress -> NyxoraElectricBlue
                                isPrivateMode -> NyxoraVibrantViolet.copy(alpha = 0.55f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .pointerInput(currentModeTabs, activeTab.id) {
                            detectHorizontalDragGestures(
                                onDragStart = { horizontalSwipeDelta = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    horizontalSwipeDelta += dragAmount
                                },
                                onDragEnd = {
                                    val idx = currentModeTabs.indexOfFirst { it.id == activeTab.id }
                                    if (idx != -1 && currentModeTabs.size > 1) {
                                        if (horizontalSwipeDelta < -90f && idx < currentModeTabs.lastIndex) {
                                            onSelectTab(currentModeTabs[idx + 1].id)
                                        } else if (horizontalSwipeDelta > 90f && idx > 0) {
                                            onSelectTab(currentModeTabs[idx - 1].id)
                                        }
                                    }
                                    horizontalSwipeDelta = 0f
                                }
                            )
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        isPrivateMode -> {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = stringResource(R.string.private_browsing_mode),
                                tint = NyxoraVibrantViolet,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        activeTab.isHomePage -> {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_or_enter_address),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        activeTab.isSecureConnection -> {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Secure HTTPS connection",
                                tint = NyxoraSuccessGreen,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Unencrypted or untrusted connection",
                                tint = NyxoraWarningAmber,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    val displayedText = when {
                        isEditingAddress -> addressInput
                        activeTab.isHomePage -> ""
                        else -> activeTab.safeDisplayHost.ifBlank { activeTab.url }
                    }

                    BasicTextField(
                        value = displayedText,
                        onValueChange = onAddressInputChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isEditingAddress) FontFamily.Monospace else FontFamily.Default
                        ),
                        cursorBrush = SolidColor(NyxoraElectricBlue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onSubmitAddress() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(addressFocusRequester)
                            .onFocusChanged { state -> onEditingChanged(state.isFocused) }
                            .testTag("main_address_bar_input"),
                        decorationBox = { innerTextField ->
                            if (displayedText.isEmpty()) {
                                Text(
                                    text = if (isPrivateMode) {
                                        "Search ${settings.searchEngine.label} or enter address privately"
                                    } else {
                                        "Search ${settings.searchEngine.label} or enter address"
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

                    if (isEditingAddress && addressInput.isNotEmpty()) {
                        IconButton(
                            onClick = onClearAddress,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_input),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else if (!activeTab.isHomePage || activeTab.isLoading) {
                        IconButton(
                            onClick = onReloadOrStop,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("toolbar_reload_button")
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab.isLoading) {
                                    stringResource(R.string.stop_loading)
                                } else {
                                    stringResource(R.string.reload_page)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Thin progress bar in the top bar driven by real progress; animates only transform (scaleX) and opacity (alpha)
            if (showProgressBar || animatedProgressAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                        .let { base ->
                            if (showProgressBar) base.testTag("top_address_bar_progress") else base
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = animatedProgressScale
                                alpha = animatedProgressAlpha
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .background(if (isPrivateMode) NyxoraVibrantViolet else NyxoraElectricBlue)
                    )
                }
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            }

            AnimatedVisibility(
                visible = findInPageVisible,
                enter = fadeIn(tween(if (settings.reducedMotion) 0 else 150)),
                exit = fadeOut(tween(if (settings.reducedMotion) 0 else 150))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = findInPageQuery,
                        onValueChange = onFindQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(NyxoraElectricBlue),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("find_in_page_input"),
                        decorationBox = { inner ->
                            if (findInPageQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.find_in_page_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    )
                    Text(
                        text = "${findInPageMatches.first}/${findInPageMatches.second}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { onFindNext(false) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
                    }
                    IconButton(
                        onClick = { onFindNext(true) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
                    }
                    IconButton(
                        onClick = onCloseFindInPage,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close find in page")
                    }
                }
            }
        }
    }
}

/**
 * Minimalist Bottom Navigation Bar: Back, Forward, Home, Tabs (with count), Menu. Nothing else.
 */
@Composable
fun NyxoraBottomTabManagementBar(
    activeTab: BrowserTab,
    visibleModeTabsCount: Int,
    isPrivateMode: Boolean,
    compactMode: Boolean,
    showOverflowMenu: Boolean,
    splitSecondTabId: String?,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onToggleOverflowMenu: (Boolean) -> Unit,
    onMenuNewTab: () -> Unit,
    onMenuTogglePrivateMode: () -> Unit,
    onMenuOpenLocalFile: () -> Unit,
    onMenuToggleBookmark: () -> Unit,
    onMenuToggleDesktop: () -> Unit,
    onMenuOpenFindInPage: () -> Unit,
    onMenuOpenReaderMode: () -> Unit,
    onMenuToggleSplitView: () -> Unit,
    onMenuSharePage: () -> Unit,
    onMenuCopyLink: () -> Unit,
    onMenuOpenOverlay: (OverlaySheet) -> Unit
) {
    val activeAccent = if (isPrivateMode) NyxoraVibrantViolet else NyxoraElectricBlue
    val tabsDescription = stringResource(R.string.nav_tabs)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nyxora_bottom_tab_bar"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = if (compactMode) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 1. Back
                IconButton(
                    onClick = onBack,
                    enabled = !activeTab.isHomePage,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        tint = if (!activeTab.isHomePage) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }

                // 2. Forward
                IconButton(
                    onClick = onForward,
                    enabled = activeTab.canGoForward,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.nav_forward),
                        tint = if (activeTab.canGoForward) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }

                // 3. Home
                IconButton(
                    onClick = onHome,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_home_or_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = stringResource(R.string.nav_home),
                        tint = if (activeTab.isHomePage) activeAccent else MaterialTheme.colorScheme.onSurface
                    )
                }

                // 4. Tabs (with count)
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenTabSwitcher)
                        .semantics { contentDescription = tabsDescription }
                        .testTag("toolbar_tabs_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .border(
                                width = 1.8.dp,
                                color = if (isPrivateMode) NyxoraVibrantViolet else MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(7.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = visibleModeTabsCount.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isPrivateMode) NyxoraVibrantViolet else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // 5. Menu
                Box {
                    IconButton(
                        onClick = { onToggleOverflowMenu(true) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toolbar_overflow_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.nav_menu)
                        )
                    }

                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { onToggleOverflowMenu(false) }
                    ) {
                        DropdownMenuItem(
                            text = { Text("New tab") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuNewTab()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isPrivateMode) "Exit private mode" else "New private tab") },
                            leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuTogglePrivateMode()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Bookmarks") },
                            leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuOpenOverlay(OverlaySheet.BOOKMARKS)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("History") },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuOpenOverlay(OverlaySheet.HISTORY)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Downloads") },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuOpenOverlay(OverlaySheet.DOWNLOADS)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Open file") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuOpenLocalFile()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Site settings") },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuOpenOverlay(OverlaySheet.SITE_SETTINGS)
                            }
                        )

                        if (!activeTab.isHomePage) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Bookmark this page") },
                                leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuToggleBookmark()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (activeTab.desktopMode) "Mobile site" else "Desktop site")
                                },
                                leadingIcon = {
                                    Icon(
                                        if (activeTab.desktopMode) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuToggleDesktop()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Find in page") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuOpenFindInPage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reader mode") },
                                leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuOpenReaderMode()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (splitSecondTabId != null) "Close split view" else "Split view") },
                                leadingIcon = { Icon(Icons.Default.Splitscreen, contentDescription = null) },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuToggleSplitView()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share page") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuSharePage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy link") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onMenuCopyLink()
                                }
                            )
                        }

                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onMenuOpenOverlay(OverlaySheet.SETTINGS)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PrivatePinLockView(
    onVerifyPin: (String) -> Boolean,
    onExitPrivate: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = NyxoraVibrantViolet,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Private Session Locked",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter your NYXORA PIN to unlock private tabs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedTextField(
            value = pinInput,
            onValueChange = {
                pinInput = it.take(12)
                pinError = false
            },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            isError = pinError,
            singleLine = true,
            modifier = Modifier.widthIn(max = 260.dp)
        )
        if (pinError) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Incorrect PIN", color = NyxoraDangerRed, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExitPrivate) {
                Text("Return to Normal Tabs")
            }
            Button(
                onClick = {
                    if (!onVerifyPin(pinInput)) {
                        pinError = true
                    }
                }
            ) {
                Text("Unlock")
            }
        }
    }
}

@Composable
internal fun NyxoraSecurityAndPermissionDialogs(
    sitePermPrompt: PendingSitePermissionPrompt?,
    externalSchemePrompt: PendingExternalSchemePrompt?,
    riskyDownloadPrompt: PendingRiskyDownload?,
    sslWarningPrompt: PendingSslErrorPrompt?,
    onResolveSitePermission: (allow: Boolean, rememberForSite: Boolean) -> Unit,
    onDismissExternalScheme: () -> Unit,
    onConfirmRiskyDownload: (Boolean) -> Unit,
    onResolveSslError: (Boolean) -> Unit,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current

    sitePermPrompt?.let { prompt ->
        var rememberChoice by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = {
                onResolveSitePermission(false, false)
            },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = NyxoraElectricBlue) },
            title = { Text("Site Permission Request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${prompt.host} wants to access: ${prompt.permissionTypes.joinToString(", ")}.\n\n" +
                            "NYXORA blocks hardware access by default unless you explicitly allow it."
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberChoice,
                            onCheckedChange = { rememberChoice = it }
                        )
                        Text("Remember decision for ${prompt.host}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onResolveSitePermission(true, rememberChoice) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolveSitePermission(false, rememberChoice) }) {
                    Text("Block")
                }
            }
        )
    }

    externalSchemePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissExternalScheme,
            icon = { Icon(Icons.Default.Public, contentDescription = null, tint = NyxoraElectricBlue) },
            title = { Text("Open External Application?") },
            text = {
                Text(
                    "This page wants to open an external application for scheme \"${prompt.scheme}:\"\n\n" +
                        prompt.url
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rawUrl = prompt.url
                        onDismissExternalScheme()
                        try {
                            val intent = if (rawUrl.startsWith("intent://", ignoreCase = true)) {
                                Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
                            } else {
                                Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl))
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            onToast("No application installed to handle this link")
                        }
                    }
                ) {
                    Text("Open App")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissExternalScheme) {
                    Text("Stay in NYXORA")
                }
            }
        )
    }

    riskyDownloadPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { onConfirmRiskyDownload(false) },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = NyxoraWarningAmber) },
            title = { Text("Potentially Risky File") },
            text = {
                Text(
                    "The file \"${prompt.suggestedFileName ?: prompt.url}\" may contain executable code or packages that could harm your device.\n\n" +
                        "Source: ${prompt.url}"
                )
            },
            confirmButton = {
                Button(onClick = { onConfirmRiskyDownload(true) }) {
                    Text("Download Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmRiskyDownload(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    sslWarningPrompt?.let { ssl ->
        AlertDialog(
            onDismissRequest = { onResolveSslError(false) },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = NyxoraDangerRed) },
            title = { Text("Connection Is Not Private") },
            text = {
                Text(
                    "The security certificate for ${ssl.failingUrl} is not trusted (${ssl.primaryErrorName}).\n\n" +
                        "Attackers might be trying to steal your information."
                )
            },
            confirmButton = {
                Button(onClick = { onResolveSslError(false) }) {
                    Text("Back to Safety")
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolveSslError(true) }) {
                    Text("Proceed (Unsafe)")
                }
            }
        )
    }
}
