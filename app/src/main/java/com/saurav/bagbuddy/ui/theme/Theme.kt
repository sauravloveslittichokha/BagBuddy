package com.saurav.bagbuddy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BagBuddyAccent,
    secondary = Color(0xFFA6A0B7),
    tertiary = Color(0xFFFFB5CF),
    background = BagBuddyBackground,
    surface = BagBuddySurface,
    onPrimary = Color(0xFF171324),
    onSecondary = Color(0xFF17151D),
    onTertiary = Color(0xFF28151E),
    onBackground = BagBuddyText,
    onSurface = BagBuddyText
)

@Composable
fun BagBuddyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}