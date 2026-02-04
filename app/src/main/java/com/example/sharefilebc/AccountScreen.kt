package com.example.sharefilebc

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.network.PublicKeyApiClient
import com.example.sharefilebc.network.TokenSubmitResult
import com.example.sharefilebc.ui.theme.IosGroupedBG
import com.example.sharefilebc.ui.theme.ModalOverlay
import com.example.sharefilebc.ui.theme.PureWhite
import com.example.sharefilebc.ui.theme.rememberAvatarColors
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val context = LocalContext.current.applicationContext
    val db = AppDatabase.getDatabase(context)
    val accountViewModel: AccountViewModel = viewModel(
        factory = AccountViewModelFactory(
            db.emailKeyDao(),
            db.blockedSenderDao(),
            WalletSettingsManager.getInstance(context)
        )
    )

    var showPublicKeys by remember { mutableStateOf(false) }
    var showBlockedSenders by remember { mutableStateOf(false) }
    var showNetworkSettings by remember { mutableStateOf(false) }
    var showThresholdPicker by remember { mutableStateOf(false) }
    var showSendFeePicker by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

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
                // ───── ヘッダー ─────
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                SectionRow(
                    title = "公開鍵一覧",
                    onClick = { showPublicKeys = true }
                )

                Spacer(Modifier.height(20.dp))

                // ───── 返金拒否リスト ─────
                SectionRow(
                    title = "返金拒否リスト",
                    onClick = { showBlockedSenders = true }
                )

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
                        value = "$tokenThreshold TOKEN",
                        onClick = { showThresholdPicker = true }
                    )

                    Spacer(Modifier.height(12.dp))

                    TokenSettingRow(
                        title = "送金量",
                        value = "$sendFee TOKEN",
                        onClick = { showSendFeePicker = true }
                    )
                }

                Spacer(Modifier.height(20.dp))

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

                Spacer(Modifier.height(20.dp))

                // ───── 環境設定 ─────
                SectionRow(
                    title = "環境設定",
                    onClick = { showNetworkSettings = true }
                )

                Spacer(Modifier.height(28.dp))

                // ───── サインアウト ─────
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
        PublicKeyListDialog(
            onClose = { showPublicKeys = false },
            viewModel = accountViewModel
        )
    }

    if (showBlockedSenders) {
        BlockedSendersDialog(
            onClose = { showBlockedSenders = false },
            viewModel = accountViewModel
        )
    }

    if (showNetworkSettings) {
        NetworkSettingsDialog(
            onClose = { showNetworkSettings = false },
            viewModel = accountViewModel,
            onConfigApplied = {
                WalletManager.getInstance(context).resetWallet()
                showNetworkSettings = false
            }
        )
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
private fun PublicKeyListDialog(
    onClose: () -> Unit,
    viewModel: AccountViewModel
) {
    val publicKeys by viewModel.publicKeys.collectAsState(initial = emptyList())
    val isLoading by viewModel.isLoading.collectAsState(initial = true)

    LaunchedEffect(Unit) {
        viewModel.loadPublicKeys()
    }

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
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
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

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    publicKeys.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "登録された公開鍵がありません",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(publicKeys, key = { it.email }) { item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = accountCardBackground()
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 12.dp
                                        )
                                    ) {
                                        // email（太字）
                                        Text(
                                            item.email,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )

                                        Spacer(Modifier.height(6.dp))

                                        // ✅ A（手本）: trustLayerPublicKey を灰
                                        if (item.trustLayerPublicKey.isNotBlank()) {
                                            Text(
                                                text = item.trustLayerPublicKey,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // ✅ A（手本）: derivedPublicKey を青
                                        if (item.derivedPublicKey.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = item.derivedPublicKey,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedSendersDialog(
    onClose: () -> Unit,
    viewModel: AccountViewModel
) {
    val blockedSenders by viewModel.blockedSenders.collectAsState(initial = emptyList())
    var emailInput by remember { mutableStateOf("") }
    var reasonInput by remember { mutableStateOf("") }

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
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
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
                        text = "返金拒否リスト",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = accountCardBackground())
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "送信者を追加",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            placeholder = { Text("メールアドレス") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = reasonInput,
                            onValueChange = { reasonInput = it },
                            placeholder = { Text("拒否理由（任意）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                viewModel.addBlockedSender(emailInput, reasonInput)
                                emailInput = ""
                                reasonInput = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = emailInput.isNotBlank(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("追加")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (blockedSenders.isEmpty()) {
                    Text(
                        text = "登録された拒否リストはありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(blockedSenders, key = { it.email }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = accountCardBackground()
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp
                                    )
                                ) {
                                    Text(
                                        text = item.email,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!item.reason.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = item.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "登録元: ${item.source}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { viewModel.removeBlockedSender(item.email) }) {
                                            Text("解除", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkSettingsDialog(
    onClose: () -> Unit,
    viewModel: AccountViewModel,
    onConfigApplied: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val config by viewModel.networkConfig.collectAsState()
    var selectedMode by remember { mutableStateOf(config.networkMode) }
    var networkIdInput by remember { mutableStateOf(config.networkId.toString()) }
    var genesisHashInput by remember { mutableStateOf(config.genesisHash) }
    var esploraUrlInput by remember { mutableStateOf(config.esploraUrl) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showEditControls by remember { mutableStateOf(false) }
    // Kotlin 1.9+ : Enum.values() より Enum.entries 推奨
    val networkModes = remember { com.chaintope.tapyrus.wallet.Network.entries }
    val tokenColorId = Constants.Strings.tokenColorId

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
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
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
                        text = "環境設定",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = { showEditControls = !showEditControls },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text(
                            text = "変更",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = accountCardBackground())
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "システム設定",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "カラーID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tokenColorId,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "ネットワーク設定",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "index サーバURL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = config.esploraUrl,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "ネットワークモード",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = config.networkMode.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "ネットワークID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = config.networkId.toString(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (showEditControls) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "編集",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Box {
                                Button(
                                    onClick = { showModeMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Network: ${selectedMode.name}")
                                }
                                DropdownMenu(
                                    expanded = showModeMenu,
                                    onDismissRequest = { showModeMenu = false }
                                ) {
                                    networkModes.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.name) },
                                            onClick = {
                                                selectedMode = mode
                                                showModeMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = networkIdInput,
                                onValueChange = { networkIdInput = it },
                                placeholder = { Text("networkId") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = genesisHashInput,
                                onValueChange = { genesisHashInput = it },
                                placeholder = { Text("genesisHash") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = esploraUrlInput,
                                onValueChange = { esploraUrlInput = it },
                                placeholder = { Text("esploraUrl") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val newConfig = WalletNetworkConfig(
                                        preset = WalletNetworkPreset.CUSTOM,
                                        networkMode = selectedMode,
                                        networkId = networkIdInput.toUIntOrNull() ?: config.networkId,
                                        genesisHash = genesisHashInput.trim(),
                                        esploraUrl = esploraUrlInput.trim()
                                    )
                                    viewModel.updateNetworkConfig(newConfig)
                                    onConfigApplied()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("保存")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "切替後はウォレット同期が必要です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
private fun SectionRow(
    title: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accountCardBackground())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
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
}

@Composable
private fun OutlinedTokenInput() {
    var tokenValue by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val api = remember { PublicKeyApiClient() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accountCardBackground())
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            OutlinedTextField(
                value = tokenValue,
                onValueChange = {
                    tokenValue = it
                    if (statusMessage.isNotBlank()) {
                        statusMessage = ""
                    }
                },
                placeholder = { Text("Enter token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val trimmed = tokenValue.trim()
                    if (trimmed.isBlank()) return@Button

                    isSubmitting = true
                    statusMessage = ""
                    scope.launch {
                        api.submitOneTimeToken(trimmed)
                            .onSuccess { result ->
                                statusMessage = when (result) {
                                    TokenSubmitResult.Success -> "送信しました"
                                    TokenSubmitResult.NotFound -> "トークンが存在しません"
                                    is TokenSubmitResult.Failed -> {
                                        "送信に失敗しました（HTTP ${result.code}）"
                                    }
                                }
                            }
                            .onFailure {
                                statusMessage = "通信に失敗しました"
                            }
                        isSubmitting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = tokenValue.isNotBlank() && !isSubmitting,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Submit Token")
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        statusMessage.contains("送信しました") -> Color(0xFF2E7D32)
                        statusMessage.contains("失敗") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
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
                            contentPadding = PaddingValues(
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
                colors = CardDefaults.cardColors(containerColor = PureWhite)
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
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("キャンセル", color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = onConfirm) {
                            Text(
                                text = "サインアウト",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
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