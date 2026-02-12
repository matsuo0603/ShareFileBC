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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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

        // Deep Link経由で来ていて、未ログイン or Drive権限なしなら自動でサインイン開始
        val already = GoogleSignIn.getLastSignedInAccount(this)
        val hasRequiredScopes = already?.let {
            GoogleSignIn.hasPermissions(
                it,
                Scope(DriveScopes.DRIVE),
                Scope(GmailScopes.GMAIL_SEND)
            )
        } ?: false
        if (deepLinkUriFromHomeActivity != null && (already == null || !hasRequiredScopes)) {
            launcher.launch(googleSignInClient.signInIntent)
        }

        setContent { ShareFileBCTheme { LoginScreen() } }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun LoginScreen() {
        var pressed by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "ShareFileBC", style = MaterialTheme.typography.headlineMedium)

            Spacer(modifier = Modifier.height(32.dp))

            Image(
                painter = painterResource(id = R.drawable.btn_google_signin),
                contentDescription = "Google Sign In",
                modifier = Modifier
                    .width(250.dp)
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
                    ) {
                        // ✅ ここで signOut/revokeAccess しないと、
                        // 以前のトークンや scope が残って Drive のアップロードが 401/403 で失敗することがある。
                        // WorkManager のバックグラウンド処理は「ログイン後に」成立させる方針に切り替える。
                        googleSignInClient.signOut().addOnCompleteListener {
                            launcher.launch(googleSignInClient.signInIntent)
                        }
                    }
            )
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

        } catch (e: Exception) {
            Log.e(TAG, "❌ Googleサインイン失敗: ${e.message}", e)
            Toast.makeText(this, "サインインに失敗しました", Toast.LENGTH_LONG).show()
        }
    }
}
