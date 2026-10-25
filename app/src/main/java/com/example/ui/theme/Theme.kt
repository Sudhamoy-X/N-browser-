package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.local.ThemeMode

private val NyxoraDarkScheme = darkColorScheme(
    primary = NyxoraElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = NyxoraVibrantViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E1065),
    onSecondaryContainer = Color(0xFFEDE9FE),
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

private val NyxoraLightScheme = lightColorScheme(
    primary = NyxoraLightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = NyxoraLightPrimary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF4F46E5),
    background = NyxoraSoftWhite,
    onBackground = NyxoraTextPrimaryLight,
    surface = NyxoraLightSurface,
    onSurface = NyxoraTextPrimaryLight,
    surfaceVariant = NyxoraLightElevated,
    onSurfaceVariant = NyxoraTextSecondaryLight,
    outline = NyxoraLightBorder,
    error = NyxoraDangerRed,
    onError = Color.White
)

// Moderate rounded corners (no excessive pills)
val NyxoraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
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
