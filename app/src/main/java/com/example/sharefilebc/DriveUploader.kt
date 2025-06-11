package com.example.sharefilebc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.SharedFolderEntity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

class DriveUploader(private val context: Context) {

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf("https://www.googleapis.com/auth/drive.file")
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ShareFileBC").build()
    }

    // New method to resolve the "Unresolved reference" error in HomeScreen.kt
    // フォルダID、ファイルID、ファイル名をまとめて返すように変更
    suspend fun uploadFileAndRecord(fileUri: Uri, recipientName: String, db: AppDatabase): Triple<String, String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext null

                // Get file name from Uri
                val fileName = context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "Unknown File"

                // Read file bytes from Uri
                val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)
                val fileBytes = inputStream?.readBytes() ?: return@withContext null

                // Create a new folder for the recipient if it doesn't exist under ShareFileBCApp
                // まず ShareFileBCApp ルートフォルダのIDを取得または作成
                var shareFileBcAppFolderId: String? = null
                val existingAppFolders = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='ShareFileBCApp' and 'root' in parents and trashed=false")
                    .setFields("files(id)")
                    .execute()

                if (existingAppFolders.files.isNotEmpty()) {
                    shareFileBcAppFolderId = existingAppFolders.files[0].id
                } else {
                    val appFolderMetadata = File().apply {
                        name = "ShareFileBCApp"
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf("root") // ルート直下に作成
                    }
                    val createdAppFolder = driveService.files().create(appFolderMetadata)
                        .setFields("id")
                        .execute()
                    shareFileBcAppFolderId = createdAppFolder.id
                }

                if (shareFileBcAppFolderId == null) {
                    Log.e("DriveUploader", "Failed to create or find ShareFileBCApp folder.")
                    return@withContext null
                }

                // 次に、受信者ごとのフォルダを ShareFileBCApp の下に作成または取得
                var recipientFolderId: String? = null
                val existingRecipientFolders = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='$recipientName' and '$shareFileBcAppFolderId' in parents and trashed=false")
                    .setFields("files(id)")
                    .execute()

                if (existingRecipientFolders.files.isNotEmpty()) {
                    recipientFolderId = existingRecipientFolders.files[0].id
                } else {
                    val recipientFolderMetadata = File().apply {
                        name = recipientName
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf(shareFileBcAppFolderId)
                    }
                    val createdRecipientFolder = driveService.files().create(recipientFolderMetadata)
                        .setFields("id")
                        .execute()
                    recipientFolderId = createdRecipientFolder.id
                }

                if (recipientFolderId == null) {
                    Log.e("DriveUploader", "Failed to create or find folder for $recipientName")
                    return@withContext null
                }

                // 日付フォルダを作成または取得 (今回はファイルごとに日付フォルダを作成せず、Recipientフォルダ直下にファイルを置く、または新しい日付フォルダロジックを検討)
                // 頂いた仕様に「共有相手ごとにフォルダを作成し、日付単位で分類」とあるので、日付フォルダを Recipientフォルダの下に作成します。
                val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                var dateFolderId: String? = null
                val existingDateFolders = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='$currentDate' and '$recipientFolderId' in parents and trashed=false")
                    .setFields("files(id)")
                    .execute()

                if (existingDateFolders.files.isNotEmpty()) {
                    dateFolderId = existingDateFolders.files[0].id
                } else {
                    val dateFolderMetadata = File().apply {
                        name = currentDate
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf(recipientFolderId)
                    }
                    val createdDateFolder = driveService.files().create(dateFolderMetadata)
                        .setFields("id")
                        .execute()
                    dateFolderId = createdDateFolder.id
                }

                if (dateFolderId == null) {
                    Log.e("DriveUploader", "Failed to create or find date folder for $currentDate")
                    return@withContext null
                }

                // Upload the file to the determined date folder
                val fileMetadata = File().apply {
                    name = fileName
                    parents = listOf(dateFolderId) // 日付フォルダを親にする
                }

                val fileContent = com.google.api.client.http.ByteArrayContent("application/octet-stream", fileBytes)
                val uploadedFile = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id, name, webViewLink") // Request webViewLink
                    .execute()

                val fileId = uploadedFile.id // Google Drive上のファイルID
                val webViewLink = uploadedFile.webViewLink // Get the webViewLink

                // Record in Room database - file information included
                val dao = db.sharedFolderDao()
                dao.insert(
                    SharedFolderEntity(
                        date = currentDate, // 現在の日付
                        recipientName = recipientName,
                        folderId = dateFolderId, // 保存された日付フォルダのID
                        fileName = fileName, // ファイル名も保存
                        fileGoogleDriveId = fileId // ファイルのGoogle Drive IDも保存
                    )
                )
                Log.d("DriveUploader", "Uploaded File ID: $fileId, Folder ID: $dateFolderId, Web View Link: $webViewLink, FileName: $fileName")

                return@withContext Triple(fileName, fileId, dateFolderId) // Return fileName, fileId, and folderId
            } catch (e: Exception) {
                Log.e("DriveUploader", "Error uploading file and recording", e)
                return@withContext null
            }
        }
    }
}