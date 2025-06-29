package com.example.sharefilebc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun BottomNavigationBar(selectedTab: BottomTab, onTabSelected: (BottomTab) -> Unit) {
    NavigationBar {
        BottomTab.values().forEach { tab ->
            NavigationBarItem(
                icon = { Icon(getTabIcon(tab), contentDescription = tab.label) },
                label = { Text(tab.label) },
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

fun getTabIcon(tab: BottomTab): ImageVector {
    return when (tab) {
        BottomTab.Home -> Icons.Default.Home
        BottomTab.Download -> Icons.Default.Download
        BottomTab.Sent -> Icons.Default.Send
    }
}
