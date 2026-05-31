package com.example.sharefilebc.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// お手本の“Shapes.kt”構成に合わせ、iOS寄りの大きめ角丸
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(16.dp),   // カード/リスト
    large      = RoundedCornerShape(20.dp),   // ダイアログ/シート
    extraLarge = RoundedCornerShape(28.dp)    // ヒーロー要素など
)
