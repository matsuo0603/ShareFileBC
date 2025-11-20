package com.example.sharefilebc.ui.theme

import androidx.compose.ui.graphics.Color

/* -------- Light (iOSライク) -------- */
val md_theme_light_primary = Color(0xFF0A84FF)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)

val md_theme_light_secondary = Color(0xFF30D158)
val md_theme_light_onSecondary = Color(0xFF000000)

val md_theme_light_tertiary = Color(0xFFFF9F0A)
val md_theme_light_onTertiary = Color(0xFF000000)

val md_theme_light_background = Color(0xFFF2F2F7)   // grouped 背景
val md_theme_light_onBackground = Color(0xFF000000)

val md_theme_light_surface = Color(0xFFFFFFFF)      // カード/シート/ボトムバーなど白基調
val md_theme_light_onSurface = Color(0xFF000000)

val md_theme_light_surfaceVariant = Color(0xFFEFEFF4)
val md_theme_light_onSurfaceVariant = Color(0xFF3A3A3C)

val md_theme_light_outline = Color(0xFFC6C6C8)

val md_theme_light_error = Color(0xFFFF3B30)
val md_theme_light_onError = Color(0xFFFFFFFF)

/* === 追加: ナビゲーション用の無効アイコン色（iOS系グレー） === */
val md_nav_light_icon_inactive = Color(0xFF8E8E93)

/* -------- Dark (iOSライク) -------- */
val md_theme_dark_primary = Color(0xFF0A84FF)
val md_theme_dark_onPrimary = Color(0xFF000000)

val md_theme_dark_secondary = Color(0xFF30D158)
val md_theme_dark_onSecondary = Color(0xFF000000)

val md_theme_dark_tertiary = Color(0xFFFF9F0A)
val md_theme_dark_onTertiary = Color(0xFF000000)

val md_theme_dark_background = Color(0xFF1C1C1E)
val md_theme_dark_onBackground = Color(0xFFFFFFFF)

val md_theme_dark_surface = Color(0xFF2C2C2E)
val md_theme_dark_onSurface = Color(0xFFFFFFFF)

val md_theme_dark_surfaceVariant = Color(0xFF1F1F21)
val md_theme_dark_onSurfaceVariant = Color(0xFFEBEBF5)

val md_theme_dark_outline = Color(0xFF3A3A3C)

val md_theme_dark_error = Color(0xFFFF453A)
val md_theme_dark_onError = Color(0xFF000000)

/* === 追加: ダーク時の無効アイコン色（やや明るめグレー） === */
val md_nav_dark_icon_inactive = Color(0xFF9FA3A8)

/* -------- Shared Screen Custom Palette -------- */
object SharedScreenColors {
    val Background = md_theme_light_surfaceVariant
    val TabSelected = Color(0xFFFFFFFF)
    val TabUnselected = Color(0xFFD1D1D6)
    val UserCardBackground = Color(0xFFFFFFFF)
    val UserCardBorder = Color(0xFFDDDEE3)
    val UserEmail = Color(0xFF8E8E93)
    val DateLabel = Color(0xFF8E8E93)
}

/* -------- Home Screen Controls -------- */
object HomeScreenButtonColors {
    val BalanceButtonBackgroundLight = Color(0xFFE5E5EA)
    val BalanceButtonBackgroundDark = Color(0xFF3A3A3C)
    val BalanceButtonContent = Color(0xFF000000)
}

/* -------- Account Avatar Palettes (Googleサービスに近い配色) -------- */
val AvatarPaletteLight = listOf(
    Color(0xFFDB4437), // Google Red 500
    Color(0xFFF4B400), // Google Yellow 500
    Color(0xFF0F9D58), // Google Green 500
    Color(0xFF4285F4), // Google Blue 500
    Color(0xFFAB47BC), // Purple 400
    Color(0xFF00ACC1), // Cyan 600
    Color(0xFF5E35B1), // Deep Purple 600
    Color(0xFFF09300), // Orange 600
    Color(0xFF7CB342), // Light Green 600
    Color(0xFF00897B), // Teal 600
    Color(0xFF795548), // Brown 500
    Color(0xFF546E7A)  // Blue Grey 600
)

val AvatarPaletteDark = listOf(
    Color(0xFFF28B82),
    Color(0xFFFDE293),
    Color(0xFF81C995),
    Color(0xFFAECBFA),
    Color(0xFFD7AEFB),
    Color(0xFF80DEEA),
    Color(0xFFB39DDB),
    Color(0xFFF6AD70),
    Color(0xFFC5E1A5),
    Color(0xFF8DDAD5),
    Color(0xFFBCAAA4),
    Color(0xFF9AA0A6)
)
