// RecievedFileDao.kt
package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceivedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReceivedFileEntity)

    @Query("SELECT * FROM received_files")
    suspend fun getAll(): List<ReceivedFileEntity>

    // ✅ UI表示用（DownloadScreen は Room の結果を表示するだけ）
    @Query("SELECT * FROM received_files ORDER BY id DESC")
    fun observeAll(): Flow<List<ReceivedFileEntity>>

    /**
     * ShareProcessor.processReceivedShare で使用
     * shareIDで検索して該当レコードを取得
     */
    @Query("SELECT * FROM received_files WHERE shareID = :shareId LIMIT 1")
    suspend fun findByShareId(shareId: String): ReceivedFileEntity?
}
