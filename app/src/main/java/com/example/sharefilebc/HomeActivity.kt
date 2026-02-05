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
    }

    private data class DeepLinkParams(
        val folderId: String?,
        val fileId: String?,
        val senderPublicKey: String?,
        val recipientEmail: String?,
        val senderAddress: String?,
        val threshold: ULong?,
        val uuid: String?,
        val txid: String?,
        val refundAddress: String?
    )

    // ✅ Activity側で状態を保持（onNewIntent からも更新できるようにする）
    private var selectedTabState = mutableStateOf(BottomTab.Home)
    private var deepLinkParamsState = mutableStateOf<DeepLinkParams?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ WalletManager の初期化を最優先で実行（ただし UI はブロックしない）
        lifecycleScope.launch {
            runCatching {
                val walletManager = WalletManager.getInstance(this@HomeActivity)
                walletManager.initializeIfNeeded()
                Log.d(TAG, "✅ WalletManager initialized successfully")

                val currentAddress = walletManager.getNewAddress()
                Log.d(TAG, "Tapyrus current address = $currentAddress")

                val (address, pubKey) = walletManager.getNewAddressWithPublicKey()
                Log.d(TAG, "Tapyrus public key = $pubKey")
                Log.d(TAG, "Tapyrus address = $address")
            }.onFailure { e ->
                Log.e(TAG, "❌ WalletManager initialization failed", e)
                Toast.makeText(
                    this@HomeActivity,
                    "ウォレットの初期化に失敗しました",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // ✅ 起動時 deep link をここで一元処理
        handleIncomingIntent(intent, isFirstLaunch = true)

        setContent {
            ShareFileBCTheme {
                val selectedTab by selectedTabState
                val deepLink = deepLinkParamsState.value

                Scaffold(
                    bottomBar = {
                        BottomNavigationBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTabState.value = it }
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
                            SharedScreen(
                                modifier = Modifier.padding(innerPadding),
                                // ✅ SharedScreenで処理する。DownloadScreenにはdeep linkを渡さない方針にする
                                initialFolderId = deepLink?.folderId,
                                initialFileId = deepLink?.fileId,
                                deepLinkSenderPublicKey = deepLink?.senderPublicKey,
                                deepLinkRecipientEmail = deepLink?.recipientEmail,
                                deepLinkSenderAddress = deepLink?.senderAddress,
                                deepLinkThreshold = deepLink?.threshold,
                                deepLinkUuid = deepLink?.uuid,
                                deepLinkTxid = deepLink?.txid,
                                deepLinkRefundAddress = deepLink?.refundAddress
                            )
                        }
                    }
                }
            }
        }
    }

    // ✅ アプリ起動中に deep link が来た場合もここで必ず拾う（Swift版の「受けた瞬間にSharedへ」）
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        handleIncomingIntent(intent, isFirstLaunch = false)
    }

    private fun handleIncomingIntent(intent: Intent, isFirstLaunch: Boolean) {
        val deepLinkUri: Uri? = intent.data
        val parsed = parseDeepLink(deepLinkUri)

        // extra fallback（端末差対策）
        val folderIdFromExtra: String? = intent.getStringExtra("folderId")
        val fileIdFromExtra: String? = intent.getStringExtra("fileId")
        val senderKeyFromExtra: String? = intent.getStringExtra("senderPublicKey")
        val recipientEmailFromExtra: String? = intent.getStringExtra("recipientEmail")
        val senderAddressFromExtra: String? = intent.getStringExtra("senderAddress")
        val thresholdFromExtra: ULong? = intent.getStringExtra("threshold")?.toULongOrNull()
        val uuidFromExtra: String? = intent.getStringExtra("uuid")
        val txidFromExtra: String? = intent.getStringExtra("txid")
        val refundFromExtra: String? = intent.getStringExtra("refund")

        // 新形式: https://sharefilebcapp.web.app/folder/<ID>
        val folderIdFromPath: String? = deepLinkUri?.pathSegments?.let { segs ->
            if (segs.size >= 2 && segs[0] == "folder") segs[1] else null
        }
        // 旧形式: https://.../download?folderId=<ID>
        val folderIdFromQuery: String? = deepLinkUri?.getQueryParameter("folderId")

        val folderId = folderIdFromExtra ?: folderIdFromPath ?: folderIdFromQuery ?: parsed.folderId
        val fileId = fileIdFromExtra ?: parsed.fileId
        val senderKey = senderKeyFromExtra ?: parsed.senderPublicKey
        val recipientEmail = recipientEmailFromExtra ?: parsed.recipientEmail
        val senderAddress = senderAddressFromExtra ?: parsed.senderAddress
        val threshold = thresholdFromExtra ?: parsed.threshold
        val uuid = uuidFromExtra ?: parsed.uuid
        val txid = txidFromExtra ?: parsed.txid
        val refund = refundFromExtra ?: parsed.refundAddress

        Log.d(TAG, "🟩 handleIncomingIntent(first=$isFirstLaunch) data=$deepLinkUri")
        Log.d(TAG, "🟩 picked folderId=$folderId fileId=$fileId uuid=$uuid txid=$txid refund=$refund")

        // ✅ PublicKeyLink は受信した瞬間に登録（従来通り）
        val pubKeyLink = PublicKeyLinkBuilder.parse(deepLinkUri)
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
                    try {
                        val keys = db.emailKeyDao().getAll().first()
                        keys.forEach { key ->
                            Log.d("DEBUG", "Email: ${key.email}")
                            Log.d("DEBUG", "  derivedPublicKey(/1): ${key.derivedPublicKey.take(16)}...")
                            Log.d("DEBUG", "  trustLayerPublicKey(/0): ${key.trustLayerPublicKey.take(16)}...")
                        }
                    } catch (e: Exception) {
                        Log.e("DEBUG", "EmailKeyEntity dump failed", e)
                    }
                }
            }
        }

        // ✅ ログイン/スコープ確認（従来通り）
        val account: GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(this)
        val hasDriveScope = account?.let { GoogleSignIn.hasPermissions(it, Scope(DriveScopes.DRIVE)) } ?: false

        if (account == null || !hasDriveScope) {
            Log.d(TAG, "🟧 Need sign-in or Drive scope. Redirecting to LoginActivity...")
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    deepLinkUri?.let { data = it }
                    folderId?.let { putExtra("folderId", it) }
                    fileId?.let { putExtra("fileId", it) }
                    senderKey?.let { putExtra("senderPublicKey", it) }
                    recipientEmail?.let { putExtra("recipientEmail", it) }
                    senderAddress?.let { putExtra("senderAddress", it) }
                    threshold?.let { putExtra("threshold", it.toString()) }
                    uuid?.let { putExtra("uuid", it) }
                    txid?.let { putExtra("txid", it) }
                    refund?.let { putExtra("refund", it) }
                }
            )
            finish()
            return
        }

        // ✅ DeepLink判定：folderId/fileId がなくても uuid+txid があれば Sharedへ
        val isFromDeepLink =
            (folderId != null || fileId != null) ||
                    (!uuid.isNullOrBlank() && !txid.isNullOrBlank())

        if (isFromDeepLink) {
            deepLinkParamsState.value = DeepLinkParams(
                folderId = folderId,
                fileId = fileId,
                senderPublicKey = senderKey,
                recipientEmail = recipientEmail,
                senderAddress = senderAddress,
                threshold = threshold,
                uuid = uuid,
                txid = txid,
                refundAddress = refund
            )
            // ✅ Swift版と同じ：受信した瞬間に Shared タブへ
            selectedTabState.value = BottomTab.Shared
        }

        // --- 初期トークン配布申請（従来通り） ---
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

        // --- 公開鍵レジストリ登録（従来通り） ---
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
    }

    @Deprecated("Activity Result API への移行推奨だが互換のため残置")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        EmailSender.onActivityResultBridge(this, requestCode, resultCode)
    }

    private fun parseDeepLink(uri: Uri?): DeepLinkParams {
        if (uri == null) {
            return DeepLinkParams(
                folderId = null,
                fileId = null,
                senderPublicKey = null,
                recipientEmail = null,
                senderAddress = null,
                threshold = null,
                uuid = null,
                txid = null,
                refundAddress = null
            )
        }

        val segments = uri.pathSegments.orEmpty()
        val isSharePath = segments.firstOrNull() == "share"
        val isFilePath = segments.firstOrNull() == "file"

        val fileIdFromPath = when {
            isSharePath && segments.size >= 2 -> segments[1]
            isFilePath && segments.size >= 2 -> segments[1]
            else -> null
        }

        val sender = uri.getQueryParameter("sender")
        val to = uri.getQueryParameter("to")
        val fileIdFromQuery = uri.getQueryParameter("fileId")
        val senderAddress = uri.getQueryParameter("senderAddress")
        val threshold = uri.getQueryParameter("threshold")?.toULongOrNull()

        val uuid = uri.getQueryParameter("uuid")
        val txid = uri.getQueryParameter("txid")
        val refundAddress = uri.getQueryParameter("refund")

        return DeepLinkParams(
            folderId = uri.getQueryParameter("folderId"),
            fileId = fileIdFromQuery ?: fileIdFromPath,
            senderPublicKey = sender,
            recipientEmail = to,
            senderAddress = senderAddress,
            threshold = threshold,
            uuid = uuid,
            txid = txid,
            refundAddress = refundAddress
        )
    }
}
