package com.example.sharefilebc.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(user: UserEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity)

    @Transaction
    suspend fun upsertByEmail(user: UserEntity): Int {
        val insertedId = insertOrIgnore(user)
        if (insertedId != -1L) return insertedId.toInt()

        val existing = findByEmail(user.email)
        return if (existing != null) {
            update(user.copy(id = existing.id))
            existing.id
        } else {
            insert(user).toInt()
        }
    }

    @Query("SELECT * FROM users")
    fun getAll(): Flow<List<UserEntity>>

    @Query("DELETE FROM users WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("UPDATE users SET publicKeyHex = :publicKeyHex WHERE email = :email")
    suspend fun updatePublicKeyByEmail(email: String, publicKeyHex: String)

    @Query("UPDATE users SET folderIDFromMe = :folderId WHERE email = :email")
    suspend fun updateFolderIdByEmail(email: String, folderId: String)

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): UserEntity?
}