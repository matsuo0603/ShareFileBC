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
import com.example.sharefilebc.managers.TapyrusWalletManager
import com.example.sharefilebc.crypto.SecurePackage

class DriveDownloader(private val context: Context) {

    // 表示用期限：アップロードから7日
    private val expirationMillis: Long = 7L * 24 * 60 * 60 * 1000

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
                    .setFields("id, name, parents, owners(displayName, emailAddress)")
                    .execute()

                val parentFolderId = folderInfo.parents?.firstOrNull()
                val parentFolderInfo = parentFolderId?.let {
                    driveService.files().get(it)
                        .setFields("name, owners(displayName, emailAddress)")
                        .execute()
                }

                val folderOwner = folderInfo.owners?.firstOrNull()
                val folderOwnerName = folderOwner?.displayName?.takeIf(String::isNotBlank)
                val folderOwnerEmail = folderOwner?.emailAddress?.takeIf(String::isNotBlank)

                val parentOwner = parentFolderInfo?.owners?.firstOrNull()
                val parentOwnerName = parentOwner?.displayName?.takeIf(String::isNotBlank)
                val parentOwnerEmail = parentOwner?.emailAddress?.takeIf(String::isNotBlank)

                val parentNameValue = parentFolderInfo?.name
                val parentNameFallback = parentNameValue?.takeIf(String::isNotBlank)

                val fallbackSenderName = when {
                    folderOwnerName != null -> folderOwnerName
                    parentOwnerName != null -> parentOwnerName
                    folderOwnerEmail != null -> folderOwnerEmail
                    parentOwnerEmail != null -> parentOwnerEmail
                    parentNameFallback != null -> parentNameFallback
                    else -> "Unknown Sender"
                }

                val fileList = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("files(id, name, mimeType, createdTime, owners(displayName, emailAddress))")
                    .execute()

                val jst = TimeZone.getTimeZone("Asia/Tokyo")
                val fullFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                    timeZone = jst
                }

                val files = fileList.files?.map { file ->
                    val isFolder = file.mimeType == "application/vnd.google-apps.folder"
                    val uploadMillis = file.createdTime?.value ?: System.currentTimeMillis()
                    val uploadDate = Date(uploadMillis)
                    val uploadStr = fullFormatter.format(uploadDate)
                    val deleteStr = fullFormatter.format(Date(uploadMillis + expirationMillis))
                    val owner = file.owners?.firstOrNull()
                    val ownerDisplayName = owner?.displayName?.takeIf(String::isNotBlank)
                    val ownerEmail = owner?.emailAddress?.takeIf(String::isNotBlank)

                    DriveFileInfo(
                        id = file.id,
                        name = file.name,
                        mimeType = file.mimeType,
                        isFolder = isFolder,
                        senderName = if (isFolder) "" else (
                                ownerDisplayName ?: ownerEmail ?: fallbackSenderName
                                ),
                        uploadDateTime = if (isFolder) "" else uploadStr,
                        deleteDateTime = if (isFolder) "" else deleteStr
                    )
                } ?: emptyList()

                val folderName = folderInfo.name ?: "Unknown Date"

                FolderStructure(
                    folderName = folderName,
                    files = files
                )
            } catch (e: Exception) {
                Log.e("DriveDownloader", "Error getting folder structure", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "フォルダ情報の取得に失敗しました: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
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

                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = java.io.File(downloadsDir, fileMetadata.name)

                FileOutputStream(outputFile).use {
                    driveService.files().get(fileId).executeMediaAndDownloadTo(it)
                }

                val decryptedFile = tryDecryptPackageIfNeeded(outputFile)

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(decryptedFile.absolutePath),
                    null,
                    null
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "${decryptedFile.name} をダウンロードしました。",
                        Toast.LENGTH_LONG
                    ).show()
                }

                decryptedFile
            } catch (e: Exception) {
                Log.e("DriveDownloader", "❌ ダウンロードエラー", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "ダウンロードに失敗しました: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            }
        }
    }

    private fun tryDecryptPackageIfNeeded(downloadedFile: java.io.File): java.io.File {
        if (!downloadedFile.name.endsWith(".vpfs")) return downloadedFile

        return try {
            val packageBytes = downloadedFile.readBytes()
            val wallet = TapyrusWalletManager.getInstance(context)
            val recipientPrivateKeyHex = wallet.getCurrentPrivateKeyHex()
            val (decryptedBytes, fileName) = SecurePackage.unpack(
                packageData = packageBytes,
                recipientPrivateKeyHex = recipientPrivateKeyHex,
                signerPublicKeyHex = null
            )

            val outputFile = java.io.File(downloadedFile.parentFile, fileName)
            FileOutputStream(outputFile).use { it.write(decryptedBytes) }
            outputFile
        } catch (e: Exception) {
            Log.e("DriveDownloader", "パッケージ復号に失敗しました", e)
            downloadedFile
        }
    }
}
