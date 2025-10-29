package com.example.sharefilebc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.ui.theme.SharedScreenColors

@Composable
fun SentFilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val sharedDao = db.sharedFolderDao()
    val userDao = db.userDao()
    val viewModel: SentFilesViewModel = viewModel(
        factory = SentFilesViewModelFactory(sharedDao, userDao)
    )
    val sentGroups = viewModel.sentFileGroups.collectAsState(initial = emptyList()).value

    if (sentGroups.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "共有済みのファイルはありません",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var selectedRecipient by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sentGroups) {
        if (selectedRecipient != null && sentGroups.none { it.recipientName == selectedRecipient }) {
            selectedRecipient = null
        }
    }

    val activeGroup = sentGroups.firstOrNull { it.recipientName == selectedRecipient }

    if (activeGroup == null) {
        RecipientList(
            modifier = modifier,
            groups = sentGroups,
            onSelect = { selectedRecipient = it.recipientName }
        )
    } else {
        SentFileDetail(
            modifier = modifier,
            group = activeGroup,
            onBack = { selectedRecipient = null }
        )
    }
}

@Composable
private fun RecipientList(
    modifier: Modifier,
    groups: List<SentFileGroupUi>,
    onSelect: (SentFileGroupUi) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        items(groups, key = { it.recipientName }) { group ->
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
                            text = group.recipientName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        group.recipientEmail?.takeIf { it.isNotBlank() }?.let { email ->
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
private fun SentFileDetail(
    modifier: Modifier,
    group: SentFileGroupUi,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
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
                    imageVector = Icons.Outlined.ArrowBack,
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
            Text(
                text = group.recipientName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            val groupedByDate = group.files.groupBy { it.uploadedAt.take(10) }
            val sortedDates = groupedByDate.keys.sortedDescending()
            sortedDates.forEach { dateKey ->
                item(key = "header-$dateKey") {
                    Text(
                        text = dateKey,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = SharedScreenColors.DateLabel,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(groupedByDate[dateKey] ?: emptyList(), key = { it.id }) { file ->
                    SentFileItem(file = file)
                }
            }
        }
    }
}

@Composable
private fun SentFileItem(file: SentFileItemUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SharedScreenColors.UserCardBackground)
            .border(BorderStroke(1.dp, SharedScreenColors.UserCardBorder), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = file.fileName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        val deleteText = file.deleteAt.ifBlank { "不明" }
        Text(
            text = "削除予定: $deleteText",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium
        )
    }
}