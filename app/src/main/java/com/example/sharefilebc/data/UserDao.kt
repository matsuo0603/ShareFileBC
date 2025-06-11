package com.example.sharefilebc.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert
    suspend fun insert(user: UserEntity)

    @Query("SELECT * FROM users")
    fun getAll(): Flow<List<UserEntity>>

    @Query("DELETE FROM users WHERE name = :name")
    suspend fun deleteByName(name: String)
}
