package com.attendance.tracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Extra soft-UI colours not covered by Material's scheme. */
@Immutable
data class SoftColors(
    val background: Color,
    val card: Color,
    val cardMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val hairline: Color,
    val shadow: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
)

private val LightSoft = SoftColors(
    background = Color(0xFFF5F6FB),
    card = Color(0xFFFFFFFF),
    cardMuted = Color(0xFFF0F2F8),
    textPrimary = Color(0xFF1C2033),
    textSecondary = Color(0xFF7A8099),
    hairline = Color(0xFFE8EAF2),
    shadow = Color(0xFF5B6CFF).copy(alpha = 0.10f),
    accent = Color(0xFF5B6CFF),
    accentSoft = Color(0xFFEBEDFF),
    success = Color(0xFF12B886),
    successSoft = Color(0xFFE3F8F1),
    warning = Color(0xFFF59F00),
    warningSoft = Color(0xFFFFF4DB),
    danger = Color(0xFFF0506E),
    dangerSoft = Color(0xFFFFE8EC),
)

private val DarkSoft = SoftColors(
    background = Color(0xFF0E1016),
    card = Color(0xFF181B24),
    cardMuted = Color(0xFF20242F),
    textPrimary = Color(0xFFECEEF6),
    textSecondary = Color(0xFF9097AE),
    hairline = Color(0xFF262A36),
    shadow = Color.Black.copy(alpha = 0.35f),
    accent = Color(0xFF8C98FF),
    accentSoft = Color(0xFF262B4D),
    success = Color(0xFF3DD6A3),
    successSoft = Color(0xFF16332A),
    warning = Color(0xFFFFC14D),
    warningSoft = Color(0xFF3A2F16),
    danger = Color(0xFFFF7A93),
    dangerSoft = Color(0xFF3D1F27),
)

val LocalSoft = staticCompositionLocalOf { LightSoft }

object Soft {
    val colors: SoftColors @Composable get() = LocalSoft.current
    val cardShape = RoundedCornerShape(28.dp)
    val tileShape = RoundedCornerShape(22.dp)
    val pillShape = RoundedCornerShape(50)
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun AttendanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val soft = if (dark) DarkSoft else LightSoft
    val scheme = if (dark) darkColorScheme(
        primary = soft.accent, onPrimary = Color(0xFF0E1016),
        primaryContainer = soft.accentSoft, onPrimaryContainer = soft.textPrimary,
        background = soft.background, surface = soft.card, surfaceVariant = soft.cardMuted,
        onBackground = soft.textPrimary, onSurface = soft.textPrimary, onSurfaceVariant = soft.textSecondary,
        outline = soft.hairline, outlineVariant = soft.hairline, error = soft.danger,
        surfaceContainer = soft.card, surfaceContainerHigh = soft.card, surfaceContainerHighest = soft.cardMuted,
    ) else lightColorScheme(
        primary = soft.accent, onPrimary = Color.White,
        primaryContainer = soft.accentSoft, onPrimaryContainer = soft.textPrimary,
        background = soft.background, surface = soft.card, surfaceVariant = soft.cardMuted,
        onBackground = soft.textPrimary, onSurface = soft.textPrimary, onSurfaceVariant = soft.textSecondary,
        outline = soft.hairline, outlineVariant = soft.hairline, error = soft.danger,
        surfaceContainer = soft.card, surfaceContainerHigh = soft.card, surfaceContainerHighest = soft.cardMuted,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalSoft provides soft) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
            content = content,
        )
    }
}
