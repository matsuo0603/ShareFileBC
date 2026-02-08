@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sharefilebc

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.BlockedSenderEntity
import org.json.JSONArray
import org.json.JSONObject
import com.example.sharefilebc.data.FolderStructure
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.example.sharefilebc.data.SharePaymentEntity
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.ui.theme.SharedScreenColors
import kotlinx.coroutines.launch
import com.google.android.gms.auth.api.signin.GoogleSignIn

private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")

// iOS（Grouped List / ActionSheet）っぽい見た目に寄せるための色
private val IOS_GROUP_BG = Color(0xFFF2F2F7)
private val IOS_SEPARATOR = Color(0xFFE5E5EA)
private val IOS_BLUE = Color(0xFF007AFF)
private val IOS_DESTRUCTIVE = Color(0xFFFF3B30)
private val IOS_ORANGE = Color(0xFFFF9500)
private val IOS_SECONDARY_TEXT = Color(0xFF8E8E93)

private fun String.toDateOnly(): String = DATE_REGEX.find(this)?.value ?: this

private data class ReceivedDateGroupSummaryUi(
    val dateLabel: String,
    val folderIds: List<String>,
    val displayUpload: String,
    val displayDelete: String
)

private data class ReceivedSenderSummaryUi(
    val senderName: String,
    val senderEmail: String?,
    val dateGroups: List<ReceivedDateGroupSummaryUi>,
    val cacheToken: String,
    val latestUpload: String,
    val hasPendingRefund: Boolean,
    val isBlocked: Boolean
)

private data class ReceivedFileItemUi(
    val id: String,
    val fileName: String,
    val uploadedAt: String,
    val deleteAt: String
)

private data class ReceivedDateGroupDetailUi(
    val dateLabel: String,
    val deleteAt: String,
    val files: List<ReceivedFileItemUi>
)

private data class ReceivedSenderDetailUi(
    val senderName: String,
    val senderEmail: String?,
    val dateGroups: List<ReceivedDateGroupDetailUi>,
    val cacheToken: String
)

private data class PaymentInfoUi(
    val threshold: ULong,
    val senderAddress: String,
    val statusLabel: String,
    val isPaid: Boolean,
    val isUnderpaid: Boolean
)

@Composable
fun DownloadScreen(
    initialFolderId: String?,
    initialFileId: String?,
    deepLinkSenderPublicKey: String?,
    deepLinkRecipientEmail: String?,
    deepLinkSenderAddress: String?,
    deepLinkThreshold: ULong?,
    deepLinkUuid: String? = null,
    deepLinkTxid: String? = null,
    deepLinkRefundAddress: String? = null,
) {
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    val db = remember { AppDatabase.getDatabase(context) }
    val receivedDao = remember { db.receivedFolderDao() }
    val userDao = remember { db.userDao() }
    val sharePaymentDao = remember { db.sharePaymentDao() }
    val receivedFileDao = remember { db.receivedFileDao() }
    val refundTaskDao = remember { db.refundTaskDao() }
    val blockedSenderDao = remember { db.blockedSenderDao() }

    val receivedFolders by receivedDao.getAll().collectAsState(initial = emptyList())
    val users by userDao.getAll().collectAsState(initial = emptyList())
    val receivedFiles by receivedFileDao.observeAll().collectAsState(initial = emptyList())
    val refundTasks by refundTaskDao.observeAll().collectAsState(initial = emptyList())
    val blockedSenders by blockedSenderDao.getAll().collectAsState(initial = emptyList())


    var isLoading by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf(false) }
    var selectedSender by remember { mutableStateOf<String?>(null) }
    var detailState by remember { mutableStateOf<ReceivedSenderDetailUi?>(null) }
    val detailCache = remember { mutableStateMapOf<String, ReceivedSenderDetailUi>() }
    var pendingAutoDownloadFileId by remember { mutableStateOf(initialFileId) }
    var isPaying by remember { mutableStateOf(false) }

    val walletManager = remember { WalletManager.getInstance(context) }
    val walletSettingsManager = remember { WalletSettingsManager.getInstance(context) }

    val requiresPayment = deepLinkSenderAddress != null && deepLinkThreshold != null

    val paymentRecordFlow = remember(initialFileId, initialFolderId) {
        if (initialFileId != null && initialFolderId != null) {
            sharePaymentDao.observeLatest(initialFileId, initialFolderId)
        } else {
            null
        }
    }
    val paymentRecord by (paymentRecordFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf<SharePaymentEntity?>(null) })

    val paymentStatusLabel = when (paymentRecord?.result) {
        "PAID" -> "支払い済み"
        "UNDERPAID" -> "不足"
        "REFUNDED" -> "返金済み"
        "FAILED" -> "失敗"
        else -> "未払い"
    }
    val isPaid = paymentRecord?.result == "PAID"
    val isUnderpaid = paymentRecord?.result == "UNDERPAID"
    val canDownload = !requiresPayment || isPaid

    val senderGroups = remember(receivedFolders, users, refundTasks, blockedSenders) {
        buildSenderSummaries(
            receivedFolders = receivedFolders,
            users = users,
            refundTasks = refundTasks,
            blockedSenders = blockedSenders
        )
    }
    LaunchedEffect(receivedFolders) {
        Log.d("DownloadScreen", "📦 receivedFolders.size=${receivedFolders.size}")
        if (receivedFolders.isNotEmpty()) {
            val head = receivedFolders.take(3).joinToString { "${it.senderName}/${it.folderName}" }
            Log.d("DownloadScreen", "📦 receivedFolders(head3)=$head")
        }
    }

    LaunchedEffect(senderGroups) {
        if (selectedSender != null && senderGroups.none { it.senderName == selectedSender }) {
            selectedSender = null
            detailState = null
        }
    }

    /**
     * ✅ 重要：DownloadScreen は「表示専用」へ
     *
     * deep link 起点で
     * - Drive へアクセスして folder/file を解決する
     * - ReceivedFolderEntity を insert する
     * - sender 公開鍵を登録する
     * などの「受信処理」をここで行わない。
     *
     * 受信（検証・返金・ブロック・sync・DB保存）は Shared 側で完了している前提。
     *
     * ここでは deep link が来ていた場合、Room の received_folders から「該当 folderId の sender」を探して
     * 自動で sender を選択するだけにする。
     */
    LaunchedEffect(initialFolderId, initialFileId, receivedFolders) {
        if (initialFolderId.isNullOrBlank() && initialFileId.isNullOrBlank()) return@LaunchedEffect

        Log.d(
            "DownloadScreen",
            "DeepLink(display-only): folder=$initialFolderId file=$initialFileId uuid=$deepLinkUuid txid=${deepLinkTxid?.take(8)}... senderPub=${deepLinkSenderPublicKey?.take(8)}..."
        )

        // folderId が Room に存在するなら、その sender を開く
        val matched = initialFolderId?.let { fid ->
            receivedFolders.firstOrNull { it.folderId == fid }
        }

        if (matched != null) {
            selectedSender = matched.senderName
            pendingAutoDownloadFileId = initialFileId
            needsLogin = false
        } else {
            // まだ Shared 側の受信処理が DB 保存を終えてない可能性があるので
            // ここでは何もしない（＝勝手にDriveへ行かない）
            // ユーザーに「受信処理中」の状態が伝わるようにログだけ出す
            Log.d("DownloadScreen", "DeepLink folderId not found in Room yet. Waiting for Shared processing.")
        }
    }

    LaunchedEffect(selectedSender) {
        if (selectedSender != null) {
            detailState = null
        }
    }
    LaunchedEffect(selectedSender, senderGroups) {
        val sender = selectedSender ?: return@LaunchedEffect
        val group = senderGroups.firstOrNull { it.senderName == sender } ?: return@LaunchedEffect
        val cached = detailCache[sender]
        if (cached != null && cached.cacheToken == group.cacheToken) {
            detailState = cached
            return@LaunchedEffect
        }

        isLoading = true
        needsLogin = false

        val dateGroups = mutableListOf<ReceivedDateGroupDetailUi>()
        for (dateGroup in group.dateGroups) {
            val structures = mutableListOf<FolderStructure>()
            for (folderId in dateGroup.folderIds) {
                val structure = downloader.getFolderStructure(folderId)
                if (structure == null) {
                    // null の原因が「ログイン切れ」なのか「権限/共有未反映」なのかを区別する
                    val signedIn = GoogleSignIn.getLastSignedInAccount(context) != null
                    if (!signedIn) {
                        needsLogin = true
                        Log.w("DownloadScreen", "⚠️ getFolderStructure=null (need login) folderId=$folderId")
                    } else {
                        Log.w("DownloadScreen", "⚠️ getFolderStructure=null (signed-in) folderId=$folderId")
                    }
                    continue
                }
                structures += structure
            }

            if (structures.isEmpty()) continue

            val mergedFiles = structures
                .flatMap { it.files }
                .filter { !it.isFolder }
                .map {
                    ReceivedFileItemUi(
                        id = it.id,
                        fileName = it.name,
                        uploadedAt = it.uploadDateTime,
                        deleteAt = it.deleteDateTime
                    )
                }
                .sortedByDescending { it.uploadedAt }

            if (mergedFiles.isEmpty()) continue

            dateGroups += ReceivedDateGroupDetailUi(
                dateLabel = dateGroup.dateLabel.toDateOnly(),
                deleteAt = dateGroup.displayDelete,
                files = mergedFiles
            )
        }

        isLoading = false

        val detail = ReceivedSenderDetailUi(
            senderName = group.senderName,
            senderEmail = group.senderEmail,
            dateGroups = dateGroups.sortedByDescending { it.dateLabel },
            cacheToken = group.cacheToken
        )
        detailState = detail
        detailCache[sender] = detail
    }

    LaunchedEffect(detailState, pendingAutoDownloadFileId, canDownload) {
        val targetFileId = pendingAutoDownloadFileId ?: return@LaunchedEffect
        if (!canDownload) return@LaunchedEffect
        val detail = detailState ?: return@LaunchedEffect
        val exists = detail.dateGroups.any { group -> group.files.any { it.id == targetFileId } }
        if (exists) {
            isLoading = true
            downloader.downloadFile(targetFileId)
            isLoading = false
            pendingAutoDownloadFileId = null
        }
    }

    val paymentInfo = if (requiresPayment && deepLinkThreshold != null && deepLinkSenderAddress != null) {
        PaymentInfoUi(
            threshold = deepLinkThreshold,
            senderAddress = deepLinkSenderAddress,
            statusLabel = paymentStatusLabel,
            isPaid = isPaid,
            isUnderpaid = isUnderpaid
        )
    } else null

    val onPayAndDownload: (() -> Unit)? = if (paymentInfo != null) {
        val action: () -> Unit = action@{
            if (isPaying) return@action
            val fileId: String = initialFileId ?: run {
                Toast.makeText(context, "共有情報が不足しています", Toast.LENGTH_LONG).show()
                return@action
            }
            val folderId: String = initialFolderId ?: run {
                Toast.makeText(context, "共有情報が不足しています", Toast.LENGTH_LONG).show()
                return@action
            }

            coroutineScope.launch {
                isPaying = true
                val paymentResult = runCatching {
                    walletManager.sync()
                    val payerRefundAddress = walletManager.getNewAddress()
                    val paidAmount = paymentInfo.threshold
                    val txid = walletManager.transfer(paymentInfo.senderAddress, paidAmount)
                    walletManager.sync()
                    val balance = walletManager.getBalance()
                    walletSettingsManager.setLastKnownBalance(balance)

                    val result = if (paidAmount >= paymentInfo.threshold) "PAID" else "UNDERPAID"
                    sharePaymentDao.insert(
                        SharePaymentEntity(
                            fileId = fileId,
                            folderId = folderId,
                            txid = txid,
                            amount = paidAmount.toLong(),
                            paidAt = nowIsoString(),
                            payerRefundAddress = payerRefundAddress,
                            result = result
                        )
                    )
                    if (fileId.isNotBlank() && result == "PAID") {
                        downloader.downloadFile(fileId)
                    }
                }

                if (paymentResult.isFailure) {
                    Toast.makeText(
                        context,
                        "支払いに失敗しました: ${paymentResult.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                isPaying = false
            }
        }
        action
    } else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IOS_GROUP_BG)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp)
        ) {
            AnimatedVisibility(visible = needsLogin) {
                LoginRequiredCard(onLogin = {
                    context.startActivity(Intent(context, LoginActivity::class.java))
                })
            }

            ReceivedProcessingSummaryCard(
                receivedCount = receivedFiles.size,
                refundPendingCount = refundTasks.count { (it.status ?: "PENDING") == "PENDING" },
                recentShareIds = receivedFiles.mapNotNull { it.shareID }.distinct().take(3)
            )

            if (senderGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "受信したファイルはありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (detailState == null || selectedSender == null) {
                    ReceivedSenderList(
                        modifier = Modifier.weight(1f),
                        groups = senderGroups,
                        onSelect = { selectedSender = it.senderName }
                    )
                } else {
                    ReceivedSenderDetail(
                        modifier = Modifier.weight(1f),
                        detail = detailState!!,
                        onBack = { selectedSender = null },
                        refundTasks = refundTasks,
                        onRefundOrDecline = { task, doRefund ->
                            coroutineScope.launch {
                                val shareId = task.shareID
                                val senderPubKey = task.senderPublicKey
                                if (shareId.isNullOrBlank()) {
                                    Toast.makeText(context, "shareID が不正です", Toast.LENGTH_LONG).show()
                                    return@launch
                                }

                                val contextJson = runCatching { JSONObject(task.contextJSON ?: "{}") }.getOrNull()
                                val contractId = contextJson?.optString("contractId")?.takeIf { it.isNotBlank() }
                                val refundAddress = contextJson?.optString("refundAddress")?.takeIf { it.isNotBlank() }
                                val transactions: List<Pair<String, String>> = run {
                                    val txArray = contextJson?.optJSONArray("transactions") ?: JSONArray()
                                    buildList {
                                        for (i in 0 until txArray.length()) {
                                            val txObj = txArray.optJSONObject(i) ?: continue
                                            val txid = txObj.optString("txid")
                                            val tx = txObj.optString("transaction")
                                            if (txid.isNotBlank() && tx.isNotBlank()) {
                                                add(txid to tx)
                                            }
                                        }
                                    }
                                }

                                if (contractId.isNullOrBlank()) {
                                    Toast.makeText(context, "contractId が不足しています", Toast.LENGTH_LONG).show()
                                    return@launch
                                }

                                isLoading = true
                                val result = if (doRefund) {
                                    if (refundAddress.isNullOrBlank()) {
                                        isLoading = false
                                        Toast.makeText(context, "refundAddress が不足しています", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    ShareProcessor.refundShare(
                                        context = context,
                                        uuid = shareId,
                                        refundAddress = refundAddress,
                                        contractStr = contractId,
                                        transactions = transactions
                                    )
                                } else {
                                    if (senderPubKey.isNullOrBlank()) {
                                        isLoading = false
                                        Toast.makeText(context, "senderPublicKey が不足しています", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    ShareProcessor.declineRefund(
                                        context = context,
                                        uuid = shareId,
                                        contractId = contractId,
                                        senderPublicKey = senderPubKey
                                    )
                                }
                                isLoading = false

                                when (result) {
                                    is RefundResult.Success -> Toast.makeText(context, "返金送信しました: ${result.txid}", Toast.LENGTH_LONG).show()
                                    RefundResult.Declined -> Toast.makeText(context, "受領しました（残高反映のため同期します）", Toast.LENGTH_LONG).show()
                                    is RefundResult.Error -> Toast.makeText(context, "失敗: ${result.message}", Toast.LENGTH_LONG).show()
                                    else -> Toast.makeText(context, "結果: $result", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        paymentInfo = paymentInfo,
                        isPaying = isPaying,
                        canDownload = canDownload,
                        onPayAndDownload = onPayAndDownload,
                        onDownload = { fileId ->
                            if (!canDownload) {
                                Toast.makeText(
                                    context,
                                    "支払いが完了していません",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@ReceivedSenderDetail
                            }
                            coroutineScope.launch {
                                isLoading = true
                                downloader.downloadFile(fileId)
                                isLoading = false
                            }
                        }
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private fun buildSenderSummaries(
    receivedFolders: List<ReceivedFolderEntity>,
    users: List<UserEntity>,
    refundTasks: List<com.example.sharefilebc.data.RefundTaskEntity>,
    blockedSenders: List<com.example.sharefilebc.data.BlockedSenderEntity>
): List<ReceivedSenderSummaryUi> {
    val emailMap = users.associate { it.name to it.email }

    // blocked_senders は email が主キー
    val blockedEmailSet = blockedSenders.map { it.email }.toSet()

    // 返金待ち（PENDING）の folderId を集める（relatedFolderId があれば senderName に紐付けできる）
    val pendingRefundFolderIds: Set<String> = refundTasks
        .asSequence()
        .filter { (it.status ?: "PENDING") == "PENDING" }
        .mapNotNull { it.relatedFolderId }
        .toSet()

    return receivedFolders
        .groupBy { it.senderName }
        .map { (senderName, folders) ->
            val dateGroups = folders
                .groupBy { it.folderName }
                .map { (dateName, rows) ->
                    val latest = rows.maxByOrNull { it.uploadDateTime } ?: rows.first()
                    ReceivedDateGroupSummaryUi(
                        dateLabel = dateName,
                        folderIds = rows.map { it.folderId },
                        displayUpload = latest.uploadDateTime,
                        displayDelete = latest.deleteDateTime
                    )
                }
                .sortedByDescending { it.displayUpload }

            val cacheToken = dateGroups.joinToString(separator = "|") { group ->
                buildString {
                    append(group.dateLabel)
                    append(":")
                    append(group.folderIds.sorted().joinToString(","))
                    append(":")
                    append(group.displayUpload)
                    append(":")
                    append(group.displayDelete)
                }
            }

            val latestUpload = dateGroups.firstOrNull()?.displayUpload ?: ""

            val senderEmail = emailMap[senderName]
            val isBlocked = senderEmail != null && blockedEmailSet.contains(senderEmail)
            val hasPendingRefund = folders.any { pendingRefundFolderIds.contains(it.folderId) }

            ReceivedSenderSummaryUi(
                senderName = senderName,
                senderEmail = senderEmail,
                dateGroups = dateGroups,
                cacheToken = cacheToken,
                latestUpload = latestUpload,
                hasPendingRefund = hasPendingRefund,
                isBlocked = isBlocked
            )
        }
        .sortedByDescending { it.latestUpload }
}
@Composable
private fun LoginRequiredCard(onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Googleアカウントのログインが必要です",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Button(
            onClick = onLogin,
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("ログインへ")
        }
    }
}

@Composable
private fun ReceivedSenderList(
    modifier: Modifier,
    groups: List<ReceivedSenderSummaryUi>,
    onSelect: (ReceivedSenderSummaryUi) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        items(groups, key = { it.senderName }) { sender ->
            SenderRowCard(sender = sender, onClick = { onSelect(sender) })
        }
    }
}

@Composable
private fun SenderRowCard(sender: ReceivedSenderSummaryUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, IOS_SEPARATOR), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sender.senderName.ifBlank { "不明なユーザー" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.Black
            )
            sender.senderEmail?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = IOS_SECONDARY_TEXT
                )
            }
        }
        // 送信元の状態表示（Swift版と同等）
        // - 返金待ちあり: 黄色アイコン
        // - 返金拒否リスト登録済み: 赤アイコン
        if (sender.isBlocked) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "返金拒否リスト登録済み",
                tint = IOS_DESTRUCTIVE
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (sender.hasPendingRefund) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = "返金対象の共有ファイルあり",
                tint = IOS_ORANGE
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = "詳細へ",
            tint = IOS_SECONDARY_TEXT
        )
    }
}

@Composable
private fun ReceivedSenderDetail(
    modifier: Modifier,
    detail: ReceivedSenderDetailUi,
    onBack: () -> Unit,
    refundTasks: List<com.example.sharefilebc.data.RefundTaskEntity>,
    onRefundOrDecline: (com.example.sharefilebc.data.RefundTaskEntity, Boolean) -> Unit,
    paymentInfo: PaymentInfoUi?,
    isPaying: Boolean,
    canDownload: Boolean,
    onPayAndDownload: (() -> Unit)?,
    onDownload: (String) -> Unit
) {
    val pendingRefunds = remember(refundTasks) {
        refundTasks.filter { (it.status ?: "PENDING") == "PENDING" }
    }

    var showRefundDialog by remember { mutableStateOf(false) }
    var selectedRefundTask by remember { mutableStateOf<com.example.sharefilebc.data.RefundTaskEntity?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IOS_GROUP_BG)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronLeft,
                        contentDescription = "戻る",
                        tint = IOS_BLUE
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "共有ファイル",
                        style = MaterialTheme.typography.titleMedium,
                        color = IOS_BLUE
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = detail.senderName.ifBlank { "不明なユーザー" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black
                    )
                    detail.senderEmail?.takeIf { it.isNotBlank() }?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = IOS_SECONDARY_TEXT
                        )
                    }
                }
            }

            if (paymentInfo != null) {
                PaymentInfoCard(
                    info = paymentInfo,
                    isPaying = isPaying,
                    onPayAndDownload = onPayAndDownload
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 156.dp)
            ) {
                detail.dateGroups.forEach { group ->
                    item(key = "header-${group.dateLabel}") {
                        Text(
                            text = group.dateLabel,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = SharedScreenColors.DateLabel,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(group.files, key = { it.id }) { file ->
                        ReceivedFileItem(
                            file = file,
                            downloadEnabled = canDownload,
                            onDownload = onDownload
                        )
                    }
                }
            }

            RefundManagementCard(
                pendingRefundCount = pendingRefunds.size,
                task = pendingRefunds.firstOrNull(),
                onOpenDialog = {
                    selectedRefundTask = pendingRefunds.firstOrNull()
                    showRefundDialog = selectedRefundTask != null
                }
            )
        }

        if (showRefundDialog) {
            val task = selectedRefundTask
            if (task != null) {
                RefundDecisionDialog(
                    onRefund = {
                        showRefundDialog = false
                        onRefundOrDecline(task, true)
                    },
                    onDecline = {
                        showRefundDialog = false
                        onRefundOrDecline(task, false)
                    },
                    onCancel = {
                        showRefundDialog = false
                    }
                )
            } else {
                showRefundDialog = false
            }
        }
    }
}

@Composable
private fun RefundManagementCard(
    pendingRefundCount: Int,
    task: com.example.sharefilebc.data.RefundTaskEntity?,
    onOpenDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, IOS_SEPARATOR), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "返金管理",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.Black
        )

        if (pendingRefundCount <= 0 || task == null) {
            Text(
                text = "返金対象の共有はありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { /* disabled */ },
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = IOS_SEPARATOR,
                    disabledContentColor = IOS_SECONDARY_TEXT
                )
            ) {
                Text("返金確認")
            }
            return
        }

        val contextJson = runCatching { JSONObject(task.contextJSON ?: "{}") }.getOrNull()
        val detectedAmount = task.detectedAmount?.toString()
            ?: contextJson?.optLong("amount")?.toString()
            ?: "?"
        val threshold = task.paymentThreshold?.toString()
            ?: contextJson?.optLong("threshold")?.toString()
            ?: "?"

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "共有 $pendingRefundCount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "受信した送金量: $detectedAmount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "閾値: $threshold",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = onOpenDialog,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IOS_BLUE)
        ) {
            Text("返金確認")
        }
    }
}

@Composable
private fun RefundDecisionDialog(
    onRefund: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit
) {
    // Swift版の ActionSheet（下から出る）に寄せる
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = IOS_GROUP_BG,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // iOSのActionSheetは「説明テキスト + ボタン群」なので、カード風にまとめる
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(BorderStroke(1.dp, IOS_SEPARATOR), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "返金の確認",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.Black
                )
                Text(
                    text = "返金の可否を選択してください。\n相手が信頼できない場合は『返金しない』を選択してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = IOS_SECONDARY_TEXT
                )
            }

            // ボタン群（返金する/返金しない）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(BorderStroke(1.dp, IOS_SEPARATOR), RoundedCornerShape(14.dp))
            ) {
                TextButton(
                    onClick = onRefund,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("返金する", color = IOS_DESTRUCTIVE, fontWeight = FontWeight.SemiBold)
                }
                Divider(color = IOS_SEPARATOR)
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("返金しない", color = IOS_BLUE, fontWeight = FontWeight.SemiBold)
                }
            }

            // キャンセル（単独カード）
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(BorderStroke(1.dp, IOS_SEPARATOR), RoundedCornerShape(14.dp))
            ) {
                Text("キャンセル", color = IOS_BLUE, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReceivedFileItem(
    file: ReceivedFileItemUi,
    downloadEnabled: Boolean,
    onDownload: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, IOS_SEPARATOR), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.Black
            )
            if (file.deleteAt.isNotBlank()) {
                Text(
                    text = "削除予定: ${file.deleteAt}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IOS_DESTRUCTIVE,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(
            onClick = { onDownload(file.id) },
            enabled = downloadEnabled
        ) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = "ダウンロード",
                tint = if (downloadEnabled) IOS_BLUE else IOS_SECONDARY_TEXT
            )
        }
    }
}

@Composable
private fun PaymentInfoCard(
    info: PaymentInfoUi,
    isPaying: Boolean,
    onPayAndDownload: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SharedScreenColors.UserCardBackground),
        border = BorderStroke(1.dp, SharedScreenColors.UserCardBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "支払い情報",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "しきい値: ${info.threshold} TPC",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "送金先: ${info.senderAddress}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "支払い状態: ${info.statusLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (info.isPaid) {
                    MaterialTheme.colorScheme.primary
                } else if (info.isUnderpaid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (info.isUnderpaid) {
                Text(
                    text = "不足のためダウンロードできません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!info.isPaid) {
                Text(
                    text = "未払いのためダウンロードできません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = { onPayAndDownload?.invoke() },
                enabled = !info.isPaid && !isPaying && onPayAndDownload != null
            ) {
                Text(text = if (isPaying) "支払い中..." else "支払ってダウンロード")
            }
        }
    }
}

private fun nowIsoString(): String {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.getDefault())
    formatter.timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
    return formatter.format(java.util.Date())
}

@Composable
private fun ReceivedProcessingSummaryCard(
    receivedCount: Int,
    refundPendingCount: Int,
    recentShareIds: List<String>
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "受信処理サマリー",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "受信済み: ${receivedCount}件 / 返金待ち: ${refundPendingCount}件",
                style = MaterialTheme.typography.bodyMedium
            )
            if (recentShareIds.isNotEmpty()) {
                Text(
                    text = "shareID: ${recentShareIds.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RefundPendingListCard(
    refundTasks: List<com.example.sharefilebc.data.RefundTaskEntity>,
    onRefund: (com.example.sharefilebc.data.RefundTaskEntity) -> Unit,
    onDecline: (com.example.sharefilebc.data.RefundTaskEntity) -> Unit
) {
    val pending = remember(refundTasks) {
        refundTasks.filter { (it.status ?: "PENDING") == "PENDING" }
    }

    if (pending.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "返金待ち（確認が必要）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            pending.forEach { task ->
                val shareId = task.shareID ?: ""
                val amount = task.detectedAmount
                val contractId = runCatching {
                    JSONObject(task.contextJSON ?: "{}").optString("contractId")
                }.getOrNull().orEmpty()

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "shareID: ${shareId.take(20)}${if (shareId.length > 20) "..." else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (contractId.isNotBlank()) {
                            Text(
                                text = "contractId: ${contractId.take(20)}${if (contractId.length > 20) "..." else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (amount != null) {
                            Text(
                                text = "検出額: $amount",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { onRefund(task) }
                            ) {
                                Text("返金する")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { onDecline(task) }
                            ) {
                                Text("返金しない")
                            }
                        }
                    }
                }
            }
        }
    }
}
