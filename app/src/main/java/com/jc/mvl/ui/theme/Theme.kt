package com.jc.mvl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4FFF),
    secondary = Color(0xFFD0BCFF),
    background = Color(0xFFF9F6FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFEDE7FF),
    onSurfaceVariant = Color(0xFF1C1B1F),
    primaryContainer = Color(0xFFEEF6FF),
    onPrimaryContainer = Color(0xFF003366),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFB8FF),
    secondary = Color(0xFFD0BCFF),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF2B2930),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D2B33),
    onSurfaceVariant = Color(0xFFE6E1E5),
    primaryContainer = Color(0xFF3A1F6B),
    onPrimaryContainer = Color(0xFFE8DDFF),
)

@Composable
fun MVLTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}