package com.beyond.nvr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ══════════════════════════════════════════════════════════════════
//  小清新 (Fresh & Light) palette
//  Mint green primary · Soft coral secondary · Lavender tertiary
// ══════════════════════════════════════════════════════════════════

// ── Dark palette ────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7DD4BE),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF1C6B58),
    onPrimaryContainer = Color(0xFFBBF0DE),
    secondary = Color(0xFFF0B0A0),
    onSecondary = Color(0xFF3D1E15),
    secondaryContainer = Color(0xFF5D3428),
    onSecondaryContainer = Color(0xFFFCE0D4),
    tertiary = Color(0xFFD0B8E8),
    onTertiary = Color(0xFF2D1B42),
    tertiaryContainer = Color(0xFF4A305E),
    onTertiaryContainer = Color(0xFFE6D4F8),
    background = Color(0xFF1A1C18),
    surface = Color(0xFF22241F),
    surfaceVariant = Color(0xFF2E302A),
    onBackground = Color(0xFFE0DDD4),
    onSurface = Color(0xFFE0DDD4),
    onSurfaceVariant = Color(0xFFC4C1B8),
    outline = Color(0xFF7A786E),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF3E1515),
    errorContainer = Color(0xFF6E2727),
    onErrorContainer = Color(0xFFFCD4D4),
)

// ── Light palette ───────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4DAF98),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F0E8),
    onPrimaryContainer = Color(0xFF0A3D30),
    secondary = Color(0xFFD4927E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE8E0),
    onSecondaryContainer = Color(0xFF3D1E15),
    tertiary = Color(0xFF9E82BD),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE0F8),
    onTertiaryContainer = Color(0xFF2D1B42),
    background = Color(0xFFFAF8F2),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0EEE8),
    onBackground = Color(0xFF2C2B26),
    onSurface = Color(0xFF2C2B26),
    onSurfaceVariant = Color(0xFF76736A),
    outline = Color(0xFFD4D0C6),
    error = Color(0xFFD96B6B),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E4),
    onErrorContainer = Color(0xFF3E1515),
)

@Composable
fun GoNvrTheme(
    theme: String = "system",
    content: @Composable () -> Unit,
) {
    val isDark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
