package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "share_payments")
data class SharePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileId: String,
    val folderId: String,
    val txid: String,
    val amount: Long,
    val paidAt: String,
    val payerRefundAddress: String?,
    val result: String = "PAID"
)
