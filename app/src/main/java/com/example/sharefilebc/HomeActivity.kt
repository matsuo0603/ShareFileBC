package com.example.sharefilebc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

class HomeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔐 マスター鍵(xprv)の生成・取得（今回は秘密鍵/公開鍵機能だけ。ウォレット機能は後で）
        try {
            val keyManager = KeyManager.getInstance(applicationContext)
            val xprv = keyManager.getOrCreateMasterXprv()
            // セキュリティ的にフル出力は危険なので、デバッグ用に先頭だけログに出す
            val preview = if (xprv.length > 16) xprv.take(16) + "..." else xprv
            Log.d(TAG, "Tapyrus master xprv (preview) = $preview")
        } catch (e: Exception) {
            Log.e(TAG, "Tapyrus getOrCreateMasterXprv error", e)
        }

        val deepLinkUri: Uri? = intent?.data

        // HomeActivity に戻ってくる際に folderId を extra で受ける（OS差/端末差で data 欠落の対策）
        val folderIdFromExtra: String? = intent.getStringExtra("folderId")

        // 新形式: https://sharefilebcapp.web.app/folder/<ID>
        val folderIdFromPath: String? = deepLinkUri?.pathSegments?.let { segs ->
            if (segs.size >= 2 && segs[0] == "folder") segs[1] else null
        }
        // 旧形式: https://.../download?folderId=<ID>
        val folderIdFromQuery: String? = deepLinkUri?.getQueryParameter("folderId")

        val folderIdFromLink: String? = folderIdFromExtra ?: folderIdFromPath ?: folderIdFromQuery
        val displayNameFromIntent = intent.getStringExtra("displayName") ?: "ゲスト"

        Log.d(TAG, "🟩 onCreate - Intent data: $deepLinkUri")
        Log.d(
            TAG,
            "🟩 onCreate - folderId(extra=$folderIdFromExtra, path=$folderIdFromPath, query=$folderIdFromQuery) -> PICKED: $folderIdFromLink"
        )
        Log.d(TAG, "🟩 onCreate - DisplayName: $displayNameFromIntent")

        // 目視確認用（端末上に出す）
        Toast.makeText(
            this,
            "HomeActivity:\ndata=${deepLinkUri?.toString() ?: "null"}\nfolderId=$folderIdFromLink",
            Toast.LENGTH_LONG
        ).show()

        val isFromDeepLink = folderIdFromLink != null

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
                    // Deep Link を data で、folderId を extra でも渡す（二重化で欠落対策）
                    deepLinkUri?.let { data = it }
                    folderIdFromLink?.let { putExtra("folderId", it) }
                }
            )
            finish()
            return
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
                                initialFolderId = if (isFromDeepLink) folderIdFromLink else null
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
}
