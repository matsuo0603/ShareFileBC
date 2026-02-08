package com.example.sharefilebc

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 受信フォルダ同期（WorkManager）:
 * - Drive上の sharedWithMe フォルダを拾って Room に反映（受信一覧用）
 * - ✅ 今回の修正：各ファイルの description から shareメタを復元して
 *   ShareProcessor.processReceivedShare を自動実行し、トークン反映まで進める。
 *
 * これにより「メールリンクを開いたときだけ増える」問題を解消する。
 */
object IncomingFilesSyncer {

    private const val TAG = "IncomingFilesSyncer"

    private val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")

    private data class ShareMeta(
        val uuid: String,
        val txids: List<String>,
        val senderPublicKey: String,
        val refundAddress: String?,
        val threshold: ULong
    )

    suspend fun syncOnce(context: Context): Int = withContext(Dispatchers.IO) {
        Log.d(LogTags.TAG_SYNC_INCOMING, "start")

        // ✅ Drive 同期の有無に関わらず、ウォレットは定期的に同期しておく。
        // 返金は「相手から自分のアドレスへの通常送金」なので、
        // 受信側（送信者側）が何も操作しなくても WorkManager 経由で反映できる。
        runCatching {
            val wm = WalletManager.getInstance(context)
            wm.initializeIfNeeded()
            wm.sync()
        }.onFailure {
            Log.w(LogTags.TAG_SYNC_INCOMING, "wallet sync skipped: ${it.message}")
        }

        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e(LogTags.TAG_SYNC_INCOMING, "Drive 取得失敗", it) }
            .getOrNull() ?: return@withContext 0

        val db = AppDatabase.getDatabase(context)
        val receivedDao = db.receivedFolderDao()

        val sharedFolders = drive.files().list()
            .setQ("sharedWithMe and trashed=false and mimeType='application/vnd.google-apps.folder'")
            .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
            .execute()
            .files ?: emptyList()

        val jst = TimeZone.getTimeZone("Asia/Tokyo")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { timeZone = jst }
        val expirationMillis = 7L * 24 * 60 * 60 * 1000

        var upsertCount = 0

        for (folder in sharedFolders) {
            val dateOnly = dateRegex.find(folder.name ?: "")?.value ?: continue

            // ✅ description を読むため fields に description を追加
            val childFiles = drive.files().list()
                .setQ("'${folder.id}' in parents and trashed=false and mimeType!='application/vnd.google-apps.folder'")
                .setFields("files(id, name, createdTime, owners(displayName, emailAddress), description)")
                .execute()
                .files ?: emptyList()

            val firstFile = childFiles.firstOrNull()
            val uploadMillis = firstFile?.createdTime?.value
                ?: folder.createdTime?.value
                ?: System.currentTimeMillis()

            val uploadDate = formatter.format(Date(uploadMillis))
            val deleteDate = formatter.format(Date(uploadMillis + expirationMillis))

            val owner = firstFile?.owners?.firstOrNull() ?: folder.owners?.firstOrNull()
            val senderName = owner?.displayName?.takeIf(String::isNotBlank)
                ?: owner?.emailAddress
                ?: "Unknown Sender"

            val entity = ReceivedFolderEntity(
                folderId = folder.id,
                folderName = dateOnly,
                senderName = senderName,
                uploadDateTime = uploadDate,
                deleteDateTime = deleteDate
            )

            val existing = receivedDao.findByFolderId(folder.id)
            if (existing == null) {
                receivedDao.insert(entity)
                upsertCount++
            } else if (
                existing.senderName != entity.senderName ||
                existing.folderName != entity.folderName ||
                existing.uploadDateTime != entity.uploadDateTime ||
                existing.deleteDateTime != entity.deleteDateTime
            ) {
                receivedDao.insert(entity.copy(id = existing.id))
                upsertCount++
            }

            // ✅ ★ここが本題：自動受信でも processReceivedShare を実行
            autoProcessSharesFromDescriptions(context, childFiles)
        }

        Log.d(LogTags.TAG_SYNC_INCOMING, "end")
        upsertCount
    }

    private suspend fun autoProcessSharesFromDescriptions(context: Context, files: List<File>) {
        if (files.isEmpty()) return

        for (f in files) {
            val desc = f.description?.trim().orEmpty()
            if (desc.isBlank()) continue

            // ✅ threshold が description に入っていない/壊れているケースがあり、0 だと
            // UIで「閾値: 0」と誤表示されやすい。
            // Swift版は paymentThreshold のデフォルトが 1 なので、Androidも同じく
            // WalletSettings の値をデフォルトとして補完する。
            val defaultThreshold = WalletSettingsManager
                .getInstance(context)
                .getPaymentThreshold()
                .toLong()

            val meta = parseShareMeta(desc, defaultThreshold) ?: continue

            Log.d(
                LogTags.TAG_SYNC_INCOMING,
                "🔁 auto processReceivedShare: fileId=${f.id} uuid=${meta.uuid} txids=${meta.txids.size} th=${meta.threshold}"
            )

            val result = ShareProcessor.processReceivedShare(
                context = context,
                uuid = meta.uuid,
                txids = meta.txids,
                senderPublicKey = meta.senderPublicKey,
                refundAddress = meta.refundAddress,
                threshold = meta.threshold
            )

            Log.d(LogTags.TAG_SYNC_INCOMING, "🔁 auto processReceivedShare result=$result")

            // ✅ 反映が遅い場合があるので、受信処理の直後にもう一度 sync→balance を取ってログに残す
            runCatching {
                val wm = WalletManager.getInstance(context)
                val bal = wm.getBalanceAfterSync(colorId = Constants.Strings.tokenColorId)
                Log.d(LogTags.TAG_SYNC_INCOMING, "[BALANCE] after auto processReceivedShare = $bal")
            }
        }
    }

    /**
     * description 形式：
     *  uuid=...&txid=...&sender=...&threshold=...&refund=...
     */
    private fun parseShareMeta(description: String, defaultThreshold: Long): ShareMeta? {
        return try {
            val q = description.removePrefix("?")
            val uri = Uri.parse("https://dummy.local/?$q")

            val uuid = uri.getQueryParameter("uuid")?.trim().orEmpty()

            val sender = (uri.getQueryParameter("sender") ?: uri.getQueryParameter("senderPublicKey"))
                ?.trim()
                .orEmpty()

            val thresholdStr = uri.getQueryParameter("threshold")?.trim().orEmpty()
            val thresholdParsed = thresholdStr.toLongOrNull()
            val threshold = when {
                thresholdParsed == null -> defaultThreshold
                thresholdParsed <= 0L -> defaultThreshold
                else -> thresholdParsed
            }.toULong()

            val refund = (uri.getQueryParameter("refund") ?: uri.getQueryParameter("refundAddress"))
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            val txids = buildList {
                uri.getQueryParameter("txid")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.let { addAll(it) }

                uri.getQueryParameter("txids")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.let { addAll(it) }

                addAll(uri.getQueryParameters("txid").map { it.trim() }.filter { it.isNotBlank() })
            }.distinct()

            if (uuid.isBlank() || sender.isBlank() || threshold == 0uL || txids.isEmpty()) return null

            ShareMeta(
                uuid = uuid,
                txids = txids,
                senderPublicKey = sender,
                refundAddress = refund,
                threshold = threshold
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseShareMeta failed: ${e.message}")
            null
        }
    }
}
