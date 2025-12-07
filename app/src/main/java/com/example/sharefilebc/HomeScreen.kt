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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.managers.TapyrusWalletManager
import com.example.sharefilebc.network.PublicKeyApiClient
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
    val driveUploader = remember { DriveUploader(context) }
    val publicKeyApi = remember { PublicKeyApiClient() }
    var users by remember { mutableStateOf(listOf<UserEntity>()) }
    var isUploading by remember { mutableStateOf(false) }
    var showAccountScreen by remember { mutableStateOf(false) }
    var tokenThreshold by remember { mutableStateOf(1) }
    var sendFee by remember { mutableStateOf(1) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addEmail by remember { mutableStateOf("") }

    // 🔐 Tapyrus ウォレットの現在アドレス（Swift版 HomeView の currentAddress 相当）
    var walletAddress by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

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
    var selectedUser by remember { mutableStateOf<UserEntity?>(null) }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = FilePickerContract(),
        onResult = { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            val target = selectedUser ?: return@rememberLauncherForActivityResult
            scope.launch {
                isUploading = true
                withContext(Dispatchers.IO) {
                    val result = driveUploader.uploadFileAndRecordWithSharing(
                        fileUri = uri,
                        recipient = target,
                        db = db
                    )
                    withContext(Dispatchers.Main) {
                        isUploading = false
                        when (result) {
                            is UploadResult.Success -> {
                                val (fileName, _, folderId) = result
                                EmailSender.sendEmailWithDriveLink(
                                    context = context,
                                    recipientEmail = target.email,
                                    fileName = fileName,
                                    folderId = folderId
                                )
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

    // TapyrusWalletManager から現在の受取アドレスを 1 回取得
    LaunchedEffect(Unit) {
        val manager = TapyrusWalletManager.getInstance(context)
        val addr = try {
            manager.getCurrentAddress()
        } catch (e: Exception) {
            null
        }
        walletAddress = addr
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
                    onClick = { isBalanceVisible = !isBalanceVisible }
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
                BalanceSummaryCard(
                    balanceTitle = "Balance",
                    primaryAmount = "0",
                    primaryUnit = "Token",
                    secondaryAmount = "0.00000000",
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

            if (users.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "現在登録されている共有相手はいません",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(users, key = { it.id }) { user ->
                    SwipeRevealUserRow(
                        user = user,
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { userDao.deleteByName(user.name) }
                                snackbarHostState.showSnackbar("削除しました")
                            }
                        },
                        onShare = {
                            selectedUser = user
                            openFileLauncher.launch(Unit)
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
                                val userId = withContext(Dispatchers.IO) {
                                    userDao.insert(
                                        UserEntity(
                                            name = name,
                                            email = email,
                                            publicKeyHex = null
                                        )
                                    ).toInt()
                                }
                                addName = ""
                                addEmail = ""
                                showAddDialog = false
                                snackbarHostState.showSnackbar("登録が完了しました")
                                val fetchResult = withContext(Dispatchers.IO) {
                                    publicKeyApi.fetchPublicKey(email)
                                }
                                fetchResult.onSuccess { key ->
                                    if (key != null) {
                                        withContext(Dispatchers.IO) {
                                            userDao.updatePublicKey(userId, key)
                                        }
                                        snackbarHostState.showSnackbar("公開鍵を自動取得しました")
                                    }
                                }.onFailure { e ->
                                    Log.e("HomeScreen", "公開鍵の取得に失敗しました", e)
                                }
                            }
                        }
                    }
                ) { Text("登録") }
            },
            shape = RoundedCornerShape(24.dp),

            // Material3 AlertDialog は colors パラメータを持たないので、
            // 個別プロパティで色を指定する
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
                    ) { change: PointerInputChange, dragAmount: Float ->
                        // change.consume() は不要なので削除
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
                }
            }
        }
    }
}
