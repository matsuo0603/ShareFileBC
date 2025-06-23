package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "received_folders")
data class ReceivedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val folderId: String,          // Google DriveのフォルダID
    val folderName: String,        // フォルダ名（日付など）
    val senderName: String,        // 送信者名（フォルダ構造から推測）
    val receivedDate: String,      // 受信日時
    val lastAccessDate: String     // 最後にアクセスした日時
)