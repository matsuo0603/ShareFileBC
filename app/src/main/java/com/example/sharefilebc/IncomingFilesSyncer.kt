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
 *
 * - sharedWithMe に存在するフォルダ(=相手から共有されたフォルダ)を検索
 * - フォルダ名から yyyy-MM-dd を抽出して ReceivedFolderEntity として保存
 *
 * WorkManager(Periodic) と UIイベント(ログイン直後/画面表示) の両方から同じ処理を呼べるように、
 * Worker からロジックを分離した。
 */
object IncomingFilesSyncer {

    private const val TAG = "IncomingFilesSyncer"
    private val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")

    /**
     * 受信フォルダを 1 回同期する。
     * @return DB upsert 件数
     */
    suspend fun syncOnce(context: Context): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "▶ syncOnce start")

        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e(TAG, "❌ Drive 取得失敗", it) }
            .getOrNull() ?: return@withContext 0

        val db = AppDatabase.getDatabase(context)
        val receivedDao = db.receivedFolderDao()

        val sharedFolders = drive.files().list()
            .setQ("sharedWithMe and trashed=false and mimeType='application/vnd.google-apps.folder'")
            .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
            .execute()
            .files
            ?: emptyList()

        Log.d(TAG, "🔍 共有フォルダ検索結果: ${sharedFolders.size} 件")

        val jst = TimeZone.getTimeZone("Asia/Tokyo")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            timeZone = jst
        }

        // 期限は現状の実装に合わせて 7 日（必要なら後で設定化）
        val expirationMillis = 7L * 24 * 60 * 60 * 1000

        var upsertCount = 0
        for (folder in sharedFolders) {
            val folderName = folder.name ?: ""
            val dateOnly = dateRegex.find(folderName)?.value ?: continue

            val childFiles = drive.files().list()
                .setQ("'${folder.id}' in parents and trashed=false and mimeType!='application/vnd.google-apps.folder'")
                .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
                .execute()
                .files
                ?: emptyList()

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

            val existing = receivedDao.findByFolderId(folder.id)
            val entity = ReceivedFolderEntity(
                folderId = folder.id,
                folderName = dateOnly,
                senderName = senderName,
                uploadDateTime = uploadDate,
                deleteDateTime = deleteDate
            )

            if (existing == null) {
                receivedDao.insert(entity)
                upsertCount += 1
            } else if (
                existing.senderName != entity.senderName ||
                existing.folderName != entity.folderName ||
                existing.uploadDateTime != entity.uploadDateTime ||
                existing.deleteDateTime != entity.deleteDateTime
            ) {
                receivedDao.insert(entity.copy(id = existing.id))
                upsertCount += 1
            }
        }

        Log.d(TAG, "✅ DB upsert 件数: $upsertCount")
        Log.d(TAG, "✅ syncOnce end")
        upsertCount
    }
}
