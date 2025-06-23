package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.example.sharefilebc.data.SharedFolderEntity
import com.example.sharefilebc.data.DriveServiceHelper
import kotlinx.coroutines.runBlocking
import org.threeten.bp.LocalDateTime
import org.threeten.bp.format.DateTimeFormatter

object FileDeleter {
    fun deleteExpiredFiles(context: Context) = runBlocking {
        Log.d("FileDeleter", "🧹 削除処理開始")

        val db = AppDatabase.getDatabase(context)
        val receivedFolderDao = db.receivedFolderDao()
        val sharedFolderDao = db.sharedFolderDao()

        // ✅ 受信者側と送信者側の両方を処理
        deleteExpiredReceivedFiles(context, receivedFolderDao)
        deleteExpiredSharedFiles(context, sharedFolderDao)

        Log.d("FileDeleter", "🧹 削除処理終了")
    }

    private suspend fun deleteExpiredReceivedFiles(context: Context, dao: com.example.sharefilebc.data.ReceivedFolderDao) {
        val allFolders = dao.getAllOnce()
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val expired = allFolders.filter { entry ->
            try {
                val entryDate = LocalDateTime.parse(entry.receivedDate, formatter)
                entryDate.isBefore(now.minusMinutes(15)) // ✅ 15分に変更
            } catch (e: Exception) {
                Log.e("FileDeleter", "日付解析エラー: ${entry.receivedDate}", e)
                false
            }
        }

        Log.d("FileDeleter", "⏰ 削除対象受信フォルダ数: ${expired.size}")

        try {
            val driveService = DriveServiceHelper.getDriveService(context)
            expired.forEach { entry ->
                try {
                    Log.d("FileDeleter", "🗂 受信フォルダ削除対象: ${entry.folderName} (${entry.folderId})")
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

    private suspend fun deleteExpiredSharedFiles(context: Context, dao: com.example.sharefilebc.data.SharedFolderDao) {
        val allSharedFiles = dao.getAllOnce() // ✅ 新しいメソッドが必要
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val expired = allSharedFiles.filter { entry ->
            try {
                val entryDate = LocalDateTime.parse(entry.date, formatter)
                entryDate.isBefore(now.minusMinutes(15)) // ✅ 15分に変更
            } catch (e: Exception) {
                Log.e("FileDeleter", "日付解析エラー: ${entry.date}", e)
                false
            }
        }

        Log.d("FileDeleter", "⏰ 削除対象共有ファイル数: ${expired.size}")

        try {
            val driveService = DriveServiceHelper.getDriveService(context)
            expired.forEach { entry ->
                try {
                    Log.d("FileDeleter", "📄 共有ファイル削除対象: ${entry.fileName} (${entry.fileGoogleDriveId})")
                    // ✅ ファイル自体を削除
                    driveService.files().delete(entry.fileGoogleDriveId).execute()
                    // ✅ DBからも削除
                    dao.deleteById(entry.id) // ✅ 新しいメソッドが必要
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