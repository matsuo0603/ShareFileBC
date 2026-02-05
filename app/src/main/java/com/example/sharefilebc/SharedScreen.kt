package com.example.sharefilebc

import android.util.Log
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
import com.example.sharefilebc.ui.theme.SharedScreenColors
import com.example.sharefilebc.ui.theme.SharedScreenDimens

private enum class SharedInnerTab(val label: String) {
    Sent("送信"),
    Received("受信");
}

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
    deepLinkTxid: String? = null,
    deepLinkRefundAddress: String? = null,
) {
    // ✅ folderId/fileId がなくても uuid/txid があれば「受信」タブ開始にする
    val shouldOpenReceived =
        (initialFolderId != null || initialFileId != null) ||
                (!deepLinkUuid.isNullOrBlank() && !deepLinkTxid.isNullOrBlank())

    var selectedTab by remember(initialFolderId, initialFileId, deepLinkUuid, deepLinkTxid) {
        mutableStateOf(if (shouldOpenReceived) SharedInnerTab.Received else SharedInnerTab.Sent)
    }

    val context = LocalContext.current

    // ✅ Compose再描画対策（メモリ内ガード）
    // ※プロセス死後の多重は ShareProcessor 側の idempotent 化で吸収する
    val processedMap = remember { mutableStateMapOf<String, Boolean>() }

    // ✅ Swift版思想：DeepLinkを受けたら Shared側で即処理（UI表示とは独立して1回だけ）
    LaunchedEffect(
        deepLinkUuid,
        deepLinkTxid,
        deepLinkSenderPublicKey,
        deepLinkThreshold,
        deepLinkRefundAddress
    ) {
        val uuid = deepLinkUuid
        val txid = deepLinkTxid
        val senderKey = deepLinkSenderPublicKey
        val threshold = deepLinkThreshold

        // deep link が揃ってないなら何もしない
        if (uuid.isNullOrBlank() || txid.isNullOrBlank() || senderKey.isNullOrBlank() || threshold == null) {
            return@LaunchedEffect
        }

        val key = "$uuid|$txid"
        if (processedMap[key] == true) {
            Log.d("SharedScreen", "⏭️ skip processReceivedShare (already launched in this session) key=$key")
            return@LaunchedEffect
        }

        processedMap[key] = true

        Log.d("SharedScreen", "🔍 processReceivedShare start uuid=$uuid txid=${txid.take(8)}... threshold=$threshold")

        runCatching {
            ShareProcessor.processReceivedShare(
                context = context,
                uuid = uuid,
                txids = listOf(txid),
                senderPublicKey = senderKey,
                refundAddress = deepLinkRefundAddress,
                threshold = threshold,
                colorId = Constants.Strings.tokenColorId
            )
        }.onSuccess { result ->
            Log.d("SharedScreen", "✅ processReceivedShare result=$result uuid=$uuid txid=${txid.take(8)}...")
        }.onFailure {
            Log.e("SharedScreen", "❌ processReceivedShare failed", it)
        }
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
                    // ✅ DownloadScreen は表示専用に戻す
                    // deep link パラメータは渡さない（入口をSharedに一本化）
                    DownloadScreen(
                        initialFolderId = initialFolderId,
                        initialFileId = initialFileId,
                        deepLinkSenderPublicKey = null,
                        deepLinkRecipientEmail = null,
                        deepLinkSenderAddress = null,
                        deepLinkThreshold = null,
                        deepLinkUuid = null,
                        deepLinkTxid = null,
                        deepLinkRefundAddress = null,
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
