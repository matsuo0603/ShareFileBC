package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReceivedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReceivedFileEntity)

    @Query("SELECT * FROM received_files")
    suspend fun getAll(): List<ReceivedFileEntity>

    /**
     * ShareProcessor.processReceivedShare で使用
     * shareIDで検索して該当レコードを取得
     */
    @Query("SELECT * FROM received_files WHERE shareID = :shareId LIMIT 1")
    suspend fun findByShareId(shareId: String): ReceivedFileEntity?
}