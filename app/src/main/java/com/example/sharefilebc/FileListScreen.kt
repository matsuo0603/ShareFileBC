package com.example.sharefilebc

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.SharedFolderEntity
import kotlinx.coroutines.launch // launch をインポート

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(selectedDate: String, allSharedFolders: List<SharedFolderEntity>) {
    val context = LocalContext.current
    // 選択された日付に紐づくファイル情報をRoomから直接取得
    var filesForSelectedDate by remember { mutableStateOf<List<SharedFolderEntity>>(emptyList()) }
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(selectedDate, allSharedFolders) {
        // allSharedFoldersの中から選択された日付のファイルのみをフィルタリング
        filesForSelectedDate = allSharedFolders
            .filter { it.date == selectedDate }
            .distinctBy { it.fileGoogleDriveId } // ファイルIDで重複を除去
            .sortedByDescending { it.id } // RoomのIDで新しいものが上に来るようにソート (または、より適切なタイムスタンプフィールドがあればそれを使用)
        Log.d("FileListScreen", "Files for $selectedDate from Room: ${filesForSelectedDate.size}")
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "ファイル一覧 ($selectedDate)",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        if (filesForSelectedDate.isEmpty()) {
            Text("この日付に共有されたファイルはありません。")
        } else {
            LazyColumn {
                items(filesForSelectedDate) { fileEntity ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(fileEntity.fileName, style = MaterialTheme.typography.titleMedium) // Roomからファイル名を表示
                                Spacer(modifier = Modifier.height(4.dp))
                                // MIMEタイプはSharedFolderEntityにないため、表示できないか、別途取得ロジックが必要
                                // ここでは、Google Driveから取得する代わりに、簡易的にファイル拡張子などで表示することも可能
                                // Text(file.mimeType ?: "Unknown Type", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Shared via ${fileEntity.recipientName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 共有相手名を表示
                            }
                            Button(onClick = {
                                coroutineScope.launch {
                                    downloader.downloadFile(fileEntity.fileGoogleDriveId) // Roomから取得したファイルIDでダウンロード
                                }
                            }) {
                                Text("ダウンロード")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}