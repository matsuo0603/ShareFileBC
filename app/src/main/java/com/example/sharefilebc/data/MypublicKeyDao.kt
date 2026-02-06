package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MyPublicKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MyPublicKeyEntity)

    @Query("SELECT * FROM my_public_keys WHERE id = 1")
    suspend fun getPrimary(): MyPublicKeyEntity?

    @Query("DELETE FROM my_public_keys")
    suspend fun deleteAll()

    @Update
    suspend fun update(entity: MyPublicKeyEntity)
}
