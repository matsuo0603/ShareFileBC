package com.example.sharefilebc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.FolderStructure
import kotlinx.coroutines.launch

@Composable
fun FileListScreen(folderStructure: FolderStructure) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloader = remember { DriveDownloader(context) }

    Text("📁 ${folderStructure.folderName}", style = MaterialTheme.typography.titleLarge)

    if (folderStructure.files.isEmpty()) {
        Text("このフォルダにはファイルがありません。")
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(folderStructure.files.filter { !it.isFolder }) { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📄 ${file.name}", style = MaterialTheme.typography.titleMedium)
                        Text("👤 ${file.senderName}", style = MaterialTheme.typography.bodyMedium)
                        Text("⏱ ${file.uploadDateTime}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "🗑 ${file.deleteDateTime} に削除予定",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    downloader.downloadFile(file.id)
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("ダウンロード")
                        }
                    }
                }
            }
        }
    }
}
