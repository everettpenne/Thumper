package com.everettpenne.thumper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The iOS-style palette: system blue accent, grouped backgrounds, and "secondary" fills for cards. */
object Thumper {
    /** Thumper violet: tab selection, icons, buttons. */
    val accent = Color(0xFF7B61FF)
    /** The other end of Jellyfin's brand gradient. */
    val purple = Color(0xFFAA5CC3)
    val link = Color(0xFF0A84FF)
    val green = Color(0xFF34C759)
    val indigo = Color(0xFF5E5CE6)
    val red = Color(0xFFFF453A)
    val yellow = Color(0xFFFFD60A)
    val orange = Color(0xFFFF9F0A)
}

private val Light = lightColorScheme(
    primary = Thumper.accent, onPrimary = Color.White, background = Color(0xFFF2F2F7), surface = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E), onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF6C6C70), outlineVariant = Color(0xFFD1D1D6), error = Thumper.red)
private val Dark = darkColorScheme(
    primary = Thumper.accent, onPrimary = Color.White, background = Color(0xFF000000), surface = Color(0xFF000000), onBackground = Color(0xFFF2F2F7), onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFF98989D), outlineVariant = Color(0xFF38383A), error = Thumper.red)

@Composable
fun ThumperTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = ThumperTypography,
        shapes = Shapes(small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp))) {
        // Text outside a Material surface takes this colour. Without it, titles and settings rows were black on the black dark-mode page.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
    }
}
