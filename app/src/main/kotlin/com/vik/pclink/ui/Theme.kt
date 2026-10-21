package com.vik.pclink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

class Tokens(
    val dark: Boolean,
    val bg: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val fill: Color,
    val material: Color,
    val segment: Color,
    val separator: Color,
    val tint: Color,
    val onTint: Color,
    val accent: Color,
    val green: Color,
    val amber: Color,
)

private fun rgba(hex: Long, alpha: Float) = Color(hex).copy(alpha = alpha)

val LightTokens = Tokens(
    dark = false,
    bg = Color(0xFFF2F2F7),
    label = Color(0xFF1C1C1E),
    secondary = rgba(0xFF3C3C43, .68f),
    tertiary = rgba(0xFF3C3C43, .32f),
    fill = rgba(0xFF787880, .12f),
    material = Color.White,
    segment = Color.White,
    separator = rgba(0xFF3C3C43, .18f),
    tint = Color(0xFF007AFF),
    onTint = Color.White,
    accent = Color(0xFFFF3B30),
    green = Color(0xFF248A3D),
    amber = Color(0xFFB25000),
)

val DarkTokens = Tokens(
    dark = true,
    bg = Color.Black,
    label = Color(0xFFF5F5F7),
    secondary = rgba(0xFFEBEBF5, .64f),
    tertiary = rgba(0xFFEBEBF5, .3f),
    fill = rgba(0xFF787880, .24f),
    material = Color(0xFF1C1C1E),
    segment = Color(0xFF636366),
    separator = rgba(0xFF545458, .6f),
    tint = Color(0xFF0A84FF),
    onTint = Color.White,
    accent = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    amber = Color(0xFFFF9F0A),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

@Composable
fun resolveDark(): Boolean = isSystemInDarkTheme()

@Composable
fun PCLinkTheme(dark: Boolean, content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(primary = t.tint, onPrimary = t.onTint, background = t.bg, surface = t.material, onBackground = t.label, onSurface = t.label, error = t.accent)
    } else {
        lightColorScheme(primary = t.tint, onPrimary = t.onTint, background = t.bg, surface = t.material, onBackground = t.label, onSurface = t.label, error = t.accent)
    }
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
