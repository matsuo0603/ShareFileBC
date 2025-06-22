// ファイルパス: com.example.sharefilebc.DriveUploader.kt

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
import com.google.api.services.drive.model.Permission
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

    suspend fun uploadFileAndRecordWithSharing(
        fileUri: Uri,
        recipientName: String,
        recipientEmail: String,
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

                val appFolderId = getOrCreateFolder(driveService, "ShareFileBCApp", "root")
                val recipientFolderId = getOrCreateFolder(driveService, recipientName, appFolderId)

                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = jst }
                val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { timeZone = jst }
                val now = Calendar.getInstance(jst).time
                val currentDate = dateFormatter.format(now)
                val currentDateTime = dateTimeFormatter.format(now)

                val dateFolderId = getOrCreateFolder(driveService, currentDate, recipientFolderId)

                val fileMetadata = File().apply {
                    name = fileName
                    parents = listOf(dateFolderId)
                }
                val fileContent = com.google.api.client.http.ByteArrayContent("application/octet-stream", fileBytes)
                val uploadedFile = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id, name, webViewLink")
                    .execute()

                // 👇 共有権限を追加
                val permission = Permission().apply {
                    type = "user"
                    role = "reader"
                    emailAddress = recipientEmail
                }
                driveService.permissions().create(uploadedFile.id, permission)
                    .setSendNotificationEmail(false)
                    .execute()

                db.sharedFolderDao().insert(
                    SharedFolderEntity(
                        date = currentDateTime,
                        recipientName = recipientName,
                        folderId = dateFolderId,
                        fileName = fileName,
                        fileGoogleDriveId = uploadedFile.id
                    )
                )

                return@withContext Triple(fileName, uploadedFile.id, dateFolderId)
            } catch (e: Exception) {
                Log.e("DriveUploader", "Upload error", e)
                return@withContext null
            }
        }
    }

    private fun getOrCreateFolder(drive: Drive, name: String, parentId: String): String {
        val existing = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='$name' and '$parentId' in parents and trashed=false")
            .setFields("files(id)")
            .execute()

        return if (existing.files.isNotEmpty()) {
            existing.files[0].id
        } else {
            val metadata = File().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            val created = drive.files().create(metadata)
                .setFields("id")
                .execute()
            created.id
        }
    }
}
