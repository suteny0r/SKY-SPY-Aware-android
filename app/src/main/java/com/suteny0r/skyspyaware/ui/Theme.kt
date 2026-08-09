package com.suteny0r.skyspyaware.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SkySpyColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFF00C853),
    tertiary = Color(0xFFFFA726),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF101418),
    onPrimary = Color(0xFF001418),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

@Composable
fun SkySpyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SkySpyColors,
        content = content
    )
}
