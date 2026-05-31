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

    @Query("UPDATE refund_tasks SET status = :status WHERE shareID = :shareId")
    suspend fun markStatusByShareId(shareId: String, status: String)

    /**
     * ✅ 既存データ互換：過去に paymentThreshold が保存されていない（null/0）タスクがあると
     * UIで「閾値: 0」と誤表示されやすい。
     *
     * DownloadScreen は task.paymentThreshold を優先表示するため、ここで埋めるだけで
     * 画面側の修正なしに表示を矯正できる。
     */
    @Query(
        "UPDATE refund_tasks " +
                "SET paymentThreshold = :defaultThreshold " +
                "WHERE paymentThreshold IS NULL OR paymentThreshold <= 0"
    )
    suspend fun fillMissingThreshold(defaultThreshold: Long)
}
