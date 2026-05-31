package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sent_shares")
data class SentShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileId: String,
    val folderId: String,
    val recipientEmail: String,
    val createdAt: String,
    val threshold: Long,
    val senderAddress: String,
    val status: String = "PENDING"
)