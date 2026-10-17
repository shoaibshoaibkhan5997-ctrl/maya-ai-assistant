package com.maya.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MayaDark = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF1B1146),
    primaryContainer = Color(0xFF3A2F7A),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF6CB4FF),
    onSecondary = Color(0xFF00233F),
    background = Color(0xFF0D0E1A),
    onBackground = Color(0xFFE8E8F5),
    surface = Color(0xFF161728),
    onSurface = Color(0xFFE8E8F5),
    surfaceVariant = Color(0xFF22243A),
    onSurfaceVariant = Color(0xFFC4C5DC),
    error = Color(0xFFFF8A9B),
    outline = Color(0xFF5A5C7A),
)

@Composable
fun MayaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MayaDark, typography = Typography(), content = content)
}
