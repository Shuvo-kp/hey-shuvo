package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

/**
 * Generous line heights and neutral letter spacing ensure Bangla conjuncts and vowel signs
 * (মাত্রা, ী, ু, ূ, ্র, ঁ) render crisply without top/bottom clipping alongside English.
 */
fun buildScaledTypography(scale: Float = 1.0f): Typography {
    val s = scale.coerceIn(0.85f, 1.45f)
    return Typography(
        displaySmall = TextStyle(
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (30f * s).sp,
            lineHeight = (42f * s).sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (23f * s).sp,
            lineHeight = (33f * s).sp
        ),
        titleLarge = TextStyle(
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (19f * s).sp,
            lineHeight = (28f * s).sp
        ),
        titleMedium = TextStyle(
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16f * s).sp,
            lineHeight = (24f * s).sp
        ),
        bodyLarge = TextStyle(
            fontFamily = InterFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (15f * s).sp,
            lineHeight = (24f * s).sp
        ),
        bodyMedium = TextStyle(
            fontFamily = InterFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (13.5f * s).sp,
            lineHeight = (21f * s).sp
        ),
        bodySmall = TextStyle(
            fontFamily = InterFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (12.5f * s).sp,
            lineHeight = (19f * s).sp
        ),
        labelLarge = TextStyle(
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (13.5f * s).sp,
            lineHeight = (20f * s).sp
        ),
        labelSmall = TextStyle(
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (11.5f * s).sp,
            lineHeight = (17f * s).sp
        )
    )
}

val Typography = buildScaledTypography(1.0f)
