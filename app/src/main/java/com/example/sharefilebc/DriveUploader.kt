package com.example.sharefilebc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.sharefilebc.crypto.SecurePackage
import com.example.sharefilebc.crypto.PublicKeyUtils
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.EmailKeyEntity
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
    data class Success(
        val fileName: String,
        val fileId: String,
        val folderId: String,
        /** Swift版互換: URLクエリに載せる nameMeta (Base64(JSON)) */
        val nameMetaBase64: String
    ) : UploadResult()
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

                // ✅ P2C の paymentBase は Tapyrus HdWallet が発行した公開鍵である必要がある
                // （KeyDerivation の鍵を混ぜると受信側で storeContract が invalid payment base になり得る）
                WalletManager.getInstance(context).initializeIfNeeded()

                val wallet = KeyDerivation.getInstance(context)

                // --- KEY SNAPSHOT (切り分け用) ---
                // 仕様:
                // - derivedKey(/1): ECIES(暗号化) / 署名(ECDSA) 用
                // - trustLayerKey(/0): sender識別 / P2C(送金・返金) 用
                val PATH_DERIVED = KeyDerivation.DERIVED_KEY_PATH
                val PATH_TRUST = KeyDerivation.TRUST_LAYER_PATH

                val myPubDerived = wallet.getCurrentPublicKeyHex(PATH_DERIVED)
                val myPrivDerived = wallet.getCurrentPrivateKeyHex(PATH_DERIVED)
                val myPubTrust = wallet.getCurrentPublicKeyHex(PATH_TRUST)
                val myPrivTrust = wallet.getCurrentPrivateKeyHex(PATH_TRUST)
                CryptoTrace.logMyKeySnapshot(
                    event = "DriveUploader.uploadFileAndRecordWithSharing:beforeEncrypt",
                    path0 = PATH_TRUST,
                    pub0 = myPubTrust,
                    priv0Hex = myPrivTrust,
                    path1 = PATH_DERIVED,
                    pub1 = myPubDerived,
                    priv1Hex = myPrivDerived
                )

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

                // ✅ 役割整合性ログ（推測ではなく、実際に使った値を確定させる）
                // - ECIES(暗号化) に使った相手鍵: recipientPublicKeyHex（相手の derived）
                // - 署名に使う自分の鍵: derivedKey(/1)
                // - メールURLの sender= に入れる鍵: TrustLayer 公開鍵(/0)（sender 識別子）
                //   ※ 受信側は sender(TrustLayer) をキーに EmailKey を検索し、derivedPublicKey を署名検証鍵として使う。
                val signingPrivateKeyHex = myPrivDerived
                // ✅ 署名用公開鍵は「署名に使う秘密鍵」から導出して確定
                val signerPublicKeyHex = PublicKeyUtils.compressedPublicKeyHexFromPrivateKeyHex(signingPrivateKeyHex)

                if (!signerPublicKeyHex.equals(myPubDerived, ignoreCase = true)) {
                    Log.e(
                        "DriveUploader",
                        "🚨 SIGNER_KEY_MISMATCH: pubDerivedFromPriv != wallet.getCurrentPublicKeyHex(DERIVED)\n" +
                                "  pubFromPriv=$signerPublicKeyHex\n" +
                                "  pubFromWallet=$myPubDerived\n" +
                                "  (Android側の鍵導出/保存が壊れている可能性あり。署名鍵は pubFromPriv を採用)"
                    )
                }

                CryptoTrace.logSendKeyRoles(
                    event = "DriveUploader.encrypt",
                    recipientEmail = recipient.email,
                    recipientDerivedKey = effectiveKey.derivedPublicKey,
                    recipientTrustLayerKey = effectiveKey.trustLayerPublicKey,
                    recipientPubKeyUsedForECIES = recipientPublicKeyHex,
                    signerPath = PATH_DERIVED,
                    signerPubKeyUsed = signerPublicKeyHex,
                    senderParamInserted = myPubTrust
                )

                // Swift版互換: nameMeta(Base64(JSON)) も同時に生成してメールURLに付与する
                val secure = SecurePackage.createWithNameMeta(
                    data = fileBytes,
                    fileName = fileName,
                    recipientPublicKeyHex = recipientPublicKeyHex,
                    signingPrivateKeyHex = signingPrivateKeyHex,
                    signerPublicKeyHex = signerPublicKeyHex
                )

                // ✅ デバッグ: iOS との互換確認用（nameMeta は URL-safe Base64 のはず）
                Log.d("DriveUploader", "🔐 nameMeta(base64url) len=${secure.nameMetaBase64.length} head=${secure.nameMetaBase64.take(24)}")

                val uploadName = "securePackage.vpfs"

                val fileMetadata = File().apply {
                    name = uploadName
                    parents = listOf(dateFolderId)
                }

                val fileContent = com.google.api.client.http.ByteArrayContent("application/octet-stream", secure.packageBytes)
                val uploadedFile = driveService.files().create(fileMetadata, fileContent)
                    .setFields("id, name, webViewLink")
                    .execute()

                // ✅ Swift版仕様: ShareFileBCApp 以外は「リンクを知っている人が閲覧可」にする
                // iOS側は sharedWithMe ではなく URL(fileId) から辿る設計なので、
                // Android 送信でも anyone 権限を付与して iOS/Android 双方で取得できるようにする。
                setPublicAccess(driveService, recipientFolderId)
                setPublicAccess(driveService, dateFolderId)
                setPublicAccess(driveService, uploadedFile.id)

                db.sharedFolderDao().insert(
                    SharedFolderEntity(
                        date = currentDateTime,
                        recipientName = recipient.name,
                        folderId = dateFolderId,
                        fileName = fileName,
                        fileGoogleDriveId = uploadedFile.id
                    )
                )

                UploadResult.Success(fileName, uploadedFile.id, dateFolderId, secure.nameMetaBase64)
            } catch (e: Exception) {
                Log.e("DriveUploader", "Upload error", e)
                UploadResult.Failure(e)
            }
        }
    }

    private fun setPublicAccess(driveService: Drive, targetId: String) {
        try {
            val permission = Permission().apply {
                type = "anyone"
                role = "reader"
            }
            driveService.permissions().create(targetId, permission)
                .setSendNotificationEmail(false)
                .execute()
            Log.d("DriveUploader", "✅ granted public access(anyone:reader): target=$targetId")
        } catch (e: Exception) {
            Log.e("DriveUploader", "❌ failed to grant public access: target=$targetId", e)
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
