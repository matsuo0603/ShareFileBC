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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
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

    suspend fun uploadFileAndRecord(
        fileUri: Uri,
        recipientName: String,
        db: AppDatabase
    ): Triple<String, String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext null

                val fileName = context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "Unknown File"

                val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)
                val fileBytes = inputStream?.readBytes() ?: return@withContext null

                val existingAppFolders = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='ShareFileBCApp' and 'root' in parents and trashed=false")
                    .setFields("files(id)")
                    .execute()

                val shareFileBcAppFolderId = if (existingAppFolders.files.isNotEmpty()) {
                    existingAppFolders.files[0].id
                } else {
                    val appFolderMetadata = File().apply {
                        name = "ShareFileBCApp"
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf("root")
                    }
                    val createdAppFolder = driveService.files().create(appFolderMetadata)
                        .setFields("id")
                        .execute()
                    createdAppFolder.id
                }

                val existingRecipientFolders = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='$recipientName' and '$shareFileBcAppFolderId' in parents and trashed=false")
                    .setFields("files(id)")
                    .execute()

                val recipientFolderId = if (existingRecipientFolders.files.isNotEmpty()) {
                    existingRecipientFolders.files[0].id
                } else {
                    val recipientFolderMetadata = File().apply {
                        name = recipientName
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf(shareFileBcAppFolderId)
                    }
                    val createdRecipientFolder = driveService.files().create(recipientFolderMetadata)
                        .setFields("id")
                        .execute()
                    createdRecipientFolder.id
                }

                val jstTimeZone = TimeZone.getTimeZone("Asia/Tokyo")
                val jstCalendar = Calendar.getInstance(jstTimeZone)

                val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                dateFormatter.timeZone = jstTimeZone
                val currentDateOnly = dateFormatter.format(jstCalendar.time)

                val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                dateTimeFormatter.timeZone = jstTimeZone
                val currentDateTimeForRecord = dateTimeFormatter.format(jstCalendar.time)

                Log.d("DriveUploader", "📅 JST現在時刻: $currentDateTimeForRecord")

                val existingDateFolders = driveService.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='$currentDateOnly' and '$recipientFolderId' in parents and trashed=false")
                    .setFields("files(id)")
                    .execute()

                val dateFolderId = if (existingDateFolders.files.isNotEmpty()) {
                    existingDateFolders.files[0].id
                } else {
                    val dateFolderMetadata = File().apply {
                        name = currentDateOnly
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf(recipientFolderId)
                    }
                    val createdDateFolder = driveService.files().create(dateFolderMetadata)
                        .setFields("id")
                        .execute()
                    createdDateFolder.id
                }

                val fileMetadata = File().apply {
                    name = fileName
                    parents = listOf(dateFolderId)
                }

                val fileContent = com.google.api.client.http.ByteArrayContent(
                    "application/octet-stream",
                    fileBytes
                )
                val uploadedFile = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id, name, webViewLink")
                    .execute()

                val fileId = uploadedFile.id
                val webViewLink = uploadedFile.webViewLink

                val dao = db.sharedFolderDao()
                dao.insert(
                    SharedFolderEntity(
                        date = currentDateTimeForRecord,
                        recipientName = recipientName,
                        folderId = dateFolderId,
                        fileName = fileName,
                        fileGoogleDriveId = fileId
                    )
                )

                Log.d("DriveUploader", "Uploaded File ID: $fileId, Folder ID: $dateFolderId, Web View Link: $webViewLink, FileName: $fileName")
                Log.d("DriveUploader", "📅 アップロード時刻(JST): $currentDateTimeForRecord")

                return@withContext Triple(fileName, fileId, dateFolderId)
            } catch (e: Exception) {
                Log.e("DriveUploader", "Error uploading file and recording", e)
                return@withContext null
            }
        }
    }
}
