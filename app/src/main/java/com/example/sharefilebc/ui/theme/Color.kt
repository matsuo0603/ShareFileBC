package com.example.sharefilebc.ui.theme

import androidx.compose.ui.graphics.Color

/* ----------------------------------------------------------
 *  iOS 風デザインで共通して使う色
 * ---------------------------------------------------------- */

/** 🔵 iOS純正の青色（全画面でこれを使う） */
val IosBlue = Color(0xFF0A84FF)

/** 🔴 iOS純正エラー色 */
val IosRed = Color(0xFFFF3B30)

/** 🔳 半透明の黒（モーダル背景用 - アカウントページ透ける） */
val ModalOverlay = Color(0x66000000)

/** ⚪ 白 */
val PureWhite = Color(0xFFFFFFFF)

/** iOSの「超薄い灰色」背景 (#F2F2F7) */
val IosGroupedBG = Color(0xFFF2F2F7)

/** iOSのカード背景（白） */
val IosCardBG = Color(0xFFFFFFFF)

/** iOSの淡い境界線グレー */
val IosSeparator = Color(0xFFC6C6C8)

/** iOSの文字グレー */
val IosTextGray = Color(0xFF3A3A3C)

/* ----------------------------------------------------------
 * MaterialTheme に紐づく値（必要に応じて IosBlue を参照）
 * ---------------------------------------------------------- */

/* -------- Light (iOSライク) -------- */
val md_theme_light_primary = IosBlue
val md_theme_light_onPrimary = Color.White

val md_theme_light_secondary = Color(0xFF30D158)
val md_theme_light_onSecondary = Color.Black

val md_theme_light_tertiary = Color(0xFFFF9F0A)
val md_theme_light_onTertiary = Color.Black

val md_theme_light_background = IosGroupedBG
val md_theme_light_onBackground = Color.Black

val md_theme_light_surface = IosCardBG
val md_theme_light_onSurface = Color.Black

val md_theme_light_surfaceVariant = Color(0xFFEFEFF4)
val md_theme_light_onSurfaceVariant = IosTextGray

val md_theme_light_outline = IosSeparator

val md_theme_light_error = IosRed
val md_theme_light_onError = Color.White

/* -------- ナビバーなどの非アクティブアイコン -------- */
val md_nav_light_icon_inactive = Color(0xFF8E8E93)


/* -------- Dark (iOSライク) -------- */
val md_theme_dark_primary = IosBlue
val md_theme_dark_onPrimary = Color.Black

val md_theme_dark_secondary = Color(0xFF30D158)
val md_theme_dark_onSecondary = Color.Black

val md_theme_dark_tertiary = Color(0xFFFF9F0A)
val md_theme_dark_onTertiary = Color.Black

val md_theme_dark_background = Color(0xFF1C1C1E)
val md_theme_dark_onBackground = Color.White

val md_theme_dark_surface = Color(0xFF2C2C2E)
val md_theme_dark_onSurface = Color.White

val md_theme_dark_surfaceVariant = Color(0xFF1F1F21)
val md_theme_dark_onSurfaceVariant = Color(0xFFEBEBF5)

val md_theme_dark_outline = Color(0xFF3A3A3C)

val md_theme_dark_error = Color(0xFFFF453A)
val md_theme_dark_onError = Color.Black

val md_nav_dark_icon_inactive = Color(0xFF9FA3A8)


/* ----------------------------------------------------------
 *  Shared Screen Custom Palette
 * ---------------------------------------------------------- */

object SharedScreenColors {
    val Background = md_theme_light_surfaceVariant
    val TabSelected = Color.White
    val TabUnselected = Color(0xFFD1D1D6)
    val UserCardBackground = Color.White
    val UserCardBorder = Color(0xFFDDDEE3)
    val UserEmail = Color(0xFF8E8E93)
    val DateLabel = Color(0xFF8E8E93)
}

/* ----------------------------------------------------------
 *  Home Screen Controls
 * ---------------------------------------------------------- */

object HomeScreenButtonColors {
    val BalanceButtonBackgroundLight = Color(0xFFE5E5EA)
    val BalanceButtonBackgroundDark = Color(0xFF3A3A3C)
    val BalanceButtonContent = Color.Black
}

/* ----------------------------------------------------------
 *  Avatar Colors (Google系）
 * ---------------------------------------------------------- */

val AvatarPaletteLight = listOf(
    Color(0xFFDB4437), Color(0xFFF4B400), Color(0xFF0F9D58),
    Color(0xFF4285F4), Color(0xFFAB47BC), Color(0xFF00ACC1),
    Color(0xFF5E35B1), Color(0xFFF09300), Color(0xFF7CB342),
    Color(0xFF00897B), Color(0xFF795548), Color(0xFF546E7A)
)

val AvatarPaletteDark = listOf(
    Color(0xFFF28B82), Color(0xFFFDE293), Color(0xFF81C995),
    Color(0xFFAECBFA), Color(0xFFD7AEFB), Color(0xFF80DEEA),
    Color(0xFFB39DDB), Color(0xFFF6AD70), Color(0xFFC5E1A5),
    Color(0xFF8DDAD5), Color(0xFFBCAAA4), Color(0xFF9AA0A6)
)
