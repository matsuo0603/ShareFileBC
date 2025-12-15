package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MySharedFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MySharedFolderEntity)

    @Query("SELECT * FROM my_shared_folders")
    suspend fun getAll(): List<MySharedFolderEntity>
}