package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "refund_tasks")
data class RefundTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val shareID: String? = null,
    val senderPublicKey: String? = null,
    val contextJSON: String? = null,
    val createdAt: String? = null,
    val status: String? = null,
    val detectedAmount: Long? = null,
    val paymentThreshold: Long? = null,
    val relatedFolderId: String? = null,
    val relatedFileId: String? = null,
)