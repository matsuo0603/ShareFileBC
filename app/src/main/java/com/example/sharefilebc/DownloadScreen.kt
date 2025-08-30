package com.example.sharefilebc

import android.content.Intent
import android.util.Log
import android.widget.Toast
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
    val TAG = "DownloadScreen"
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    var currentFolderStructure by remember { mutableStateOf<FolderStructure?>(null) }
    var receivedFolders by remember { mutableStateOf<List<ReceivedFolderEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf(false) }

    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.receivedFolderDao()

    // Deep Link から来た最初の取得（フォルダ単位でそのまま開く）
    LaunchedEffect(initialFolderId) {
        Log.d(TAG, "🔎 LaunchedEffect: initialFolderId=$initialFolderId")
        if (initialFolderId != null) {
            isLoading = true
            val folderStructure = downloader.getFolderStructure(initialFolderId)
            Log.d(TAG, "📥 getFolderStructure($initialFolderId) -> ${if (folderStructure == null) "null" else "OK"}")

            if (folderStructure == null) {
                // 未認証/権限不足/その他エラー
                needsLogin = true
            } else {
                currentFolderStructure = folderStructure

                // DB 登録は「初めて見る folderId なら」実行
                val exists = dao.findByFolderId(initialFolderId)
                if (exists == null) {
                    val first = folderStructure.files.firstOrNull()
                    dao.insert(
                        ReceivedFolderEntity(
                            folderId = initialFolderId,
                            folderName = folderStructure.folderName, // 例: yyyy-MM-dd
                            senderName = first?.senderName ?: "Unknown Sender",
                            uploadDateTime = first?.uploadDateTime ?: "",
                            deleteDateTime = first?.deleteDateTime ?: ""
                        )
                    )
                }
            }
            isLoading = false
        }
    }

    // 受信一覧の監視（Flow）
    LaunchedEffect(Unit) {
        dao.getAll().collectLatest { list ->
            receivedFolders = list
            Log.d(TAG, "📚 receivedFolders.size=${list.size}")
        }
    }

    // ==== 同じ（senderName, folderName）を 1 カードに統合するための ViewModel 的変換 ====
    data class Grouped(
        val senderName: String,
        val dateName: String,
        val folderIds: List<String>,       // 同グループ内の全フォルダID
        val displayUpload: String,         // 代表表示用（最新の uploadDateTime など）
        val displayDelete: String          // 代表表示用（最新の deleteDateTime など）
    )

    // senderName, folderName でグループ化し、代表行を作る
    val groupedList by remember(receivedFolders) {
        mutableStateOf(
            receivedFolders
                .groupBy { it.senderName to it.folderName }
                .map { (key, rows) ->
                    val latest = rows.maxByOrNull { it.uploadDateTime } ?: rows.first()
                    Grouped(
                        senderName = key.first,
                        dateName   = key.second,
                        folderIds  = rows.map { it.folderId },
                        displayUpload = latest.uploadDateTime,
                        displayDelete = latest.deleteDateTime
                    )
                }
                // 新しい(表示upload)順に並べる
                .sortedByDescending { it.displayUpload }
        )
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
                return@Column
            }

            // 取得失敗時の導線
            if (needsLogin) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Google ログインまたは Drive 権限が必要です。")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(context, LoginActivity::class.java))
                        }) {
                            Text("ログインへ")
                        }
                    }
                }
            }

            // 現在フォルダを開いている場合
            currentFolderStructure?.let { opened ->
                Button(
                    onClick = { currentFolderStructure = null },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("← 受信フォルダ一覧に戻る")
                }
                FileListScreen(opened) // フォルダ内の階層は従来通り掘れる
                return@Column
            }

            // === ここから “フォルダ単位 + 重複統合” の一覧 ===
            Text("受信したフォルダ", style = MaterialTheme.typography.titleLarge)

            if (groupedList.isEmpty()) {
                Text("受信したフォルダがありません。")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(groupedList, key = { it.senderName + "|" + it.dateName }) { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    // ★ 同じ（sender, date）の複数フォルダを「開く時に」マージする
                                    coroutineScope.launch {
                                        isLoading = true
                                        val structures = group.folderIds.mapNotNull { fid ->
                                            downloader.getFolderStructure(fid)
                                        }
                                        isLoading = false
                                        if (structures.isEmpty()) {
                                            Toast.makeText(
                                                context,
                                                "フォルダを開けませんでした。権限またはネットワークをご確認ください。",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            // 1つしかなければそれ、複数なら中身をマージして1画面に集約
                                            val merged = if (structures.size == 1) {
                                                structures.first()
                                            } else {
                                                FolderStructure(
                                                    folderName = group.dateName,
                                                    files = structures.flatMap { it.files }
                                                )
                                            }
                                            currentFolderStructure = merged
                                        }
                                    }
                                },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("📁 ${group.dateName}", style = MaterialTheme.typography.titleMedium)
                                if (group.senderName.isNotBlank()) {
                                    Text("👤 ${group.senderName}", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (group.displayUpload.isNotBlank()) {
                                    Text("⏱ ${group.displayUpload}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (group.displayDelete.isNotBlank()) {
                                    Text(
                                        "🗑 ${group.displayDelete} に削除予定",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                // 必要なら「同日フォルダが N 件あります」表示
                                if (group.folderIds.size > 1) {
                                    Text(
                                        "（同日の共有が ${group.folderIds.size} 件あります）",
                                        style = MaterialTheme.typography.bodySmall
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
