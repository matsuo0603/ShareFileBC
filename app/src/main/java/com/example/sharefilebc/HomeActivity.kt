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
import com.example.sharefilebc.ui.HomeScreen
import com.example.sharefilebc.ui.DownloadScreen
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.example.sharefilebc.ui.BottomTab
import com.example.sharefilebc.ui.BottomNavigationBar
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deepLinkUri: Uri? = intent?.data
        val folderIdFromLink: String? = deepLinkUri?.getQueryParameter("folderId")
        val displayNameFromIntent = intent.getStringExtra("displayName") ?: "ゲスト"

        Log.d("HomeActivity", "🟩 onCreate - Intent data: $deepLinkUri")
        Log.d("HomeActivity", "🟩 onCreate - Parsed folderId: $folderIdFromLink")
        Log.d("HomeActivity", "🟩 onCreate - DisplayName: $displayNameFromIntent")

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
                val initialSelectedTab = if (folderIdFromLink != null) BottomTab.Download else BottomTab.Home
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
                            DownloadScreen(initialFolderId = folderIdFromLink)
                        }
                    }
                }
            }
        }
    }
}
