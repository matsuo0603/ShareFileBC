package com.example.sharefilebc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.sharefilebc.crypto.SecurePackage
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.EmailKeyEntity
import com.example.sharefilebc.data.MyPublicKeyEntity
import com.example.sharefilebc.data.SharedFolderEntity
import com.example.sharefilebc.data.UserEntity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

sealed class UploadResult {
    data class Success(val fileName: String, val fileId: String, val folderId: String) : UploadResult()
    data class MissingRecipientPublicKey(val registrationLink: String?) : UploadResult()
    data class Failure(val error: Throwable? = null) : UploadResult()
}

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
        recipientKey: EmailKeyEntity,
        db: AppDatabase
    ): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext UploadResult.Failure()

                val fileName = context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "Unknown File"

                val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)
                val fileBytes = inputStream?.readBytes() ?: return@withContext UploadResult.Failure()

                val appFolderId = getOrCreateFolder(driveService, "ShareFileBCApp", "root")
                val recipientFolderId = getOrCreateFolder(driveService, recipient.name, appFolderId)

                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = jst }
                val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { timeZone = jst }
                val now = Calendar.getInstance(jst).time
                val currentDate = dateFormatter.format(now)
                val currentDateTime = dateTimeFormatter.format(now)

                val dateFolderId = getOrCreateFolder(driveService, currentDate, recipientFolderId)

                val wallet = KeyDerivation.getInstance(context)
                resolveMyKeys(db, wallet) // DBに自分の鍵を保存・更新

                // ✅ 重要：送信直前にDBから相手鍵を取り直す（Swift版寄せ）
                val emailKeyDao = db.emailKeyDao()
                val latestFromDb = runCatching { emailKeyDao.findByEmail(recipient.email) }.getOrNull()
                val effectiveKey = latestFromDb ?: recipientKey

                if (latestFromDb == null) {
                    Log.w("DriveUploader", "⚠️ recipient key not found in DB. fallback to passed key. email=${recipient.email}")
                } else if (!latestFromDb.derivedPublicKey.equals(recipientKey.derivedPublicKey, ignoreCase = true)) {
                    Log.w(
                        "DriveUploader",
                        "⚠️ recipientKey mismatch (passed vs DB). use DB.\nemail=${recipient.email}\npassed=${recipientKey.derivedPublicKey}\ndb=${latestFromDb.derivedPublicKey}"
                    )
                }

                val recipientPublicKeyHex = effectiveKey.derivedPublicKey.trim().takeIf { it.isNotBlank() }
                    ?: return@withContext UploadResult.MissingRecipientPublicKey(null)

                // ✅ 形式チェック（圧縮公開鍵 33byte = 66hex、先頭 02/03）
                val okFormat =
                    recipientPublicKeyHex.length == 66 &&
                            (recipientPublicKeyHex.startsWith("02") || recipientPublicKeyHex.startsWith("03"))
                if (!okFormat) {
                    Log.e(
                        "DriveUploader",
                        "❌ invalid recipientPublicKeyHex. email=${recipient.email} len=${recipientPublicKeyHex.length} key=$recipientPublicKeyHex"
                    )
                    return@withContext UploadResult.MissingRecipientPublicKey(null)
                }

                // ✅ 必須ログ（全文）
                Log.d("DriveUploader", "🔐 encrypt recipientPublicKeyHex(full)=$recipientPublicKeyHex")
                Log.d("DriveUploader", "🔐 encrypt recipientPublicKeyHex(len)=${recipientPublicKeyHex.length}")
                Log.d("DriveUploader", "📧 recipient=${recipient.email} name=${recipient.name}")

                val signingPrivateKeyHex = wallet.getCurrentPrivateKeyHex()
                val signerPublicKeyHex = wallet.getCurrentPublicKeyHex()

                val secureBytes = SecurePackage.create(
                    data = fileBytes,
                    fileName = fileName,
                    recipientPublicKeyHex = recipientPublicKeyHex,
                    signingPrivateKeyHex = signingPrivateKeyHex,
                    signerPublicKeyHex = signerPublicKeyHex
                )
                val uploadName = "$fileName.vpfs"

                val fileMetadata = File().apply {
                    name = uploadName
                    parents = listOf(dateFolderId)
                }

                val fileContent = com.google.api.client.http.ByteArrayContent("application/octet-stream", secureBytes)
                val uploadedFile = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id, name, webViewLink")
                    .execute()

                grantReaderPermission(driveService, dateFolderId, recipient.email)
                grantReaderPermission(driveService, uploadedFile.id, recipient.email)

                db.sharedFolderDao().insert(
                    SharedFolderEntity(
                        date = currentDateTime,
                        recipientName = recipient.name,
                        folderId = dateFolderId,
                        fileName = fileName,
                        fileGoogleDriveId = uploadedFile.id
                    )
                )

                UploadResult.Success(fileName, uploadedFile.id, dateFolderId)
            } catch (e: Exception) {
                Log.e("DriveUploader", "Upload error", e)
                UploadResult.Failure(e)
            }
        }
    }

    private fun grantReaderPermission(
        driveService: Drive,
        targetId: String,
        recipientEmail: String
    ) {
        try {
            val permission = Permission().apply {
                type = "user"
                role = "reader"
                emailAddress = recipientEmail
            }
            driveService.permissions().create(targetId, permission)
                .setSendNotificationEmail(false)
                .execute()
            Log.d("DriveUploader", "✅ granted reader permission: target=$targetId to=$recipientEmail")
        } catch (e: Exception) {
            Log.e("DriveUploader", "❌ failed to grant permission: target=$targetId to=$recipientEmail", e)
        }
    }

    private suspend fun resolveMyKeys(
        db: AppDatabase,
        wallet: KeyDerivation
    ): Pair<String, String> {
        val myKeyDao = db.myPublicKeyDao()
        val existing = myKeyDao.getPrimary()
        val trustLayer = existing?.trustLayerPublicKey
            ?: wallet.getCurrentPublicKeyHex("m/44'/0'/0'/0/0")
        val derived = existing?.derivedPublicKey
            ?: wallet.getCurrentPublicKeyHex("m/44'/0'/0'/0/1")

        if (existing == null || existing.trustLayerPublicKey != trustLayer || existing.derivedPublicKey != derived) {
            myKeyDao.upsert(
                MyPublicKeyEntity(
                    trustLayerPublicKey = trustLayer,
                    derivedPublicKey = derived
                )
            )
        }
        return trustLayer to derived
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
