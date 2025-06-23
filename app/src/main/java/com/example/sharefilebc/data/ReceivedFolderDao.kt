package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceivedFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receivedFolder: ReceivedFolderEntity)

    @Update
    suspend fun update(receivedFolder: ReceivedFolderEntity)

    // ✅ UIで使う：リアルタイムに監視する用（Flow）
    @Query("SELECT * FROM received_folders ORDER BY lastAccessDate DESC")
    fun getAll(): Flow<List<ReceivedFolderEntity>>

    // ✅ バッチ処理で使う：即時取得用
    @Query("SELECT * FROM received_folders ORDER BY lastAccessDate DESC")
    suspend fun getAllOnce(): List<ReceivedFolderEntity>

    // 特定のフォルダIDが既に保存されているかチェック
    @Query("SELECT * FROM received_folders WHERE folderId = :folderId LIMIT 1")
    suspend fun findByFolderId(folderId: String): ReceivedFolderEntity?

    // 最後のアクセス日時を更新
    @Query("UPDATE received_folders SET lastAccessDate = :accessDate WHERE folderId = :folderId")
    suspend fun updateLastAccessDate(folderId: String, accessDate: String)

    @Query("DELETE FROM received_folders WHERE id = :id")
    fun deleteById(id: Int)

    @Query("DELETE FROM received_folders WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: String)

}