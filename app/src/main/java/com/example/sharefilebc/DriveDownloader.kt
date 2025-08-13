package com.example.sharefilebc

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.example.sharefilebc.data.DriveFileInfo
import com.example.sharefilebc.data.FolderStructure
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

class DriveDownloader(private val context: Context) {

    // 表示用期限：アップロードから7日
    private val EXPIRATION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE)
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
                    .setFields("id, name, parents")
                    .execute()

                val parentFolderId = folderInfo.parents?.firstOrNull()
                val parentFolderName = parentFolderId?.let {
                    driveService.files().get(it).setFields("name").execute().name
                } ?: "Unknown Sender"

                val fileList = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("files(id, name, mimeType, createdTime)")
                    .execute()

                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val fullFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                    timeZone = jst
                }
                val dateOnlyFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                    timeZone = jst
                }

                val files = fileList.files?.map { file ->
                    val uploadMillis = file.createdTime?.value ?: System.currentTimeMillis()
                    val uploadDate = Date(uploadMillis)
                    val uploadStr = fullFormatter.format(uploadDate)
                    val deleteStr = fullFormatter.format(Date(uploadMillis + EXPIRATION_MILLIS))

                    DriveFileInfo(
                        id = file.id,
                        name = file.name,
                        mimeType = file.mimeType,
                        isFolder = file.mimeType == "application/vnd.google-apps.folder",
                        senderName = parentFolderName,
                        uploadDateTime = uploadStr,
                        deleteDateTime = deleteStr
                    )
                } ?: emptyList()

                val folderName = files.firstOrNull()?.let {
                    val uploadDate = fullFormatter.parse(it.uploadDateTime)
                    dateOnlyFormatter.format(uploadDate!!)
                } ?: "Unknown Date"

                FolderStructure(
                    folderName = folderName,
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
                val driveService = getDriveService() ?: return@withContext null
                val fileMetadata = driveService.files().get(fileId).execute()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = java.io.File(downloadsDir, fileMetadata.name)
                FileOutputStream(outputFile).use {
                    driveService.files().get(fileId).executeMediaAndDownloadTo(it)
                }
                MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), null, null)
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
