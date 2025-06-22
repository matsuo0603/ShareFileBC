package com.example.sharefilebc

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.ui.theme.ShareFileBCTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Task
import androidx.compose.ui.ExperimentalComposeUiApi
import android.util.Log
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

class LoginActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var launcher: ActivityResultLauncher<Intent>

    // Deep LinkのURIを保持するためのプロパティ
    // HomeActivityからリダイレクトされた場合にのみ設定される
    private var deepLinkUriFromHomeActivity: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        }

        // HomeActivityからDeep LinkのURIが渡されたかチェック
        // intent.getBooleanExtra("isDeepLink", false) の代わりに、dataそのものをチェック
        if (intent?.data != null) {
            deepLinkUriFromHomeActivity = intent.data
            Log.d("LoginActivity", "LoginActivity onCreate: received Deep Link URI from HomeActivity: $deepLinkUriFromHomeActivity")
        } else {
            Log.d("LoginActivity", "LoginActivity onCreate: Normal app launch.")
        }

        setContent {
            ShareFileBCTheme {
                LoginScreen()
            }
        }
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
            Text(
                text = "ShareFileBC",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .width(250.dp)
                    .pointerInteropFilter {
                        when (it.action) {
                            MotionEvent.ACTION_DOWN -> pressed = true
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = false
                        }
                        false
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        // 既にログイン済みのセッションをクリアしてからサインインプロセスを開始
                        googleSignInClient.signOut().addOnCompleteListener {
                            val signInIntent = googleSignInClient.signInIntent
                            launcher.launch(signInIntent)
                        }
                    }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.btn_google_signin),
                    contentDescription = "Google Sign In",
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (pressed) 0.7f else 1f)
                )
            }
        }
    }

    private fun handleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(Exception::class.java)
            val displayName = account?.displayName ?: "ログインユーザー"

            val intent = Intent(this, HomeActivity::class.java).apply {
                putExtra("displayName", displayName)
                // LoginActivityに引き継がれたDeep LinkのURIがあれば、HomeActivityにも引き継ぐ
                deepLinkUriFromHomeActivity?.let { uri ->
                    data = uri
                    Log.d("LoginActivity", "Login successful, passing Deep Link URI to HomeActivity: $uri")
                }
            }
            startActivity(intent)
            finish()

        } catch (e: Exception) {
            e.printStackTrace()
            println("Googleサインイン失敗: ${e.message}")
            Log.e("LoginActivity", "Googleサインイン失敗: ${e.message}")
        }
    }
}