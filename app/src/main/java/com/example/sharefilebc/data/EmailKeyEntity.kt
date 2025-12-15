package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "email_keys")
data class EmailKeyEntity(
    @PrimaryKey val email: String,
    val derivedPublicKey: String,
    val trustLayerPublicKey: String,
    val folderIDFromPartner: String,
    val isRefundRejected: Boolean = false,
)