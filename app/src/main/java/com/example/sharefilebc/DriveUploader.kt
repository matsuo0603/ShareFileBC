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
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.managers.TapyrusWalletManager
import com.example.sharefilebc.crypto.SecurePackage

class DriveUploader(private val context: Context) {

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

    suspend fun uploadFileAndRecordWithSharing(
        fileUri: Uri,
        recipient: UserEntity,
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
                val recipientFolderId = getOrCreateFolder(driveService, recipient.name, appFolderId)
                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = jst }
                val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { timeZone = jst }
                val now = Calendar.getInstance(jst).time
                val currentDate = dateFormatter.format(now)
                val currentDateTime = dateTimeFormatter.format(now)

                val dateFolderId = getOrCreateFolder(driveService, currentDate, recipientFolderId)
                val wallet = TapyrusWalletManager.getInstance(context)

                val recipientPublicKeyHex = recipient.publicKeyHex.takeIf { it.isNotBlank() }
                val signingPrivateKeyHex = wallet.getCurrentPrivateKeyHex()
                val signerPublicKeyHex = wallet.getCurrentPublicKeyHex()

                val (uploadBytes, uploadName) = if (recipientPublicKeyHex != null) {
                    val secureBytes = SecurePackage.create(
                        data = fileBytes,
                        fileName = fileName,
                        recipientPublicKeyHex = recipientPublicKeyHex,
                        signingPrivateKeyHex = signingPrivateKeyHex,
                        signerPublicKeyHex = signerPublicKeyHex
                    )
                    secureBytes to "$fileName.vpfs"
                } else {
                    fileBytes to fileName
                }
                val fileMetadata = File().apply {
                    name = uploadName
                    parents = listOf(dateFolderId)
                }
                val fileContent = com.google.api.client.http.ByteArrayContent("application/octet-stream", uploadBytes)
                val uploadedFile = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id, name, webViewLink")
                    .execute()

                // ✅ 受信者名フォルダを共有（ファイル個別や日付フォルダには付与しない）
                val folderPermission = Permission().apply {
                    type = "anyone"
                    role = "reader"
                }
                driveService.permissions().create(recipientFolderId, folderPermission)
                    .setSendNotificationEmail(false)
                    .execute()

                // 送信ログは従来どおり日付フォルダIDを保存し、削除処理に利用
                db.sharedFolderDao().insert(
                    SharedFolderEntity(
                        date = currentDateTime,
                        recipientName = recipient.name,
                        folderId = dateFolderId,
                        fileName = fileName,
                        fileGoogleDriveId = uploadedFile.id
                    )
                )

                // 共有リンク用には受信者名フォルダIDを返す
                return@withContext Triple(fileName, uploadedFile.id, recipientFolderId)
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
