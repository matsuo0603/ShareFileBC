// RefundTaskDao.kt
package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RefundTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RefundTaskEntity): Long

    @Query("SELECT * FROM refund_tasks")
    suspend fun getAll(): List<RefundTaskEntity>

    // ✅ UI表示用（返金待ち表示など）
    @Query("SELECT * FROM refund_tasks ORDER BY id DESC")
    fun observeAll(): Flow<List<RefundTaskEntity>>

    /**
     * ShareProcessor.refundShare で使用
     * shareIDで返金タスクを検索
     */
    @Query("SELECT * FROM refund_tasks WHERE shareID = :shareId LIMIT 1")
    suspend fun findByShareId(shareId: String): RefundTaskEntity?

    /**
     * ShareProcessor.refundShare / declineRefund で使用
     * 返金完了後にタスクを削除
     */
    @Query("DELETE FROM refund_tasks WHERE shareID = :shareId")
    suspend fun deleteByShareId(shareId: String)
}
