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
import com.google.api.client.googleapis.json.GoogleJsonResponseException
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
import javax.crypto.AEADBadTagException

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
    // ✅ iOS(Swift) の BIP32 派生鍵 (Constants.Strings.bip32Path) と合わせる
    // Swift 側では xprv から m/44'/0'/0'/0/0 を導出して ECIES 復号に使っている。
    // ここがズレると ECIES で復号した AESKey が不一致となり、AES-GCM のタグ不一致(AEADBadTagException) になる。
    // ✅ 受信(復号)に使うのは derivedKey(/1)
    private val RECIPIENT_PRIVATE_KEY_PATH = KeyDerivation.DERIVED_KEY_PATH

    /**
     * Drive上の実ファイル名は securePackage.vpfs のままなので、
     * 受信一覧では Room(received_files) に保存してある「復号済みファイル名」を優先して表示する。
     *
     * deep link で nameMeta を受け取った直後は、Drive(sharedWithMe) に出てこないケースがある
     * （anyone:reader の“公開リンク共有”）ため、
     * "file:<fileId>" の擬似フォルダ表示でも必ずここを通して表示名を決める。
     */
    private suspend fun resolveDisplayFileName(fileId: String, driveName: String?): String {
        val fallback = driveName ?: "securePackage.vpfs"

        val db = AppDatabase.getDatabase(context)
        val row = runCatching { db.receivedFileDao().findByFileId(fileId) }.getOrNull()

        // 既に復号済みの名前が保存されているならそれを使う
        val savedName = row?.fileName?.trim().orEmpty()
        if (savedName.isNotBlank() && !isVpfsLikeFileName(savedName)) {
            return savedName
        }

        // nameMeta があり、かつ表示名が .vpfs のままなら、ここで「ファイル名だけ」復号して DB に保存する
        val nameMeta = row?.nameMetadata?.trim().orEmpty()
        val senderParam = row?.senderPublicKey?.trim().orEmpty()
        if (nameMeta.isBlank() || senderParam.isBlank()) {
            return fallback
        }
        if (!isVpfsLikeFileName(fallback) && !isVpfsLikeFileName(savedName)) {
            return fallback
        }

        return runCatching {
            val kd = KeyDerivation.getInstance(context)
            val recipientPriv = kd.getCurrentPrivateKeyHex(RECIPIENT_PRIVATE_KEY_PATH)
            val recipientPub = kd.getCurrentPublicKeyHex(RECIPIENT_PRIVATE_KEY_PATH)
            val candidates = SignerKeyResolver.resolveCandidates(
                context = context,
                senderParamPubKeyHex = senderParam,
                senderEmailOrNull = null
            )

            CryptoTrace.logReceiveKeyRoles(
                event = "DriveDownloader.resolveDisplayFileName:nameMeta",
                senderParam = senderParam,
                signerPubKeyUsedForVerify = candidates.firstOrNull() ?: senderParam,
                recipientPathUsedForDecrypt = RECIPIENT_PRIVATE_KEY_PATH,
                recipientPrivKeyHexUsed = recipientPriv,
                recipientPubDerivedFromPriv = recipientPub
            )

            var lastErr: Throwable? = null
            var plainName: String? = null
            var verifiedKey: String? = null
            for (cand in candidates) {
                try {
                    val n = SecurePackage.decryptFileNameFromNameMeta(
                        nameMetaBase64Url = nameMeta,
                        recipientPrivateKeyHex = recipientPriv,
                        signerPublicKeyHex = cand
                    )
                    plainName = n
                    verifiedKey = cand
                    break
                } catch (t: Throwable) {
                    lastErr = t
                }
            }
            if (plainName == null) throw (lastErr ?: IllegalStateException("nameMeta decrypt failed"))

            if (verifiedKey != null && !verifiedKey.equals(senderParam, ignoreCase = true)) {
                Log.w("DriveDownloader", "⚠️ nameMeta signer fallback used: senderParam=${senderParam.take(16)}... verified=${verifiedKey.take(16)}...")
            }

            // DB に保存（次回以降、UIはDBの名前を優先できる）
            runCatching {
                val dao = db.receivedFileDao()
                val latest = dao.findByFileId(fileId)
                if (latest != null) {
                    dao.insert(
                        latest.copy(
                            fileName = plainName,
                            nameMetadataError = null
                        )
                    )
                }
            }

            Log.d("DriveDownloader", "✅ resolved display fileName via nameMeta: fileId=$fileId -> $plainName")
            plainName
        }.getOrElse { e ->
            // 次の切り分け用に DB に残す
            runCatching {
                val dao = db.receivedFileDao()
                val latest = dao.findByFileId(fileId)
                if (latest != null && latest.nameMetadataError.isNullOrBlank()) {
                    dao.insert(latest.copy(nameMetadataError = "nameMeta decrypt failed: ${e.message}"))
                }
            }
            Log.w("DriveDownloader", "⚠️ resolveDisplayFileName failed: fileId=$fileId reason=${e.message}")
            fallback
        }
    }

    private fun isVpfsLikeFileName(name: String): Boolean {
        // .tmp も許容（temp保存してるため）
        return name.endsWith(".vpfs", ignoreCase = true) || name.contains(".vpfs", ignoreCase = true)
    }

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.w("DriveDownloader", "⚠️ Drive service unavailable: GoogleSignIn.getLastSignedInAccount == null (need login)")
            return null
        }
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

                // ✅ 擬似フォルダID（file:<fileId>）対応：
                if (folderId.startsWith("file:")) {
                    val fileId = folderId.removePrefix("file:").trim()
                    if (fileId.isBlank()) return@withContext null

                    val file = driveService.files().get(fileId)
                        .setFields("id, name, mimeType, createdTime, owners(displayName, emailAddress)")
                        .execute()

                    val displayName = resolveDisplayFileName(fileId = file.id, driveName = file.name)

                    val jst = TimeZone.getTimeZone("Asia/Tokyo")
                    val fullFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                        timeZone = jst
                    }

                    val uploadMillis = file.createdTime?.value ?: System.currentTimeMillis()
                    val uploadDate = Date(uploadMillis)
                    val uploadStr = fullFormatter.format(uploadDate)
                    val deleteStr = fullFormatter.format(Date(uploadMillis + expirationMillis))

                    val owner = file.owners?.firstOrNull()
                    val ownerDisplayName = owner?.displayName?.takeIf(String::isNotBlank)
                    val ownerEmail = owner?.emailAddress?.takeIf(String::isNotBlank)
                    val senderName = ownerDisplayName ?: ownerEmail ?: "Unknown Sender"

                    return@withContext FolderStructure(
                        folderName = "(shared file)",
                        files = listOf(
                            DriveFileInfo(
                                id = file.id,
                                name = displayName,
                                mimeType = file.mimeType,
                                isFolder = false,
                                senderName = senderName,
                                uploadDateTime = uploadStr,
                                deleteDateTime = deleteStr
                            )
                        )
                    )
                }

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

                    val displayName = if (isFolder) {
                        file.name
                    } else {
                        resolveDisplayFileName(fileId = file.id, driveName = file.name)
                    }

                    DriveFileInfo(
                        id = file.id,
                        name = displayName,
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

                if (e is GoogleJsonResponseException && e.statusCode == 404) {
                    Log.w("DriveDownloader", "⚠️ Folder not found (404). Removing from Room. folderId=$folderId")
                    runCatching {
                        val db = AppDatabase.getDatabase(context.applicationContext)
                        db.receivedFolderDao().deleteByFolderId(folderId)
                    }.onFailure { t ->
                        Log.w("DriveDownloader", "⚠️ Failed to delete invalid folderId from Room: $folderId", t)
                    }
                    return@withContext null
                }

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

                val fileCtx = resolveFileContext(fileId)

                val receivedSenderPubKey: String? = runCatching {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    db.receivedFileDao().findByFileId(fileId)?.senderPublicKey
                }.getOrNull()

                val fileMetadata = driveService.files().get(fileId).execute()
                val originalName = fileMetadata.name ?: "downloaded.vpfs"

                val tempDir: File = context.getExternalFilesDir(null) ?: context.filesDir
                val tempFile = File(tempDir, "$originalName.tmp")

                Log.d("DriveDownloader", "📥 ダウンロード開始: $originalName")
                Log.d("DriveDownloader", "📂 保存先(temp): ${tempFile.absolutePath}")

                FileOutputStream(tempFile).use {
                    driveService.files().get(fileId).executeMediaAndDownloadTo(it)
                }

                Log.d("DriveDownloader", "✅ ダウンロード完了: ${tempFile.length()} bytes")

                val decryptedFile = tryDecryptPackageIfNeeded(
                    downloadedFile = tempFile,
                    senderEmail = fileCtx?.ownerEmail,
                    senderPublicKeyHex = receivedSenderPubKey
                )

                if (decryptedFile == tempFile && isVpfsLikeFileName(tempFile.name)) {
                    if (tempFile.exists()) tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "復号に失敗しました。\n送信側が別の受信者鍵で暗号化している可能性があります。\n共有相手登録（鍵交換）をやり直してください。",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext null
                }

                val finalFile = copyToDownloads(decryptedFile)

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
     * 署名検証に使う送信者公開鍵を「候補」として集める。
     *
     * iOS 側で sender パラメータが誤って別の公開鍵（例: アドレス生成で得た可変鍵）になった場合でも、
     * 送信者Emailに紐づく trustLayerPublicKey(/0) で署名検証できる可能性がある。
     *
     * 戻り値は "pk1,pk2,..." のカンマ区切り。
     * SecurePackage 側が候補を順に verify し、通った鍵を採用する。
     */
    private suspend fun resolveSignerPublicKeyHex(senderEmail: String?, senderPublicKeyHex: String?): String? {
        val candidates = mutableListOf<String>()

        val direct = senderPublicKeyHex?.trim()?.takeIf { it.isNotBlank() }
        if (direct != null) {
            Log.d("DriveDownloader", "🔑 送信者署名用公開鍵候補(direct): ${direct.take(16)}...")
            candidates.add(direct)
        }

        if (!senderEmail.isNullOrBlank()) {
            try {
                val db = AppDatabase.getDatabase(context.applicationContext)

                val emailKey = db.emailKeyDao().findByEmail(senderEmail)
                val fromEmailKey = emailKey?.trustLayerPublicKey?.trim()?.takeIf { it.isNotBlank() }
                if (fromEmailKey != null && !candidates.contains(fromEmailKey)) {
                    Log.d("DriveDownloader", "🔑 送信者署名用公開鍵候補(emailKey /0): ${fromEmailKey.take(16)}...")
                    candidates.add(fromEmailKey)
                }

                val user = db.userDao().findByEmail(senderEmail)
                val fromUser = user?.publicKeyHex?.trim()?.takeIf { it.isNotBlank() }
                if (fromUser != null && !candidates.contains(fromUser)) {
                    Log.d("DriveDownloader", "🔑 送信者署名用公開鍵候補(user): ${fromUser.take(16)}...")
                    candidates.add(fromUser)
                }
            } catch (e: Exception) {
                Log.e("DriveDownloader", "⚠ 送信者公開鍵候補の取得に失敗しました", e)
            }
        }

        return candidates.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private suspend fun tryDecryptPackageIfNeeded(
        downloadedFile: java.io.File,
        senderEmail: String?,
        senderPublicKeyHex: String?
    ): java.io.File {
        val name = downloadedFile.name
        val isVpfsLike = name.endsWith(".vpfs", ignoreCase = true) || name.contains(".vpfs", ignoreCase = true)
        if (!isVpfsLike) return downloadedFile

        return try {
            Log.d("DriveDownloader", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d("DriveDownloader", "📦 .vpfsファイルの復号を開始")
            Log.d("DriveDownloader", "📄 ファイル名: ${downloadedFile.name}")
            Log.d("DriveDownloader", "📧 送信者メール: $senderEmail")

            // ✅ Swift互換: sender= と署名鍵がズレるケースがあるので、複数候補で verify を試す。
            val senderParam = senderPublicKeyHex?.trim().orEmpty()
            val baseForResolve = if (senderParam.isNotBlank()) senderParam else {
                resolveSignerPublicKeyHex(senderEmail, null)?.trim().orEmpty()
            }
            val candidates = if (baseForResolve.isNotBlank()) {
                SignerKeyResolver.resolveCandidates(
                    context = context,
                    senderParamPubKeyHex = baseForResolve,
                    senderEmailOrNull = senderEmail
                )
            } else emptyList()

            if (candidates.isEmpty()) {
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

            val recipientPrivateKeyHex = wallet.getCurrentPrivateKeyHex(RECIPIENT_PRIVATE_KEY_PATH)
            val recipientPublicKeyHex = wallet.getCurrentPublicKeyHex(RECIPIENT_PRIVATE_KEY_PATH)

            val myDerivedPub = wallet.getCurrentPublicKeyHex(RECIPIENT_PRIVATE_KEY_PATH)
            Log.d("DriveDownloader", "🔑 my recipient publicKey(path=$RECIPIENT_PRIVATE_KEY_PATH, full)=$myDerivedPub")
            Log.d("DriveDownloader", "🔑 my recipient publicKey(len)=${myDerivedPub.length}")

            Log.d("DriveDownloader", "🔐 recipientPublicKeyHex(full)=$recipientPublicKeyHex")
            Log.d("DriveDownloader", "🔐 recipientPublicKeyHex(len)=${recipientPublicKeyHex.length}")
            Log.d(
                "DriveDownloader",
                "🔎 keys equal? path=$RECIPIENT_PRIVATE_KEY_PATH -> ${myDerivedPub.equals(recipientPublicKeyHex, ignoreCase = true)}"
            )

            Log.d("DriveDownloader", "🔐 受信者公開鍵(path=$RECIPIENT_PRIVATE_KEY_PATH): ${recipientPublicKeyHex.take(16)}...")
            Log.d("DriveDownloader", "✍️  signer candidates: ${candidates.map { it.take(16) + "..." }}")
            Log.d("DriveDownloader", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            var lastErr: Throwable? = null
            var unpacked: SecurePackage.Unpacked? = null
            for (cand in candidates) {
                try {
                    val r = SecurePackage.unpack(
                        vpfsBytes = packageBytes,
                        recipientPrivateKeyHex = recipientPrivateKeyHex,
                        signerPublicKeyHex = cand
                    )
                    unpacked = r
                    break
                } catch (t: Throwable) {
                    lastErr = t
                }
            }
            if (unpacked == null) throw (lastErr ?: IllegalStateException("signature verify failed"))

            val (decryptedBytes, fileName, verifiedSignerPubKeyHex) = unpacked

            // 🔎 sender パラメータが誤っていた場合にここで判定できる（iOS側の senderKey が未保存で別鍵になるケース）
            if (!senderParam.isBlank() && !senderParam.equals(verifiedSignerPubKeyHex, ignoreCase = true)) {
                Log.w(
                    "DriveDownloader",
                    "⚠ signerPublicKey mismatch: senderParam=${senderParam.take(16)}... verified=${verifiedSignerPubKeyHex.take(16)}..."
                )
            }

            // sender パラメータ or DB の鍵がズレていた場合はログに残す（原因切り分け用）
            if (senderParam.isNotBlank() && !senderParam.equals(verifiedSignerPubKeyHex, ignoreCase = true)) {
                Log.w(
                    "DriveDownloader",
                    "⚠ signer mismatch: senderParam=$senderParam verified=$verifiedSignerPubKeyHex"
                )
            }

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
                val msg = when (e) {
                    is AEADBadTagException ->
                        "復号に失敗しました（GCMタグ不一致）\n" +
                                "送信側が別の受信者鍵で暗号化している可能性があります。\n" +
                                "共有相手登録（鍵交換）をやり直してください。"
                    else ->
                        "エラーが発生しました: ${e.message}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            downloadedFile
        }
    }

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

                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sourceFile.name)
                } else {
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
                sourceFile
            }
        }
    }

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
