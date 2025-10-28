@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sharefilebc

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.UserEntity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Text(
                        text = "ShareFileBC",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    AccountInitialAvatar(
                        name = accountName,
                        email = accountEmail
                    )
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
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
                    style = MaterialTheme.typography.bodyLarge, // あなたの命名に合わせて必要なら typography に
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
                        },
                        onOpen = {
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

/* =========================================================
 * 左スワイプで削除(赤)/共有(青)
 * ========================================================= */
@Composable
private fun SwipeRevealUserRow(
    user: UserEntity,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
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
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            onClick = {
                if (abs(offsetX.value) > 1f) {
                    scope.launch { offsetX.animateTo(0f, tween(180)) }
                } else {
                    onOpen()
                }
            }
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
                Icon(
                    imageVector = Icons.Outlined.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    val initial = when {
        !name.isNullOrBlank() -> name.trim().first().uppercase()
        !email.isNullOrBlank() -> email.trim().first().uppercase()
        else -> "?"
    }

    val bg = MaterialTheme.colorScheme.secondaryContainer
    val fg = MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = modifier.size(sizeDp.dp),
        shape = CircleShape,
        color = bg,
        contentColor = fg
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }
}
