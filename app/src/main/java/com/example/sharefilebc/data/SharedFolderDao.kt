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

    // 特定の日付に共有されたすべてのファイルの情報を取得するためのクエリ
    // RoomがSharedFolderEntityの新しい構造に対応してくれるため、これはこれでOK
    @Query("SELECT * FROM shared_folders WHERE date = :selectedDate ORDER BY date DESC")
    fun getFilesByDate(selectedDate: String): Flow<List<SharedFolderEntity>>

    // すべての共有履歴を取得 (日付のソートはComposable側で行う)
    @Query("SELECT * FROM shared_folders ORDER BY date DESC, id DESC") // 新しいものを上にするための並び順
    fun getAll(): Flow<List<SharedFolderEntity>>
}