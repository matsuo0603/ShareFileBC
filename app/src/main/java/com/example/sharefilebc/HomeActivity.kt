package com.example.sharefilebc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.sharefilebc.ui.DownloadScreen
import com.example.sharefilebc.ui.HomeScreen
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import androidx.compose.foundation.layout.padding
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Deep LinkからURI全体とfolderIdを取得
        val deepLinkUri: Uri? = intent?.data
        val folderIdFromLink: String? = deepLinkUri?.getQueryParameter("folderId")

        // LoginActivityからdisplayNameが渡された場合に使用
        val displayNameFromIntent = intent.getStringExtra("displayName") ?: "ゲスト"

        Log.d("HomeActivity", "🟩 onCreate - Intent data: ${intent?.data}")
        Log.d("HomeActivity", "🟩 onCreate - Parsed folderId: $folderIdFromLink")
        Log.d("HomeActivity", "🟩 onCreate - DisplayName from Intent: $displayNameFromIntent")

        // Googleサインイン状態をチェック
        val account: GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(this)

        if (account == null) {
            // 未ログインの場合、ログイン画面へリダイレクト
            Log.d("HomeActivity", "未ログイン: LoginActivityへリダイレクトします。")
            val loginIntent = Intent(this, LoginActivity::class.java).apply {
                // Deep Linkの情報をLoginActivityに引き継ぐ
                // HomeActivityからリダイレクトされる場合にのみDeep Link URIを渡す
                // (AndroidManifestでHomeActivityがDeep Linkの受け口のため、初回起動時のみここにURIがある)
                deepLinkUri?.let { data = it }
            }
            startActivity(loginIntent)
            finish() // HomeActivityを終了
            return // ここで処理を終了
        }

        // ログイン済みの場合のみコンテンツを設定
        setContent {
            ShareFileBCTheme {
                // Deep Link経由の起動 (folderIdFromLink != null) の場合、Downloadタブをデフォルトにする
                // そうでなければ、通常の起動としてHomeタブをデフォルトにする
                val initialSelectedTab = if (folderIdFromLink != null) BottomTab.Download else BottomTab.Home
                var selectedTab by remember { mutableStateOf(initialSelectedTab) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            BottomTab.values().forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        BottomTab.Home -> {
                            HomeScreen(
                                modifier = Modifier.padding(innerPadding),
                                displayName = displayNameFromIntent
                            )
                        }
                        BottomTab.Download -> {
                            // initialFolderId を DownloadScreen に渡す
                            DownloadScreen(initialFolderId = folderIdFromLink)
                        }
                    }
                }
            }
        }
    }
}

// BottomTab Enum の定義が HomeActivity.kt のこの位置にあることを確認
enum class BottomTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("Home", Icons.Default.Home),
    Download("Download", Icons.Default.CloudDownload)
}