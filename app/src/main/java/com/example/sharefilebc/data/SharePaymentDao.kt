package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SharePaymentDao {
    @Insert
    suspend fun insert(entity: SharePaymentEntity): Long

    @Query(
        """
        SELECT * FROM share_payments
        WHERE fileId = :fileId AND folderId = :folderId
        ORDER BY paidAt DESC, id DESC
        LIMIT 1
        """
    )
    fun observeLatest(fileId: String, folderId: String): Flow<SharePaymentEntity?>

    @Query(
        """
        SELECT * FROM share_payments
        WHERE result = :result
        ORDER BY paidAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findLatestByResult(result: String): SharePaymentEntity?

    @Query("UPDATE share_payments SET result = :result WHERE id = :id")
    suspend fun updateResult(id: Int, result: String)
}