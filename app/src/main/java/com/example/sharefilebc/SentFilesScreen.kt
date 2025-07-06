package com.example.sharefilebc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.SharedFolderEntity
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SentFilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = AppDatabase.getDatabase(context).sharedFolderDao()
    val viewModel: SentFilesViewModel = viewModel(
        factory = SentFilesViewModelFactory(dao)
    )
    val sentFiles = viewModel.sentFiles.collectAsState(initial = emptyList()).value

    Surface(modifier = modifier.fillMaxSize()) {
        if (sentFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("共有済みのファイルはありません")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp), // 横だけpadding（上下は contentPadding で管理）
                contentPadding = PaddingValues(bottom = 80.dp) // 👈 これを追加！
            ) {
                items(sentFiles) { file ->
                    FileItem(file = file)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

        }
    }
}

@Composable
fun FileItem(file: SharedFolderEntity) {
    // SharedFolderEntityのdateフィールドを使用（uploadDateTimeの代わり）
    val uploadDateTime = parseJSTDateTime(file.date)
    val deleteDateTime = uploadDateTime?.let { calculateDeleteTime(it) }
    val deleteTimeStr = deleteDateTime?.let { formatJSTTime(it) } ?: "不明"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "📄 ファイル名: ${file.fileName}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "👤 送信相手: ${file.recipientName}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "🕒 アップロード時間: ${file.date}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "🗑️ 削除予定時間: $deleteTimeStr", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun parseJSTDateTime(datetimeStr: String): Date? {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).parse(datetimeStr)
    } catch (e: Exception) {
        null
    }
}

fun calculateDeleteTime(uploaded: Date): Date {
    val calendar = Calendar.getInstance()
    calendar.time = uploaded
    calendar.add(Calendar.MINUTE, 10)
    return calendar.time
}

fun formatJSTTime(date: Date): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN)
    return formatter.format(date)
}