package com.example.sharefilebc.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.DriveDownloader
import com.example.sharefilebc.FolderStructure
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.ReceivedFolderEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import org.threeten.bp.LocalDateTime
import org.threeten.bp.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(initialFolderId: String?) {
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    var selectedFolder by remember { mutableStateOf<ReceivedFolderEntity?>(null) }
    var receivedFolders by remember { mutableStateOf<List<ReceivedFolderEntity>>(emptyList()) }
    var currentFolderStructure by remember { mutableStateOf<FolderStructure?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val db = remember { AppDatabase.getDatabase(context) }
    val receivedFolderDao = db.receivedFolderDao()

    // ✅ 削除予定時間を計算する関数
    fun calculateDeleteTime(receivedDate: String): String {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val receivedDateTime = LocalDateTime.parse(receivedDate, formatter)
            val deleteDateTime = receivedDateTime.plusMinutes(15)
            deleteDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            "不明"
        }
    }

    // 🟦 Deep Linkでフォルダが指定された場合の処理
    LaunchedEffect(initialFolderId) {
        if (initialFolderId != null) {
            Log.d("DownloadScreen", "🟦 initialFolderId = $initialFolderId")
            isLoading = true
            val folderStructure = downloader.getFolderStructure(initialFolderId)
            if (folderStructure != null) {
                Log.d("DownloadScreen", "🟩 folderStructure 取得成功: ${folderStructure.folderName}")
                currentFolderStructure = folderStructure

                val existingFolder = receivedFolderDao.findByFolderId(initialFolderId)
                val currentDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

                if (existingFolder != null) {
                    receivedFolderDao.updateLastAccessDate(initialFolderId, currentDate)
                    selectedFolder = existingFolder.copy(lastAccessDate = currentDate)
                } else {
                    val newFolder = ReceivedFolderEntity(
                        folderId = initialFolderId,
                        folderName = folderStructure.folderName,
                        senderName = folderStructure.senderName,
                        receivedDate = currentDate,
                        lastAccessDate = currentDate
                    )
                    receivedFolderDao.insert(newFolder)
                    selectedFolder = newFolder
                }
            } else {
                Log.e("DownloadScreen", "🟥 folderStructure が取得できませんでした（null）")
            }
            isLoading = false
        }
    }

    // 📥 受信フォルダ一覧を取得
    LaunchedEffect(Unit) {
        receivedFolderDao.getAll().collectLatest { folders ->
            receivedFolders = folders
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val folder = selectedFolder
                val folderStructure = currentFolderStructure

                if (folder != null && folderStructure != null) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(100)
                                selectedFolder = null
                                currentFolderStructure = null
                            }
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("← フォルダ一覧に戻る")
                    }

                    Text("📁 ${folderStructure.folderName}", style = MaterialTheme.typography.titleLarge)
                    Text("送信者: ${folderStructure.senderName}", style = MaterialTheme.typography.bodyMedium)

                    if (folderStructure.files.isEmpty()) {
                        Text("このフォルダにはファイルがありません。")
                    } else {
                        LazyColumn {
                            items(folderStructure.files.filter { !it.isFolder }) { file ->
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
                                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                file.mimeType ?: "Unknown Type",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            // ✅ 削除予定時間を表示
                                            Text(
                                                "${calculateDeleteTime(folder.receivedDate)}に削除予定",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Button(onClick = {
                                            coroutineScope.launch {
                                                downloader.downloadFile(file.id)
                                            }
                                        }) {
                                            Text("ダウンロード")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("受信したフォルダ一覧", style = MaterialTheme.typography.titleLarge)

                    if (receivedFolders.isEmpty()) {
                        Text("受信したフォルダがありません。")
                    } else {
                        LazyColumn {
                            items(receivedFolders) { folderItem ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedFolder = folderItem
                                            coroutineScope.launch {
                                                isLoading = true
                                                val folderStructure = downloader.getFolderStructure(folderItem.folderId)
                                                currentFolderStructure = folderStructure
                                                val currentDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                                                receivedFolderDao.updateLastAccessDate(folderItem.folderId, currentDate)
                                                isLoading = false
                                            }
                                        },
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("📁 ${folderItem.folderName}", style = MaterialTheme.typography.titleMedium)
                                        Text("送信者: ${folderItem.senderName}", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "受信日: ${folderItem.receivedDate}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        // ✅ フォルダ一覧でも削除予定時間を表示
                                        Text(
                                            "${calculateDeleteTime(folderItem.receivedDate)}に削除予定",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
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