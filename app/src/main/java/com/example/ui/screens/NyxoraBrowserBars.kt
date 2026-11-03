package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.NyxoraSettingsState
import com.example.domain.browser.BrowserNetworkClient
import com.example.ui.theme.NyxoraDangerRed
import com.example.ui.theme.NyxoraElectricBlue
import com.example.ui.theme.NyxoraSuccessGreen
import com.example.ui.theme.NyxoraWarningAmber
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.OverlaySheet
import com.example.ui.viewmodel.PendingExternalSchemePrompt
import com.example.ui.viewmodel.PendingRiskyDownload
import com.example.ui.viewmodel.PendingSitePermissionPrompt
import com.example.ui.viewmodel.PendingSslErrorPrompt
import kotlinx.coroutines.delay

/**
 * Clean, compact NYXORA Top Bar for URL/search input and real-time thin progress indicator.
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
            durationMillis = if (settings.reducedMotion) 0 else 150,
            easing = FastOutSlowInEasing
        ),
        label = "nyxora_top_progress_scale"
    )

    val animatedProgressAlpha by animateFloatAsState(
        targetValue = if (showProgressBar) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (settings.reducedMotion) 0 else 140,
            easing = FastOutSlowInEasing
        ),
        label = "nyxora_top_progress_alpha"
    )

    // Debounced DNS prefetch for typed host in address bar
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
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
        ) {
            val verticalPad = if (settings.compactBottomToolbar) 4.dp else 6.dp
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
                        .height(if (settings.compactBottomToolbar) 38.dp else 42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,
                            color = if (isEditingAddress) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(10.dp)
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
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = stringResource(R.string.private_browsing_mode),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        activeTab.isHomePage -> {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search_or_enter_address),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        activeTab.isSecureConnection -> {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = "Secure HTTPS connection",
                                tint = NyxoraSuccessGreen,
                                modifier = Modifier
                                    .size(15.dp)
                                    .testTag("top_bar_secure_lock_icon")
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
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
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("toolbar_clear_address_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.clear_input),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    } else if (!activeTab.isHomePage || activeTab.isLoading) {
                        IconButton(
                            onClick = onReloadOrStop,
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("toolbar_reload_button")
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                                contentDescription = if (activeTab.isLoading) {
                                    stringResource(R.string.stop_loading)
                                } else {
                                    stringResource(R.string.reload_page)
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // Thin progress bar in the top bar driven by real progress
            if (showProgressBar || animatedProgressAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
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
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }

            AnimatedVisibility(
                visible = findInPageVisible,
                enter = fadeIn(tween(if (settings.reducedMotion) 0 else 140)),
                exit = fadeOut(tween(if (settings.reducedMotion) 0 else 140))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
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
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { onFindNext(true) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = onCloseFindInPage,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close find in page", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/**
 * Compact, minimalist 5-button Bottom Navigation Bar with consistent soft-grey outline icons:
 * 1. Back (Simple left chevron)
 * 2. Forward (Simple right chevron)
 * 3. Home (Minimal outline home)
 * 4. Tabs (Thin rounded-square outline with centered tab count)
 * 5. Menu (Three thin horizontal lines)
 */
@Composable
fun NyxoraBottomTabManagementBar(
    activeTab: BrowserTab,
    visibleModeTabsCount: Int,
    isPrivateMode: Boolean,
    compactMode: Boolean,
    showOverflowMenu: Boolean,
    hasUpdateAvailable: Boolean = false,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onToggleOverflowMenu: (Boolean) -> Unit
) {
    val canGoBack = !activeTab.isHomePage ||
        activeTab.canGoBack ||
        activeTab.backStack.isNotEmpty() ||
        !activeTab.parentTabId.isNullOrBlank()
    val canGoForward = activeTab.canGoForward || activeTab.forwardStack.isNotEmpty()
    val tabsDescription = stringResource(R.string.nav_tabs)

    val enabledIconTint = if (activeTab.isHomePage) {
        Color(0xFFE2E8F0)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val disabledIconTint = if (activeTab.isHomePage) {
        Color(0xFFE2E8F0).copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    }

    val chevronLeftIcon = remember { buildNyxoraChevronLeftIcon() }
    val chevronRightIcon = remember { buildNyxoraChevronRightIcon() }
    val outlineHomeIcon = remember { buildNyxoraOutlineHomeIcon() }
    val threeLineMenuIcon = remember { buildNyxoraThreeLineMenuIcon() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nyxora_bottom_tab_bar"),
        color = Color(0xFF071222),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .height(if (compactMode) 48.dp else 52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 1. Back: Simple left chevron
            IconButton(
                onClick = onBack,
                enabled = canGoBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .testTag("toolbar_back_button")
            ) {
                Icon(
                    imageVector = chevronLeftIcon,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = if (canGoBack) enabledIconTint else disabledIconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 2. Forward: Simple right chevron
            IconButton(
                onClick = onForward,
                enabled = canGoForward,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .testTag("toolbar_forward_button")
            ) {
                Icon(
                    imageVector = chevronRightIcon,
                    contentDescription = stringResource(R.string.nav_forward),
                    tint = if (canGoForward) enabledIconTint else disabledIconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 3. Home: Minimal outline home
            IconButton(
                onClick = onHome,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .testTag("toolbar_home_or_forward_button")
            ) {
                Icon(
                    imageVector = outlineHomeIcon,
                    contentDescription = stringResource(R.string.nav_home),
                    tint = enabledIconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 4. Tabs: Thin rounded-square outline with the tab count centered inside
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenTabSwitcher)
                    .semantics { contentDescription = tabsDescription }
                    .testTag("toolbar_tabs_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(
                            width = 1.25.dp,
                            color = enabledIconTint,
                            shape = RoundedCornerShape(5.dp)
                        )
                        .testTag("toolbar_tabs_counter_box"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = visibleModeTabsCount.coerceAtMost(99).toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        color = enabledIconTint,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            // 5. Menu: Three thin horizontal lines + small update indicator dot when a newer APK release is available
            IconButton(
                onClick = { onToggleOverflowMenu(!showOverflowMenu) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .testTag("toolbar_overflow_menu_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = threeLineMenuIcon,
                        contentDescription = stringResource(R.string.nav_menu),
                        tint = enabledIconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    if (hasUpdateAvailable) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(NyxoraElectricBlue)
                                .testTag("toolbar_update_indicator_dot")
                        )
                    }
                }
            }
        }
    }
}

internal const val NYXORA_BOTTOM_NAV_ICON_STROKE_WIDTH = 1.6f

internal fun buildNyxoraChevronLeftIcon(): ImageVector =
    ImageVector.Builder(
        name = "NyxoraChevronLeft",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = NYXORA_BOTTOM_NAV_ICON_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14.5f, 6.0f)
            lineTo(8.5f, 12.0f)
            lineTo(14.5f, 18.0f)
        }
    }.build()

internal fun buildNyxoraChevronRightIcon(): ImageVector =
    ImageVector.Builder(
        name = "NyxoraChevronRight",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = NYXORA_BOTTOM_NAV_ICON_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9.5f, 6.0f)
            lineTo(15.5f, 12.0f)
            lineTo(9.5f, 18.0f)
        }
    }.build()

internal fun buildNyxoraOutlineHomeIcon(): ImageVector =
    ImageVector.Builder(
        name = "NyxoraOutlineHome",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = NYXORA_BOTTOM_NAV_ICON_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Roof
            moveTo(4.5f, 10.5f)
            lineTo(12.0f, 4.2f)
            lineTo(19.5f, 10.5f)
            // Walls & base
            moveTo(6.5f, 9.2f)
            lineTo(6.5f, 19.0f)
            lineTo(17.5f, 19.0f)
            lineTo(17.5f, 9.2f)
        }
    }.build()

internal fun buildNyxoraThreeLineMenuIcon(): ImageVector =
    ImageVector.Builder(
        name = "NyxoraThreeLineMenu",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = NYXORA_BOTTOM_NAV_ICON_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5.0f, 7.0f)
            lineTo(19.0f, 7.0f)
            moveTo(5.0f, 12.0f)
            lineTo(19.0f, 12.0f)
            moveTo(5.0f, 17.0f)
            lineTo(19.0f, 17.0f)
        }
    }.build()

/**
 * Compact Bottom Menu Sheet displayed in a clean 3-row x 3-column grid:
 * Row 1: Bookmarks, History, Downloads
 * Row 2: Add Bookmark, Desktop Site, Incognito
 * Row 3: Room Sync, Share, Settings
 */
@Composable
fun NyxoraBottomMenuSheet(
    visible: Boolean,
    reducedMotion: Boolean,
    isPrivateMode: Boolean,
    isDesktopMode: Boolean,
    isCurrentPageBookmarked: Boolean,
    isRoomSyncActive: Boolean = false,
    hasUpdateAvailable: Boolean = false,
    onDismiss: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onToggleIncognito: () -> Unit,
    onToggleAddBookmark: () -> Unit,
    onToggleDesktopSite: () -> Unit,
    onOpenRoomSync: () -> Unit = {},
    onSharePage: () -> Unit,
    onOpenSettings: () -> Unit
) {
    BackHandler(enabled = visible, onBack = onDismiss)

    val animDuration = if (reducedMotion) 0 else 160

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(animDuration)),
        exit = fadeOut(tween(animDuration))
    ) {
        var cumulativeDragDown by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                    .testTag("bottom_menu_scrim")
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { cumulativeDragDown = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                cumulativeDragDown += dragAmount
                            },
                            onDragEnd = {
                                if (cumulativeDragDown > 48f) {
                                    onDismiss()
                                }
                                cumulativeDragDown = 0f
                            }
                        )
                    }
                    .testTag("nyxora_bottom_menu_sheet"),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Subtle swipe-down handle
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outline)
                        )

                        // Row 1: Bookmarks, History, Downloads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BottomMenuGridItem(
                                icon = Icons.Outlined.Bookmarks,
                                label = "Bookmarks",
                                isActive = false,
                                testTag = "menu_item_bookmarks",
                                onClick = {
                                    onDismiss()
                                    onOpenBookmarks()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BottomMenuGridItem(
                                icon = Icons.Outlined.History,
                                label = "History",
                                isActive = false,
                                testTag = "menu_item_history",
                                onClick = {
                                    onDismiss()
                                    onOpenHistory()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BottomMenuGridItem(
                                icon = Icons.Outlined.Download,
                                label = "Downloads",
                                isActive = false,
                                testTag = "menu_item_downloads",
                                onClick = {
                                    onDismiss()
                                    onOpenDownloads()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Row 2: Add Bookmark, Desktop Site, Incognito
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BottomMenuGridItem(
                                icon = if (isCurrentPageBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                                label = if (isCurrentPageBookmarked) "Bookmarked" else "Add Bookmark",
                                isActive = isCurrentPageBookmarked,
                                testTag = "menu_item_add_bookmark",
                                onClick = {
                                    onDismiss()
                                    onToggleAddBookmark()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BottomMenuGridItem(
                                icon = Icons.Outlined.Computer,
                                label = "Desktop Site",
                                isActive = isDesktopMode,
                                testTag = "menu_item_desktop_site",
                                onClick = {
                                    onDismiss()
                                    onToggleDesktopSite()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BottomMenuGridItem(
                                icon = Icons.Outlined.VisibilityOff,
                                label = "Incognito",
                                isActive = isPrivateMode,
                                testTag = "menu_item_incognito",
                                onClick = {
                                    onDismiss()
                                    onToggleIncognito()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Row 3: Room Sync, Share, Settings
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BottomMenuGridItem(
                                icon = Icons.Outlined.CastConnected,
                                label = "Room Sync",
                                isActive = isRoomSyncActive,
                                testTag = "menu_item_room_sync",
                                onClick = {
                                    onDismiss()
                                    onOpenRoomSync()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BottomMenuGridItem(
                                icon = Icons.Outlined.Share,
                                label = "Share",
                                isActive = false,
                                testTag = "menu_item_share",
                                onClick = {
                                    onDismiss()
                                    onSharePage()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            BottomMenuGridItem(
                                icon = Icons.Outlined.Settings,
                                label = "Settings",
                                isActive = false,
                                showBadgeDot = hasUpdateAvailable,
                                badgeTestTag = "menu_settings_update_indicator",
                                testTag = "menu_item_settings",
                                onClick = {
                                    onDismiss()
                                    onOpenSettings()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun BottomMenuGridItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showBadgeDot: Boolean = false,
    badgeTestTag: String? = null
) {
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val contentTint = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentTint,
                modifier = Modifier.size(20.dp)
            )
            if (showBadgeDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(NyxoraElectricBlue)
                        .let { base -> if (badgeTestTag != null) base.testTag(badgeTestTag) else base }
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = contentTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
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
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Private Session Locked",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
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
            icon = { Icon(Icons.Outlined.Security, contentDescription = null, tint = NyxoraElectricBlue) },
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
            icon = { Icon(Icons.Outlined.Public, contentDescription = null, tint = NyxoraElectricBlue) },
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
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = NyxoraWarningAmber) },
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
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = NyxoraDangerRed) },
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
