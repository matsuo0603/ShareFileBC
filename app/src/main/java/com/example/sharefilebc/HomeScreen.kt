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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.EmailKeyEntity
import com.example.sharefilebc.data.MyPublicKeyDao
import com.example.sharefilebc.data.MyPublicKeyEntity
import com.example.sharefilebc.data.SentShareEntity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    val walletSettingsManager = remember { WalletSettingsManager.getInstance(context) }

    // Stateとして保持するDBインスタンス（nullの間は未初期化）
    var dbState: AppDatabase? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        if (dbState == null) {
            dbState = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context)
            }
        }
    }

    if (dbState == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // 以降は non-null を保証
    val db = dbState!!
    val userDao = db.userDao()
    val emailKeyDao = db.emailKeyDao()
    val myPublicKeyDao = db.myPublicKeyDao()
    val sentShareDao = db.sentShareDao()

    val driveUploader = remember { DriveUploader(context) }
    var users by remember { mutableStateOf(listOf<UserEntity>()) }
    var emailKeys by remember { mutableStateOf(listOf<EmailKeyEntity>()) }
    var isUploading by remember { mutableStateOf(false) }
    // 共有処理中に表示するメッセージ（iOS版の体感に合わせて、トークン送金完了まで表示を維持する）
    var uploadingMessage by remember { mutableStateOf("ファイルを共有中...") }
    var isRegistering by remember { mutableStateOf(false) }
    var showAccountScreen by remember { mutableStateOf(false) }
    var tokenThreshold by remember { mutableStateOf(walletSettingsManager.getPaymentThreshold().toLong().toInt()) }
    var sendFee by remember { mutableStateOf(walletSettingsManager.getTokenTransferAmount().toLong().toInt()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addEmail by remember { mutableStateOf("") }
    var registrationSnackbarMessage by remember { mutableStateOf<String?>(null) }

    // ===== 共有相手削除の確認ダイアログ =====
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteUser by remember { mutableStateOf<UserEntity?>(null) }

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

    /**
     * ✅ 重要: 公開鍵リンク/公開鍵ファイルの emailParam(ownerEmail) は「送信者本人のメールアドレス」だけにする。
     *
     * ここが recipient のメールにすり替わると、iOS 側は「別人の鍵」として保存してしまい、
     * 結果的に iOS→Android の暗号化で “別の受信者の公開鍵” が選ばれ、Android 側で復号できず
     * AES-GCM の "GCMタグ不一致" (BadTag) になります。
     */
    fun requireSenderEmail(): String {
        val email = accountEmail
        if (email.isNullOrBlank()) {
            Toast.makeText(context, "Googleログイン情報が取得できません。再ログインしてください", Toast.LENGTH_LONG).show()
            throw IllegalStateException("GoogleSignIn accountEmail is null")
        }
        return email
    }
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

    // ===== 残高オーバーレイ用 State =====
    var showBalanceOverlay by remember { mutableStateOf(false) }
    var isBalanceLoading by remember { mutableStateOf(false) }
    var balanceSat by remember { mutableStateOf<ULong?>(null) }
    var isBalanceSyncing by remember { mutableStateOf(false) }

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
                uploadingMessage = "ファイルを共有中..."
                try {
                    val result = withContext(Dispatchers.IO) {
                        driveUploader.uploadFileAndRecordWithSharing(
                            fileUri = uri,
                            recipient = target,
                            recipientKey = recipientKey,
                            db = db
                        )
                    }

                    when (result) {
                        is UploadResult.Success -> {
                            val fileName = result.fileName
                            val fileId = result.fileId
                            val folderId = result.folderId
                            val nameMetaBase64 = result.nameMetaBase64
                            val myKeyEntity = withContext(Dispatchers.IO) { db.myPublicKeyDao().getPrimary() }
                            val senderTrustLayerPublicKey = myKeyEntity?.trustLayerPublicKey

                            if (senderTrustLayerPublicKey == null) {
                                Toast.makeText(
                                    context,
                                    "送信者の公開鍵（TrustLayer）を取得できませんでした",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                            val walletManager = WalletManager.getInstance(context)
                            val ws = WalletSettingsManager.getInstance(context)

                            // ✅ iOS(Swift)側との互換性を上げるため、UUIDは大文字表記に統一する
                            // - iOS→iOS で使われている shareID 表記に合わせる
                            // - P2Cアドレス生成・URLクエリ・DB保存で同じ文字列を必ず使う
                            val uuid = java.util.UUID.randomUUID().toString().uppercase()
                            val transferAmount = ws.getTokenTransferAmount()
                            val threshold = ws.getPaymentThreshold()

                            uploadingMessage = "ファイルを共有中...（トークン送金中）"
                            val refundAddress = withContext(Dispatchers.IO) {
                                runCatching {
                                    walletManager.getNewAddressWithPublicKey(
                                        colorId = Constants.Strings.tokenColorId
                                    ).first
                                }.getOrNull()
                            }

                            if (refundAddress == null) {
                                Toast.makeText(
                                    context,
                                    "返金用アドレスの生成に失敗しました",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                            val p2cAddress = withContext(Dispatchers.IO) {
                                runCatching {
                                    walletManager.generateP2CAddress(
                                        publicKey = recipientKey.trustLayerPublicKey,
                                        contract = uuid,
                                        colorId = Constants.Strings.tokenColorId
                                    )
                                }.getOrNull()
                            }

                            if (p2cAddress == null) {
                                Toast.makeText(
                                    context,
                                    "P2Cアドレスの生成に失敗しました",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                            val txid = withContext(Dispatchers.IO) {
                                runCatching {
                                    walletManager.transferToken(
                                        toAddress = p2cAddress,
                                        amount = transferAmount,
                                        colorId = Constants.Strings.tokenColorId,
                                        utxos = emptyList()   // ←必要なら後でUTXOを渡す
                                    )
                                }.getOrNull()
                            }

                            if (txid == null) {
                                Toast.makeText(
                                    context,
                                    "トークン送金に失敗しました",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                            withContext(Dispatchers.IO) {
                                runCatching { walletManager.sync() }
                                    .onFailure { Log.w("HomeScreen", "⚠️ Wallet sync failed", it) }
                            }

                            withContext(Dispatchers.IO) {
                                sentShareDao.insert(
                                    SentShareEntity(
                                        fileId = fileId,
                                        folderId = folderId,
                                        recipientEmail = target.email,
                                        createdAt = nowIsoString(),
                                        threshold = threshold.toLong(),
                                        senderAddress = refundAddress,
                                        status = "PAID"
                                    )
                                )
                            }

                            uploadingMessage = "ファイルを共有中...（メール送信中）"
                            EmailSender.sendEmailWithDriveLink(
                                context = context,
                                recipientEmail = target.email,
                                recipientName = target.name,
                                fileName = fileName,
                                folderId = folderId,
                                fileId = fileId,
                                senderPublicKeyHex = senderTrustLayerPublicKey,
                                threshold = threshold,
                                senderAddress = refundAddress,
                                uuid = uuid,
                                txid = txid,
                                nameMetaBase64 = nameMetaBase64
                            )

                            Toast.makeText(
                                context,
                                "ファイル共有とトークン送金が完了しました",
                                Toast.LENGTH_LONG
                            ).show()
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
                } finally {
                    isUploading = false
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
                "鍵マーク判定: email=${user.email} derivedPublicKey=$hasDerived trustLayerPublicKey=$hasTrustLayer"
            )
        }
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
            ) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = "Add")
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
                    isVisible = showBalanceOverlay,
                    onClick = {
                        if (!showBalanceOverlay) {
                            // まずオーバーレイを出してから、ローカル残高を取得して表示
                            showBalanceOverlay = true
                            scope.launch {
                                isBalanceLoading = true
                                val manager = WalletManager.getInstance(context)
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        manager.initializeIfNeeded()
                                        manager.getBalance(colorId = Constants.Strings.tokenColorId)
                                    }
                                }.onSuccess { balance ->
                                    balanceSat = balance
                                }.onFailure { e ->
                                    Log.e("HomeScreen", "残高取得に失敗しました", e)
                                    Toast.makeText(context, "残高取得に失敗しました", Toast.LENGTH_LONG).show()
                                }
                                isBalanceLoading = false
                            }
                        } else {
                            showBalanceOverlay = false
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

            selectedEmailKey = null
            if (isUploading) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    uploadingMessage,
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
                            pendingDeleteUser = user
                            showDeleteConfirm = true
                        },
                        onShare = {
                            scope.launch {
                                val key = withContext(Dispatchers.IO) { emailKeyDao.findByEmail(user.email) }
                                if (key == null) {
                                    val (trustLayerPublicKey, derivedPublicKey) = withContext(Dispatchers.IO) {
                                        resolveMyKeys(myPublicKeyDao, context)
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
                                        email = requireSenderEmail(),
                                        derivedPublicKey = derivedPublicKey,
                                        trustLayerPublicKey = trustLayerPublicKey,
                                        folderId = folderId
                                    )

                                    // 切り分け用ログ：iOSに送った公開鍵リンクの中身
                                    runCatching {
                                        val kd = KeyDerivation.getInstance(context)
                                        val myPub = kd.getCurrentPublicKeyHex()
                                        val km = KeyManager.getInstance(context)
                                        Log.d(
                                            "PUBKEY_DEBUG",
                                            "[sendPubKeyLink:onShare] to=${user.email} emailParam=${requireSenderEmail()} " +
                                                    "derivedPublicKey=$derivedPublicKey trustLayerPublicKey=$trustLayerPublicKey folderId=$folderId " +
                                                    "myDerivedPub(m/44'/0'/0'/0/0)=$myPub masterFp=${km.getMasterXprvFingerprintOrNull()} url=$registrationUrl"
                                        )
                                    }

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
                                selectedEmailKey = key
                                openFileLauncher.launch(Unit)
                            }
                        }
                    )
                }
            }
        }
    }

    // ===== 共有相手削除 確認ダイアログ（iOS風） =====
    if (showDeleteConfirm) {
        val target = pendingDeleteUser
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                pendingDeleteUser = null
            },
            title = {
                Text(
                    text = "この共有相手を削除しますか？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "この操作は元に戻せません。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        pendingDeleteUser = null
                    }
                ) {
                    Text("キャンセル")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val u = target ?: return@TextButton
                        showDeleteConfirm = false
                        pendingDeleteUser = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // email ではなく name で削除している（既存仕様を維持）
                                userDao.deleteByName(u.name)
                            }
                            snackbarHostState.showSnackbar("削除しました")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFD32F2F)
                    )
                ) {
                    Text("削除")
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = Color.White,
            tonalElevation = 6.dp
        )
    }

    // ===== 残高オーバーレイ（お手本のUI）=====
    if (showBalanceOverlay) {
        BalanceOverlayDialog(
            balanceSat = balanceSat,
            isLoading = isBalanceLoading,
            isRefreshing = isBalanceSyncing,
            onDismiss = { showBalanceOverlay = false },
            onRefresh = {
                if (isBalanceSyncing) return@BalanceOverlayDialog
                scope.launch {
                    isBalanceSyncing = true
                    val manager = WalletManager.getInstance(context)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            manager.sync()
                            manager.getBalance(colorId = Constants.Strings.tokenColorId)
                        }
                    }.onSuccess { balance ->
                        balanceSat = balance
                    }.onFailure { e ->
                        Log.e("HomeScreen", "残高更新に失敗しました", e)
                        Toast.makeText(context, "残高更新に失敗しました", Toast.LENGTH_LONG).show()
                    }
                    isBalanceSyncing = false
                }
            }
        )
    }

    if (showAccountScreen) {
        AccountScreen(
            name = accountName,
            email = accountEmail,
            tokenThreshold = tokenThreshold,
            sendFee = sendFee,
            onClose = { showAccountScreen = false },
            onTokenThresholdChange = {
                tokenThreshold = it
                walletSettingsManager.setPaymentThreshold(it.toLong())
            },
            onSendFeeChange = {
                sendFee = it
                walletSettingsManager.setTokenTransferAmount(it.toLong())
            },
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
                                        resolveMyKeys(myPublicKeyDao, context)
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
                                                ownerEmail = requireSenderEmail(),
                                                senderMasterPublicKeyHex = trustLayerPublicKey,
                                                senderDerivedPublicKeyHex = derivedPublicKey,
                                                trustLayerPublicKey = trustLayerPublicKey,
                                                updatedAt = updatedAt
                                            ),
                                            recipientEmail = email
                                        )
                                    }
                                    PublicKeyLinkBuilder.build(
                                        email = requireSenderEmail(),
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
                                        // 切り分け用ログ：新規登録時に送る公開鍵リンクの中身
                                        runCatching {
                                            val parsed = PublicKeyLinkBuilder.parse(android.net.Uri.parse(url))
                                            val kd = KeyDerivation.getInstance(context)
                                            val myPub = kd.getCurrentPublicKeyHex()
                                            val km = KeyManager.getInstance(context)
                                            Log.d(
                                                "PUBKEY_DEBUG",
                                                "[sendPubKeyLink:onRegister] to=${email} emailParam=${requireSenderEmail()} " +
                                                        "derivedPublicKey=${parsed?.derivedPublicKey} trustLayerPublicKey=${parsed?.trustLayerPublicKey} folderId=${parsed?.folderId} " +
                                                        "myDerivedPub(m/44'/0'/0'/0/0)=$myPub masterFp=${km.getMasterXprvFingerprintOrNull()} url=$url"
                                            )
                                        }
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

private fun formatTpc(balance: ULong): String {
    val unit = 100_000_000u
    val whole = balance / unit
    val fraction = (balance % unit).toString().padStart(8, '0')
    return "$whole.$fraction"
}

/**
 * 残高を画面中央に表示するオーバーレイ。
 * 共有相手登録ダイアログと同じ「背景を少し灰色にして中央にカード」を出すUI。
 */
@Composable
private fun BalanceOverlayDialog(
    balanceSat: ULong?,
    isLoading: Boolean,
    isRefreshing: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val walletManager = remember { WalletManager.getInstance(context) }

    // TPC残高（sat単位）をこのダイアログ内で取得して保持
    var tpcSat by remember { mutableStateOf<ULong?>(null) }
    var isTpcLoading by remember { mutableStateOf(false) }

    suspend fun loadTpcBalance() {
        isTpcLoading = true
        val result = runCatching {
            withContext(Dispatchers.IO) {
                // colorId = null を「TPC」として扱う実装になっている前提
                walletManager.getBalance(colorId = null)
            }
        }
        tpcSat = result.getOrNull()
        isTpcLoading = false
    }

    // 初回表示時にTPCを取得
    LaunchedEffect(Unit) {
        loadTpcBalance()
    }

    // 「残高を更新」後（isRefreshingがfalseに戻ったタイミング）でTPCも取り直す
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            loadTpcBalance()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .offset(y = (-80).dp)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val interactionSource = remember { MutableInteractionSource() }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = PureWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Balance",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (isLoading && balanceSat == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        // ===== TOKEN =====
                        val tokenDisplay = balanceSat ?: 0uL
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = tokenDisplay.toString(),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Token",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFFE0E0E0))
                        )
                        Spacer(Modifier.height(6.dp))

                        // ===== TPC =====
                        if (isTpcLoading && tpcSat == null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .height(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "TPC 取得中...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val tpcDisplay = tpcSat ?: 0uL
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = formatTpc(tpcDisplay),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "TPC",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Button(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("残高を更新")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "残高を更新"
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("残高を更新")
                        }
                    }
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

    val walletManager = WalletManager.getInstance(context)
    walletManager.initializeIfNeeded()
    // ✅ 念のため、ここで必ずmy_public_keysを復旧/更新してから読み取る
    walletManager.ensureMyPublicKeysPersisted()

    val existing = myPublicKeyDao.getPrimary()
        ?: run {
            // それでも無い場合は、固定パスから直接導出して保存してから返す
            val kd = KeyDerivation.getInstance(context)
            val trust = kd.getCurrentPublicKeyHex(KeyDerivation.TRUST_LAYER_PATH)
            val derived = kd.getCurrentPublicKeyHex(KeyDerivation.DERIVED_KEY_PATH)
            myPublicKeyDao.upsert(MyPublicKeyEntity(id = 1, trustLayerPublicKey = trust, derivedPublicKey = derived))
            android.util.Log.w("HomeScreen", "⚠️ my_public_keys missing -> recovered by KeyDerivation (trust=${trust.take(16)}... derived=${derived.take(16)}...)")
            myPublicKeyDao.getPrimary() ?: throw IllegalStateException("MyPublicKeyEntity still missing after recovery")
        }

    val trustLayer = existing.trustLayerPublicKey
        ?: throw IllegalStateException("trustLayerPublicKey is null")

    val derived = existing.derivedPublicKey
        ?: throw IllegalStateException("derivedPublicKey is null")

    return trustLayer to derived
}


private fun nowIsoString(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
    formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    return formatter.format(Date())
}
