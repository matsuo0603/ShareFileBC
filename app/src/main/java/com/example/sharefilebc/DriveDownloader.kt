package com.example.sharefilebc

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.example.sharefilebc.crypto.SecurePackage
import com.example.sharefilebc.data.AppDatabase
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DriveDownloader(private val context: Context) {

    data class FileContext(
        val parentFolderId: String,
        val ownerName: String,
        val ownerEmail: String?,
    )

    // 表示用期限：アップロードから7日
    private val expirationMillis: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * 鍵の用途とパスの整理：
     *
     * 【暗号化・復号】
     * - 送信側: 受信者の /1 公開鍵で AES鍵を暗号化（ECIES）
     * - 受信側: 自分の /1 秘密鍵で AES鍵を復号（ECIES）
     *
     * 【署名・検証】
     * - 送信側: 自分の /0 秘密鍵で署名（ECDSA）
     * - 受信側: 送信者の /0 公開鍵で署名検証（ECDSA）
     */
    private val RECIPIENT_PRIVATE_KEY_PATH = "m/44'/0'/0'/0/1"

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

    suspend fun resolveFileContext(fileId: String): FileContext? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext null
                val fileMetadata = driveService.files().get(fileId)
                    .setFields("id, parents, owners(displayName, emailAddress)")
                    .execute()

                val parentFolderId = fileMetadata.parents?.firstOrNull() ?: return@withContext null
                val owner = fileMetadata.owners?.firstOrNull()
                val ownerName = owner?.displayName?.takeIf(String::isNotBlank)
                    ?: owner?.emailAddress
                    ?: "Unknown Sender"

                FileContext(
                    parentFolderId = parentFolderId,
                    ownerName = ownerName,
                    ownerEmail = owner?.emailAddress
                )
            } catch (e: Exception) {
                Log.e("DriveDownloader", "Error resolving file context", e)
                null
            }
        }
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
                        senderName = if (isFolder) "" else (ownerDisplayName ?: ownerEmail ?: fallbackSenderName),
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

    /**
     * ✅ 修正ポイント：
     * 1) Downloads直書きせず、まず app 専用領域（外部アプリ領域 or 内部）へ保存
     * 2) 復号も同じ一時領域で実行
     * 3) 最後に MediaStore 経由で Downloads にコピー（Android10+対応）
     */
    suspend fun downloadFile(fileId: String): java.io.File? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext null

                // 送信者（ownerEmail）を取っておく（復号時の署名検証に必要）
                val fileCtx = resolveFileContext(fileId)

                val fileMetadata = driveService.files().get(fileId).execute()
                val originalName = fileMetadata.name ?: "downloaded.vpfs"

                // ✅ 一時保存先（EACCES回避）
                val tempDir: File = context.getExternalFilesDir(null) ?: context.filesDir
                val tempFile = File(tempDir, "$originalName.tmp")

                Log.d("DriveDownloader", "📥 ダウンロード開始: $originalName")
                Log.d("DriveDownloader", "📂 保存先(temp): ${tempFile.absolutePath}")

                FileOutputStream(tempFile).use {
                    driveService.files().get(fileId).executeMediaAndDownloadTo(it)
                }

                Log.d("DriveDownloader", "✅ ダウンロード完了: ${tempFile.length()} bytes")

                // 復号処理（tempDir内で完結）
                val decryptedFile = tryDecryptPackageIfNeeded(
                    downloadedFile = tempFile,
                    senderEmail = fileCtx?.ownerEmail
                )

                // ✅ 最終的にDownloadsへコピー（MediaStore）
                val finalFile = copyToDownloads(decryptedFile)

                // 一時ファイル削除
                if (tempFile.exists()) tempFile.delete()
                if (decryptedFile != tempFile && decryptedFile.exists()) decryptedFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "✅ ${finalFile.name} をDownloadsに保存しました",
                        Toast.LENGTH_LONG
                    ).show()
                }

                finalFile
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

    /**
     * 送信者の公開鍵（署名検証用）をDBから取得する。
     *
     * 署名は /0 で行われているため、trustLayerPublicKey（/0）を使用する。
     *
     * 優先順位：
     *  1) EmailKeyEntity.trustLayerPublicKey（署名用：/0）
     *  2) UserEntity.publicKeyHex（フォールバック）
     */
    private suspend fun resolveSignerPublicKeyHex(senderEmail: String?): String? {
        if (senderEmail.isNullOrBlank()) return null

        return try {
            val db = AppDatabase.getDatabase(context.applicationContext)

            val emailKey = db.emailKeyDao().findByEmail(senderEmail)
            val fromEmailKey = emailKey?.trustLayerPublicKey?.takeIf { it.isNotBlank() }
            if (fromEmailKey != null) {
                Log.d("DriveDownloader", "🔑 送信者署名用公開鍵（/0）: ${fromEmailKey.take(16)}...")
                return fromEmailKey
            }

            val user = db.userDao().findByEmail(senderEmail)
            val fromUser = user?.publicKeyHex?.takeIf { it.isNotBlank() }
            if (fromUser != null) {
                Log.d("DriveDownloader", "🔑 送信者公開鍵（User）: ${fromUser.take(16)}...")
            }
            fromUser
        } catch (e: Exception) {
            Log.e("DriveDownloader", "⚠ 送信者公開鍵の取得に失敗しました", e)
            null
        }
    }

    private suspend fun tryDecryptPackageIfNeeded(
        downloadedFile: java.io.File,
        senderEmail: String?
    ): java.io.File {
        // ✅ .tmp も許容（temp保存してるため）
        val name = downloadedFile.name
        val isVpfsLike = name.endsWith(".vpfs", ignoreCase = true) || name.contains(".vpfs", ignoreCase = true)
        if (!isVpfsLike) return downloadedFile

        return try {
            Log.d("DriveDownloader", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d("DriveDownloader", "📦 .vpfsファイルの復号を開始")
            Log.d("DriveDownloader", "📄 ファイル名: ${downloadedFile.name}")
            Log.d("DriveDownloader", "📧 送信者メール: $senderEmail")

            val signerPublicKeyHex = resolveSignerPublicKeyHex(senderEmail)

            if (signerPublicKeyHex.isNullOrBlank()) {
                Log.e("DriveDownloader", "❌ 復号に必要な送信者公開鍵が見つかりません")
                Log.e("DriveDownloader", "💡 ヒント: 共有相手登録（公開鍵の交換）が完了しているか確認してください")

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "送信者の公開鍵が見つかりません。\n共有相手登録を確認してください。",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return downloadedFile
            }

            val packageBytes = downloadedFile.readBytes()
            Log.d("DriveDownloader", "📊 パッケージサイズ: ${packageBytes.size} bytes")

            val wallet = KeyDerivation.getInstance(context)

            // 受信用秘密鍵（/1）
            val recipientPrivateKeyHex = wallet.getCurrentPrivateKeyHex(RECIPIENT_PRIVATE_KEY_PATH)
            val recipientPublicKeyHex = wallet.getCurrentPublicKeyHex(RECIPIENT_PRIVATE_KEY_PATH)

            Log.d("DriveDownloader", "🔐 受信者公開鍵(/1): ${recipientPublicKeyHex.take(16)}...")
            Log.d("DriveDownloader", "✍️  送信者署名用公開鍵(/0): ${signerPublicKeyHex.take(16)}...")
            Log.d("DriveDownloader", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val (decryptedBytes, fileName) = SecurePackage.unpack(
                packageData = packageBytes,
                recipientPrivateKeyHex = recipientPrivateKeyHex,
                signerPublicKeyHex = signerPublicKeyHex
            )

            // ✅ 同じディレクトリ（tempDir）に復号ファイルを作成
            val outputFile = java.io.File(downloadedFile.parentFile, fileName)
            FileOutputStream(outputFile).use { it.write(decryptedBytes) }

            Log.d("DriveDownloader", "✅ 復号成功: $fileName (${decryptedBytes.size} bytes)")

            outputFile
        } catch (e: IllegalStateException) {
            Log.e("DriveDownloader", "❌ パッケージ復号エラー: ${e.message}", e)
            withContext(Dispatchers.Main) {
                val errorMsg = when {
                    e.message?.contains("署名検証") == true ->
                        "署名検証に失敗しました。\n送信者の公開鍵が正しいか確認してください。"
                    e.message?.contains("パッケージの内容") == true ->
                        "ファイルが破損しています。"
                    else ->
                        "ファイルの復号に失敗しました: ${e.message}"
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
            downloadedFile
        } catch (e: Exception) {
            Log.e("DriveDownloader", "❌ 予期しないエラー", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "エラーが発生しました: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            downloadedFile
        }
    }

    /**
     * ファイルをDownloadsフォルダへコピー（MediaStore経由）
     * Android 10以降でも動作する方法
     */
    private suspend fun copyToDownloads(sourceFile: java.io.File): java.io.File {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(sourceFile.name))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val uri: Uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        contentValues
                    ) ?: throw IOException("Failed to create MediaStore entry")

                    resolver.openOutputStream(uri)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw IOException("Failed to open output stream")

                    Log.d("DriveDownloader", "✅ MediaStore経由でコピー完了: ${sourceFile.name} uri=$uri")

                    // Android 10+ は MediaStore 登録済みなので scan は基本不要（やるなら file path ではなく uri が必要）
                    // ここでは何もしない

                    // 返り値は表示用に “Downloadsにあるはずのファイル名” を持つ File を返す
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sourceFile.name)
                } else {
                    // Android 9以前: 直接コピー（WRITE_EXTERNAL_STORAGE が必要になる可能性あり）
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val destFile = java.io.File(downloadsDir, sourceFile.name)

                    sourceFile.inputStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        null,
                        null
                    )

                    Log.d("DriveDownloader", "✅ 直接コピー完了: ${destFile.absolutePath}")
                    destFile
                }
            } catch (e: Exception) {
                Log.e("DriveDownloader", "❌ Downloadsフォルダへのコピー失敗", e)
                // エラー時は元のファイルを返す
                sourceFile
            }
        }
    }

    /**
     * ファイル名からMIMEタイプを推測
     */
    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
