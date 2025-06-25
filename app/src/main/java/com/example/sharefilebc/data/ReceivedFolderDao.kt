package com.example.sharefilebc.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceivedFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: ReceivedFolderEntity)

    @Query("SELECT * FROM received_folders ORDER BY uploadDateTime DESC")
    fun getAll(): Flow<List<ReceivedFolderEntity>>

    @Query("SELECT * FROM received_folders WHERE folderId = :folderId LIMIT 1")
    suspend fun findByFolderId(folderId: String): ReceivedFolderEntity?

    @Query("DELETE FROM received_folders WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: String)

    @Query("SELECT * FROM received_folders WHERE deleteDateTime <= :currentTime")
    suspend fun getExpiredFolders(currentTime: String): List<ReceivedFolderEntity>

    // 即時取得用（Flow ではなく suspend で取得）
    @Query("SELECT * FROM received_folders ORDER BY uploadDateTime DESC")
    suspend fun getAllOnce(): List<ReceivedFolderEntity>

    // ID指定で削除
    @Query("DELETE FROM received_folders WHERE id = :id")
    suspend fun deleteById(id: Int)

}
