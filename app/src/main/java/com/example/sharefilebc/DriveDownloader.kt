package com.example.sharefilebc

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import com.example.sharefilebc.data.DriveFileInfo
import com.example.sharefilebc.data.FolderStructure


class DriveDownloader(private val context: Context) {

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ShareFileBC").build()
    }

    suspend fun getFolderStructure(folderId: String): FolderStructure? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext null

                val folderInfo = driveService.files().get(folderId)
                    .setFields("id, name, parents, createdTime")
                    .execute()

                val senderName = if (folderInfo.parents != null && folderInfo.parents.isNotEmpty()) {
                    try {
                        val parentFolder = driveService.files().get(folderInfo.parents[0])
                            .setFields("name")
                            .execute()
                        parentFolder.name ?: "Unknown Sender"
                    } catch (e: Exception) {
                        "Unknown Sender"
                    }
                } else {
                    "Unknown Sender"
                }

                val filesResult = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("files(id, name, mimeType)")
                    .execute()

                val files = filesResult.files?.map { file ->
                    DriveFileInfo(
                        id = file.id,
                        name = file.name,
                        mimeType = file.mimeType,
                        isFolder = file.mimeType == "application/vnd.google-apps.folder"
                    )
                } ?: emptyList()

                // ✅ JST時間を正しく取得・変換
                val uploadDate = folderInfo.createdTime?.let { created ->
                    try {
                        // Google Drive APIから取得した時間はUTC
                        val utcMillis = created.value

                        // JSTのCalendarインスタンスを作成
                        val jstCalendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
                        jstCalendar.timeInMillis = utcMillis

                        // JST時間でフォーマット
                        val jstFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        jstFormatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
                        val formatted = jstFormatter.format(jstCalendar.time)

                        Log.d("DriveDownloader", "📅 UTC millis: $utcMillis")
                        Log.d("DriveDownloader", "📅 JST uploadDate: $formatted")
                        formatted
                    } catch (e: Exception) {
                        Log.e("DriveDownloader", "時間変換エラー", e)
                        // エラー時は現在のJST時間を使用
                        val jstFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        jstFormatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
                        jstFormatter.format(Date())
                    }
                } ?: run {
                    // createdTimeがnullの場合は現在のJST時間を使用
                    val jstFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    jstFormatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
                    jstFormatter.format(Date())
                }

                FolderStructure(
                    folderName = folderInfo.name ?: "Unknown Date",
                    senderName = senderName,
                    uploadDate = uploadDate,
                    files = files
                )
            } catch (e: Exception) {
                Log.e("DriveDownloader", "Error getting folder structure", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "フォルダ情報の取得に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
    }

    suspend fun downloadFile(fileId: String): java.io.File? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("DriveDownloader", "📥 ダウンロード開始: $fileId")

                val driveService = getDriveService() ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Google Driveサービスに接続できません。", Toast.LENGTH_LONG).show()
                    }
                    return@withContext null
                }

                val fileMetadata = driveService.files().get(fileId).execute()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = java.io.File(downloadsDir, fileMetadata.name)

                FileOutputStream(outputFile).use { outputStream ->
                    driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    null,
                    null
                )

                Log.d("DriveDownloader", "✅ ダウンロード完了: ${outputFile.absolutePath}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${fileMetadata.name} をダウンロードしました。", Toast.LENGTH_LONG).show()
                }

                outputFile
            } catch (e: Exception) {
                Log.e("DriveDownloader", "❌ ダウンロードエラー", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "ダウンロードに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
    }
}