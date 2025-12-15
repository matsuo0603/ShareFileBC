package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_shared_folders")
data class MySharedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val folderID: String? = null,
    val fileID: String? = null,
    val fileName: String? = null,
    val expiresAt: String? = null,
)