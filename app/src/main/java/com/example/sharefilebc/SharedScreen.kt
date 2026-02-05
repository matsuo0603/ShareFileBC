// SharedScreen.kt
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
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.ui.theme.SharedScreenColors
import com.example.sharefilebc.ui.theme.SharedScreenDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // ✅ folderId/fileId がなくても uuid/txid があれば「受信」タブ開始
    val shouldOpenReceived =
        (initialFolderId != null || initialFileId != null) ||
                (!deepLinkUuid.isNullOrBlank() && !deepLinkTxid.isNullOrBlank())

    var selectedTab by remember(initialFolderId, initialFileId, deepLinkUuid, deepLinkTxid) {
        mutableStateOf(if (shouldOpenReceived) SharedInnerTab.Received else SharedInnerTab.Sent)
    }

    val context = LocalContext.current

    // ✅ 多重実行ガード（メモリ内 + DB存在チェック）
    val processedMap = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(
        selectedTab,
        deepLinkUuid,
        deepLinkTxid,
        deepLinkSenderPublicKey,
        deepLinkThreshold,
        deepLinkRefundAddress
    ) {
        if (selectedTab != SharedInnerTab.Received) return@LaunchedEffect

        val uuid = deepLinkUuid
        val txid = deepLinkTxid
        val senderKey = deepLinkSenderPublicKey
        val threshold = deepLinkThreshold

        if (uuid.isNullOrBlank() || txid.isNullOrBlank() || senderKey.isNullOrBlank() || threshold == null) {
            return@LaunchedEffect
        }

        val key = "$uuid|$txid"

        // ① メモリ内ガード（再描画・タブ切替）
        if (processedMap[key] == true) {
            Log.d("SharedScreen", "⏭️ skip processReceivedShare (already processed in-memory) key=$key")
            return@LaunchedEffect
        }

        // ② DBガード（アプリ再起動・リンク再オープン）
        val alreadyProcessedInDb = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            db.receivedFileDao().findByShareId(uuid) != null
        }
        if (alreadyProcessedInDb) {
            Log.d("SharedScreen", "⏭️ skip processReceivedShare (already processed in-DB) key=$key")
            processedMap[key] = true
            return@LaunchedEffect
        }

        Log.d(
            "SharedScreen",
            "🔍 processReceivedShare start uuid=$uuid txid=${txid.take(8)}... threshold=$threshold"
        )

        val result = runCatching {
            ShareProcessor.processReceivedShare(
                context = context,
                uuid = uuid,
                txids = listOf(txid),
                senderPublicKey = senderKey,
                refundAddress = deepLinkRefundAddress,
                threshold = threshold,
                colorId = Constants.Strings.tokenColorId
            )
        }.onFailure {
            Log.e("SharedScreen", "❌ processReceivedShare failed", it)
        }.getOrNull()

        Log.d("SharedScreen", "✅ processReceivedShare result=$result uuid=$uuid txid=${txid.take(8)}...")

        processedMap[key] = true
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
                    // ✅ DownloadScreen は「表示専用」。deep link 情報は渡してもいいが処理しない。
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
