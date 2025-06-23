package com.example.sharefilebc.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// バージョンを5に設定します (ReceivedFolderEntity にuploadDateフィールド追加のため)
@Database(entities = [UserEntity::class, SharedFolderEntity::class, ReceivedFolderEntity::class], version = 5)
abstract class AppDatabase : RoomDatabase() {
    // 抽象メソッドとして、各DAOへのアクセスを提供する
    abstract fun userDao(): UserDao
    abstract fun sharedFolderDao(): SharedFolderDao
    abstract fun receivedFolderDao(): ReceivedFolderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database" // データベースファイル名
                ).fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}