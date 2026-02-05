// HomeActivity.kt
package com.example.sharefilebc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
        val senderPublicKey: String? = null,
        val recipientEmail: String? = null,
        val senderAddress: String? = null,
        val threshold: ULong? = null,
        val uuid: String? = null,
        val txid: String? = null,
        val refundAddress: String? = null,
    )

    /**
     * ✅ Swift版に合わせるための設計
     * - DeepLink を受けた瞬間に「Shared」タブへ切り替える
     * - 受信処理（検証/返金/ブロック/sync）は SharedScreen 側で 1回だけ 実行する
     * - DownloadScreen は保存済み結果の表示専用
     *
     * そのため HomeActivity は「DeepLinkを受け取ってUI状態に反映」だけを担当する。
     * ※ DeepLink は onNewIntent でも来るため、Activity側の state を更新する。
     */
    private val selectedTabState = mutableStateOf(BottomTab.Home)
    private val deepLinkParamsState = mutableStateOf(DeepLinkParams())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ WalletManager 初期化（UIはブロックしない）
        lifecycleScope.launch {
            runCatching {
                val walletManager = WalletManager.getInstance(this@HomeActivity)
                walletManager.initializeIfNeeded()
                Log.d(TAG, "✅ WalletManager initialized successfully")
            }.onFailure { e ->
                Log.e(TAG, "❌ WalletManager initialization failed", e)
                Toast.makeText(
                    this@HomeActivity,
                    "ウォレットの初期化に失敗しました",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // ✅ DeepLink 入口ログ（onCreate）
        logDeepLinkIntent("onCreate", intent)

        // ✅ DeepLink を解析して state に反映（ここで Shared へ切替される）
        applyDeepLinkIfAny(intent)

        val deepLinkUri: Uri? = intent?.data
        val pubKeyLink = PublicKeyLinkBuilder.parse(deepLinkUri)

        // 公開鍵リンク（登録）を受け取った場合
        if (pubKeyLink != null) {
            lifecycleScope.launch {
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
                Toast.makeText(
                    this@HomeActivity,
                    "公開鍵を登録しました",
                    Toast.LENGTH_LONG
                ).show()

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
            }
        }

        // ✅ アカウント + Drive スコープをチェック
        val account: GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(this)
        val hasDriveScope = account?.let {
            GoogleSignIn.hasPermissions(it, Scope(DriveScopes.DRIVE))
        } ?: false

        Log.d(
            TAG,
            "🟩 SignedIn=${account != null}, DriveScope=$hasDriveScope, displayName=${account?.displayName}"
        )

        if (account == null || !hasDriveScope) {
            Log.d(TAG, "🟧 Need sign-in or Drive scope. Redirecting to LoginActivity...")

            // ✅ DeepLink を LoginActivity に引き継ぐ（ログイン後に HomeActivity に戻る想定）
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

        // --- 初期トークン配布申請 ---
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

                    if (ok) {
                        Log.d(TAG, "🟢 initial token request OK. email=$email addr=$address")
                    } else {
                        Log.e(TAG, "🟠 initial token request FAILED. email=$email addr=$address")
                    }
                }.onFailure { e ->
                    Log.e(TAG, "🟠 initial token request ERROR", e)
                }
            }
        }

        // --- 公開鍵レジストリ登録（自分） ---
        account.email?.let { email ->
            lifecycleScope.launch {
                runCatching {
                    val wm = WalletManager.getInstance(applicationContext)
                    wm.initializeIfNeeded()
                    val (_, publicKeyHex) = wm.getNewAddressWithPublicKey()
                    val api = PublicKeyApiClient()
                    val result = withContext(Dispatchers.IO) {
                        api.registerMyPublicKey(email, publicKeyHex)
                    }
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

                Scaffold(
                    bottomBar = {
                        BottomNavigationBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        BottomTab.Home -> {
                            HomeScreen(
                                modifier = Modifier.padding(innerPadding),
                            )
                        }

                        BottomTab.Shared -> {
                            // ✅ DeepLink の入力は SharedScreen に渡すだけ
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

    /**
     * ✅ 起動済みアプリに deep link が来たときは onNewIntent で来る
     * - state を更新して Shared タブへ切替
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        logDeepLinkIntent("onNewIntent", intent)
        applyDeepLinkIfAny(intent)
    }

    @Deprecated("Activity Result API への移行推奨だが互換のため残置")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        EmailSender.onActivityResultBridge(this, requestCode, resultCode)
    }

    private fun applyDeepLinkIfAny(intent: Intent?) {
        val uri = intent?.data
        val paramsFromUri = parseDeepLink(uri)

        // ✅ OS差/端末差で intent.data が欠落するケース対策：extra も併用
        val paramsFromExtra = DeepLinkParams(
            folderId = intent?.getStringExtra("folderId"),
            fileId = intent?.getStringExtra("fileId"),
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
            senderPublicKey = paramsFromExtra.senderPublicKey ?: paramsFromUri.senderPublicKey,
            recipientEmail = paramsFromExtra.recipientEmail ?: paramsFromUri.recipientEmail,
            senderAddress = paramsFromExtra.senderAddress ?: paramsFromUri.senderAddress,
            threshold = paramsFromExtra.threshold ?: paramsFromUri.threshold,
            uuid = paramsFromExtra.uuid ?: paramsFromUri.uuid,
            txid = paramsFromExtra.txid ?: paramsFromUri.txid,
            refundAddress = paramsFromExtra.refundAddress ?: paramsFromUri.refundAddress,
        )

        val isFromDeepLink =
            (merged.folderId != null || merged.fileId != null) ||
                    (!merged.uuid.isNullOrBlank() && !merged.txid.isNullOrBlank())

        deepLinkParamsState.value = merged

        if (isFromDeepLink) {
            // ✅ Swift版に合わせる：DeepLink受信時点で Shared に切替
            selectedTabState.value = BottomTab.Shared
        }

        Log.d(TAG, "🟩 applyDeepLinkIfAny uri=$uri -> $merged, switchToShared=$isFromDeepLink")
    }

    private fun logDeepLinkIntent(where: String, intent: Intent?) {
        val uri = intent?.data
        Log.d(DL_TAG, "---- $where ----")
        Log.d(DL_TAG, "intent.action=${intent?.action}")
        Log.d(DL_TAG, "intent.categories=${intent?.categories}")
        Log.d(DL_TAG, "intent.data=$uri")
        if (uri == null) {
            Log.d(DL_TAG, "data=null (intent.data is null)")
            return
        }
        Log.d(DL_TAG, "scheme=${uri.scheme} host=${uri.host} path=${uri.path}")
        Log.d(DL_TAG, "segments=${uri.pathSegments}")
        Log.d(DL_TAG, "query=${uri.query}")
        Log.d(DL_TAG, "fragment=${uri.fragment}")
        runCatching {
            val names = uri.queryParameterNames
            Log.d(DL_TAG, "queryNames=$names")
        }.onFailure { e ->
            Log.e(DL_TAG, "queryParameterNames dump failed", e)
        }
    }

    private fun parseDeepLink(uri: Uri?): DeepLinkParams {
        if (uri == null) return DeepLinkParams()

        // 新形式: https://sharefilebcapp.web.app/folder/<ID>
        val folderIdFromPath: String? = uri.pathSegments?.let { segs ->
            if (segs.size >= 2 && segs[0] == "folder") segs[1] else null
        }

        // 旧形式: https://.../download?folderId=<ID>
        val folderIdFromQuery: String? = uri.getQueryParameter("folderId")

        // fileId: https://.../file/<ID> または query
        val fileIdFromPath: String? = uri.pathSegments?.let { segs ->
            if (segs.size >= 2 && (segs[0] == "file" || segs[0] == "share")) segs[1] else null
        }
        val fileIdFromQuery: String? = uri.getQueryParameter("fileId")

        // 支払い・検証系
        val senderKey = uri.getQueryParameter("sender")
        val recipientEmail = uri.getQueryParameter("to")
        val senderAddress = uri.getQueryParameter("senderAddress")
        val threshold = uri.getQueryParameter("threshold")?.toULongOrNull()

        val uuid = uri.getQueryParameter("uuid")
        val txid = uri.getQueryParameter("txid")
        val refund = uri.getQueryParameter("refund")

        return DeepLinkParams(
            folderId = folderIdFromPath ?: folderIdFromQuery,
            fileId = fileIdFromQuery ?: fileIdFromPath,
            senderPublicKey = senderKey,
            recipientEmail = recipientEmail,
            senderAddress = senderAddress,
            threshold = threshold,
            uuid = uuid,
            txid = txid,
            refundAddress = refund
        )
    }
}
