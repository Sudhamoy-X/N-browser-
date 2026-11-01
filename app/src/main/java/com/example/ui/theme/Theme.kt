package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.local.ThemeMode

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
        shapes = NyxoraShapes,
        content = content
    )
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
