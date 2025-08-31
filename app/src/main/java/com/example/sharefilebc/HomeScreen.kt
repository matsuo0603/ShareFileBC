package com.example.sharefilebc

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    displayName: String
) {
    val context = LocalContext.current
    val driveUploader = remember { DriveUploader(context) }
    val coroutineScope = rememberCoroutineScope()

    val db = remember { AppDatabase.getDatabase(context) }
    val userDao = db.userDao()

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(listOf<UserEntity>()) }
    var selectedUser by remember { mutableStateOf<UserEntity?>(null) }
    var isUploading by remember { mutableStateOf(false) }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = FilePickerContract(),
        onResult = { uri: Uri? ->
            uri?.let { fileUri ->
                selectedUser?.let { user ->
                    coroutineScope.launch {
                        isUploading = true
                        withContext(Dispatchers.IO) {
                            val result = driveUploader.uploadFileAndRecordWithSharing(
                                fileUri = fileUri,
                                recipientName = user.name,
                                db = db
                            )
                            withContext(Dispatchers.Main) {
                                isUploading = false
                                if (result == null) {
                                    Toast.makeText(context, "アップロードに失敗しました（Google認証を確認）", Toast.LENGTH_LONG).show()
                                } else {
                                    val (fileName, _, folderId) = result
                                    Log.d("ShareFileBC", "📦 folderId being sent: $folderId")

                                    // ✅ メール処理は EmailSender に集約（自動送信 or 権限同意→後送信）
                                    EmailSender.sendEmailWithDriveLink(
                                        context = context,
                                        recipientEmail = user.email,
                                        fileName = fileName,
                                        folderId = folderId
                                    )
                                }
                            }
                        }
                    }
                }
                selectedUser = null
            }
        }
    )

    LaunchedEffect(Unit) {
        userDao.getAll().collectLatest { list ->
            users = list
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text("ようこそ、$displayName さん！", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名前を入力") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("メールアドレスを入力") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        Button(onClick = {
            if (name.isNotBlank() && email.isNotBlank()) {
                coroutineScope.launch {
                    userDao.insert(UserEntity(name = name, email = email))
                    name = ""
                    email = ""
                }
            }
        }) {
            Text("作成")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("共有相手一覧：", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (isUploading) {
            CircularProgressIndicator()
            Text("アップロード中... Gmailで送信します", modifier = Modifier.padding(top = 8.dp))
        }

        users.forEach { user ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        selectedUser = user
                        openFileLauncher.launch(Unit)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isUploading
                ) {
                    Text("${user.name}（${user.email}）")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            userDao.deleteByName(user.name)
                        }
                    },
                    enabled = !isUploading
                ) {
                    Text("削除")
                }
            }
        }
    }
}
