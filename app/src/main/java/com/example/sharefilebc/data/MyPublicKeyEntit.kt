package com.example.sharefilebc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_public_keys")
data class MyPublicKeyEntity(
    @PrimaryKey val id: Int = 1,
    val trustLayerPublicKey: String? = null,
    val derivedPublicKey: String? = null,
)