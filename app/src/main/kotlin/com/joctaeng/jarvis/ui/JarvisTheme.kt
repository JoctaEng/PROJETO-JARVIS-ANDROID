package com.joctaeng.jarvis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF3A66D4)
private val BlueLight = Color(0xFF8EAEFF)
private val Ink = Color(0xFF1B2036)

/** Tema do app, claro e escuro, com o azul do personagem. */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = BlueLight, onPrimary = Ink, secondary = Color(0xFFFF8FA3))
    } else {
        lightColorScheme(primary = Blue, onPrimary = Color.White, secondary = Color(0xFFE0607E))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
