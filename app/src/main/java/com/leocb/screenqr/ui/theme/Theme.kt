package com.leocb.screenqr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9F2E3),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCBE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    background = Color(0xFFF2F8F5),
    onBackground = Color(0xFF161D1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDCE5E1),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BD6B8),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF7CF8D8),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1D352F),
    secondaryContainer = Color(0xFF334B44),
    onSecondaryContainer = Color(0xFFCCE8DF),
    background = Color(0xFF0B1210),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF121B18),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4945),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun ScreenQrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
