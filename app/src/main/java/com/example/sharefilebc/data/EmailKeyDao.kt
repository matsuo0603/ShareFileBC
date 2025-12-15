package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: EmailKeyEntity)

    @Query("SELECT * FROM email_keys")
    fun getAll(): Flow<List<EmailKeyEntity>>

    @Query("SELECT * FROM email_keys WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): EmailKeyEntity?

    @Update
    suspend fun update(key: EmailKeyEntity)
}