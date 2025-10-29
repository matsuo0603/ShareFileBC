package com.example.sharefilebc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.absoluteValue

data class AvatarColorPair(
    val background: Color,
    val content: Color
)

/**
 * アバター用の背景色/文字色を返す。
 * - ダーク/ライトでパレットを切替
 * - key（メール等）に基づいて安定した色を選択
 * - パレットが空のときは MaterialTheme の secondaryContainer をフォールバックに使う
 */
@Composable
fun rememberAvatarColors(key: String): AvatarColorPair {
    val isDarkTheme = isSystemInDarkTheme()
    val palette = if (isDarkTheme) AvatarPaletteDark else AvatarPaletteLight
    val safeKey = if (key.isNotBlank()) key else "default"

    // ⚠ MaterialTheme は @Composable。非 @Composable なラムダ内で触らないよう、先に外で取得しておく。
    val fallbackColor = MaterialTheme.colorScheme.secondaryContainer

    val background = remember(safeKey, isDarkTheme, fallbackColor, palette) {
        val colors = if (palette.isEmpty()) {
            listOf(fallbackColor)
        } else {
            palette
        }
        colors[safeKey.hashCode().absoluteValue % colors.size]
    }

    val content = remember(background) {
        if (background.luminance() < 0.5f) Color.White else Color.Black
    }

    return AvatarColorPair(
        background = background,
        content = content
    )
}
