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

    val processedMap = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(processKey, selectedTab) {
        if (processKey.isBlank()) {
            Log.d(DL_TAG, "[SharedScreen] skip: empty processKey")
            return@LaunchedEffect
        }
        if (selectedTab != SharedInnerTab.Received) {
            Log.d(DL_TAG, "[SharedScreen] skip: selectedTab=$selectedTab processKey=$processKey")
            return@LaunchedEffect
        }

        val uuid = deepLinkUuid?.trim()
        val senderKey = deepLinkSenderPublicKey?.trim()
        // ✅ Swift版は paymentThreshold がデフォルト 1（UserDefaults）
        // Android側も deep link 側に threshold が無い/0 の場合は WalletSettings の値を使う。
        val threshold = deepLinkThreshold
            ?.takeIf { it > 0uL }
            ?: WalletSettingsManager.getInstance(context).getPaymentThreshold()

        if (uuid.isNullOrBlank() || txidList.isEmpty() || senderKey.isNullOrBlank()) {
            Log.d(
                DL_TAG,
                "[SharedScreen] missing params uuid=$uuid txids=${txidList.size} senderKeyEmpty=${senderKey.isNullOrBlank()} threshold=$threshold"
            )
            return@LaunchedEffect
        }

        // ① メモリガード
        if (processedMap[processKey] == true) {
            Log.d(DL_TAG, "[SharedScreen] skip by memory guard key=$processKey")
            return@LaunchedEffect
        }

        // ② DBガード（received_files OR refund_tasks）
        val alreadyProcessedInDb = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val hasReceived = db.receivedFileDao().findByShareId(uuid) != null
            val hasRefundTask = db.refundTaskDao().findByShareId(uuid) != null
            hasReceived || hasRefundTask
        }
        if (alreadyProcessedInDb) {
            processedMap[processKey] = true
            Log.d(DL_TAG, "[SharedScreen] skip by DB guard key=$processKey")
            return@LaunchedEffect
        }

        Log.d(DL_TAG, "[SharedScreen] processReceivedShare start key=$processKey")

        runCatching {
            ShareProcessor.processReceivedShare(
                context = context,
                uuid = uuid,
                txids = txidList,
                senderPublicKey = senderKey,
                refundAddress = deepLinkRefundAddress,
                threshold = threshold,
                colorId = Constants.Strings.tokenColorId
            )
        }.onSuccess { result ->
            processedMap[processKey] = true
            Log.d(DL_TAG, "[SharedScreen] processReceivedShare end key=$processKey result=$result")
        }.onFailure { throwable ->
            Log.e(DL_TAG, "[SharedScreen] processReceivedShare failed key=$processKey", throwable)
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
