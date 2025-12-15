package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RefundTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RefundTaskEntity)

    @Query("SELECT * FROM refund_tasks")
    suspend fun getAll(): List<RefundTaskEntity>
}