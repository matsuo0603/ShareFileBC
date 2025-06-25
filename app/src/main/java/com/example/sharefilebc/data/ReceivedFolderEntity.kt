package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "received_folders")
data class ReceivedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    val folderId: String,            // Google Drive上のフォルダID
    val folderName: String,          // UI表示用のフォルダ名（例: 2025-06-25）
    val senderName: String,          // 誰から共有されたか（送信者名）
    val uploadDateTime: String,      // アップロードされた時刻（DateTime単位）
    val deleteDateTime: String       // 削除予定時刻（この時刻を過ぎたら自動削除）
)
