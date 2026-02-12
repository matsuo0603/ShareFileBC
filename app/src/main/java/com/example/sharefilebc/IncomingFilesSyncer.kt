package com.example.sharefilebc

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.ReceivedFileEntity
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 受信フォルダ同期（WorkManager）:
 * - Drive上の sharedWithMe フォルダを拾って Room に反映（受信一覧用）
 * - ✅ 今回の修正：各ファイルの description から shareメタを復元して
 *   ShareProcessor.processReceivedShare を自動実行し、トークン反映まで進める。
 *
 * これにより「メールリンクを開いたときだけ増える」問題を解消する。
 */
object IncomingFilesSyncer {

    private const val TAG = "IncomingFilesSyncer"
    private const val NAME_TAG = "NAME_META"

    private val dateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")

    private data class ShareMeta(
        val uuid: String,
        val txids: List<String>,
        val senderPublicKey: String,
        val refundAddress: String?,
        val threshold: ULong,
        val nameMeta: String? = null
    )

    suspend fun syncOnce(context: Context): Int = withContext(Dispatchers.IO) {
        Log.d(LogTags.TAG_SYNC_INCOMING, "start")

        // ✅ Drive 同期の有無に関わらず、ウォレットは定期的に同期しておく。
        // 返金は「相手から自分のアドレスへの通常送金」なので、
        // 受信側（送信者側）が何も操作しなくても WorkManager 経由で反映できる。
        runCatching {
            val wm = WalletManager.getInstance(context)
            wm.initializeIfNeeded()
            wm.sync()
        }.onFailure {
            Log.w(LogTags.TAG_SYNC_INCOMING, "wallet sync skipped: ${it.message}")
        }

        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e(LogTags.TAG_SYNC_INCOMING, "Drive 取得失敗", it) }
            .getOrNull() ?: return@withContext 0

        val db = AppDatabase.getDatabase(context)
        val receivedDao = db.receivedFolderDao()
        val receivedFileDao = db.receivedFileDao()

        val sharedFolders = drive.files().list()
            .setQ("sharedWithMe and trashed=false and mimeType='application/vnd.google-apps.folder'")
            .setFields("files(id, name, createdTime, owners(displayName, emailAddress))")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .execute()
            .files ?: emptyList()

        // ✅ Swift版は「ファイル単体共有（folder権限なし）」が前提。
        // Android側も sharedWithMe の *ファイル* を拾わないと、
        // 受信一覧に何も出ず、トークンだけ増える状態になる。
        val sharedFiles = drive.files().list()
            .setQ("sharedWithMe and trashed=false and mimeType!='application/vnd.google-apps.folder'")
            // parents を取っておく（フォルダ共有の中のファイルと単体共有を区別するため）
            .setFields("files(id, name, createdTime, owners(displayName, emailAddress), description, parents)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .execute()
            .files ?: emptyList()

        val jst = TimeZone.getTimeZone("Asia/Tokyo")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { timeZone = jst }
        val expirationMillis = 7L * 24 * 60 * 60 * 1000

        var upsertCount = 0

        for (folder in sharedFolders) {
            val dateOnly = dateRegex.find(folder.name ?: "")?.value ?: continue

            // ✅ description を読むため fields に description を追加
            val childFiles = drive.files().list()
                .setQ("'${folder.id}' in parents and trashed=false and mimeType!='application/vnd.google-apps.folder'")
                .setFields("files(id, name, createdTime, owners(displayName, emailAddress), description, parents)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute()
                .files ?: emptyList()

            val firstFile = childFiles.firstOrNull()
            val uploadMillis = firstFile?.createdTime?.value
                ?: folder.createdTime?.value
                ?: System.currentTimeMillis()

            val uploadDate = formatter.format(Date(uploadMillis))
            val deleteDate = formatter.format(Date(uploadMillis + expirationMillis))

            val owner = firstFile?.owners?.firstOrNull() ?: folder.owners?.firstOrNull()
            val senderName = owner?.displayName?.takeIf(String::isNotBlank)
                ?: owner?.emailAddress
                ?: "Unknown Sender"

            val entity = ReceivedFolderEntity(
                folderId = folder.id,
                folderName = dateOnly,
                senderName = senderName,
                uploadDateTime = uploadDate,
                deleteDateTime = deleteDate
            )

            val existing = receivedDao.findByFolderId(folder.id)
            if (existing == null) {
                receivedDao.insert(entity)
                upsertCount++
            } else if (
                existing.senderName != entity.senderName ||
                existing.folderName != entity.folderName ||
                existing.uploadDateTime != entity.uploadDateTime ||
                existing.deleteDateTime != entity.deleteDateTime
            ) {
                receivedDao.insert(entity.copy(id = existing.id))
                upsertCount++
            }

            // ✅ ★ここが本題：自動受信でも processReceivedShare を実行
            autoProcessSharesFromDescriptions(context, childFiles, parentFolderId = folder.id)
        }

        // ✅ 追加：sharedWithMe ファイル（フォルダ外）も処理する
        // - description から shareメタを復元
        // - received_files に fileId 等を保存
        // - received_folders に「擬似フォルダ（file:<fileId>）」を作成（DownloadScreen を変更せず一覧に出す）
        // - processReceivedShare を実行（トークン反映 / 返金タスクなど）
        upsertReceivedFoldersForSharedFiles(
            context = context,
            receivedDao = receivedDao,
            files = sharedFiles,
            formatter = formatter,
            expirationMillis = expirationMillis
        )

        autoProcessSharesFromDescriptions(context, sharedFiles, parentFolderId = null)

        Log.d(LogTags.TAG_SYNC_INCOMING, "end")
        upsertCount
    }

    private suspend fun autoProcessSharesFromDescriptions(
        context: Context,
        files: List<File>,
        parentFolderId: String?
    ) {
        if (files.isEmpty()) return

        for (f in files) {
            val desc = f.description?.trim().orEmpty()
            if (desc.isBlank()) continue

            // ✅ threshold が description に入っていない/壊れているケースがあり、0 だと
            // UIで「閾値: 0」と誤表示されやすい。
            // Swift版は paymentThreshold のデフォルトが 1 なので、Androidも同じく
            // WalletSettings の値をデフォルトとして補完する。
            val defaultThreshold = WalletSettingsManager
                .getInstance(context)
                .getPaymentThreshold()
                .toLong()

            val meta = parseShareMeta(desc, defaultThreshold) ?: continue

            // ✅ received_files に fileId / fileName / nameMeta を保存して
            // 「フォルダ共有なし」でも受信一覧に表示できるようにする。
            upsertReceivedFileRow(
                context = context,
                file = f,
                meta = meta,
                parentFolderId = parentFolderId
            )

            Log.d(
                LogTags.TAG_SYNC_INCOMING,
                "🔁 auto processReceivedShare: fileId=${f.id} uuid=${meta.uuid} txids=${meta.txids.size} th=${meta.threshold}"
            )

            val result = ShareProcessor.processReceivedShare(
                context = context,
                uuid = meta.uuid,
                txids = meta.txids,
                senderPublicKey = meta.senderPublicKey,
                refundAddress = meta.refundAddress,
                threshold = meta.threshold
            )

            Log.d(LogTags.TAG_SYNC_INCOMING, "🔁 auto processReceivedShare result=$result")

            // ✅ 反映が遅い場合があるので、受信処理の直後にもう一度 sync→balance を取ってログに残す
            runCatching {
                val wm = WalletManager.getInstance(context)
                val bal = wm.getBalanceAfterSync(colorId = Constants.Strings.tokenColorId)
                Log.d(LogTags.TAG_SYNC_INCOMING, "[BALANCE] after auto processReceivedShare = $bal")
            }
        }
    }

    private suspend fun upsertReceivedFileRow(
        context: Context,
        file: File,
        meta: ShareMeta,
        parentFolderId: String?
    ) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.receivedFileDao()

        // ✅ DownloadScreen のグルーピングは received_folders.folderId と received_files.folderID を揃える前提。
        // フォルダ共有の場合：parentFolderId を採用
        // 単体ファイル共有の場合：擬似フォルダID（file:<fileId>）を採用
        val normalizedFolderId = parentFolderId?.takeIf { it.isNotBlank() }
            ?: "file:${file.id}" // folder権限が無い共有でも一覧に出すため

        // ✅ nameMeta があれば「ファイル名だけ」先に復号して一覧表示で使う。
        // 失敗した場合は file.name(securePackage.vpfs) のままにし、原因は nameMetadataError に残す。
        val decodedNameResult = runCatching {
            Log.d(TAG, "🔍 Attempting to decrypt filename for shareId=${meta.uuid}, fileId=${file.id}")
            val nm = meta.nameMeta?.trim().orEmpty()
            if (nm.isBlank()) {
                Log.d(TAG, "   nameMeta is blank, skipping decrypt")
                return@runCatching null
            }
            Log.d(TAG, "   nameMeta length: ${nm.length}")

            val kd = KeyDerivation.getInstance(context)
            // ✅ 仕様: nameMeta の AESKey は「受信者の derivedKey(/1)」で復号する
            val recipientPriv = kd.getCurrentPrivateKeyHex(KeyDerivation.DERIVED_KEY_PATH)
            val recipientPub = kd.getCurrentPublicKeyHex(KeyDerivation.DERIVED_KEY_PATH)
            CryptoTrace.logReceiveKeyRoles(
                event = "IncomingFilesSyncer.decryptNameMeta",
                senderParam = meta.senderPublicKey,
                signerPubKeyUsedForVerify = meta.senderPublicKey,
                recipientPathUsedForDecrypt = KeyDerivation.DERIVED_KEY_PATH,
                recipientPrivKeyHexUsed = recipientPriv,
                recipientPubDerivedFromPriv = recipientPub
            )

            Log.d(TAG, "   recipientPrivKey: ${recipientPriv.take(16)}...")

            // ✅ 署名検証鍵は sender パラメータが誤っている可能性があるため、DB にある /0 も候補に含める
            // NOTE: 共有リンクには senderEmail が入っていないため、IncomingFilesSyncer の meta から email を参照できない。
            //       代わりに「senderPublicKey(/0) → email_keys を逆引き」して email を特定できる場合のみ候補を追加する。
            val db = AppDatabase.getDatabase(context.applicationContext)
            val signerCandidates = mutableListOf<String>()
            val direct = meta.senderPublicKey.trim().takeIf { it.isNotBlank() }
            if (direct != null) signerCandidates.add(direct)

            val senderEmailFromDb: String? = if (direct != null) {
                runCatching { db.emailKeyDao().findByTrustLayerPublicKey(direct)?.email }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } else null

            if (senderEmailFromDb != null) {
                // email_keys に保存されている /0（TrustLayer）公開鍵
                val ek = runCatching { db.emailKeyDao().findByEmail(senderEmailFromDb) }.getOrNull()
                val pk0 = ek?.trustLayerPublicKey?.trim()?.takeIf { it.isNotBlank() }
                if (pk0 != null && !signerCandidates.contains(pk0)) signerCandidates.add(pk0)

                // user テーブルに保存されている公開鍵（存在する場合）も候補に入れる
                val user = runCatching { db.userDao().findByEmail(senderEmailFromDb) }.getOrNull()
                val pkUser = user?.publicKeyHex?.trim()?.takeIf { it.isNotBlank() }
                if (pkUser != null && !signerCandidates.contains(pkUser)) signerCandidates.add(pkUser)
            }

            val signerCandidatesJoined = signerCandidates.joinToString(",")

            Log.d(TAG, "   senderPublicKey(direct): ${meta.senderPublicKey.take(16)}...")
            Log.d(TAG, "   signerCandidates(count)=${signerCandidates.size}")

            com.example.sharefilebc.crypto.SecurePackage.decryptFileNameFromNameMeta(
                nameMetaBase64Url = nm,
                recipientPrivateKeyHex = recipientPriv,
                signerPublicKeyHex = signerCandidatesJoined
            )
        }

        val decodedName: String? = decodedNameResult.getOrNull()
        val decodedNameError: String? = decodedNameResult.exceptionOrNull()?.let { e ->
            Log.e(TAG, "❌ Failed to decrypt filename: ${e.javaClass.simpleName}: ${e.message}", e)
            // 次回の切り分け用に要点だけ残す
            "nameMeta decrypt failed: ${e.message}"
        }

        val displayFileName = decodedName ?: file.name

        if (decodedName != null) {
            Log.d(TAG, "✅ Filename decrypted successfully: $decodedName")
        } else {
            Log.w(TAG, "⚠️ Using original filename: ${file.name}, error: $decodedNameError")
        }
        // 既に ShareProcessor だけで作られた行がある可能性があるので上書きする
        val existing = dao.findByShareId(meta.uuid)
        val updated = if (existing != null) {
            existing.copy(
                folderID = normalizedFolderId,
                fileID = file.id,
                fileName = displayFileName,
                nameMetadata = meta.nameMeta,
                senderPublicKey = meta.senderPublicKey,
                nameMetadataError = existing.nameMetadataError ?: decodedNameError
            )
        } else {
            ReceivedFileEntity(
                shareID = meta.uuid,
                folderID = normalizedFolderId,
                fileID = file.id,
                fileName = displayFileName,
                nameMetadata = meta.nameMeta,
                senderPublicKey = meta.senderPublicKey,
                nameMetadataError = decodedNameError
            )
        }

        dao.insert(updated)
    }

    /**
     * ✅ 単体ファイル共有（sharedWithMe のフォルダ外ファイル）を DownloadScreen に出すために、
     * received_folders に擬似フォルダ行（folderId = file:<fileId>）を作成する。
     */
    private suspend fun upsertReceivedFoldersForSharedFiles(
        context: Context,
        receivedDao: com.example.sharefilebc.data.ReceivedFolderDao,
        files: List<File>,
        formatter: SimpleDateFormat,
        expirationMillis: Long
    ) {
        if (files.isEmpty()) return

        val jst = TimeZone.getTimeZone("Asia/Tokyo")
        val dateOnlyFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = jst }

        for (f in files) {
            // description が無いファイルは ShareFileBC の共有物である保証がないため、ここでは一覧に出さない
            val desc = f.description?.trim().orEmpty()
            if (desc.isBlank()) continue

            val uploadMillis = f.createdTime?.value ?: System.currentTimeMillis()
            val uploadDateTime = formatter.format(Date(uploadMillis))
            val deleteDateTime = formatter.format(Date(uploadMillis + expirationMillis))
            val dateOnly = dateOnlyFormatter.format(Date(uploadMillis))

            val owner = f.owners?.firstOrNull()
            val senderName = owner?.displayName?.takeIf(String::isNotBlank)
                ?: owner?.emailAddress
                ?: "Unknown Sender"

            val pseudoFolderId = "file:${f.id}"
            val entity = ReceivedFolderEntity(
                folderId = pseudoFolderId,
                folderName = dateOnly,
                senderName = senderName,
                uploadDateTime = uploadDateTime,
                deleteDateTime = deleteDateTime
            )

            val existing = receivedDao.findByFolderId(pseudoFolderId)
            if (existing == null) {
                receivedDao.insert(entity)
            } else if (
                existing.senderName != entity.senderName ||
                existing.folderName != entity.folderName ||
                existing.uploadDateTime != entity.uploadDateTime ||
                existing.deleteDateTime != entity.deleteDateTime
            ) {
                receivedDao.insert(entity.copy(id = existing.id))
            }
        }
    }

    /**
     * description 形式：
     *  uuid=...&txid=...&sender=...&threshold=...&refund=...
     */
    private fun parseShareMeta(description: String, defaultThreshold: Long): ShareMeta? {
        return try {
            val q = description.removePrefix("?")
            val uri = Uri.parse("https://dummy.local/?$q")

            val uuid = uri.getQueryParameter("uuid")?.trim().orEmpty()

            val sender = (uri.getQueryParameter("sender") ?: uri.getQueryParameter("senderPublicKey"))
                ?.trim()
                .orEmpty()

            val thresholdStr = uri.getQueryParameter("threshold")?.trim().orEmpty()
            val thresholdParsed = thresholdStr.toLongOrNull()
            val threshold = when {
                thresholdParsed == null -> defaultThreshold
                thresholdParsed <= 0L -> defaultThreshold
                else -> thresholdParsed
            }.toULong()

            val refund = (uri.getQueryParameter("refund") ?: uri.getQueryParameter("refundAddress"))
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            // Swift版/Android版とも nameMeta(Base64) はURL上で % エンコードされる想定。
            // ただし過去ビルドや一部クライアントで '+' が空白に化けるケースがあるため補正する。
            val nameMeta = uri.getQueryParameter("nameMeta")
                ?.trim()
                ?.replace(" ", "+")
                ?.takeIf { it.isNotBlank() }

            val txids = buildList {
                uri.getQueryParameter("txid")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.let { addAll(it) }

                uri.getQueryParameter("txids")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.let { addAll(it) }

                addAll(uri.getQueryParameters("txid").map { it.trim() }.filter { it.isNotBlank() })
            }.distinct()

            if (uuid.isBlank() || sender.isBlank() || threshold == 0uL || txids.isEmpty()) {
                Log.w(
                    NAME_TAG,
                    "share meta incomplete: uuid='${uuid.take(16)}' senderLen=${sender.length} " +
                            "txids=${txids.size} nameMetaLen=${nameMeta?.length ?: 0} q='${q.take(200)}'"
                )
                return null
            }

            ShareMeta(
                uuid = uuid,
                txids = txids,
                senderPublicKey = sender,
                refundAddress = refund,
                threshold = threshold,
                nameMeta = nameMeta
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseShareMeta failed: ${e.message}")
            null
        }
    }
}