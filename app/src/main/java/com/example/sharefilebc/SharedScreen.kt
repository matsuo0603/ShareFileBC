package com.example.sharefilebc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sharefilebc.ui.theme.SharedScreenColors
import com.example.sharefilebc.ui.theme.SharedScreenDimens

private enum class SharedInnerTab(val label: String) {
    Sent("送信"),
    Received("受信");
}

@Composable
fun SharedScreen(
    modifier: Modifier = Modifier,
    initialFolderId: String?
) {
    var selectedTab by remember(initialFolderId) {
        mutableStateOf(if (initialFolderId != null) SharedInnerTab.Received else SharedInnerTab.Sent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SharedScreenColors.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "共有ファイル",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        SharedTabSelector(
            selectedTab = selectedTab,
            onSelected = { selectedTab = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            SharedInnerTab.Sent -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    SentFilesScreen(modifier = Modifier.fillMaxSize())
                }
            }

            SharedInnerTab.Received -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    DownloadScreen(initialFolderId = initialFolderId)
                }
            }
        }
    }
}

@Composable
private fun SharedTabSelector(
    selectedTab: SharedInnerTab,
    onSelected: (SharedInnerTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SharedScreenDimens.TabContainerCorner))
            .background(SharedScreenColors.TabUnselected.copy(alpha = 0.4f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SharedInnerTab.values().forEach { tab ->
            val isSelected = tab == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .then(
                        if (isSelected) {
                            Modifier.shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(SharedScreenDimens.TabItemCorner),
                                clip = false
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(RoundedCornerShape(SharedScreenDimens.TabItemCorner))
                    .background(
                        color = if (isSelected) {
                            SharedScreenColors.TabSelected
                        } else {
                            SharedScreenColors.TabUnselected
                        }
                    )
                    .clickable { onSelected(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
