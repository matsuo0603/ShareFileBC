@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sharefilebc

import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.ui.theme.HomeScreenButtonColors
import com.example.sharefilebc.ui.theme.rememberAvatarColors
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.compose.material.icons.outlined.VisibilityOff

/**
 * 共有相手の登録・一覧・削除・共有送信を行う画面。
 * - 背景は白で統一
 * - 右上にGoogleアカウントの頭文字アバターを表示
 * - 各行を左スワイプすると「削除(赤)」「共有(青)」ボタンを表示
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

    var users by remember { mutableStateOf(listOf<UserEntity>()) }
    var isUploading by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addEmail by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    val account = remember { GoogleSignIn.getLastSignedInAccount(context) }
    val accountName = account?.displayName
    val accountEmail = account?.email
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
                        recipientName = target.name,
                        db = db
                    )
                    withContext(Dispatchers.Main) {
                        isUploading = false
                        if (result == null) {
                            Toast.makeText(
                                context,
                                "アップロードに失敗しました（Google認証を確認）",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val (fileName, _, folderId) = result
                            EmailSender.sendEmailWithDriveLink(
                                context = context,
                                recipientEmail = target.email,
                                fileName = fileName,
                                folderId = folderId
                            )
                        }
                    }
                }
            }
            selectedUser = null
        }
    )

    LaunchedEffect(Unit) {
        userDao.getAll().collectLatest { list -> users = list }
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
            // ★ ここを Row にして同じ高さに並べる
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BalanceVisibilityButton(
                    modifier = Modifier,
                    isVisible = isBalanceVisible,
                    onClick = {
                        isBalanceVisible = !isBalanceVisible
                    }
                )
                AccountInitialAvatar(
                    name = accountName,
                    email = accountEmail
                )
            }

            if (isBalanceVisible) {
                Spacer(Modifier.height(16.dp))
                // 数値はアプリ統合後に連携先から受け取ることを想定
                BalanceSummaryCard(
                    balanceTitle = "Balance",
                    primaryAmount = "0",
                    primaryUnit = "Token",
                    secondaryAmount = "0.00000000",
                    secondaryUnit = "TPC"
                )
            }

            Spacer(Modifier.height(16.dp))
            // ↓このあと isUploading の if が続く

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
                            val entity = UserEntity(
                                name = addName.trim(),
                                email = addEmail.trim()
                            )
                            scope.launch {
                                withContext(Dispatchers.IO) { userDao.insert(entity) }
                                addName = ""; addEmail = ""
                                showAddDialog = false
                                snackbarHostState.showSnackbar("登録が完了しました")
                            }
                        }
                    }
                ) { Text("登録") }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
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
                        change.consume()
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

/* ---------------- 右上：頭文字アバター ---------------- */
@Composable
private fun AccountInitialAvatar(
    name: String?,
    email: String?,
    modifier: Modifier = Modifier,
    sizeDp: Int = 40
) {
    val initial = remember(name, email) { resolveAccountInitial(name, email) }
    val colorKey = email?.takeIf { it.isNotBlank() } ?: name.orEmpty()
    val avatarColors = rememberAvatarColors(colorKey)

    Surface(
        modifier = modifier.size(sizeDp.dp),
        shape = CircleShape,
        color = avatarColors.background,
        contentColor = avatarColors.content
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

private fun resolveAccountInitial(name: String?, email: String?): String {
    val fromName = name?.let { extractInitialFromName(it) }
    if (!fromName.isNullOrBlank()) return fromName

    val fromEmail = extractInitialFromEmail(email)
    if (!fromEmail.isNullOrBlank()) return fromEmail

    return "?"
}

private fun extractInitialFromName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val cjkChar = trimmed.reversed().firstOrNull { it.isCjkIdeograph() }
    if (cjkChar != null) return cjkChar.toString()

    // ★ 修正ポイント: "\s+" -> "\\s+" に変更（Unsupported escape sequence 対策）
    val tokens = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    val target = tokens.lastOrNull() ?: trimmed
    val letter = target.firstOrNull { it.isLetterOrDigit() }
    return letter?.titlecaseChar()?.toString()
}

private fun extractInitialFromEmail(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    val localPart = trimmed.substringBefore('@')
    val letter = localPart.firstOrNull { it.isLetterOrDigit() }
    return letter?.titlecaseChar()?.toString()
}

private fun Char.isCjkIdeograph(): Boolean {
    val codePoint = this.code
    val script = Character.UnicodeScript.of(codePoint)
    return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
}
