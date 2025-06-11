package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_folders")
data class SharedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,
    val recipientName: String,
    val folderId: String, // フォルダIDも引き続き保存
    val fileName: String, // 追加
    val fileGoogleDriveId: String // Google Drive上のファイルIDとして名前を変更
)