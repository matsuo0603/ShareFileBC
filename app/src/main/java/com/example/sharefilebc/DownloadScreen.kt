package com.example.sharefilebc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.example.sharefilebc.data.FolderStructure
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DownloadScreen(initialFolderId: String?) {
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf<String?>(null) }
    var receivedFolders by remember { mutableStateOf<List<ReceivedFolderEntity>>(emptyList()) }
    var currentFolderStructure by remember { mutableStateOf<FolderStructure?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val db = remember { AppDatabase.getDatabase(context) }
    val receivedFolderDao = db.receivedFolderDao()

    fun getCurrentJSTTime(): String {
        val jstTimeZone = TimeZone.getTimeZone("Asia/Tokyo")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        formatter.timeZone = jstTimeZone
        return formatter.format(Date())
    }

    LaunchedEffect(initialFolderId) {
        if (initialFolderId != null) {
            isLoading = true
            val folderStructure = downloader.getFolderStructure(initialFolderId)
            if (folderStructure != null) {
                if (folderStructure.files.none { !it.isFolder }) {
                    receivedFolderDao.deleteByFolderId(initialFolderId)
                    isLoading = false
                    return@LaunchedEffect
                }

                currentFolderStructure = folderStructure

                val existingFolder = receivedFolderDao.findByFolderId(initialFolderId)
                val currentDate = getCurrentJSTTime()

                if (existingFolder != null) {
                    receivedFolderDao.updateLastAccessDate(initialFolderId, currentDate)
                } else {
                    val newFolder = ReceivedFolderEntity(
                        folderId = initialFolderId,
                        folderName = folderStructure.folderName,
                        senderName = "不明",
                        uploadDate = currentDate,
                        receivedDate = currentDate,
                        lastAccessDate = currentDate
                    )
                    receivedFolderDao.insert(newFolder)
                }

                selectedDate = folderStructure.folderName
            }
            isLoading = false
        }
    }

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
                val folderStructure = currentFolderStructure

                if (selectedDate != null && folderStructure != null) {
                    Button(
                        onClick = {
                            selectedDate = null
                            currentFolderStructure = null
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("← フォルダ一覧に戻る")
                    }

                    Text("📁 ${folderStructure.folderName}", style = MaterialTheme.typography.titleLarge)

                    if (folderStructure.files.isEmpty()) {
                        Text("このフォルダにはファイルがありません。")
                    } else {
                        LazyColumn {
                            items(folderStructure.files.filter { !it.isFolder }) { file ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                } else {
                    Text("受信したフォルダ一覧", style = MaterialTheme.typography.titleLarge)

                    if (receivedFolders.isEmpty()) {
                        Text("受信したフォルダがありません。")
                    } else {
                        val groupedByDate = receivedFolders.groupBy { it.folderName }

                        LazyColumn {
                            items(groupedByDate.entries.toList()) { (date, folders) ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedDate = date
                                            coroutineScope.launch {
                                                isLoading = true
                                                val structures = folders.mapNotNull {
                                                    downloader.getFolderStructure(it.folderId)
                                                }
                                                val merged = FolderStructure(
                                                    folderName = date,
                                                    files = structures.flatMap { it.files }
                                                )
                                                currentFolderStructure = merged
                                                isLoading = false
                                            }
                                        },
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("📁 $date", style = MaterialTheme.typography.titleMedium)
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
