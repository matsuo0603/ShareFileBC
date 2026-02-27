package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "received_files")
data class ReceivedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val shareID: String? = null,
    val folderID: String? = null,
    val fileID: String? = null,
    val fileName: String? = null,
    val senderPublicKey: String? = null,
    val nameMetadata: String? = null,
    val nameMetadataError: String? = null,
    val isDownloadAllowed: Boolean = false,
    val isDownloadBlocked: Boolean = false,
    val isDownloadEverAllowed: Boolean = false,
    // ✅ ダウンロード完了フラグ（返金ボタン表示のゲートに使う）
    val isDownloaded: Boolean = false,
)