package com.example.sharefilebc

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.sharefilebc.ui.theme.ModalOverlay
import com.example.sharefilebc.ui.theme.PureWhite
import com.example.sharefilebc.ui.theme.rememberAvatarColors
import kotlin.math.abs
import com.example.sharefilebc.ui.theme.IosGroupedBG
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(
    name: String?,
    email: String?,
    tokenThreshold: Int,
    sendFee: Int,
    onClose: () -> Unit,
    onTokenThresholdChange: (Int) -> Unit,
    onSendFeeChange: (Int) -> Unit,
    onSignOutConfirmed: () -> Unit
) {
    // ✅ Dialog内でも正しく Context を取得する
    val context = LocalContext.current.applicationContext

    var showPublicKeys by remember { mutableStateOf(false) }
    var showThresholdPicker by remember { mutableStateOf(false) }
    var showSendFeePicker by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    // --- Wallet test states ---
    val scope = rememberCoroutineScope()
    var walletStatus by remember { mutableStateOf("未実行") }
    var walletAddress by remember { mutableStateOf("") }
    var walletBalance by remember { mutableStateOf<ULong?>(null) }
    var sendToAddress by remember { mutableStateOf("") }
    var lastTxId by remember { mutableStateOf("") }

    val screenBackground = accountScreenBackground()
    val cardBackground = accountCardBackground()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ───── ヘッダー（閉じる / アカウント） ─────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "アカウント",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Text(
                            text = "閉じる",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ───── アカウントカード ─────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackground)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccountAvatar(
                                name = name,
                                email = email,
                                sizeDp = 64
                            )

                            Spacer(Modifier.width(16.dp))

                            Column {
                                Text(
                                    text = name ?: "ゲスト",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = email ?: "dev.ymatsuo@gmail.com",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ───── 公開鍵一覧 ─────
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPublicKeys = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackground)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "公開鍵一覧",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ───── トークン設定 ─────
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "トークン設定",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(12.dp))

                    TokenSettingRow(
                        title = "トークン閾値",
                        value = "$tokenThreshold TPC",
                        onClick = { showThresholdPicker = true }
                    )

                    Spacer(Modifier.height(12.dp))

                    TokenSettingRow(
                        title = "送金量",
                        value = "$sendFee TPC",
                        onClick = { showSendFeePicker = true }
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ───── Wallet テスト ─────
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ウォレットテスト",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBackground)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = "状態: $walletStatus",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (walletAddress.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "受取アドレス:\n$walletAddress",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            walletBalance?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "残高: $it (sat相当)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (lastTxId.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "TxId: $lastTxId",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            walletStatus = "sync中..."
                                            runCatching {
                                                WalletManager.getInstance(context).sync()
                                            }.onSuccess {
                                                walletStatus = "sync完了"
                                            }.onFailure {
                                                walletStatus = "sync失敗: ${it.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Sync") }

                                Spacer(Modifier.width(10.dp))

                                Button(
                                    onClick = {
                                        scope.launch {
                                            walletStatus = "アドレス取得中..."
                                            runCatching {
                                                WalletManager.getInstance(context).getNewAddress()
                                            }.onSuccess { addr ->
                                                walletAddress = addr
                                                walletStatus = "アドレス取得完了"
                                            }.onFailure {
                                                walletStatus = "アドレス失敗: ${it.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Address") }
                            }

                            Spacer(Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        walletStatus = "残高取得中..."
                                        runCatching {
                                            WalletManager.getInstance(context).getBalance()
                                        }.onSuccess { bal ->
                                            walletBalance = bal
                                            walletStatus = "残高取得完了"
                                        }.onFailure {
                                            walletStatus = "残高失敗: ${it.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Balance") }

                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = sendToAddress,
                                onValueChange = { sendToAddress = it },
                                placeholder = { Text("送金先アドレス") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        walletStatus = "送金中..."
                                        lastTxId = ""
                                        val amountSat = sendFee.toULong()

                                        runCatching {
                                            WalletManager.getInstance(context)
                                                .transfer(sendToAddress.trim(), amountSat)
                                        }.onSuccess { txid ->
                                            lastTxId = txid
                                            walletStatus = "送金完了"
                                        }.onFailure {
                                            walletStatus = "送金失敗: ${it.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = sendToAddress.isNotBlank()
                            ) { Text("Send (amount=$sendFee)") }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ───── ONE-TIME TOKEN ─────
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ONE-TIME TOKEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTokenInput()
                }

                Spacer(Modifier.height(28.dp))

                // ───── サインアウトボタン（カード） ─────
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSignOutDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "サインアウト",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showPublicKeys) {
        PublicKeyListDialog(onClose = { showPublicKeys = false })
    }

    if (showThresholdPicker) {
        NumberPickerSheet(
            title = "トークン閾値",
            initialValue = tokenThreshold,
            range = 1..10,
            onDismiss = { showThresholdPicker = false },
            onConfirm = {
                onTokenThresholdChange(it)
                showThresholdPicker = false
            }
        )
    }

    if (showSendFeePicker) {
        NumberPickerSheet(
            title = "送金量",
            initialValue = sendFee,
            range = 1..10,
            onDismiss = { showSendFeePicker = false },
            onConfirm = {
                onSendFeeChange(it)
                showSendFeePicker = false
            }
        )
    }

    if (showSignOutDialog) {
        SignOutConfirmDialog(
            onDismiss = { showSignOutDialog = false },
            onConfirm = {
                showSignOutDialog = false
                onSignOutConfirmed()
            }
        )
    }
}

@Composable
private fun accountScreenBackground(): Color {
    val isDark = isSystemInDarkTheme()
    return if (isDark) MaterialTheme.colorScheme.background else IosGroupedBG
}

@Composable
private fun accountCardBackground(): Color {
    val isDark = isSystemInDarkTheme()
    return if (isDark) MaterialTheme.colorScheme.surface else PureWhite
}

@Composable
private fun TokenSettingRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accountCardBackground())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OutlinedTokenInput() {
    var tokenValue by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accountCardBackground())
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            OutlinedTextField(
                value = tokenValue,
                onValueChange = { tokenValue = it },
                placeholder = { Text("Enter token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { /* TODO: Submit token */ },
                modifier = Modifier.fillMaxWidth(),
                enabled = tokenValue.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Submit Token")
            }
        }
    }
}

@Composable
private fun PublicKeyListDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(accountScreenBackground())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Text(
                            text = "アカウント",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "公開鍵一覧",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.height(12.dp))

                val dummyKeys = listOf(
                    "sou.koga.for.business@gmail.com" to
                            "0258313a355bda7fd3a28060c048051a96882ae8cc5521f33f971285ea3f5ead9e\n160ppyut-1cqls9jb9ps-dqjxdvknaiiet",
                    "sou.koga@gmail.com" to
                            "020a06c3de30746ac8b0d372e2d7986ecabbf18d2640d3cf1812bbec0b4b012b\n1SChRMCvJ0a9ldRdmmxjFUM5A0ebHq0t"
                )

                dummyKeys.forEachIndexed { index, (title, body) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = accountCardBackground())
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 12.dp
                            )
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (index != dummyKeys.lastIndex) {
                        Spacer(Modifier.height(12.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberPickerSheet(
    title: String,
    initialValue: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var currentValue by remember { mutableStateOf(initialValue.coerceIn(range)) }
    val values = remember(range) { range.toList() }
    val pickerHeight = 200.dp
    val itemHeight = 48.dp
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(currentValue))
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ModalOverlay),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PureWhite,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("キャンセル", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { onConfirm(currentValue) }) {
                            Text("完了", color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(pickerHeight)
                    ) {
                        LazyColumn(
                            state = listState,
                            flingBehavior = flingBehavior,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                vertical = (pickerHeight - itemHeight) / 2
                            )
                        ) {
                            items(values.size) { index ->
                                val itemValue = values[index]
                                val distance = abs(itemValue - currentValue)
                                val alpha = when (distance) {
                                    0 -> 1f
                                    1 -> 0.5f
                                    else -> 0.3f
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(itemHeight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = itemValue.toString(),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                                        fontSize = 22.sp,
                                        fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(itemHeight)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                )
                        )
                    }

                    LaunchedEffect(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset
                    ) {
                        val viewportHeight =
                            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        if (viewportHeight <= 0) return@LaunchedEffect
                        val center =
                            listState.layoutInfo.viewportStartOffset + viewportHeight / 2
                        val closest = listState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                            val itemCenter = item.offset + item.size / 2
                            abs(itemCenter - center)
                        }
                        closest?.let { currentValue = values[it.index] }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignOutConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ModalOverlay),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.padding(horizontal = 32.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PureWhite
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "サインアウトの確認",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "アカウントからサインアウトしますか？",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "キャンセル",
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        TextButton(onClick = onConfirm) {
                            Text(
                                text = "サインアウト",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountAvatar(
    name: String?,
    email: String?,
    modifier: Modifier = Modifier,
    sizeDp: Int = 40
) {
    val initial = remember(name, email) { resolveAccountInitial(name, email) }
    val colorKey = email?.takeIf { it.isNotBlank() } ?: name.orEmpty()
    val avatarColors = rememberAvatarColors(colorKey)

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(avatarColors.background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = avatarColors.content
        )
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
