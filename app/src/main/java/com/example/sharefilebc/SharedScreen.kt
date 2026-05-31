// SharedScreen.kt
package com.example.sharefilebc

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharefilebc.ui.theme.SharedScreenColors
import com.example.sharefilebc.ui.theme.SharedScreenDimens

private enum class SharedInnerTab(val label: String) {
    Sent("送信"),
    Received("受信");
}

private const val DL_TAG = "DL_DEBUG"

@Composable
fun SharedScreen(
    modifier: Modifier = Modifier,
    initialFolderId: String? = null,
    initialFileId: String? = null,
    deepLinkSenderPublicKey: String? = null,
    deepLinkRecipientEmail: String? = null,
    deepLinkSenderAddress: String? = null,
    deepLinkThreshold: ULong? = null,
    deepLinkUuid: String? = null,
    deepLinkTxid: String? = null, // カンマ区切り想定
    deepLinkRefundAddress: String? = null,
) {
    val shouldOpenReceived =
        (initialFolderId != null || initialFileId != null) ||
                (!deepLinkUuid.isNullOrBlank() && !deepLinkTxid.isNullOrBlank())

    var selectedTab by remember(initialFolderId, initialFileId, deepLinkUuid, deepLinkTxid) {
        mutableStateOf(if (shouldOpenReceived) SharedInnerTab.Received else SharedInnerTab.Sent)
    }

    val context = LocalContext.current

    val txidList = remember(deepLinkTxid) {
        deepLinkTxid
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    val processKey = remember(deepLinkUuid, txidList) {
        val uuid = deepLinkUuid?.trim().orEmpty()
        if (uuid.isBlank() || txidList.isEmpty()) "" else "$uuid|${txidList.sorted().joinToString(",")}"
    }

    // ✅ 受信処理は ViewModel の viewModelScope で実行して、Compose の LaunchedEffect キャンセル
    // （LeftCompositionCancellationException）で途中中断されないようにする。
    // ⚠ Redeclaration 回避のため SharedDeepLinkViewModel を使う
    val sharedVm: SharedDeepLinkViewModel = viewModel()

    LaunchedEffect(processKey, selectedTab, deepLinkUuid, deepLinkTxid, deepLinkSenderPublicKey, deepLinkRefundAddress, deepLinkThreshold) {
        val threshold = deepLinkThreshold
            ?.takeIf { it > 0uL }
            ?: WalletSettingsManager.getInstance(context).getPaymentThreshold()

        sharedVm.triggerProcessReceivedShareIfNeeded(
            processKey = processKey,
            selectedTabLabel = selectedTab.label,
            uuid = deepLinkUuid,
            txids = txidList,
            senderPublicKey = deepLinkSenderPublicKey,
            refundAddress = deepLinkRefundAddress,
            threshold = threshold,
            colorId = Constants.Strings.tokenColorId
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
                    SentFilesScreen(modifier = Modifier.fillMaxSize())
                }
            }

            SharedInnerTab.Received -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    DownloadScreen(
                        initialFolderId = initialFolderId,
                        initialFileId = initialFileId,
                        deepLinkSenderPublicKey = deepLinkSenderPublicKey,
                        deepLinkRecipientEmail = deepLinkRecipientEmail,
                        deepLinkSenderAddress = deepLinkSenderAddress,
                        deepLinkThreshold = deepLinkThreshold,
                        deepLinkUuid = deepLinkUuid,
                        deepLinkTxid = deepLinkTxid,
                        deepLinkRefundAddress = deepLinkRefundAddress,
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
                                ) { onSelected(tab) }
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
