package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.local.ThemeMode
import kotlinx.coroutines.launch

/**
 * Software-canvas-safe Material ripple indication that renders press, hover, and focus feedback
 * directly onto Compose's [ContentDrawScope] without instantiating Android's `RippleDrawable`
 * (`RippleDrawable.STYLE_PATTERNED`), preventing `E/RippleDrawable` errors on non-hardware
 * accelerated canvases.
 */
internal object NyxoraCanvasRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NyxoraCanvasRippleNode(interactionSource)
    }

    override fun hashCode(): Int = -1948271635

    override fun equals(other: Any?): Boolean = other === this

    private class NyxoraCanvasRippleNode(
        private val interactionSource: InteractionSource
    ) : Modifier.Node(), DrawModifierNode {
        private var isPressed = false
        private var isHovered = false
        private var isFocused = false
        private var pressPosition: Offset = Offset.Unspecified

        override fun onAttach() {
            coroutineScope.launch {
                var pressCount = 0
                var hoverCount = 0
                var focusCount = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> {
                            pressCount++
                            pressPosition = interaction.pressPosition
                        }
                        is PressInteraction.Release -> {
                            pressCount = (pressCount - 1).coerceAtLeast(0)
                        }
                        is PressInteraction.Cancel -> {
                            pressCount = (pressCount - 1).coerceAtLeast(0)
                        }
                        is HoverInteraction.Enter -> hoverCount++
                        is HoverInteraction.Exit -> hoverCount = (hoverCount - 1).coerceAtLeast(0)
                        is FocusInteraction.Focus -> focusCount++
                        is FocusInteraction.Unfocus -> focusCount = (focusCount - 1).coerceAtLeast(0)
                    }
                    val nextPressed = pressCount > 0
                    val nextHovered = hoverCount > 0
                    val nextFocused = focusCount > 0
                    if (isPressed != nextPressed || isHovered != nextHovered || isFocused != nextFocused) {
                        isPressed = nextPressed
                        isHovered = nextHovered
                        isFocused = nextFocused
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (isPressed) {
                drawRect(color = Color.White.copy(alpha = 0.10f))
                val center = if (pressPosition != Offset.Unspecified &&
                    pressPosition.x.isFinite() &&
                    pressPosition.y.isFinite()
                ) {
                    pressPosition
                } else {
                    this.center
                }
                val radius = (size.maxDimension * 0.55f).coerceAtLeast(16f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = radius,
                    center = center
                )
            } else if (isHovered || isFocused) {
                drawRect(color = Color.White.copy(alpha = 0.06f))
            }
        }
    }
}

private val NyxoraDarkScheme = darkColorScheme(
    primary = NyxoraElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF172C48),
    onPrimaryContainer = Color(0xFFE2E8F0),
    secondary = NyxoraTextSecondaryDark,
    onSecondary = Color(0xFF060E1B),
    secondaryContainer = Color(0xFF152640),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = NyxoraCyanHighlight,
    background = NyxoraCharcoalDeep,
    onBackground = NyxoraTextPrimaryDark,
    surface = NyxoraCharcoalSurface,
    onSurface = NyxoraTextPrimaryDark,
    surfaceVariant = NyxoraCharcoalElevated,
    onSurfaceVariant = NyxoraTextSecondaryDark,
    outline = NyxoraCharcoalBorder,
    error = NyxoraDangerRed,
    onError = Color.White
)

private val NyxoraLightScheme = NyxoraDarkScheme

val NyxoraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NyxoraTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) NyxoraDarkScheme else NyxoraLightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findThemeActivity() ?: return@SideEffect
            val window = activity.window ?: return@SideEffect
            val darkWindowBg = AndroidColor.parseColor("#060E1B")
            val darkNavBarBg = AndroidColor.parseColor("#071222")
            window.setBackgroundDrawable(ColorDrawable(darkWindowBg))
            window.decorView.setBackgroundColor(darkWindowBg)
            @Suppress("DEPRECATION")
            val isFullscreen = (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
            if (!isFullscreen) {
                @Suppress("DEPRECATION")
                window.statusBarColor = AndroidColor.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = darkNavBarBg
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                @Suppress("DEPRECATION")
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = NyxoraShapes
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            LocalIndication provides NyxoraCanvasRippleIndication,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    NyxoraTheme(
        themeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT,
        content = content
    )
}

private fun Context.findThemeActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
