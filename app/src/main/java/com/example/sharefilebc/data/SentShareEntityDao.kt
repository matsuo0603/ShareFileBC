package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SentShareDao {
    @Insert
    suspend fun insert(entity: SentShareEntity): Long

    @Query(
        """
        SELECT * FROM sent_shares
        WHERE status = :status
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findLatestByStatus(status: String): SentShareEntity?

    @Query("UPDATE sent_shares SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)
}