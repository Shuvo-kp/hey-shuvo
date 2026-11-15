package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.core.preferences.AppThemePalette

fun buildHeyShuvoColorScheme(
    darkTheme: Boolean,
    palette: AppThemePalette = AppThemePalette.CYBER_CYAN,
    customHue: Float = 195f
): ColorScheme {
    val safeHue = customHue.coerceIn(0f, 360f)

    if (darkTheme) {
        val (primary, secondary, tertiary, bg, surface, surfaceVar, primaryCont) = when (palette) {
            AppThemePalette.CYBER_CYAN, AppThemePalette.DAYLIGHT_CLEAN -> PaletteTokens(
                primary = HudCyanBright,
                secondary = HudAmber,
                tertiary = HudEmerald,
                bg = ObsidianDeep,
                surface = ObsidianSurface,
                surfaceVar = ObsidianCard,
                primaryCont = ObsidianElevated
            )
            AppThemePalette.EMERALD_MATRIX -> PaletteTokens(
                primary = Color(0xFF00E676),
                secondary = Color(0xFF64FFDA),
                tertiary = Color(0xFFFFD54F),
                bg = Color(0xFF05130D),
                surface = Color(0xFF0A2016),
                surfaceVar = Color(0xFF102E21),
                primaryCont = Color(0xFF15422E)
            )
            AppThemePalette.ROYAL_GOLD -> PaletteTokens(
                primary = Color(0xFFFFCA28),
                secondary = Color(0xFFFF8F00),
                tertiary = Color(0xFF26C6DA),
                bg = Color(0xFF120E05),
                surface = Color(0xFF1E170A),
                surfaceVar = Color(0xFF2B2210),
                primaryCont = Color(0xFF3E3015)
            )
            AppThemePalette.VIOLET_PULSE -> PaletteTokens(
                primary = Color(0xFFB388FF),
                secondary = Color(0xFFEA80FC),
                tertiary = Color(0xFF18FFFF),
                bg = Color(0xFF0B0718),
                surface = Color(0xFF150E2B),
                surfaceVar = Color(0xFF20163E),
                primaryCont = Color(0xFF31215C)
            )
            AppThemePalette.CRIMSON_BLAZE -> PaletteTokens(
                primary = Color(0xFFFF5252),
                secondary = Color(0xFFFFAB40),
                tertiary = Color(0xFF69F0AE),
                bg = Color(0xFF140709),
                surface = Color(0xFF220D11),
                surfaceVar = Color(0xFF32141A),
                primaryCont = Color(0xFF4A1D26)
            )
            AppThemePalette.OCEAN_SAPPHIRE -> PaletteTokens(
                primary = Color(0xFF448AFF),
                secondary = Color(0xFF00E5FF),
                tertiary = Color(0xFF69F0AE),
                bg = Color(0xFF050B18),
                surface = Color(0xFF0A152C),
                surfaceVar = Color(0xFF112142),
                primaryCont = Color(0xFF1A3365)
            )
            AppThemePalette.CUSTOM_COLOR -> PaletteTokens(
                primary = Color.hsv(safeHue, 0.78f, 1.0f),
                secondary = Color.hsv((safeHue + 42f) % 360f, 0.75f, 0.96f),
                tertiary = Color.hsv((safeHue + 145f) % 360f, 0.72f, 0.94f),
                bg = Color.hsv(safeHue, 0.45f, 0.08f),
                surface = Color.hsv(safeHue, 0.44f, 0.14f),
                surfaceVar = Color.hsv(safeHue, 0.42f, 0.21f),
                primaryCont = Color.hsv(safeHue, 0.55f, 0.30f)
            )
        }

        return darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF041318),
            primaryContainer = primaryCont,
            onPrimaryContainer = TextPrimaryDark,
            secondary = secondary,
            onSecondary = Color(0xFF1F1400),
            secondaryContainer = surfaceVar,
            onSecondaryContainer = TextPrimaryDark,
            tertiary = tertiary,
            onTertiary = Color(0xFF002412),
            background = bg,
            onBackground = TextPrimaryDark,
            surface = surface,
            onSurface = TextPrimaryDark,
            surfaceVariant = surfaceVar,
            onSurfaceVariant = TextSecondaryDark,
            error = HudCoral,
            onError = Color(0xFF3B0006),
            errorContainer = Color(0xFF3D131B),
            onErrorContainer = Color(0xFFFFD9DE),
            outline = primary.copy(alpha = 0.45f),
            outlineVariant = primary.copy(alpha = 0.22f)
        )
    } else {
        val (primary, secondary, tertiary, bg, surfaceVar, primaryCont) = when (palette) {
            AppThemePalette.CYBER_CYAN, AppThemePalette.DAYLIGHT_CLEAN -> LightPaletteTokens(
                primary = DaylightPrimary,
                secondary = DaylightSecondary,
                tertiary = Color(0xFF006D37),
                bg = DaylightBg,
                surfaceVar = DaylightCard,
                primaryCont = Color(0xFFD6F4FF)
            )
            AppThemePalette.EMERALD_MATRIX -> LightPaletteTokens(
                primary = Color(0xFF006C45),
                secondary = Color(0xFF006874),
                tertiary = Color(0xFF795900),
                bg = Color(0xFFF2FBF6),
                surfaceVar = Color(0xFFDDF3E7),
                primaryCont = Color(0xFFBDF8D8)
            )
            AppThemePalette.ROYAL_GOLD -> LightPaletteTokens(
                primary = Color(0xFF7A5900),
                secondary = Color(0xFF8B5000),
                tertiary = Color(0xFF006876),
                bg = Color(0xFFFFFBF4),
                surfaceVar = Color(0xFFF7ECD6),
                primaryCont = Color(0xFFFFE08A)
            )
            AppThemePalette.VIOLET_PULSE -> LightPaletteTokens(
                primary = Color(0xFF5E35B1),
                secondary = Color(0xFF8E24AA),
                tertiary = Color(0xFF006874),
                bg = Color(0xFFF8F5FF),
                surfaceVar = Color(0xFFEBE3FA),
                primaryCont = Color(0xFFE1D4FF)
            )
            AppThemePalette.CRIMSON_BLAZE -> LightPaletteTokens(
                primary = Color(0xFFC62828),
                secondary = Color(0xFFAD4E00),
                tertiary = Color(0xFF006C47),
                bg = Color(0xFFFFF7F7),
                surfaceVar = Color(0xFFFCE2E4),
                primaryCont = Color(0xFFFFD9DC)
            )
            AppThemePalette.OCEAN_SAPPHIRE -> LightPaletteTokens(
                primary = Color(0xFF1565C0),
                secondary = Color(0xFF007791),
                tertiary = Color(0xFF006D3B),
                bg = Color(0xFFF2F7FF),
                surfaceVar = Color(0xFFDCE9FC),
                primaryCont = Color(0xFFD3E4FF)
            )
            AppThemePalette.CUSTOM_COLOR -> LightPaletteTokens(
                primary = Color.hsv(safeHue, 0.82f, 0.62f),
                secondary = Color.hsv((safeHue + 40f) % 360f, 0.75f, 0.58f),
                tertiary = Color.hsv((safeHue + 145f) % 360f, 0.75f, 0.54f),
                bg = Color.hsv(safeHue, 0.06f, 0.98f),
                surfaceVar = Color.hsv(safeHue, 0.14f, 0.94f),
                primaryCont = Color.hsv(safeHue, 0.24f, 0.96f)
            )
        }

        return lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryCont,
            onPrimaryContainer = TextPrimaryLight,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = surfaceVar,
            onSecondaryContainer = TextPrimaryLight,
            tertiary = tertiary,
            onTertiary = Color.White,
            background = bg,
            onBackground = TextPrimaryLight,
            surface = DaylightSurface,
            onSurface = TextPrimaryLight,
            surfaceVariant = surfaceVar,
            onSurfaceVariant = TextSecondaryLight,
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            outline = primary.copy(alpha = 0.45f),
            outlineVariant = primary.copy(alpha = 0.22f)
        )
    }
}

private data class PaletteTokens(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val bg: Color,
    val surface: Color,
    val surfaceVar: Color,
    val primaryCont: Color
)

private data class LightPaletteTokens(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val bg: Color,
    val surfaceVar: Color,
    val primaryCont: Color
)

@Composable
fun HeyShuvoTheme(
    darkTheme: Boolean = true,
    palette: AppThemePalette = AppThemePalette.CYBER_CYAN,
    customAccentHue: Float = 195f,
    textScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = buildHeyShuvoColorScheme(
        darkTheme = darkTheme,
        palette = palette,
        customHue = customAccentHue
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = buildScaledTypography(textScale),
        content = content
    )
}
