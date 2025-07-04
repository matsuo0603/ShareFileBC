package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_folders")
data class SharedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String, // yyyy-MM-dd
    val recipientName: String,
    val folderId: String,
    val fileName: String,
    val fileGoogleDriveId: String,
    val deleteDateTime: String = ""
)
