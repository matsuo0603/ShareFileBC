package com.example.sharefilebc

import android.content.Intent
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
import com.example.sharefilebc.data.FolderStructure
import com.example.sharefilebc.data.ReceivedFolderEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun DownloadScreen(initialFolderId: String?) {
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf<String?>(null) }
    var currentFolderStructure by remember { mutableStateOf<FolderStructure?>(null) }
    var receivedFolders by remember { mutableStateOf<List<ReceivedFolderEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf(false) } // 👈 追加

    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.receivedFolderDao()

    LaunchedEffect(initialFolderId) {
        if (initialFolderId != null) {
            isLoading = true
            val folderStructure = downloader.getFolderStructure(initialFolderId)
            if (folderStructure == null) {
                // Drive未ログイン/権限不足などで取得失敗時
                needsLogin = true
            } else if (folderStructure.files.any { !it.isFolder }) {
                selectedDate = folderStructure.folderName
                currentFolderStructure = folderStructure

                val existing = dao.findByFolderId(initialFolderId)
                if (existing == null) {
                    val file = folderStructure.files.firstOrNull()
                    dao.insert(
                        ReceivedFolderEntity(
                            folderId = initialFolderId,
                            folderName = folderStructure.folderName,
                            senderName = file?.senderName ?: "不明",
                            uploadDateTime = file?.uploadDateTime ?: "",
                            deleteDateTime = file?.deleteDateTime ?: ""
                        )
                    )
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        dao.getAll().collectLatest { receivedFolders = it }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // 👇 追加：必要に応じてログイン誘導
                if (needsLogin) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("GoogleログインまたはDrive権限が必要です。")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                context.startActivity(Intent(context, LoginActivity::class.java).apply {
                                    // Deep Link は HomeActivity -> LoginActivity で受ける想定なのでここでは不要
                                })
                            }) {
                                Text("ログインへ")
                            }
                        }
                    }
                }

                if (selectedDate != null && currentFolderStructure != null) {
                    Button(
                        onClick = {
                            selectedDate = null
                            currentFolderStructure = null
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("← フォルダ一覧に戻る")
                    }

                    FileListScreen(currentFolderStructure!!)
                } else {
                    Text("受信したフォルダ一覧", style = MaterialTheme.typography.titleLarge)

                    if (receivedFolders.isEmpty()) {
                        Text("受信したフォルダがありません。")
                    } else {
                        val groupedByDate = receivedFolders.groupBy { it.folderName }

                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
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
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
