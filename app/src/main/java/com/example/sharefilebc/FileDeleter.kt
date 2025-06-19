package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper

import java.text.SimpleDateFormat
import java.util.*

object FileDeleter {
    suspend fun deleteExpiredFiles(context: Context, skipDriveDeletion: Boolean = false) {
        Log.d("FileDeleter", "🧹 削除処理開始")

        val db = AppDatabase.getDatabase(context)
        val receivedFolderDao = db.receivedFolderDao()
        val sharedFolderDao = db.sharedFolderDao()

        // ✅ 受信者側と送信者側の両方を処理
        deleteExpiredReceivedFiles(context, receivedFolderDao, skipDriveDeletion)
        deleteExpiredSharedFiles(context, sharedFolderDao, skipDriveDeletion)

        Log.d("FileDeleter", "🧹 削除処理終了")
    }

    // ✅ JST時間で現在時刻を取得する関数
    private fun getCurrentJSTTime(): Date {
        val jstTimeZone = TimeZone.getTimeZone("Asia/Tokyo")
        val jstCalendar = Calendar.getInstance(jstTimeZone)
        return jstCalendar.time
    }

    // ✅ JST時間で日付文字列をパースする関数
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

    // ✅ 削除時間を計算する関数（JST基準）
    private fun calculateDeleteTime(uploadDate: Date): Date {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
        calendar.time = uploadDate
        calendar.add(Calendar.MINUTE, 15) // 15分後
        return calendar.time
    }

    // ✅ デバッグ用：JST時間を文字列で表示
    private fun formatJSTTime(date: Date): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return formatter.format(date)
    }

    private suspend fun deleteExpiredReceivedFiles(
        context: Context,
        dao: com.example.sharefilebc.data.ReceivedFolderDao,
        skipDriveDeletion: Boolean
    ) {
        val allFolders = dao.getAllOnce()
        val currentJSTTime = getCurrentJSTTime()

        Log.d("FileDeleter", "📅 現在のJST時刻: ${formatJSTTime(currentJSTTime)}")

        // ✅ uploadDate（アップロード日時）を基準に削除判定
        val expired = allFolders.filter { entry ->
            val uploadDate = parseJSTDateTime(entry.uploadDate)
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

        if (skipDriveDeletion) {
            expired.forEach { dao.deleteById(it.id) }
        } else {
            try {
                val driveService = DriveServiceHelper.getDriveService(context)
                expired.forEach { entry ->
                    try {
                        Log.d(
                            "FileDeleter",
                            "🗂 受信フォルダ削除対象: ${entry.folderName} (${entry.folderId}) - アップロード日時: ${entry.uploadDate}"
                        )
                        driveService.files().delete(entry.folderId).execute()
                        dao.deleteById(entry.id)
                        Log.d("FileDeleter", "✅ 受信フォルダ削除成功: ${entry.folderId}")
                    } catch (e: Exception) {
                        Log.e("FileDeleter", "❌ 受信フォルダ削除失敗: ${entry.folderId}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("FileDeleter", "Google Drive接続エラー（受信側）", e)
            }
        }
    }

    private suspend fun deleteExpiredSharedFiles(
        context: Context,
        dao: com.example.sharefilebc.data.SharedFolderDao,
        skipDriveDeletion: Boolean
    ) {
        val allSharedFiles = dao.getAllOnce()
        val currentJSTTime = getCurrentJSTTime()

        Log.d("FileDeleter", "📅 現在のJST時刻: ${formatJSTTime(currentJSTTime)}")

        // ✅ date（アップロード日時）を基準に削除判定
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

        if (skipDriveDeletion) {
            expired.forEach { dao.deleteById(it.id) }
        } else {
            try {
                val driveService = DriveServiceHelper.getDriveService(context)
                expired.forEach { entry ->
                    try {
                        Log.d(
                            "FileDeleter",
                            "📄 共有ファイル削除対象: ${entry.fileName} (${entry.fileGoogleDriveId}) - アップロード日時: ${entry.date}"
                        )
                        driveService.files().delete(entry.fileGoogleDriveId).execute()
                        dao.deleteById(entry.id)
                        Log.d("FileDeleter", "✅ 共有ファイル削除成功: ${entry.fileGoogleDriveId}")
                    } catch (e: Exception) {
                        Log.e("FileDeleter", "❌ 共有ファイル削除失敗: ${entry.fileGoogleDriveId}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("FileDeleter", "Google Drive接続エラー（送信側）", e)
            }
        }
    }
}