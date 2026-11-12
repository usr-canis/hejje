package com.canis.hejje.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The one accent colour. Everything orange in the app comes from here (and res/values/colors.xml for the icon). */
val Accent = Color(0xFFE8590C)
private val AccentDark = Color(0xFFFF7A33)

/** Colours beyond Material's roles. Dark mode uses slightly brighter route colours for contrast. */
@Immutable
class Palette(
    val isDark: Boolean,
    /** Fills, lines, indicators. */
    val accent: Color,
    /** Accent for small text on the background; darker (light mode) or lighter (dark mode) so it stays readable. */
    val accentText: Color,
    val onAccent: Color,
    /** Slow to fast. One perceptual ramp, not a red/green judgement. */
    val speedRamp: List<Color>,
    val mapBackground: Color,
    val routeCasing: Color,
) {
    /** Colour for a speed in m/s on a fixed 0-120 km/h scale, so colours mean the same on every trip. */
    fun speedColor(speedMs: Float): Color {
        val f = (speedMs * 3.6f / 120f).coerceIn(0f, 1f) * (speedRamp.size - 1)
        val i = f.toInt().coerceAtMost(speedRamp.size - 2)
        return lerp(speedRamp[i], speedRamp[i + 1], f - i)
    }

    /** Shade of the accent for heatmaps and charts; [level] 0..1. */
    fun accentShade(level: Float): Color = lerp(accent.copy(alpha = if (isDark) 0.22f else 0.16f), accent, level.coerceIn(0f, 1f))
}

val LightPalette = Palette(
    isDark = false,
    accent = Accent,
    accentText = Color(0xFFB8430A),
    onAccent = Color(0xFF1C0A00),
    speedRamp = listOf(Color(0xFF3F3A9E), Color(0xFF8E3AA6), Color(0xFFD04476), Color(0xFFE8590C), Color(0xFFF2A413)),
    mapBackground = Color(0xFFF1EEEA),
    routeCasing = Color(0xFFFFFFFF),
)

val DarkPalette = Palette(
    isDark = true,
    accent = AccentDark,
    accentText = Color(0xFFFF9257),
    onAccent = Color(0xFF2B0E00),
    speedRamp = listOf(Color(0xFF7C78FF), Color(0xFFC06BE0), Color(0xFFF0689B), Color(0xFFFF7A33), Color(0xFFFFC247)),
    mapBackground = Color(0xFF181B1E),
    routeCasing = Color(0xFF0E1012),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalReduceMotion = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1C0A00),
    primaryContainer = Color(0xFFFFE6D6),
    onPrimaryContainer = Color(0xFF4A1D00),
    secondary = Color(0xFF6B6661),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6D6),
    onSecondaryContainer = Color(0xFF4A1D00),
    tertiary = Color(0xFF6B6661),
    background = Color(0xFFFAF8F6),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFAF8F6),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFEFEBE7),
    onSurfaceVariant = Color(0xFF68625D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF4F1EE),
    surfaceContainerHigh = Color(0xFFEFEBE7),
    surfaceContainerHighest = Color(0xFFE9E5E0),
    outline = Color(0xFF8A847E),
    outlineVariant = Color(0xFFE2DDD7),
    error = Color(0xFFB3261E),
    inverseSurface = Color(0xFF2C2A28),
    inverseOnSurface = Color(0xFFF4F1EE),
    inversePrimary = AccentDark,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF2B0E00),
    primaryContainer = Color(0xFF4A2410),
    onPrimaryContainer = Color(0xFFFFDCC8),
    secondary = Color(0xFFA39E99),
    onSecondary = Color(0xFF1B1E21),
    secondaryContainer = Color(0xFF4A2410),
    onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFA39E99),
    background = Color(0xFF131517),
    onBackground = Color(0xFFECE9E6),
    surface = Color(0xFF131517),
    onSurface = Color(0xFFECE9E6),
    surfaceVariant = Color(0xFF262A2E),
    onSurfaceVariant = Color(0xFFA8A39E),
    surfaceContainerLowest = Color(0xFF0F1113),
    surfaceContainerLow = Color(0xFF1B1E21),
    surfaceContainer = Color(0xFF1F2326),
    surfaceContainerHigh = Color(0xFF262A2E),
    surfaceContainerHighest = Color(0xFF2E3337),
    outline = Color(0xFF7D7873),
    outlineVariant = Color(0xFF2E3337),
    error = Color(0xFFF2B8B5),
    inverseSurface = Color(0xFFECE9E6),
    inverseOnSurface = Color(0xFF1B1E21),
    inversePrimary = Accent,
)

/** System font only, five sizes, three weights. Tabular figures keep numbers from jumping. */
private fun style(size: Int, weight: FontWeight, line: Int) = TextStyle(
    fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, fontFeatureSettings = "tnum", letterSpacing = 0.sp,
)

private val HejjeTypography = Typography().let { base ->
    val display = style(32, FontWeight.SemiBold, 38)
    val title = style(22, FontWeight.SemiBold, 28)
    val section = style(16, FontWeight.SemiBold, 22)
    val body = style(16, FontWeight.Normal, 24)
    val small = style(14, FontWeight.Normal, 20)
    val label = style(12, FontWeight.Medium, 16)
    base.copy(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = display, headlineMedium = title, headlineSmall = title,
        titleLarge = title, titleMedium = section, titleSmall = section.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = body, bodyMedium = small, bodySmall = label.copy(fontWeight = FontWeight.Normal),
        labelLarge = small.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label,
    )
}

/**
 * @param themeSetting 0 = follow the system, 1 = light, 2 = dark. No Material You dynamic colour.
 */
@Composable
fun HejjeTheme(themeSetting: Int = 0, content: @Composable () -> Unit) {
    val dark = when (themeSetting) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    // Honour the system "Remove animations" setting.
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = HejjeTypography, content = content)
    }
}
