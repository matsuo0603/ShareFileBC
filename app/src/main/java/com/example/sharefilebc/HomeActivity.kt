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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.sharefilebc.network.PublicKeyApiClient
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.EmailKeyEntity

class HomeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }
    private data class DeepLinkParams(
        val folderId: String?,
        val fileId: String?,
        val senderPublicKey: String?,
        val recipientEmail: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔐 Tapyrus ウォレットの受取アドレス取得（鍵生成〜派生は TapyrusWalletManager に集約）
        try {
            val walletManager = KeyDerivation.getInstance(applicationContext)
            val currentAddress = walletManager.getCurrentAddress()
            Log.d(
                TAG,
                "Tapyrus current address (m/44'/0'/0'/0/0) = $currentAddress"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tapyrus wallet initialization error", e)
        }

        // 🔍 テスト用ログ：公開鍵 / 秘密鍵の確認（秘密鍵はマスクする）
        val walletManager = KeyDerivation.getInstance(applicationContext)
        val pubHex = walletManager.getCurrentPublicKeyHex()
        Log.d(TAG, "Tapyrus public key HEX = $pubHex")

        val privHex = walletManager.getCurrentPrivateKeyHex()
        Log.d(TAG, "Tapyrus private key HEX = ${maskHex(privHex)}")


        val deepLinkUri: Uri? = intent?.data
        val deepLinkParams = parseDeepLink(deepLinkUri)
        val pubKeyLink = PublicKeyLinkBuilder.parse(deepLinkUri)

        // HomeActivity に戻ってくる際に folderId/fileId を extra で受ける（OS差/端末差で data欠落の対策）
        val folderIdFromExtra: String? = intent.getStringExtra("folderId")
        val fileIdFromExtra: String? = intent.getStringExtra("fileId")
        val senderKeyFromExtra: String? = intent.getStringExtra("senderPublicKey")
        val recipientEmailFromExtra: String? = intent.getStringExtra("recipientEmail")
        // 新形式: https://sharefilebcapp.web.app/folder/<ID>
        val folderIdFromPath: String? = deepLinkUri?.pathSegments?.let { segs ->
            if (segs.size >= 2 && segs[0] == "folder") segs[1] else null
        }
        // 旧形式: https://.../download?folderId=<ID>
        val folderIdFromQuery: String? = deepLinkUri?.getQueryParameter("folderId")

        val folderIdFromLink: String? = folderIdFromExtra
            ?: folderIdFromPath
            ?: folderIdFromQuery
            ?: deepLinkParams.folderId
        val fileIdFromLink: String? = fileIdFromExtra ?: deepLinkParams.fileId
        val senderKeyFromLink: String? = senderKeyFromExtra ?: deepLinkParams.senderPublicKey
        val recipientEmailFromLink: String? = recipientEmailFromExtra ?: deepLinkParams.recipientEmail
        val displayNameFromIntent = intent.getStringExtra("displayName") ?: "ゲスト"

        Log.d(TAG, "🟩 onCreate - Intent data: $deepLinkUri")
        Log.d(
            TAG,
            "🟩 onCreate - folderId(extra=$folderIdFromExtra, path=$folderIdFromPath, query=$folderIdFromQuery) -> PICKED: $folderIdFromLink"
        )
        Log.d(TAG, "🟩 onCreate - fileId=$fileIdFromLink, senderKey=${senderKeyFromLink?.take(6)}...")
        Log.d(TAG, "🟩 onCreate - DisplayName: $displayNameFromIntent")

        // 目視確認用（端末上に出す）
        Toast.makeText(
            this,
            "HomeActivity:\ndata=${deepLinkUri?.toString() ?: "null"}\nfolderId=$folderIdFromLink\nfileId=$fileIdFromLink",
            Toast.LENGTH_LONG
        ).show()

        val isFromDeepLink = folderIdFromLink != null || fileIdFromLink != null

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
                        val db = AppDatabase.getDatabase(applicationContext)

                        // Flow<List<EmailKeyEntity>> → first() で1回だけ取得
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
        // ✅ アカウント + Drive スコープを両方チェック
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
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    // Deep Link を data で、folderId や fileId を extra でも渡す（二重化で欠落対策）
                    deepLinkUri?.let { data = it }
                    folderIdFromLink?.let { putExtra("folderId", it) }
                    fileIdFromLink?.let { putExtra("fileId", it) }
                    senderKeyFromLink?.let { putExtra("senderPublicKey", it) }
                    recipientEmailFromLink?.let { putExtra("recipientEmail", it) }
                }
            )
            finish()
            return
        }

        account.email?.let { email ->
            lifecycleScope.launch {
                val publicKeyHex = walletManager.getCurrentPublicKeyHex("m/44'/0'/0'/0/0")
                val api = PublicKeyApiClient()
                val result = withContext(Dispatchers.IO) {
                    api.registerMyPublicKey(email, publicKeyHex)
                }
                result.onSuccess {
                    Log.d(TAG, "🟢 公開鍵を公開鍵レジストリに登録しました (${email})")
                }.onFailure { e ->
                    Log.e(TAG, "🟠 公開鍵の自動登録に失敗しました", e)
                }
            }
        }
        setContent {
            ShareFileBCTheme {
                // DeepLinkで来たときは Shared を初期表示、それ以外は Home
                val initialSelectedTab = if (isFromDeepLink) BottomTab.Shared else BottomTab.Home
                var selectedTab by remember { mutableStateOf(initialSelectedTab) }

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
                            SharedScreen(
                                modifier = Modifier.padding(innerPadding),
                                initialFolderId = if (isFromDeepLink) folderIdFromLink else null,
                                initialFileId = fileIdFromLink,
                                deepLinkSenderPublicKey = senderKeyFromLink,
                                deepLinkRecipientEmail = recipientEmailFromLink
                            )
                        }
                    }
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
        if (uri == null) return DeepLinkParams(null, null, null, null)

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

        return DeepLinkParams(
            folderId = uri.getQueryParameter("folderId"),
            fileId = fileIdFromQuery ?: fileIdFromPath,
            senderPublicKey = sender,
            recipientEmail = to
        )
    }
    private fun maskHex(hex: String, keep: Int = 6): String =
        if (hex.length <= keep) hex else hex.take(keep) + "..."
}
