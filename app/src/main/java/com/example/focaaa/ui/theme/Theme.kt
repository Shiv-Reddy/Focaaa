package com.example.focaaa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8ACEC5),
    onPrimary = FocaaaPalette.Deep,
    primaryContainer = FocaaaPalette.Deep,
    onPrimaryContainer = Color(0xFFE3F4EF),
    secondary = Color(0xFFA8C4BD),
    tertiary = FocaaaPalette.Gold,
    background = Color(0xFF102527),
    onBackground = Color(0xFFE8F3F0),
    surface = Color(0xFF193438),
    onSurface = Color(0xFFE8F3F0),
    surfaceVariant = Color(0xFF28474A),
    onSurfaceVariant = Color(0xFFBED3CF)
)

private val LightColorScheme = lightColorScheme(
    primary = FocaaaPalette.Primary,
    onPrimary = Color.White,
    primaryContainer = FocaaaPalette.Pale,
    onPrimaryContainer = FocaaaPalette.Ink,
    secondary = Color(0xFF577A78),
    tertiary = Color(0xFF8B6B31),
    background = FocaaaPalette.Canvas,
    onBackground = FocaaaPalette.Ink,
    surface = Color.White,
    onSurface = FocaaaPalette.Ink,
    surfaceVariant = FocaaaPalette.Pale,
    onSurfaceVariant = FocaaaPalette.Muted,
    outline = FocaaaPalette.Line
)

@Composable
fun FocaaaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
