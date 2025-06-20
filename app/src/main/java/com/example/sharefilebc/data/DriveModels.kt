package com.example.sharefilebc.data

data class DriveFileInfo(
    val id: String,
    val name: String,
    val mimeType: String?,
    val isFolder: Boolean
)

data class FolderStructure(
    val folderName: String,  // 日付フォルダ名
    val senderName: String,  // 送信者名（親フォルダから推測）
    val uploadDate: String,  // フォルダの作成日時（JST）
    val files: List<DriveFileInfo>
)
