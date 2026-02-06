// RecievedFileDao.kt
package com.example.sharefilebc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceivedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReceivedFileEntity)

    /**
     * ✅ shareID をキーとして upsert する。
     * ReceivedFileEntity の主キーは autoGenerate の id なので、
     * 既存レコードがある場合は id を引き継いで REPLACE を発動させる。
     */
    @Transaction
    suspend fun upsert(entity: ReceivedFileEntity) {
        val sid = entity.shareID
        if (sid.isNullOrBlank()) {
            // shareID が無い場合は素直に insert（新規）
            insert(entity)
            return
        }
        val existing = findByShareId(sid)
        if (existing == null) {
            insert(entity)
        } else {
            insert(entity.copy(id = existing.id))
        }
    }

    @Query("SELECT * FROM received_files")
    suspend fun getAll(): List<ReceivedFileEntity>

    // ✅ UI表示用（DownloadScreen は Room の結果を表示するだけ）
    @Query("SELECT * FROM received_files ORDER BY id DESC")
    fun observeAll(): Flow<List<ReceivedFileEntity>>

    /**
     * ShareProcessor.processReceivedShare で使用
     * shareIDで検索して該当レコードを取得
     */
    @Query("SELECT * FROM received_files WHERE shareID = :shareId LIMIT 1")
    suspend fun findByShareId(shareId: String): ReceivedFileEntity?
}
