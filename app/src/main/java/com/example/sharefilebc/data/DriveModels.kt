package com.example.sharefilebc.data

data class DriveFileInfo(
    val id: String,
    val name: String,
    val mimeType: String?,
    val isFolder: Boolean,
    val senderName: String,
    val uploadDateTime: String,
    val deleteDateTime: String
)

data class FolderStructure(
    val folderName: String, // yyyy-MM-dd などでグループ化
    val files: List<DriveFileInfo>
)
