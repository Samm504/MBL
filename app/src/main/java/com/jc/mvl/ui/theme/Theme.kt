package com.jc.mvl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4FFF), // violet accent
    secondary = Color(0xFFD0BCFF),
    background = Color(0xFFF9F6FF),
    onBackground = Color(0xFF1C1B1F),
)

@Composable
fun MVLTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}