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

    // Deep LinkのURIを保持（HomeActivityから渡される）
    private var deepLinkUriFromHomeActivity: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        }

        // HomeActivityからDeep LinkのURIが渡されたかチェック
        deepLinkUriFromHomeActivity = intent?.data
        if (deepLinkUriFromHomeActivity != null) {
            Log.d("LoginActivity", "onCreate: Deep Link received: $deepLinkUriFromHomeActivity")
        } else {
            Log.d("LoginActivity", "onCreate: Normal app launch.")
        }

        // 🔁 Deep Link経由で来ていて、未ログイン or Drive権限なしなら、自動でサインイン開始
        val already = GoogleSignIn.getLastSignedInAccount(this)
        val hasDrive = already?.let { GoogleSignIn.hasPermissions(it, Scope(DriveScopes.DRIVE)) } ?: false
        if (deepLinkUriFromHomeActivity != null && (already == null || !hasDrive)) {
            val signInIntent = googleSignInClient.signInIntent
            launcher.launch(signInIntent)
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
                        // 明示タップ時もサインイン開始
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
                deepLinkUriFromHomeActivity?.let { data = it } // Deep Linkを戻す
            }
            startActivity(intent)
            finish()

        } catch (e: Exception) {
            Log.e("LoginActivity", "Googleサインイン失敗: ${e.message}", e)
        }
    }
}
