package com.example.sharefilebc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deepLinkUri: Uri? = intent?.data

        // ✅ 新形式: https://sharefilebcapp.web.app/folder/<ID>
        val folderIdFromPath: String? = deepLinkUri?.pathSegments?.let { segs ->
            if (segs.size >= 2 && segs[0] == "folder") segs[1] else null
        }
        // 互換: 旧形式 https://.../download?folderId=<ID>
        val folderIdFromQuery: String? = deepLinkUri?.getQueryParameter("folderId")
        val folderIdFromLink: String? = folderIdFromPath ?: folderIdFromQuery

        val displayNameFromIntent = intent.getStringExtra("displayName") ?: "ゲスト"

        Log.d("HomeActivity", "🟩 onCreate - Intent data: $deepLinkUri")
        Log.d("HomeActivity", "🟩 onCreate - Parsed folderId: $folderIdFromLink")
        Log.d("HomeActivity", "🟩 onCreate - DisplayName: $displayNameFromIntent")

        // DeepLinkからの起動かどうかを判定
        val isFromDeepLink = folderIdFromLink != null

        val account: GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            val loginIntent = Intent(this, LoginActivity::class.java).apply {
                deepLinkUri?.let { data = it }
            }
            startActivity(loginIntent)
            finish()
            return
        }

        setContent {
            ShareFileBCTheme {
                val initialSelectedTab = if (isFromDeepLink) BottomTab.Download else BottomTab.Home
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
                                displayName = displayNameFromIntent
                            )
                        }
                        BottomTab.Download -> {
                            DownloadScreen(
                                initialFolderId = if (isFromDeepLink) folderIdFromLink else null
                            )
                        }
                        BottomTab.Sent -> {
                            SentFilesScreen(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
}
