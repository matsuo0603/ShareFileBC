package com.example.sharefilebc.ui

import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.sharefilebc.ui.theme.rememberAvatarColors

@OptIn(ExperimentalMaterial3Api::class)
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
    var showPublicKeys by remember { mutableStateOf(false) }
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
                // ───── ヘッダー（閉じる / アカウント） ─────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 中央タイトル
                    Text(
                        text = "アカウント",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    // 左上の「閉じる」
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
                        value = "$tokenThreshold 1 TPC".replace("1 1", "1"),
                        onClick = { showThresholdPicker = true }
                    )

                    Spacer(Modifier.height(12.dp))

                    TokenSettingRow(
                        title = "送金量",
                        value = "$sendFee 1 TPC".replace("1 1", "1"),
                        onClick = { showSendFeePicker = true }
                    )
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
    return if (isDark) {
        MaterialTheme.colorScheme.background
    } else {
        Color(0xFFF2F2F7) // iOS風の薄いグレー
    }
}

@Composable
private fun accountCardBackground(): Color {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        MaterialTheme.colorScheme.surface
    } else {
        Color.White
    }
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
                // ─── ヘッダー（アカウント / 公開鍵一覧） ───
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 左「アカウント」（戻る）
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Text(
                            text = "アカウント",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 中央タイトル
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberPickerSheet(
    title: String,
    initialValue: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var currentValue by remember { mutableStateOf(initialValue.coerceIn(range)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = accountCardBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { onConfirm(currentValue) }) { Text("完了") }
            }

            Spacer(Modifier.height(12.dp))

            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    NumberPicker(context).apply {
                        minValue = range.first
                        maxValue = range.last
                        value = currentValue
                        wrapSelectorWheel = false
                    }
                },
                update = { picker ->
                    picker.value = currentValue
                    picker.setOnValueChangedListener { _, _, newVal ->
                        currentValue = newVal
                    }
                }
            )
        }
    }
}

@Composable
private fun SignOutConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // カスタムダイアログ：左右に余白を持たせて中央寄せ
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.padding(horizontal = 32.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE5F0FF)
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
