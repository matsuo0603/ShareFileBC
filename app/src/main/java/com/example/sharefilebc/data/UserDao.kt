package com.example.sharefilebc.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert
    suspend fun insert(user: UserEntity): Long

    @Query("SELECT * FROM users")
    fun getAll(): Flow<List<UserEntity>>

    @Query("DELETE FROM users WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("UPDATE users SET publicKeyHex = :publicKeyHex WHERE id = :id")
    suspend fun updatePublicKey(id: Int, publicKeyHex: String)

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?
}
