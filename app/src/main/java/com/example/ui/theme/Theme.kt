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
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFE2E8F0),
    secondary = NyxoraTextSecondaryDark,
    onSecondary = Color(0xFF0B0C0E),
    secondaryContainer = Color(0xFF1E222B),
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

private val NyxoraLightScheme = lightColorScheme(
    primary = NyxoraLightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = NyxoraLightPrimary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF3B82F6),
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
