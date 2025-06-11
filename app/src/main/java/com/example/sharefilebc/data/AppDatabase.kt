package com.example.sharefilebc.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 必要なエンティティとDAOのインポートを全てここに記述します
// 全てのファイルが com.example.sharefilebc.data パッケージ直下にあるので、シンプルなインポートでOK
import com.example.sharefilebc.data.UserEntity
import com.example.sharefilebc.data.UserDao
import com.example.sharefilebc.data.SharedFolderEntity
import com.example.sharefilebc.data.SharedFolderDao
import com.example.sharefilebc.data.ReceivedFolderEntity // ★ここを ReceivedFolderEntity に修正
import com.example.sharefilebc.data.ReceivedFolderDao // ★ここを ReceivedFolderDao に修正

// バージョンを4に設定します (ReceivedFolderEntity 追加のため)
@Database(entities = [UserEntity::class, SharedFolderEntity::class, ReceivedFolderEntity::class], version = 4) // ★ここも ReceivedFolderEntity に修正
abstract class AppDatabase : RoomDatabase() {
    // 抽象メソッドとして、各DAOへのアクセスを提供します
    abstract fun userDao(): UserDao
    abstract fun sharedFolderDao(): SharedFolderDao
    abstract fun receivedFolderDao(): ReceivedFolderDao // ★ここも receivedFolderDao に修正

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database" // データベースファイル名
                ).fallbackToDestructiveMigration() // スキーマ変更時にデータベースを再構築するオプション
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}