// HomeActivity.kt
package com.example.sharefilebc

import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.EmailKeyEntity
import com.example.sharefilebc.network.PublicKeyApiClient
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HomeActivity"
        private const val DL_TAG = "DL_DEBUG"
    }

    private data class DeepLinkParams(
        val folderId: String? = null,
        val fileId: String? = null,
        /** iOS/Android共通: 一覧表示用の暗号化ファイル名メタ（base64url(JSON)） */
        val nameMeta: String? = null,
        val senderPublicKey: String? = null,
        val recipientEmail: String? = null,
        val senderAddress: String? = null,
        val threshold: ULong? = null,
        val uuid: String? = null,
        val txid: String? = null, // カンマ区切りのtxidリストを入れる
        val refundAddress: String? = null,
    )

    private val selectedTabState = mutableStateOf(BottomTab.Home)
    private val deepLinkParamsState = mutableStateOf(DeepLinkParams())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wallet init（UIはブロックしない）
        lifecycleScope.launch {
            runCatching {
                val walletManager = WalletManager.getInstance(this@HomeActivity)
                walletManager.initializeIfNeeded()
                Log.d(TAG, "✅ WalletManager initialized successfully")
            }.onFailure { e ->
                Log.e(TAG, "❌ WalletManager initialization failed", e)
                Toast.makeText(this@HomeActivity, "ウォレットの初期化に失敗しました", Toast.LENGTH_LONG).show()
            }
        }

        // DeepLink解析（入口統一）
        handleDeepLink(intent, "onCreate")

        val deepLinkUri: Uri? = intent?.data

        // Googleアカウント + Driveスコープ
        val account: GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(this)
        val hasDriveScope = account?.let { GoogleSignIn.hasPermissions(it, Scope(DriveScopes.DRIVE)) } ?: false

        Log.d(TAG, "🟩 SignedIn=${account != null}, DriveScope=$hasDriveScope, displayName=${account?.displayName}")

        if (account == null || !hasDriveScope) {
            Log.d(TAG, "🟧 Need sign-in or Drive scope. Redirecting to LoginActivity...")

            val params = deepLinkParamsState.value
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    deepLinkUri?.let { data = it }
                    params.folderId?.let { putExtra("folderId", it) }
                    params.fileId?.let { putExtra("fileId", it) }
                    params.senderPublicKey?.let { putExtra("senderPublicKey", it) }
                    params.recipientEmail?.let { putExtra("recipientEmail", it) }
                    params.senderAddress?.let { putExtra("senderAddress", it) }
                    params.threshold?.let { putExtra("threshold", it.toString()) }
                    params.uuid?.let { putExtra("uuid", it) }
                    params.txid?.let { putExtra("txid", it) }
                    params.refundAddress?.let { putExtra("refund", it) }
                }
            )
            finish()
            return
        }

        // 初期トークン配布申請
        account.email?.let { email ->
            lifecycleScope.launch {
                runCatching {
                    val wm = WalletManager.getInstance(applicationContext)
                    wm.initializeIfNeeded()
                    val address = wm.getNewAddress()

                    val ok = TokenRequestManager.requestInitialTokensIfNeeded(
                        context = applicationContext,
                        email = email,
                        p2pkhAddress = address,
                        tokenType = 3,
                        lang = "ja"
                    )

                    if (ok) Log.d(TAG, "🟢 initial token request OK. email=$email addr=$address")
                    else Log.e(TAG, "🟠 initial token request FAILED. email=$email addr=$address")
                }.onFailure { e ->
                    Log.e(TAG, "🟠 initial token request ERROR", e)
                }
            }
        }

        // 公開鍵レジストリ登録（自分）
        account.email?.let { email ->
            lifecycleScope.launch {
                runCatching {
                    val wm = WalletManager.getInstance(applicationContext)
                    wm.initializeIfNeeded()
                    val (_, publicKeyHex) = wm.getNewAddressWithPublicKey()
                    val api = PublicKeyApiClient()
                    val result = withContext(Dispatchers.IO) { api.registerMyPublicKey(email, publicKeyHex) }
                    result.onSuccess {
                        Log.d(TAG, "🟢 公開鍵を公開鍵レジストリに登録しました (${email})")
                    }.onFailure { e ->
                        Log.e(TAG, "🟠 公開鍵の自動登録に失敗しました", e)
                    }
                }.onFailure { e ->
                    Log.e(TAG, "🟠 公開鍵登録処理でエラー", e)
                }
            }
        }

        setContent {
            ShareFileBCTheme {
                var selectedTab by selectedTabState
                val deepLinkParams by deepLinkParamsState

                val composeScope = rememberCoroutineScope()

                // ✅ 返金管理「閾値: 0」対策（既存DB互換）
                // 過去に paymentThreshold を保存していない RefundTask が残っている場合、
                // UIが contextJSON の optLong("threshold") に落ちて 0 と表示されることがある。
                // DownloadScreen は task.paymentThreshold を優先表示するため、ここで埋めれば
                // 画面側を大改修しなくても 0 表示は消える。
                LaunchedEffect(Unit) {
                    composeScope.launch(Dispatchers.IO) {
                        runCatching {
                            val db = AppDatabase.getDatabase(applicationContext)
                            val defaultTh = WalletSettingsManager
                                .getInstance(applicationContext)
                                .getPaymentThreshold()
                                .toLong()
                            db.refundTaskDao().fillMissingThreshold(defaultTh)
                        }.onFailure { e ->
                            Log.w(TAG, "🟡 fillMissingThreshold failed (continue)", e)
                        }
                    }
                }

                // ✅ Shareタブを開くたびに「受信メタ同期 → wallet fullSync → balance取得」を走らせる
                LaunchedEffect(selectedTab) {
                    if (selectedTab != BottomTab.Shared) return@LaunchedEffect

                    composeScope.launch {
                        runCatching {
                            IncomingFilesSyncer.syncOnce(applicationContext)
                        }.onFailure { e ->
                            Log.w(TAG, "🟡 IncomingFilesSyncer.syncOnce failed (continue)", e)
                        }

                        runCatching {
                            val wm = WalletManager.getInstance(applicationContext)
                            wm.initializeIfNeeded()
                            val bal = wm.getBalanceAfterSync(colorId = Constants.Strings.tokenColorId)
                            WalletSettingsManager.getInstance(applicationContext).setLastKnownBalance(bal)
                            Log.d(TAG, "🟢 Shared tab refresh done. tokenBalance=$bal")
                        }.onFailure { e ->
                            Log.w(TAG, "🟡 Shared tab wallet refresh failed (continue)", e)
                        }
                    }
                }

                Scaffold(
                    bottomBar = {
                        BottomNavigationBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        BottomTab.Home -> HomeScreen(modifier = Modifier.padding(innerPadding))
                        BottomTab.Shared -> {
                            SharedScreen(
                                modifier = Modifier.padding(innerPadding),
                                initialFolderId = deepLinkParams.folderId,
                                initialFileId = deepLinkParams.fileId,
                                deepLinkSenderPublicKey = deepLinkParams.senderPublicKey,
                                deepLinkRecipientEmail = deepLinkParams.recipientEmail,
                                deepLinkSenderAddress = deepLinkParams.senderAddress,
                                deepLinkThreshold = deepLinkParams.threshold,
                                deepLinkUuid = deepLinkParams.uuid,
                                deepLinkTxid = deepLinkParams.txid,
                                deepLinkRefundAddress = deepLinkParams.refundAddress
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent, "onNewIntent")
    }

    @Deprecated("Activity Result API への移行推奨だが互換のため残置")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        EmailSender.onActivityResultBridge(this, requestCode, resultCode)
    }

    private fun handleDeepLink(intent: Intent?, where: String) {
        logDeepLinkIntent(where, intent)

        val uri = intent?.data

        // ✅ 公開鍵リンク（登録）
        // onCreate だけで処理すると、アプリが既に起動中（singleTask）で
        // Gmail からリンクを開いた場合は onNewIntent しか呼ばれず登録されない。
        // そのため DeepLink 入口(handleDeepLink)に統合して必ず処理する。
        handlePublicKeyLink(where, uri)

        val paramsFromUri = parseDeepLink(uri)

        // intent.data 欠落対策：extrasもマージ
        val paramsFromExtra = DeepLinkParams(
            folderId = intent?.getStringExtra("folderId"),
            fileId = intent?.getStringExtra("fileId"),
            nameMeta = intent?.getStringExtra("nameMeta"),
            senderPublicKey = intent?.getStringExtra("senderPublicKey"),
            recipientEmail = intent?.getStringExtra("recipientEmail"),
            senderAddress = intent?.getStringExtra("senderAddress"),
            threshold = intent?.getStringExtra("threshold")?.toULongOrNull(),
            uuid = intent?.getStringExtra("uuid"),
            txid = intent?.getStringExtra("txid"),
            refundAddress = intent?.getStringExtra("refund"),
        )

        val merged = DeepLinkParams(
            folderId = paramsFromExtra.folderId ?: paramsFromUri.folderId,
            fileId = paramsFromExtra.fileId ?: paramsFromUri.fileId,
            nameMeta = paramsFromExtra.nameMeta ?: paramsFromUri.nameMeta,
            senderPublicKey = paramsFromExtra.senderPublicKey ?: paramsFromUri.senderPublicKey,
            recipientEmail = paramsFromExtra.recipientEmail ?: paramsFromUri.recipientEmail,
            senderAddress = paramsFromExtra.senderAddress ?: paramsFromUri.senderAddress,
            threshold = paramsFromExtra.threshold ?: paramsFromUri.threshold,
            uuid = paramsFromExtra.uuid ?: paramsFromUri.uuid,
            txid = paramsFromExtra.txid ?: paramsFromUri.txid,
            refundAddress = paramsFromExtra.refundAddress ?: paramsFromUri.refundAddress,
        )

        val switchToShared =
            (merged.folderId != null || merged.fileId != null) ||
                    (!merged.uuid.isNullOrBlank() && !merged.txid.isNullOrBlank())

        deepLinkParamsState.value = merged
        if (switchToShared) selectedTabState.value = BottomTab.Shared

        // ✅ 重要：iOS→Android は「sharedWithMe」ではなく「anyoneリンク(公開)」で渡されるため、
        // Drive同期だけでは受信一覧に絶対に出ない。
        // そのため deep link を受け取った時点で received_files に fileId/nameMeta を保存し、
        // さらに ShareProcessor（txid検証・返金等）を Activity の lifecycleScope で実行する。
        // Compose の LaunchedEffect は「画面遷移/再compose」でキャンセルされ得るため、ここが本命。
        if (switchToShared) {
            lifecycleScope.launch {
                runCatching {
                    bootstrapReceivedFileFromDeepLink(applicationContext, merged)
                }.onFailure { e ->
                    Log.w(DL_TAG, "[$where] bootstrapReceivedFileFromDeepLink failed (continue)", e)
                }

                runCatching {
                    processReceivedShareFromDeepLink(applicationContext, merged)
                }.onFailure { e ->
                    Log.e(DL_TAG, "[$where] processReceivedShareFromDeepLink failed", e)
                }
            }
        }

        Log.d(
            DL_TAG,
            "[$where] parsed folderId=${merged.folderId} fileId=${merged.fileId} nameMeta=${merged.nameMeta?.let { "(len=${it.length})" } ?: "null"} uuid=${merged.uuid} txid=${merged.txid} refund=${merged.refundAddress}"
        )
        Log.d(DL_TAG, "[$where] switchedToShared=$switchToShared")
    }

    private fun logDeepLinkIntent(where: String, intent: Intent?) {
        val uri = intent?.data
        Log.d(DL_TAG, "---- $where ----")
        Log.d(DL_TAG, "action=${intent?.action}")
        Log.d(DL_TAG, "categories=${intent?.categories}")
        Log.d(DL_TAG, "data=$uri")

        if (uri == null) {
            Log.d(DL_TAG, "data=null (intent.data is null), extras=${intent?.extras?.keySet()}")
            return
        }

        Log.d(DL_TAG, "scheme=${uri.scheme}")
        Log.d(DL_TAG, "host=${uri.host}")
        Log.d(DL_TAG, "pathSegments=${uri.pathSegments}")
        runCatching {
            Log.d(DL_TAG, "queryNames=${uri.queryParameterNames}")
        }.onFailure { e ->
            Log.e(DL_TAG, "queryParameterNames dump failed", e)
        }
    }

    private fun handlePublicKeyLink(where: String, uri: Uri?) {
        val pubKeyLink = PublicKeyLinkBuilder.parse(uri) ?: return

        lifecycleScope.launch {
            runCatching {
                val db = AppDatabase.getDatabase(applicationContext)
                withContext(Dispatchers.IO) {
                    db.emailKeyDao().upsert(
                        EmailKeyEntity(
                            email = pubKeyLink.email,
                            derivedPublicKey = pubKeyLink.derivedPublicKey,
                            trustLayerPublicKey = pubKeyLink.trustLayerPublicKey,
                            folderIDFromPartner = pubKeyLink.folderId,
                            isRefundRejected = false
                        )
                    )
                }

                Log.d(
                    DL_TAG,
                    "[$where] ✅ pubkey registered email=${pubKeyLink.email} derived=${pubKeyLink.derivedPublicKey.take(10)}... trust=${pubKeyLink.trustLayerPublicKey.take(10)}... folderId=${pubKeyLink.folderId}"
                )
                Toast.makeText(this@HomeActivity, "公開鍵を登録しました", Toast.LENGTH_LONG).show()

                // デバッグ：DBに入ったか確認
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val keys = db.emailKeyDao().getAll().first()
                        keys.forEach { key ->
                            Log.d("DEBUG", "Email: ${key.email}")
                            Log.d("DEBUG", "  derivedPublicKey(/1): ${key.derivedPublicKey.take(16)}...")
                            Log.d("DEBUG", "  trustLayerPublicKey(/0): ${key.trustLayerPublicKey.take(16)}...")
                        }
                    }.onFailure { e ->
                        Log.e("DEBUG", "EmailKeyEntity dump failed", e)
                    }
                }
            }.onFailure { e ->
                Log.e(DL_TAG, "[$where] ❌ pubkey registration failed", e)
                Toast.makeText(this@HomeActivity, "公開鍵の登録に失敗しました", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseDeepLink(uri: Uri?): DeepLinkParams {
        if (uri == null) return DeepLinkParams()

        // NOTE: iOS→Android の nameMeta が URL エンコードされていない場合、
        // Uri.getQueryParameter() が '+' を空白に変換して Base64 が壊れることがある。
        // そのため encodedQuery から生値を取り出す（%xx だけ decode、'+' は維持）。
        fun getRawQueryParam(key: String): String? {
            val encoded = uri.encodedQuery ?: return null
            val prefix = "$key="
            val start = encoded.indexOf(prefix)
            if (start < 0) return null
            val from = start + prefix.length
            val to = encoded.indexOf('&', from).let { if (it < 0) encoded.length else it }
            val raw = encoded.substring(from, to)
            return Uri.decode(raw)
        }

        val pathSegments = uri.pathSegments ?: emptyList()

        // /folder/<ID>
        val folderIdFromPath = if (pathSegments.size >= 2 && pathSegments[0] == "folder") {
            pathSegments[1]
        } else null
        val folderIdFromQuery = uri.getQueryParameter("folderId")

        // /file/<ID> or /share/<ID>
        val fileIdFromPath = if (pathSegments.size >= 2 && (pathSegments[0] == "file" || pathSegments[0] == "share")) {
            pathSegments[1]
        } else null
        val fileIdFromQuery = uri.getQueryParameter("fileId")

        // sender/to の別名も吸収（送信側の揺れ対策）
        val senderKey = uri.getQueryParameter("sender") ?: uri.getQueryParameter("senderPublicKey")
        val recipientEmail = uri.getQueryParameter("to") ?: uri.getQueryParameter("recipientEmail")

        val senderAddress = uri.getQueryParameter("senderAddress")
        val threshold = uri.getQueryParameter("threshold")?.toULongOrNull()

        // iOS/Android: 一覧表示用 nameMeta（base64url JSON）
        // getQueryParameter だと '+' が空白化することがあるので raw 抽出を優先
        val nameMeta = getRawQueryParam("nameMeta") ?: uri.getQueryParameter("nameMeta")

        val uuid = uri.getQueryParameter("uuid")

        // txid/txids/複数/カンマ区切り をまとめて「カンマ区切り1本」に正規化
        val txidValues = buildList {
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

        val txid = txidValues.joinToString(",").ifBlank { null }

        val refund = uri.getQueryParameter("refund") ?: uri.getQueryParameter("refundAddress")

        return DeepLinkParams(
            folderId = folderIdFromPath ?: folderIdFromQuery,
            fileId = fileIdFromPath ?: fileIdFromQuery,
            nameMeta = nameMeta,
            senderPublicKey = senderKey,
            recipientEmail = recipientEmail,
            senderAddress = senderAddress,
            threshold = threshold,
            uuid = uuid,
            txid = txid,
            refundAddress = refund
        )
    }

    private suspend fun bootstrapReceivedFileFromDeepLink(context: Context, params: DeepLinkParams) {
        val uuid = params.uuid?.trim().orEmpty()
        val fileId = params.fileId?.trim().orEmpty()
        val senderKey = params.senderPublicKey?.trim().orEmpty()

        if (uuid.isBlank() || fileId.isBlank() || senderKey.isBlank()) {
            Log.d(DL_TAG, "[bootstrap] skip: uuid/fileId/senderKey missing uuid=$uuid fileId=$fileId senderKeyEmpty=${senderKey.isBlank()}")
            return
        }

        val db = AppDatabase.getDatabase(context)
        withContext(Dispatchers.IO) {
            val before = db.receivedFileDao().findByShareId(uuid)
            val updated = if (before != null) {
                before.copy(
                    fileID = fileId,
                    nameMetadata = params.nameMeta,
                    senderPublicKey = senderKey
                )
            } else {
                com.example.sharefilebc.data.ReceivedFileEntity(
                    shareID = uuid,
                    fileID = fileId,
                    nameMetadata = params.nameMeta,
                    senderPublicKey = senderKey,
                    isDownloadAllowed = false,
                    isDownloadBlocked = false,
                    isDownloadEverAllowed = false
                )
            }
            db.receivedFileDao().insert(updated)
            Log.d(
                DL_TAG,
                "[bootstrap] upsert received_files: shareID=$uuid fileID=$fileId nameMetaLen=${params.nameMeta?.length ?: 0}"
            )
        }

        // ✅ iOS→Android の「単体ファイル共有」では sharedWithMe フォルダが存在せず、
        // received_folders が空のままだと DownloadScreen に何も出ない。
        // そのため、deep link で fileId が来たタイミングで「擬似フォルダ（file:<fileId>）」を
        // 可能なら Drive メタデータから補完して Room に入れる（DownloadScreen は表示専用のままでOK）。
        runCatching {
            upsertPseudoReceivedFolderForSharedFile(context, fileId)
        }.onFailure { e ->
            Log.w(DL_TAG, "[bootstrap] upsertPseudoReceivedFolderForSharedFile skipped: ${e.message}")
        }
    }

    private suspend fun upsertPseudoReceivedFolderForSharedFile(context: Context, fileId: String) {
        if (fileId.isBlank()) return

        // ⚠ Drive API / Room はメインスレッド禁止。必ず IO で実行する。
        withContext(Dispatchers.IO) {
            val drive = com.example.sharefilebc.data.DriveServiceHelper.getDriveService(context)
            if (drive == null) {
                Log.w(DL_TAG, "[bootstrap] pseudoFolder: Drive service unavailable")
                return@withContext
            }
            val db = AppDatabase.getDatabase(context)

            // Drive から createdTime / owner を取得
            val file = drive.files().get(fileId)
                .setFields("id, createdTime, owners(displayName, emailAddress)")
                .setSupportsAllDrives(true)
                .execute()

            val uploadMillis = file.createdTime?.value ?: System.currentTimeMillis()

            val jst = java.util.TimeZone.getTimeZone("Asia/Tokyo")
            val dateOnlyFormatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
                timeZone = jst
            }
            val dateTimeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).apply {
                timeZone = jst
            }

            val folderName = dateOnlyFormatter.format(java.util.Date(uploadMillis))
            val uploadDateTime = dateTimeFormatter.format(java.util.Date(uploadMillis))
            val deleteDateTime = dateTimeFormatter.format(java.util.Date(uploadMillis + 7L * 24 * 60 * 60 * 1000))

            val owner = file.owners?.firstOrNull()
            val senderName = owner?.displayName?.takeIf(String::isNotBlank)
                ?: owner?.emailAddress
                ?: "Unknown Sender"

            val pseudoFolderId = "file:$fileId"

            val receivedDao = db.receivedFolderDao()
            val existing = receivedDao.findByFolderId(pseudoFolderId)
            val entity = com.example.sharefilebc.data.ReceivedFolderEntity(
                folderId = pseudoFolderId,
                folderName = folderName,
                senderName = senderName,
                uploadDateTime = uploadDateTime,
                deleteDateTime = deleteDateTime
            )

            if (existing == null) {
                receivedDao.insert(entity)
                Log.d(DL_TAG, "[bootstrap] inserted received_folders pseudo=$pseudoFolderId sender=$senderName")
            } else {
                receivedDao.insert(entity.copy(id = existing.id))
                Log.d(DL_TAG, "[bootstrap] updated received_folders pseudo=$pseudoFolderId sender=$senderName")
            }

            // received_files 側も folderID を揃える（DownloadScreen のグルーピング用）
            val rf = db.receivedFileDao().findByFileId(fileId)
            if (rf != null && rf.folderID != pseudoFolderId) {
                db.receivedFileDao().insert(rf.copy(folderID = pseudoFolderId))
                Log.d(DL_TAG, "[bootstrap] normalized received_files.folderID to $pseudoFolderId")
            }
        }
    }

    private suspend fun processReceivedShareFromDeepLink(context: Context, params: DeepLinkParams) {
        val uuid = params.uuid?.trim().orEmpty()
        val senderKey = params.senderPublicKey?.trim().orEmpty()
        val txids = params.txid
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()

        if (uuid.isBlank() || senderKey.isBlank() || txids.isEmpty()) {
            Log.d(DL_TAG, "[DL process] skip: missing uuid/sender/txid uuid=$uuid senderKeyEmpty=${senderKey.isBlank()} txids=${txids.size}")
            return
        }

        // ✅ 0/欠落の threshold は Swift のデフォルト(=1)相当の WalletSettings を使う
        val threshold = params.threshold
            ?.takeIf { it > 0uL }
            ?: WalletSettingsManager.getInstance(context).getPaymentThreshold()

        // ✅ 既に processed の場合は skip（重複トークン処理防止）
        val db = AppDatabase.getDatabase(context)
        val alreadyProcessed = withContext(Dispatchers.IO) {
            val hasReceived = db.receivedFileDao().findByShareId(uuid) != null
            val hasRefund = db.refundTaskDao().findByShareId(uuid) != null
            // hasReceived だけだと bootstrap で true になってしまうので、
            // 「downloadフラグが更新済み」か「refundTaskがある」かで判断する
            val received = db.receivedFileDao().findByShareId(uuid)
            val hasFlags = received?.isDownloadEverAllowed == true || received?.isDownloadBlocked == true
            hasFlags || hasRefund
        }
        if (alreadyProcessed) {
            Log.d(DL_TAG, "[DL process] skip by DB processed guard uuid=$uuid")
            return
        }

        Log.d(DL_TAG, "[DL process] processReceivedShare start uuid=$uuid txids=${txids.size} th=$threshold")

        val result = ShareProcessor.processReceivedShare(
            context = context,
            uuid = uuid,
            txids = txids,
            senderPublicKey = senderKey,
            refundAddress = params.refundAddress,
            threshold = threshold,
            colorId = Constants.Strings.tokenColorId
        )

        Log.d(DL_TAG, "[DL process] processReceivedShare end uuid=$uuid result=$result")
    }
}
