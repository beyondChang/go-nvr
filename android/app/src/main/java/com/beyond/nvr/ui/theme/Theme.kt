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
//  炫酷 (Cyber / Neon) palette
//  Cyan-teal primary · Neon-purple secondary · Warm-amber tertiary
// ══════════════════════════════════════════════════════════════════

// ── Dark palette ────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5E0),
    onPrimary = Color(0xFF002B2B),
    primaryContainer = Color(0xFF005050),
    onPrimaryContainer = Color(0xFF80F0F0),
    secondary = Color(0xFFCC66FF),
    onSecondary = Color(0xFF2D004D),
    secondaryContainer = Color(0xFF550080),
    onSecondaryContainer = Color(0xFFE6B3FF),
    tertiary = Color(0xFFFFAA33),
    onTertiary = Color(0xFF332200),
    background = Color(0xFF0A0A0F),
    surface = Color(0xFF14141C),
    surfaceVariant = Color(0xFF1E1E2A),
    onBackground = Color(0xFFE8E8F0),
    onSurface = Color(0xFFE8E8F0),
    onSurfaceVariant = Color(0xFFA0A0B0),
    outline = Color(0xFF3A3A4A),
    error = Color(0xFFFF5577),
    onError = Color(0xFF330011),
    errorContainer = Color(0xFF660022),
    onErrorContainer = Color(0xFFFFB0C0),
)

// ── Light palette ───────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0088CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCECFF),
    onPrimaryContainer = Color(0xFF00263B),
    secondary = Color(0xFF8844CC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBDDFF),
    onSecondaryContainer = Color(0xFF2A0052),
    tertiary = Color(0xFFCC7700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0A0),
    onTertiaryContainer = Color(0xFF332200),
    background = Color(0xFFF2F0F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8E5ED),
    onBackground = Color(0xFF1A1A20),
    onSurface = Color(0xFF1A1A20),
    onSurfaceVariant = Color(0xFF66607A),
    outline = Color(0xFFCCC8D8),
    error = Color(0xFFCC3344),
    onError = Color.White,
    errorContainer = Color(0xFFFFD0D6),
    onErrorContainer = Color(0xFF330011),
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
