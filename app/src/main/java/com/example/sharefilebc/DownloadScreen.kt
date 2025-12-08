package com.example.sharefilebc

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.FolderStructure
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.ui.theme.SharedScreenColors
import kotlinx.coroutines.launch
import com.example.sharefilebc.data.UserDao
import android.util.Log

private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")

private fun String.toDateOnly(): String = DATE_REGEX.find(this)?.value ?: this

private data class ReceivedDateGroupSummaryUi(
    val dateLabel: String,
    val folderIds: List<String>,
    val displayUpload: String,
    val displayDelete: String
)

private data class ReceivedSenderSummaryUi(
    val senderName: String,
    val senderEmail: String?,
    val dateGroups: List<ReceivedDateGroupSummaryUi>,
    val cacheToken: String,
    val latestUpload: String
)

private data class ReceivedFileItemUi(
    val id: String,
    val fileName: String,
    val uploadedAt: String,
    val deleteAt: String
)

private data class ReceivedDateGroupDetailUi(
    val dateLabel: String,
    val deleteAt: String,
    val files: List<ReceivedFileItemUi>
)

private data class ReceivedSenderDetailUi(
    val senderName: String,
    val senderEmail: String?,
    val dateGroups: List<ReceivedDateGroupDetailUi>,
    val cacheToken: String
)

@Composable
fun DownloadScreen(
    initialFolderId: String?,
    initialFileId: String?,
    deepLinkSenderPublicKey: String?,
    deepLinkRecipientEmail: String?,
) {
    val context = LocalContext.current
    val downloader = remember { DriveDownloader(context) }
    val coroutineScope = rememberCoroutineScope()

    val db = remember { AppDatabase.getDatabase(context) }
    val receivedDao = remember { db.receivedFolderDao() }
    val userDao = remember { db.userDao() }

    val receivedFolders by receivedDao.getAll().collectAsState(initial = emptyList())
    val users by userDao.getAll().collectAsState(initial = emptyList())

    var isLoading by remember { mutableStateOf(false) }
    var needsLogin by remember { mutableStateOf(false) }
    var selectedSender by remember { mutableStateOf<String?>(null) }
    var detailState by remember { mutableStateOf<ReceivedSenderDetailUi?>(null) }
    val detailCache = remember { mutableStateMapOf<String, ReceivedSenderDetailUi>() }
    var pendingAutoDownloadFileId by remember { mutableStateOf(initialFileId) }

    val senderGroups = remember(receivedFolders, users) {
        buildSenderSummaries(receivedFolders, users)
    }

    LaunchedEffect(senderGroups) {
        if (selectedSender != null && senderGroups.none { it.senderName == selectedSender }) {
            selectedSender = null
            detailState = null
        }
    }

    LaunchedEffect(initialFolderId, initialFileId, deepLinkSenderPublicKey) {
        if (initialFolderId != null || initialFileId != null) {
            isLoading = true
            needsLogin = false
            Log.d(
                "DownloadScreen",
                "DeepLink start: folder=$initialFolderId, file=$initialFileId, senderKey=${deepLinkSenderPublicKey?.take(6)}, to=$deepLinkRecipientEmail"
            )

            if (initialFileId != null) {
                val contextResult = downloader.resolveFileContext(initialFileId)
                if (contextResult == null) {
                    needsLogin = true
                    isLoading = false
                    return@LaunchedEffect
                }

                if (!deepLinkSenderPublicKey.isNullOrBlank()) {
                    registerSenderPublicKey(
                        userDao = userDao,
                        senderName = contextResult.ownerName,
                        senderEmail = contextResult.ownerEmail,
                        publicKeyHex = deepLinkSenderPublicKey
                    )
                }

                val result = handleInitialFolder(contextResult.parentFolderId, downloader, receivedDao)
                isLoading = false
                if (result == null) {
                    needsLogin = true
                } else {
                    selectedSender = result.senderName
                    pendingAutoDownloadFileId = initialFileId
                }
            } else {
                val result = handleInitialFolder(initialFolderId!!, downloader, receivedDao)
                isLoading = false
                if (result == null) {
                    needsLogin = true
                } else {
                    selectedSender = result.senderName
                }
            }
        }
    }

    LaunchedEffect(selectedSender) {
        if (selectedSender != null) {
            detailState = null
        }
    }

    LaunchedEffect(selectedSender, senderGroups) {
        val sender = selectedSender ?: return@LaunchedEffect
        val group = senderGroups.firstOrNull { it.senderName == sender } ?: return@LaunchedEffect
        val cached = detailCache[sender]
        if (cached != null && cached.cacheToken == group.cacheToken) {
            detailState = cached
            return@LaunchedEffect
        }

        isLoading = true
        needsLogin = false

        val dateGroups = mutableListOf<ReceivedDateGroupDetailUi>()
        for (dateGroup in group.dateGroups) {
            val structures = mutableListOf<FolderStructure>()
            for (folderId in dateGroup.folderIds) {
                val structure = downloader.getFolderStructure(folderId)
                if (structure == null) {
                    needsLogin = true
                    continue
                }
                structures += structure
            }

            if (structures.isEmpty()) continue

            val mergedFiles = structures
                .flatMap { it.files }
                .filter { !it.isFolder }
                .map {
                    ReceivedFileItemUi(
                        id = it.id,
                        fileName = it.name,
                        uploadedAt = it.uploadDateTime,
                        deleteAt = it.deleteDateTime
                    )
                }
                .sortedByDescending { it.uploadedAt }

            if (mergedFiles.isEmpty()) continue

            dateGroups += ReceivedDateGroupDetailUi(
                dateLabel = dateGroup.dateLabel.toDateOnly(),
                deleteAt = dateGroup.displayDelete,
                files = mergedFiles
            )
        }

        isLoading = false

        val detail = ReceivedSenderDetailUi(
            senderName = group.senderName,
            senderEmail = group.senderEmail,
            dateGroups = dateGroups.sortedByDescending { it.dateLabel },
            cacheToken = group.cacheToken
        )
        detailState = detail
        detailCache[sender] = detail
    }
    LaunchedEffect(detailState, pendingAutoDownloadFileId) {
        val targetFileId = pendingAutoDownloadFileId ?: return@LaunchedEffect
        val detail = detailState ?: return@LaunchedEffect
        val exists = detail.dateGroups.any { group -> group.files.any { it.id == targetFileId } }
        if (exists) {
            isLoading = true
            downloader.downloadFile(targetFileId)
            isLoading = false
            pendingAutoDownloadFileId = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp)
        ) {
            AnimatedVisibility(visible = needsLogin) {
                LoginRequiredCard(onLogin = {
                    context.startActivity(Intent(context, LoginActivity::class.java))
                })
            }

            if (senderGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "受信したファイルはありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (detailState == null || selectedSender == null) {
                    ReceivedSenderList(
                        modifier = Modifier.weight(1f),
                        groups = senderGroups,
                        onSelect = { selectedSender = it.senderName }
                    )
                } else {
                    ReceivedSenderDetail(
                        modifier = Modifier.weight(1f),
                        detail = detailState!!,
                        onBack = { selectedSender = null },
                        onDownload = { fileId ->
                            coroutineScope.launch {
                                isLoading = true
                                downloader.downloadFile(fileId)
                                isLoading = false
                            }
                        }
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private fun buildSenderSummaries(
    receivedFolders: List<ReceivedFolderEntity>,
    users: List<UserEntity>
): List<ReceivedSenderSummaryUi> {
    val emailMap = users.associate { it.name to it.email }

    return receivedFolders
        .groupBy { it.senderName }
        .map { (senderName, folders) ->
            val dateGroups = folders
                .groupBy { it.folderName }
                .map { (dateName, rows) ->
                    val latest = rows.maxByOrNull { it.uploadDateTime } ?: rows.first()
                    ReceivedDateGroupSummaryUi(
                        dateLabel = dateName,
                        folderIds = rows.map { it.folderId },
                        displayUpload = latest.uploadDateTime,
                        displayDelete = latest.deleteDateTime
                    )
                }
                .sortedByDescending { it.displayUpload }

            val cacheToken = dateGroups.joinToString(separator = "|") { group ->
                buildString {
                    append(group.dateLabel)
                    append(":")
                    append(group.folderIds.sorted().joinToString(","))
                    append(":")
                    append(group.displayUpload)
                    append(":")
                    append(group.displayDelete)
                }
            }

            val latestUpload = dateGroups.maxOfOrNull { it.displayUpload }.orEmpty()

            ReceivedSenderSummaryUi(
                senderName = senderName,
                senderEmail = emailMap[senderName],
                dateGroups = dateGroups,
                cacheToken = cacheToken,
                latestUpload = latestUpload
            )
        }
        .sortedByDescending { it.latestUpload }
}

private data class InitialFolderResult(
    val senderName: String
)

private suspend fun handleInitialFolder(
    folderId: String,
    downloader: DriveDownloader,
    dao: com.example.sharefilebc.data.ReceivedFolderDao
): InitialFolderResult? {
    val folderStructure = downloader.getFolderStructure(folderId) ?: return null

    val dateChild = folderStructure.files.firstOrNull { it.isFolder && DATE_REGEX.find(it.name) != null }
    val (targetId, targetStructure) = if (dateChild != null) {
        val childStructure = downloader.getFolderStructure(dateChild.id) ?: return null
        dateChild.id to childStructure.copy(folderName = childStructure.folderName.toDateOnly())
    } else {
        folderId to folderStructure.copy(folderName = folderStructure.folderName.toDateOnly())
    }

    val firstFile = targetStructure.files.firstOrNull { !it.isFolder }
    val senderName = firstFile?.senderName?.takeIf { it.isNotBlank() } ?: "Unknown Sender"
    val upload = firstFile?.uploadDateTime ?: ""
    val delete = firstFile?.deleteDateTime ?: ""

    val exists = dao.findByFolderId(targetId)
    val entity = ReceivedFolderEntity(
        folderId = targetId,
        folderName = targetStructure.folderName.toDateOnly(),
        senderName = senderName,
        uploadDateTime = upload,
        deleteDateTime = delete
    )

    if (exists == null) {
        dao.insert(entity)
    } else if (
        exists.senderName != entity.senderName ||
        exists.folderName != entity.folderName ||
        exists.uploadDateTime != entity.uploadDateTime ||
        exists.deleteDateTime != entity.deleteDateTime
    ) {
        dao.insert(entity.copy(id = exists.id))
    }

    return InitialFolderResult(senderName = senderName)
}
private suspend fun registerSenderPublicKey(
    userDao: UserDao,
    senderName: String,
    senderEmail: String?,
    publicKeyHex: String,
) {
    senderEmail?.let { email ->
        val existing = userDao.findByEmail(email)
        if (existing != null) {
            userDao.updatePublicKey(existing.id, publicKeyHex)
            return
        }
    }

    val existingByName = userDao.findByName(senderName)
    if (existingByName != null) {
        userDao.updatePublicKey(existingByName.id, publicKeyHex)
        return
    }

    val resolvedName = senderName.ifBlank { senderEmail ?: "Unknown Sender" }
    val resolvedEmail = senderEmail ?: senderName
    userDao.insert(
        UserEntity(
            name = resolvedName,
            email = resolvedEmail,
            publicKeyHex = publicKeyHex
        )
    )
}

@Composable
private fun LoginRequiredCard(onLogin: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Google ログインまたは Drive 権限が必要です。",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onLogin, modifier = Modifier.padding(top = 8.dp)) {
                Text("ログインへ")
            }
        }
    }
}

@Composable
private fun ReceivedSenderList(
    modifier: Modifier,
    groups: List<ReceivedSenderSummaryUi>,
    onSelect: (ReceivedSenderSummaryUi) -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        items(groups, key = { it.senderName }) { group ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(group) },
                color = SharedScreenColors.UserCardBackground,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, SharedScreenColors.UserCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = group.senderName.ifBlank { "不明なユーザー" },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        group.senderEmail?.takeIf { it.isNotBlank() }?.let { email ->
                            Text(
                                text = email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SharedScreenColors.UserEmail
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceivedSenderDetail(
    modifier: Modifier,
    detail: ReceivedSenderDetailUi,
    onBack: () -> Unit,
    onDownload: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = "戻る",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "共有ファイル",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = detail.senderName.ifBlank { "不明なユーザー" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                detail.senderEmail?.takeIf { it.isNotBlank() }?.let { email ->
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SharedScreenColors.UserEmail
                    )
                }
            }
        }

        if (detail.dateGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "表示できるファイルがありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            detail.dateGroups.forEach { group ->
                item(key = "header-${group.dateLabel}") {
                    Text(
                        text = group.dateLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = SharedScreenColors.DateLabel,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(group.files, key = { it.id }) { file ->
                    ReceivedFileItem(
                        file = file,
                        onDownload = onDownload
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceivedFileItem(
    file: ReceivedFileItemUi,
    onDownload: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SharedScreenColors.UserCardBackground)
            .border(BorderStroke(1.dp, SharedScreenColors.UserCardBorder), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            if (file.deleteAt.isNotBlank()) {
                Text(
                    text = "削除予定: ${file.deleteAt}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(onClick = { onDownload(file.id) }) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = "ダウンロード",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
