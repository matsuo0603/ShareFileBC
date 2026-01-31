package com.example.sharefilebc.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        SharedFolderEntity::class,
        ReceivedFolderEntity::class,
        EmailKeyEntity::class,
        MyPublicKeyEntity::class,
        MySharedFolderEntity::class,
        ReceivedFileEntity::class,
        RefundTaskEntity::class,
        BlockedSenderEntity::class,
    ],
    version = 13
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sharedFolderDao(): SharedFolderDao
    abstract fun receivedFolderDao(): ReceivedFolderDao
    abstract fun emailKeyDao(): EmailKeyDao
    abstract fun myPublicKeyDao(): MyPublicKeyDao
    abstract fun mySharedFolderDao(): MySharedFolderDao
    abstract fun receivedFileDao(): ReceivedFileDao
    abstract fun refundTaskDao(): RefundTaskDao
    abstract fun blockedSenderDao(): BlockedSenderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}