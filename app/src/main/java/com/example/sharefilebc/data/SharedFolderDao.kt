package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sharedFolder: SharedFolderEntity)

    @Query("SELECT * FROM shared_folders WHERE date = :selectedDate ORDER BY date DESC")
    fun getFilesByDate(selectedDate: String): Flow<List<SharedFolderEntity>>

    @Query("SELECT * FROM shared_folders ORDER BY date DESC, id DESC")
    fun getAll(): Flow<List<SharedFolderEntity>>

    @Query("SELECT * FROM shared_folders ORDER BY date DESC, id DESC")
    suspend fun getAllOnce(): List<SharedFolderEntity>

    @Query("DELETE FROM shared_folders WHERE id = :id")
    suspend fun deleteById(id: Int)
}
