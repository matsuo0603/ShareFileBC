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
    val Background = Color(0xFFE5E5EA)
    val TabSelected = Color(0xFFFFFFFF)
    val TabUnselected = Color(0xFFD1D1D6)
    val UserCardBackground = Color(0xFFFFFFFF)
    val UserCardBorder = Color(0xFFDDDEE3)
    val UserEmail = Color(0xFF8E8E93)
    val DateLabel = Color(0xFF3A3A3C)
}