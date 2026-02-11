package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.ReceivedFolderDao
import java.text.SimpleDateFormat
import java.util.*

object FileDeleter {
    /**
     * 期限切れデータの削除処理。
     *
     * ❗️重要: UIスレッドで runBlocking すると ANR の原因になる。
     * 呼び出し側（WorkManager / lifecycleScope(IO)）で suspend として呼ぶ。
     */
    suspend fun deleteExpiredFiles(context: Context) {
        Log.d("FileDeleter", "🧹 削除処理開始")

        val db = AppDatabase.getDatabase(context)
        val receivedFolderDao = db.receivedFolderDao()
        val sharedFolderDao = db.sharedFolderDao()

        deleteExpiredReceivedFiles(receivedFolderDao)
        deleteExpiredSharedFiles(context, sharedFolderDao)

        Log.d("FileDeleter", "🧹 削除処理終了")
    }

    private fun getCurrentJSTTime(): Date {
        val jstTimeZone = TimeZone.getTimeZone("Asia/Tokyo")
        val jstCalendar = Calendar.getInstance(jstTimeZone)
        return jstCalendar.time
    }

    private fun parseJSTDateTime(dateTimeString: String): Date? {
        return try {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            formatter.parse(dateTimeString)
        } catch (e: Exception) {
            Log.e("FileDeleter", "日付解析エラー: $dateTimeString", e)
            null
        }
    }

    private fun calculateDeleteTime(uploadDate: Date): Date {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
        calendar.time = uploadDate
        calendar.add(Calendar.DAY_OF_YEAR, 7) // 7日後に削除
        return calendar.time
    }

    private fun formatJSTTime(date: Date): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return formatter.format(date)
    }

    private suspend fun deleteExpiredReceivedFiles(dao: ReceivedFolderDao) {
        val allFolders = dao.getAllOnce()
        val currentJSTTime = getCurrentJSTTime()

        Log.d("FileDeleter", "📅 現在のJST時刻: ${formatJSTTime(currentJSTTime)}")

        val expired = allFolders.filter { entry ->
            val uploadDate = parseJSTDateTime(entry.uploadDateTime)
            if (uploadDate != null) {
                val deleteTime = calculateDeleteTime(uploadDate)
                val isExpired = currentJSTTime.after(deleteTime)

                Log.d("FileDeleter", "📂 ${entry.folderName}:")
                Log.d("FileDeleter", "  アップロード時刻: ${formatJSTTime(uploadDate)}")
                Log.d("FileDeleter", "  削除予定時刻: ${formatJSTTime(deleteTime)}")
                Log.d("FileDeleter", "  削除対象: $isExpired")

                isExpired
            } else {
                false
            }
        }

        Log.d("FileDeleter", "⏰ 削除対象受信フォルダ数: ${expired.size}")

        expired.forEach { entry ->
            Log.d("FileDeleter", "🗂 Roomから受信フォルダ削除: ${entry.folderName} (${entry.folderId})")
            dao.deleteById(entry.id)
        }
    }

    private suspend fun deleteExpiredSharedFiles(
        context: Context,
        dao: com.example.sharefilebc.data.SharedFolderDao
    ) {
        val allSharedFiles = dao.getAllOnce()
        val currentJSTTime = getCurrentJSTTime()

        Log.d("FileDeleter", "📅 現在のJST時刻: ${formatJSTTime(currentJSTTime)}")

        val expired = allSharedFiles.filter { entry ->
            val uploadDate = parseJSTDateTime(entry.date)
            if (uploadDate != null) {
                val deleteTime = calculateDeleteTime(uploadDate)
                val isExpired = currentJSTTime.after(deleteTime)

                Log.d("FileDeleter", "📄 ${entry.fileName}:")
                Log.d("FileDeleter", "  アップロード時刻: ${formatJSTTime(uploadDate)}")
                Log.d("FileDeleter", "  削除予定時刻: ${formatJSTTime(deleteTime)}")
                Log.d("FileDeleter", "  削除対象: $isExpired")

                isExpired
            } else {
                false
            }
        }

        Log.d("FileDeleter", "⏰ 削除対象共有ファイル数: ${expired.size}")

        try {
            val driveService = DriveServiceHelper.getDriveService(context)
            val deletedFolderIds = mutableSetOf<String>()

            expired.forEach { entry ->
                try {
                    Log.d(
                        "FileDeleter",
                        "📄 共有ファイル削除対象: ${entry.fileName} (${entry.fileGoogleDriveId}) - アップロード日時: ${entry.date}"
                    )
                    driveService.files().delete(entry.fileGoogleDriveId).execute()
                    dao.deleteById(entry.id)
                    Log.d("FileDeleter", "✅ 共有ファイル削除成功: ${entry.fileGoogleDriveId}")
                    deletedFolderIds.add(entry.folderId)
                } catch (e: Exception) {
                    Log.e("FileDeleter", "❌ 共有ファイル削除失敗: ${entry.fileGoogleDriveId}", e)
                }
            }

            // 📁 空のフォルダを削除
            for (folderId in deletedFolderIds) {
                try {
                    val filesInFolder = driveService.files().list()
                        .setQ("'$folderId' in parents and trashed = false")
                        .setFields("files(id)")
                        .execute()
                        .files

                    if (filesInFolder.isNullOrEmpty()) {
                        driveService.files().delete(folderId).execute()
                        Log.d("FileDeleter", "✅ 空のフォルダ削除成功: $folderId")
                    }
                } catch (e: Exception) {
                    Log.e("FileDeleter", "❌ フォルダ削除失敗: $folderId", e)
                }
            }
        } catch (e: Exception) {
            Log.e("FileDeleter", "Google Drive接続エラー（送信側）", e)
        }
    }
}
