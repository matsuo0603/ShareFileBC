package com.example.sharefilebc

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.ReceivedFolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class IncomingFilesSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")

    override suspend fun doWork(): Result {
        Log.d("IncomingFilesSyncWorker", "▶ Worker 実行開始")
        val context = applicationContext
        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e("IncomingFilesSyncWorker", "❌ Drive 取得失敗", it) }
            .getOrNull() ?: return Result.success()

        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val receivedDao = db.receivedFolderDao()

                val sharedFolders = drive.files().list()
                    .setQ("sharedWithMe and trashed=false and mimeType='application/vnd.google-apps.folder'")
                    .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
                    .execute()
                    .files
                    ?: emptyList()

                Log.d("IncomingFilesSyncWorker", "🔍 共有フォルダ検索結果: ${sharedFolders.size} 件")

                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                    timeZone = jst
                }
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

                Log.d("IncomingFilesSyncWorker", "✅ DB upsert 件数: $upsertCount")
                Log.d("IncomingFilesSyncWorker", "✅ Worker 正常終了")
                Result.success()
            } catch (e: Exception) {
                Log.e("IncomingFilesSyncWorker", "❌ Worker 実行エラー", e)
                Result.failure()
            }
        }
    }
}