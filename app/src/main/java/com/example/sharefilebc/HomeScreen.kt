@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sharefilebc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.EmailKeyEntity
import com.example.sharefilebc.data.MyPublicKeyDao
import com.example.sharefilebc.data.MyPublicKeyEntity
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.ui.theme.HomeScreenButtonColors
import com.example.sharefilebc.ui.theme.PureWhite
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 共有相手の登録・一覧・削除・共有送信を行う画面。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember { AppDatabase.getDatabase(context) }
    val userDao = db.userDao()
    val emailKeyDao = db.emailKeyDao()
    val myPublicKeyDao = db.myPublicKeyDao()

    val driveUploader = remember { DriveUploader(context) }
    var users by remember { mutableStateOf(listOf<UserEntity>()) }
    var emailKeys by remember { mutableStateOf(listOf<EmailKeyEntity>()) }
    var isUploading by remember { mutableStateOf(false) }
    var isRegistering by remember { mutableStateOf(false) }
    var showAccountScreen by remember { mutableStateOf(false) }
    var tokenThreshold by remember { mutableStateOf(1) }
    var sendFee by remember { mutableStateOf(1) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addEmail by remember { mutableStateOf("") }
    var registrationSnackbarMessage by remember { mutableStateOf<String?>(null) }

    // 🔐 Tapyrus ウォレットの現在アドレス（Swift版 HomeView の currentAddress 相当）
    var walletAddress by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // ===== 切り分け用：Home表示直後の即同期を一旦無効化 =====
    LaunchedEffect(Unit) {
        try {
            // val keyUpserts = PublicKeySyncer.syncOnce(context)
            // val folderUpserts = IncomingFilesSyncer.syncOnce(context)
            Log.d("HomeScreen", "⏭ Immediate sync on HomeScreen is DISABLED for debugging")
        } catch (e: Exception) {
            Log.e("HomeScreen", "⚠️ Immediate sync on HomeScreen failed", e)
        }
    }

    val account = remember { GoogleSignIn.getLastSignedInAccount(context) }
    val accountName = account?.displayName
    val accountEmail = account?.email
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
        GoogleSignIn.getClient(context, gso)
    }
    val signOutAndNavigate: () -> Unit = {
        googleSignInClient.signOut().addOnCompleteListener {
            val intent = Intent(context, LoginActivity::class.java)
            context.startActivity(intent)
            (context as? Activity)?.finish()
        }
    }

    var isBalanceVisible by remember { mutableStateOf(false) }
    var balanceSat by remember { mutableStateOf<ULong?>(null) }

    var selectedUser by remember { mutableStateOf<UserEntity?>(null) }
    var selectedEmailKey by remember { mutableStateOf<EmailKeyEntity?>(null) }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = FilePickerContract(),
        onResult = { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            val target = selectedUser ?: return@rememberLauncherForActivityResult
            val recipientKey = selectedEmailKey ?: return@rememberLauncherForActivityResult
            scope.launch {
                isUploading = true
                withContext(Dispatchers.IO) {
                    val result = driveUploader.uploadFileAndRecordWithSharing(
                        fileUri = uri,
                        recipient = target,
                        recipientKey = recipientKey,
                        db = db
                    )
                    withContext(Dispatchers.Main) {
                        isUploading = false
                        when (result) {
                            is UploadResult.Success -> {
                                val (fileName, fileId, folderId) = result
                                val wallet = KeyDerivation.getInstance(context)
                                val senderPublicKey = runCatching {
                                    wallet.getCurrentPublicKeyHex("m/44'/0'/0'/0/0")
                                }.getOrNull()

                                if (senderPublicKey != null) {
                                    EmailSender.sendEmailWithDriveLink(
                                        context = context,
                                        recipientEmail = target.email,
                                        fileName = fileName,
                                        folderId = folderId,
                                        fileId = fileId,
                                        senderPublicKeyHex = senderPublicKey
                                    )
                                } else {
                                    Toast.makeText(
                                        context,
                                        "送信者の公開鍵を取得できませんでした",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            is UploadResult.MissingRecipientPublicKey -> {
                                val link = result.registrationLink
                                val snackbarResult = snackbarHostState.showSnackbar(
                                    message = "相手の公開鍵がまだ登録されていません",
                                    actionLabel = link?.let { "メール送信" }
                                )
                                if (snackbarResult == SnackbarResult.ActionPerformed && link != null) {
                                    EmailSender.sendPublicKeyRegistrationEmail(
                                        context = context,
                                        recipientEmail = target.email,
                                        registrationUrl = link,
                                        senderEmail = accountEmail
                                    )
                                }
                            }

                            is UploadResult.Failure -> {
                                Toast.makeText(
                                    context,
                                    "アップロードに失敗しました（Google認証を確認）",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            selectedUser = null
        }
    )

    // 共有相手一覧の購読
    LaunchedEffect(Unit) {
        userDao.getAll().collectLatest { list -> users = list }
    }

    LaunchedEffect(Unit) {
        emailKeyDao.getAll().collectLatest { list -> emailKeys = list }
    }

    LaunchedEffect(users, emailKeys) {
        val emailKeyMap = emailKeys.associateBy { it.email }
        users.forEach { user ->
            val emailKey = emailKeyMap[user.email]
            val hasDerived = !emailKey?.derivedPublicKey.isNullOrBlank()
            val hasTrustLayer = !emailKey?.trustLayerPublicKey.isNullOrBlank()
            Log.d(
                "HomeScreen",
                "鍵マーク判定: email=${user.email} derivedPublicKey=${hasDerived} trustLayerPublicKey=${hasTrustLayer}"
            )
        }
    }

    // TapyrusWalletManager から現在の受取アドレスを 1 回取得
    LaunchedEffect(Unit) {
        val manager = WalletManager.getInstance(context)
        val addr = runCatching { manager.getNewAddress() }.getOrNull()
        walletAddress = addr
    }

    LaunchedEffect(registrationSnackbarMessage) {
        registrationSnackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            registrationSnackbarMessage = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {},
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = "共有相手を追加")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // アバターと「残高を表示」ボタンを同じ高さに
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BalanceVisibilityButton(
                    modifier = Modifier,
                    isVisible = isBalanceVisible,
                    onClick = {
                        scope.launch {
                            val manager = WalletManager.getInstance(context)
                            Log.d("BalanceDebug", "WalletManager class = ${manager::class.qualifiedName}")

                            runCatching {
                                Log.d("BalanceDebug", "sync start")
                                manager.sync()
                                Log.d("BalanceDebug", "sync end")

                                Log.d("BalanceDebug", "getBalance start")
                                val bal = manager.getBalance()
                                Log.d("BalanceDebug", "getBalance end: $bal")
                                bal
                            }.onSuccess { balance ->
                                balanceSat = balance
                                isBalanceVisible = !isBalanceVisible
                            }.onFailure { e ->
                                Log.e("HomeScreen", "残高取得に失敗しました", e)
                                Toast.makeText(context, "残高取得に失敗しました", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )

                // クリック領域も丸くする
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showAccountScreen = true }
                ) {
                    AccountAvatar(
                        name = accountName,
                        email = accountEmail,
                        modifier = Modifier
                    )
                }
            }

            if (isBalanceVisible) {
                Spacer(Modifier.height(16.dp))
                val balanceDisplay = balanceSat ?: 0uL  // ★ UIntではなくULongに合わせる
                BalanceSummaryCard(
                    balanceTitle = "Balance",
                    primaryAmount = balanceDisplay.toString(),
                    primaryUnit = "Token",
                    secondaryAmount = formatTpc(balanceDisplay),
                    secondaryUnit = "TPC"
                )
            }

            // 🔐 Tapyrus アドレス表示カード（Swift HomeView の currentAddress 表示に相当）
            walletAddress?.let { addr ->
                Spacer(Modifier.height(16.dp))
                WalletAddressCard(
                    address = addr,
                    modifier = Modifier
                )
            }

            Spacer(Modifier.height(16.dp))

            selectedEmailKey = null
            if (isUploading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "アップロード中... Gmailで送信します",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isRegistering) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "共有相手を登録しています...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (users.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "現在登録されている共有相手はいません",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val registeredEmailKeys = remember(emailKeys) { emailKeys.associateBy { it.email } }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(users, key = { it.id }) { user ->
                    val emailKey = registeredEmailKeys[user.email]
                    val hasRegisteredKey = emailKey != null &&
                            emailKey.derivedPublicKey.isNotBlank() &&
                            emailKey.trustLayerPublicKey.isNotBlank()
                    SwipeRevealUserRow(
                        user = user,
                        hasRegisteredKey = hasRegisteredKey,
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { userDao.deleteByName(user.name) }
                                snackbarHostState.showSnackbar("削除しました")
                            }
                        },
                        onShare = {
                            scope.launch {
                                val emailKey = withContext(Dispatchers.IO) {
                                    emailKeyDao.findByEmail(user.email)
                                }

                                if (emailKey == null) {
                                    val (trustLayerPublicKey, derivedPublicKey) = withContext(Dispatchers.IO) {
                                        resolveMyKeys(
                                            myPublicKeyDao,
                                            context
                                        )
                                    }
                                    val folderId = withContext(Dispatchers.IO) {
                                        val existingFolderId = user.folderIDFromMe
                                        if (existingFolderId != null) {
                                            existingFolderId
                                        } else {
                                            val created = DriveServiceHelper.createUserFolder(context, user.name)
                                            userDao.updateFolderIdByEmail(user.email, created)
                                            created
                                        }
                                    }
                                    val registrationUrl = PublicKeyLinkBuilder.build(
                                        email = accountEmail ?: user.email,
                                        derivedPublicKey = derivedPublicKey,
                                        trustLayerPublicKey = trustLayerPublicKey,
                                        folderId = folderId
                                    )

                                    Toast.makeText(
                                        context,
                                        "相手の公開鍵がまだ登録されていません",
                                        Toast.LENGTH_LONG
                                    ).show()

                                    EmailSender.sendPublicKeyRegistrationEmail(
                                        context = context,
                                        recipientEmail = user.email,
                                        registrationUrl = registrationUrl,
                                        senderEmail = accountEmail
                                    )
                                    return@launch
                                }

                                selectedUser = user
                                selectedEmailKey = emailKey
                                openFileLauncher.launch(Unit)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAccountScreen) {
        AccountScreen(
            name = accountName,
            email = accountEmail,
            tokenThreshold = tokenThreshold,
            sendFee = sendFee,
            onClose = { showAccountScreen = false },
            onTokenThresholdChange = { tokenThreshold = it },
            onSendFeeChange = { sendFee = it },
            onSignOutConfirmed = signOutAndNavigate
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("共有相手の登録") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        placeholder = { Text("テストユーザー1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addEmail,
                        onValueChange = { addEmail = it },
                        placeholder = { Text("example@example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("キャンセル") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (addName.isNotBlank() && addEmail.isNotBlank()) {
                            val name = addName.trim()
                            val email = addEmail.trim()
                            scope.launch {
                                isRegistering = true
                                val registrationUrl = runCatching {
                                    val folderId = withContext(Dispatchers.IO) {
                                        DriveServiceHelper.createUserFolder(context, name)
                                    }
                                    withContext(Dispatchers.IO) {
                                        userDao.upsertByEmail(
                                            UserEntity(
                                                name = name,
                                                email = email,
                                                folderIDFromMe = folderId,
                                                publicKeyHex = null
                                            )
                                        )
                                    }
                                    val (trustLayerPublicKey, derivedPublicKey) = withContext(Dispatchers.IO) {
                                        resolveMyKeys(
                                            myPublicKeyDao,
                                            context
                                        )
                                    }
                                    val updatedAt = withContext(Dispatchers.IO) {
                                        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                                            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
                                        }
                                        formatter.format(Date())
                                    }
                                    withContext(Dispatchers.IO) {
                                        DriveServiceHelper.createOrUpdatePublicKeyFile(
                                            context = context,
                                            parentFolderId = folderId,
                                            payload = DriveServiceHelper.PublicKeyPayload(
                                                ownerEmail = accountEmail ?: email,
                                                senderMasterPublicKeyHex = trustLayerPublicKey,
                                                senderDerivedPublicKeyHex = derivedPublicKey,
                                                trustLayerPublicKey = trustLayerPublicKey,
                                                updatedAt = updatedAt
                                            ),
                                            recipientEmail = email
                                        )
                                    }
                                    PublicKeyLinkBuilder.build(
                                        email = accountEmail ?: email,
                                        derivedPublicKey = derivedPublicKey,
                                        trustLayerPublicKey = trustLayerPublicKey,
                                        folderId = folderId
                                    )
                                }
                                isRegistering = false

                                registrationUrl.onSuccess { url ->
                                    addName = ""
                                    addEmail = ""
                                    showAddDialog = false
                                    snackbarHostState.showSnackbar("登録が完了しました")
                                    if (url != null) {
                                        EmailSender.sendPublicKeyRegistrationEmail(
                                            context = context,
                                            recipientEmail = email,
                                            registrationUrl = url,
                                            senderEmail = accountEmail
                                        )
                                        registrationSnackbarMessage = "公開鍵登録リンクを送信しました"
                                    }
                                }.onFailure { e ->
                                    Log.e("HomeScreen", "共有相手登録に失敗しました", e)
                                    snackbarHostState.showSnackbar("登録に失敗しました")
                                }
                            }
                        }
                    }
                ) { Text("登録") }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = PureWhite,
            iconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BalanceVisibilityButton(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSystemInDarkTheme()) {
        HomeScreenButtonColors.BalanceButtonBackgroundDark
    } else {
        HomeScreenButtonColors.BalanceButtonBackgroundLight
    }

    val (icon, label) =
        if (isVisible) Icons.Outlined.VisibilityOff to "残高を非表示"
        else Icons.Outlined.Visibility to "残高を表示"

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = HomeScreenButtonColors.BalanceButtonContent
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = HomeScreenButtonColors.BalanceButtonContent,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun BalanceSummaryCard(
    balanceTitle: String,
    primaryAmount: String,
    primaryUnit: String,
    secondaryAmount: String,
    secondaryUnit: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = balanceTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = primaryAmount,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = primaryUnit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "$secondaryAmount  $secondaryUnit",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatTpc(balance: ULong): String {
    val unit = 100_000_000u
    val whole = balance / unit
    val fraction = (balance % unit).toString().padStart(8, '0')
    return "$whole.$fraction"
}

/**
 * Tapyrus の現在の受取アドレスを表示するカード。
 * Swift HomeView で currentAddress を表示しているカードに相当。
 */
@Composable
private fun WalletAddressCard(
    address: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "My Tapyrus Address",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(address))
                        Toast.makeText(context, "アドレスをコピーしました", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "アドレスをコピー"
                    )
                }
            }
        }
    }
}

/* =========================================================
 * 左スワイプで削除(赤)/共有(青)
 * ========================================================= */
@Composable
private fun SwipeRevealUserRow(
    user: UserEntity,
    hasRegisteredKey: Boolean,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val corner = 16.dp
    val actionWidthDp = 160.dp
    val density = LocalContext.current.resources.displayMetrics.density
    val actionWidthPx = actionWidthDp.value * density

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(corner))
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 削除（赤）
            Box(
                modifier = Modifier
                    .width(actionWidthDp / 2)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    onDelete()
                    scope.launch { offsetX.animateTo(0f, tween(180)) }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
            // 共有（青）
            Box(
                modifier = Modifier
                    .width(actionWidthDp / 2)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    onShare()
                    scope.launch { offsetX.animateTo(0f, tween(180)) }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.IosShare,
                        contentDescription = "共有",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target =
                                if (abs(offsetX.value) > actionWidthPx * 0.3f) -actionWidthPx else 0f
                            scope.launch { offsetX.animateTo(target, tween(180)) }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(180)) } }
                    ) { _: PointerInputChange, dragAmount: Float ->
                        val newX = (offsetX.value + dragAmount).coerceIn(-actionWidthPx, 0f)
                        scope.launch { offsetX.snapTo(newX) }
                    }
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        user.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!hasRegisteredKey) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "相手の公開鍵がまだ登録されていません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Icon(
                    imageVector = if (hasRegisteredKey) Icons.Outlined.VpnKey else Icons.Outlined.WarningAmber,
                    contentDescription = if (hasRegisteredKey) "公開鍵登録済み" else "公開鍵未登録",
                    tint = if (hasRegisteredKey) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

private suspend fun resolveMyKeys(
    myPublicKeyDao: MyPublicKeyDao,
    context: android.content.Context
): Pair<String, String> {
    val wallet = KeyDerivation.getInstance(context)
    val existing = myPublicKeyDao.getPrimary()
    val trustLayer = existing?.trustLayerPublicKey
        ?: wallet.getCurrentPublicKeyHex("m/44'/0'/0'/0/0")
    val derived = existing?.derivedPublicKey
        ?: wallet.getCurrentPublicKeyHex("m/44'/0'/0'/0/1")

    if (existing == null || existing.trustLayerPublicKey != trustLayer || existing.derivedPublicKey != derived) {
        myPublicKeyDao.upsert(
            MyPublicKeyEntity(
                trustLayerPublicKey = trustLayer,
                derivedPublicKey = derived
            )
        )
    }

    return trustLayer to derived
}
