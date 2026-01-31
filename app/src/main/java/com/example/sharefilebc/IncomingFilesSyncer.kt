package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.ReceivedFolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Swift版の「イベント駆動・即反映」に寄せるための受信フォルダ同期処理本体。
 */
object IncomingFilesSyncer {

    private val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")

    suspend fun syncOnce(context: Context): Int = withContext(Dispatchers.IO) {
        Log.d(LogTags.TAG_SYNC_INCOMING, "start")

        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e(LogTags.TAG_SYNC_INCOMING, "Drive 取得失敗", it) }
            .getOrNull() ?: return@withContext 0

        val db = AppDatabase.getDatabase(context)
        val receivedDao = db.receivedFolderDao()
        val refundTaskDao = db.refundTaskDao()
        val blockedSenderDao = db.blockedSenderDao()
        val walletSettingsManager = WalletSettingsManager.getInstance(context)
        val walletManager = WalletManager.getInstance(context)

        val sharedFolders = drive.files().list()
            .setQ("sharedWithMe and trashed=false and mimeType='application/vnd.google-apps.folder'")
            .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
            .execute()
            .files ?: emptyList()

        val jst = TimeZone.getTimeZone("Asia/Tokyo")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            timeZone = jst
        }

        val expirationMillis = 7L * 24 * 60 * 60 * 1000

        var upsertCount = 0
        val upsertedEntities = mutableListOf<ReceivedFolderEntity>()

        for (folder in sharedFolders) {
            val dateOnly = dateRegex.find(folder.name ?: "")?.value ?: continue

            val childFiles = drive.files().list()
                .setQ("'${folder.id}' in parents and trashed=false and mimeType!='application/vnd.google-apps.folder'")
                .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
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
                upsertedEntities.add(entity)
                upsertCount++
            } else if (
                existing.senderName != entity.senderName ||
                existing.folderName != entity.folderName ||
                existing.uploadDateTime != entity.uploadDateTime ||
                existing.deleteDateTime != entity.deleteDateTime
            ) {
                receivedDao.insert(entity.copy(id = existing.id))
                upsertedEntities.add(entity.copy(id = existing.id))
                upsertCount++
            }
        }

        if (upsertedEntities.isNotEmpty()) {
            processPaymentFlow(
                walletManager,
                walletSettingsManager,
                refundTaskDao,
                blockedSenderDao,
                upsertedEntities
            )
        }

        Log.d(LogTags.TAG_SYNC_INCOMING, "end")
        upsertCount
    }

    private suspend fun processPaymentFlow(
        walletManager: WalletManager,
        walletSettingsManager: WalletSettingsManager,
        refundTaskDao: com.example.sharefilebc.data.RefundTaskDao,
        blockedSenderDao: com.example.sharefilebc.data.BlockedSenderDao,
        targets: List<ReceivedFolderEntity>
    ) {
        for (target in targets) {

            val isBlocked = blockedSenderDao.countByEmail(target.senderName) > 0
            val threshold: ULong = walletSettingsManager.getPaymentThreshold()

            val decision = evaluateSharePayment(
                totalAmount = threshold,
                threshold = threshold,
                isSenderBlocked = isBlocked
            )

            if (decision is ShareVerificationDecision.Rejected) continue

            val balanceBefore: ULong? = walletSettingsManager.getLastKnownBalance()
            walletManager.sync()
            val balanceAfter: ULong = walletManager.getBalance()

            val delta: ULong = when {
                balanceBefore == null -> 0uL
                balanceAfter > balanceBefore -> balanceAfter - balanceBefore
                else -> 0uL
            }

            walletSettingsManager.setLastKnownBalance(balanceAfter)

            if (delta == 0uL) continue

            if (delta >= threshold) {
                val refundTask = com.example.sharefilebc.data.RefundTaskEntity(
                    shareID = target.folderId,
                    senderPublicKey = target.senderName,
                    contextJSON = """{"delta":"$delta","threshold":"$threshold"}""",
                    createdAt = nowIsoString(),
                    status = "PENDING",
                    detectedAmount = delta.toLong(),
                    paymentThreshold = threshold.toLong(),
                    relatedFolderId = target.folderId
                )
                refundTaskDao.insert(refundTask)
            }
        }
    }

    private fun nowIsoString(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        return formatter.format(Date())
    }
}
