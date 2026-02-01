package com.example.sharefilebc

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.ui.theme.SharedScreenColors
import com.example.sharefilebc.ui.theme.SharedScreenDimens

// 送信・受信 のタブ
private enum class SharedInnerTab(val label: String) {
    Sent("送信"),
    Received("受信");
}

/**
 * 共有タブ全体の画面。
 *
 * - initialFolderId / initialFileId / deepLinkSenderPublicKey / deepLinkRecipientEmail は
 *   Deep Link で開かれたときだけ埋まる想定なので、すべて null 許容 & デフォルト null にしておく。
 * - 既存の呼び出しコードは SharedScreen() / SharedScreen(initialFolderId = ...) だけでもOK。
 */
@Composable
fun SharedScreen(
    modifier: Modifier = Modifier,
    initialFolderId: String? = null,
    initialFileId: String? = null,
    deepLinkSenderPublicKey: String? = null,
    deepLinkRecipientEmail: String? = null,
    deepLinkSenderAddress: String? = null,
    deepLinkThreshold: ULong? = null,
) {
    // DeepLink でフォルダ or ファイル指定があれば「受信」タブから開始
    var selectedTab by remember(initialFolderId, initialFileId) {
        mutableStateOf(
            if (initialFolderId != null || initialFileId != null) {
                SharedInnerTab.Received
            } else {
                SharedInnerTab.Sent
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SharedScreenColors.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "共有ファイル",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        SharedTabSelector(
            selectedTab = selectedTab,
            onSelected = { selectedTab = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            SharedInnerTab.Sent -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // 送信済みファイル一覧（既存の Composable をそのまま呼ぶ）
                    SentFilesScreen(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            SharedInnerTab.Received -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // 受信タブでは、DeepLink から渡ってきた情報を DownloadScreen に渡す想定。
                    // いまは initialFolderId だけを使っている既存実装があるので、
                    // それを壊さないように initialFolderId だけ渡しておく。
                    DownloadScreen(
                        initialFolderId = initialFolderId,
                        // DeepLink 関連の引数は、DownloadScreen 側で対応したタイミングで使う前提。
                        initialFileId = initialFileId,
                        deepLinkSenderPublicKey = deepLinkSenderPublicKey,
                        deepLinkRecipientEmail = deepLinkRecipientEmail,
                        deepLinkSenderAddress = deepLinkSenderAddress,
                        deepLinkThreshold = deepLinkThreshold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedTabSelector(
    selectedTab: SharedInnerTab,
    onSelected: (SharedInnerTab) -> Unit
) {
    val containerShape = RoundedCornerShape(SharedScreenDimens.TabContainerCorner)
    val indicatorShape = RoundedCornerShape(SharedScreenDimens.TabItemCorner)
    val tabs = SharedInnerTab.entries.toList()
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(SharedScreenColors.TabUnselected.copy(alpha = 0.4f))
            .padding(3.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        ) {
            val indicatorWidth = maxWidth / tabs.size
            val targetOffset = indicatorWidth * selectedIndex
            val animatedOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = 120),
                label = "shared_tab_indicator"
            )

            Box(modifier = Modifier.fillMaxSize()) {
                // 背景のインジケータ
                Box(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .width(indicatorWidth)
                        .fillMaxHeight()
                        .clip(indicatorShape)
                        .background(SharedScreenColors.TabSelected)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEach { tab ->
                        val isSelected = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onSelected(tab)
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
