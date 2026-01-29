package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedSenderDao {
    @Query("SELECT * FROM blocked_senders ORDER BY createdAt DESC")
    fun getAll(): Flow<List<BlockedSenderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockedSenderEntity)

    @Query("DELETE FROM blocked_senders WHERE email = :email")
    suspend fun deleteByEmail(email: String)

    @Query("SELECT COUNT(*) FROM blocked_senders WHERE email = :email")
    suspend fun countByEmail(email: String): Int
}