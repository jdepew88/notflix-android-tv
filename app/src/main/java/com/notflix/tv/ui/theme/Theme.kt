package com.notflix.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Design tokens from src/app/globals.css (Phase 4). */
object NotflixColors {
    val Canvas = Color(0xFF141414)
    val Surface = Color(0xFF1A1A1D)
    val Surface2 = Color(0xFF232327)
    val Surface3 = Color(0xFF2E2E33)
    val Scrim = Color(0xB8000000)
    val Ink = Color(0xFFFFFFFF)
    val Ink2 = Color(0xFFD6D6DA)
    val Ink3 = Color(0xFFA3A3AB)
    val Ink4 = Color(0xFF75757D)
    val Brand = Color(0xFFE50914)
    val BrandHover = Color(0xFFF6121D)
    val BrandDeep = Color(0xFF9C050C)
    val Line = Color(0x1AFFFFFF)
    val LineStrong = Color(0x38FFFFFF)
    val Focus = Color(0xFFFFFFFF)
    val Success = Color(0xFF46D369)
}

private val colors = darkColorScheme(
    primary = NotflixColors.Brand,
    onPrimary = NotflixColors.Ink,
    secondary = NotflixColors.Surface3,
    onSecondary = NotflixColors.Ink,
    background = NotflixColors.Canvas,
    onBackground = NotflixColors.Ink,
    surface = NotflixColors.Surface,
    onSurface = NotflixColors.Ink,
    surfaceVariant = NotflixColors.Surface2,
    onSurfaceVariant = NotflixColors.Ink2,
    border = NotflixColors.LineStrong,
    error = NotflixColors.Brand,
)

private val base = Typography()

private val typography = Typography(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 48.sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

@Composable
fun NotflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography) {
        // tv-material Text inherits LocalContentColor, which is black outside a Surface.
        CompositionLocalProvider(LocalContentColor provides NotflixColors.Ink, content = content)
    }
}

val SubtitleStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 38.sp)
