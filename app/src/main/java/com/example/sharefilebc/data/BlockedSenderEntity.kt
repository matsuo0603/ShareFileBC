package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_senders")
data class BlockedSenderEntity(
    @PrimaryKey val email: String,
    val reason: String? = null,
    val source: String = BlockedSenderSource.MANUAL.name,
    val createdAt: String
)

enum class BlockedSenderSource {
    MANUAL,
    AUTO_THRESHOLD,
    AUTO_REFUND
}