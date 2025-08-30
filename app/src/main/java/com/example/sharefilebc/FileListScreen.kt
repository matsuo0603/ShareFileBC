package com.example.sharefilebc

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.DriveFileInfo
import com.example.sharefilebc.data.FolderStructure
import kotlinx.coroutines.launch

/**
 * フォルダもファイルも表示する版。
 * - 先頭に現在のフォルダ名を表示
 * - フォルダはタップで子フォルダを取得して“中へ”
 * - 上へ戻るボタン（パンくず1段戻し）
 * - ファイルはそのまま downloadFile() を実行
 * - 表示順は「フォルダ → ファイル（削除予定時刻順）」に統一
 */
@Composable
fun FileListScreen(folderStructure: FolderStructure) {
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val scope = rememberCoroutineScope()

    // 階層ナビ用スタック（最初の引数の構造をルートにする）
    var stack by remember { mutableStateOf(listOf(folderStructure)) }
    var isLoading by remember { mutableStateOf(false) }

    val current = stack.last()

    Column(Modifier.fillMaxSize()) {
        // ヘッダ（パンくず戻る + カレント名）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stack.size > 1) {
                Button(
                    onClick = { stack = stack.dropLast(1) },
                    modifier = Modifier.padding(end = 12.dp)
                ) { Text("← 上へ") }
            }
            Text(
                text = "📁 ${current.folderName}",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (current.files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("このフォルダには項目がありません。")
            }
            return@Column
        }

        // 表示用コレクション
        val folders = remember(current) {
            current.files.filter {
                it.isFolder && !Regex("""\d{4}-\d{2}-\d{2}""").matches(it.name)
            }
        }
        // 既存仕様を踏襲してファイルは削除予定時刻順
        val files = remember(current) { current.files.filter { !it.isFolder }.sortedBy { it.deleteDateTime } }

        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            // === フォルダ一覧 ===
            items(folders, key = { it.id }) { item ->
                FileRow(
                    item = item,
                    isFolder = true,
                    onClick = {
                        scope.launch {
                            isLoading = true
                            val child = downloader.getFolderStructure(item.id)
                            isLoading = false
                            if (child == null) {
                                Toast.makeText(
                                    context,
                                    "フォルダを開けませんでした。権限またはネットワークをご確認ください。",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                // 1階層深く進む
                                stack = stack + child
                            }
                        }
                    }
                )
            }

            // === ファイル一覧 ===
            items(files, key = { it.id }) { item ->
                FileRow(
                    item = item,
                    isFolder = false,
                    onClick = {
                        scope.launch {
                            isLoading = true
                            val ok = downloader.downloadFile(item.id) != null
                            isLoading = false
                            if (!ok) {
                                Toast.makeText(context, "ダウンロードに失敗しました。", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    item: DriveFileInfo,
    isFolder: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val icon = if (isFolder) "📂" else "📄"
            Text("$icon ${item.name}", style = MaterialTheme.typography.titleMedium)

            // ファイルのみメタ情報を補足表示
            if (!isFolder) {
                if (item.senderName.isNotBlank()) {
                    Text("👤 ${item.senderName}", style = MaterialTheme.typography.bodyMedium)
                }
                if (item.uploadDateTime.isNotBlank()) {
                    Text("⏱ ${item.uploadDateTime}", style = MaterialTheme.typography.bodySmall)
                }
                if (item.deleteDateTime.isNotBlank()) {
                    Text(
                        "🗑 ${item.deleteDateTime} に削除予定",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
