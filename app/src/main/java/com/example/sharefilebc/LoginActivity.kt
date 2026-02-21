package com.example.sharefilebc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.services.drive.DriveScopes
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var launcher: ActivityResultLauncher<Intent>

    // Deep Link と folderId を保持（HomeActivityから渡される）
    private var deepLinkUriFromHomeActivity: android.net.Uri? = null
    private var folderIdFromHomeActivity: String? = null
    private var fileIdFromHomeActivity: String? = null
    private var senderPublicKeyFromHomeActivity: String? = null
    private var recipientEmailFromHomeActivity: String? = null
    private var senderAddressFromHomeActivity: String? = null
    private var thresholdFromHomeActivity: String? = null

    private var shouldAutoProceed: Boolean = false
    private var cachedDisplayName: String = "ログインユーザー"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(DriveScopes.DRIVE),
                Scope(GmailScopes.GMAIL_SEND)
            )
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        }

        // HomeActivityから渡された Deep Link / folderId を取得
        deepLinkUriFromHomeActivity = intent?.data
        folderIdFromHomeActivity = intent.getStringExtra("folderId")
        fileIdFromHomeActivity = intent.getStringExtra("fileId")
        senderPublicKeyFromHomeActivity = intent.getStringExtra("senderPublicKey")
        recipientEmailFromHomeActivity = intent.getStringExtra("recipientEmail")
        senderAddressFromHomeActivity = intent.getStringExtra("senderAddress")
        thresholdFromHomeActivity = intent.getStringExtra("threshold")
        Log.d(TAG, "onCreate: Deep Link received: $deepLinkUriFromHomeActivity")
        Log.d(TAG, "onCreate: folderId received: $folderIdFromHomeActivity")

        val already = GoogleSignIn.getLastSignedInAccount(this)
        val hasRequiredScopes = already?.let {
            GoogleSignIn.hasPermissions(
                it,
                Scope(DriveScopes.DRIVE),
                Scope(GmailScopes.GMAIL_SEND)
            )
        } ?: false

        // ✅ iOS版の挙動に合わせる
        // - 既にログイン済み（必要なscopeもある）なら「アイコンだけ表示」→ 即 Home へ
        // - 未ログインなら「アイコン + Googleでログイン」を表示
        shouldAutoProceed = (already != null && hasRequiredScopes)
        cachedDisplayName = already?.displayName ?: "ログインユーザー"

        // Deep Link経由で来ていて、未ログイン or Drive権限なしなら自動でサインイン開始
        if (deepLinkUriFromHomeActivity != null && (already == null || !hasRequiredScopes)) {
            launcher.launch(googleSignInClient.signInIntent)
        }

        setContent {
            // ✅ iOS版トップ画面は白背景前提なので、ここは常にライトで固定
            ShareFileBCTheme(darkTheme = false) {
                LoginScreen(
                    isLoggedIn = shouldAutoProceed,
                    onLoginClick = {
                        // ✅ ここで signOut しないと、以前のトークン/scope が残って Drive が 401/403 になることがある
                        googleSignInClient.signOut().addOnCompleteListener {
                            launcher.launch(googleSignInClient.signInIntent)
                        }
                    },
                    onAutoProceed = {
                        startHome(displayName = cachedDisplayName)
                    }
                )
            }
        }
    }

    private fun startHome(displayName: String) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            putExtra("displayName", displayName)
            deepLinkUriFromHomeActivity?.let { data = it }
            folderIdFromHomeActivity?.let { putExtra("folderId", it) }
            fileIdFromHomeActivity?.let { putExtra("fileId", it) }
            senderPublicKeyFromHomeActivity?.let { putExtra("senderPublicKey", it) }
            recipientEmailFromHomeActivity?.let { putExtra("recipientEmail", it) }
            senderAddressFromHomeActivity?.let { putExtra("senderAddress", it) }
            thresholdFromHomeActivity?.let { putExtra("threshold", it) }
        }
        startActivity(intent)
        finish()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun LoginScreen(
        isLoggedIn: Boolean,
        onLoginClick: () -> Unit,
        onAutoProceed: () -> Unit
    ) {
        var pressed by remember { mutableStateOf(false) }

        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn) {
                // iOS版と同様：ログイン済みならトップ画面（アイコンのみ）を見せて即遷移
                onAutoProceed()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ✅ iOS版 TopPage と同じ見た目（Assets.xcassets の TopPage）
                Image(
                    painter = painterResource(id = R.drawable.top_page),
                    contentDescription = "ShareFileBC Icon",
                    modifier = Modifier.size(120.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "ShareFileBC",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black
                )

                if (!isLoggedIn) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier
                            .pointerInteropFilter {
                                when (it.action) {
                                    MotionEvent.ACTION_DOWN -> pressed = true
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = false
                                }
                                false
                            }
                            .alpha(if (pressed) 0.7f else 1f)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onLoginClick() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.google_icon),
                            contentDescription = "Google",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Googleでログイン",
                            // iOSの青 (0xFF0A84FF) に寄せる
                            color = Color(0xFF0A84FF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    private fun handleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(Exception::class.java)
            val displayName = account?.displayName ?: "ログインユーザー"

            val hasRequiredScopes = account?.let {
                GoogleSignIn.hasPermissions(
                    it,
                    Scope(DriveScopes.DRIVE),
                    Scope(GmailScopes.GMAIL_SEND)
                )
            } ?: false
            Log.d(TAG, "✅ SignIn ok: ${account?.email} scopesReady=$hasRequiredScopes")

            if (account == null || !hasRequiredScopes) {
                Toast.makeText(this, "Drive/Gmail権限が不足しています（再ログインしてください）", Toast.LENGTH_LONG).show()
                return
            }

            // ===== ログイン成功後に即同期 =====
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val keyUpserts = PublicKeySyncer.syncOnce(this@LoginActivity)
                    val folderUpserts = IncomingFilesSyncer.syncOnce(this@LoginActivity)
                    Log.d(TAG, "✅ Immediate sync finished: keys=$keyUpserts folders=$folderUpserts")
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Immediate sync failed", e)
                }
            }

            startHome(displayName = displayName)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Googleサインイン失敗: ${e.message}", e)
            Toast.makeText(this, "サインインに失敗しました", Toast.LENGTH_LONG).show()
        }
    }
}
