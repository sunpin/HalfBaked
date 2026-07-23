package com.noaicam.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0B0E14)
val DarkSurface = Color(0xFF161B22)
val DarkSurfaceVariant = Color(0xFF21262D)
val RawGold = Color(0xFFFFB703)
val RawGoldDark = Color(0xFFFB8500)
val RawBypassGreen = Color(0xFF06D6A0)
val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)

private val DarkColorScheme = darkColorScheme(
    primary = RawGold,
    onPrimary = Color.Black,
    primaryContainer = RawGoldDark,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    secondary = RawBypassGreen
)

@Composable
fun NoAiCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
