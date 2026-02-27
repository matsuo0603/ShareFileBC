package com.example.sharefilebc.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        SentShareEntity::class,
        SharePaymentEntity::class,
    ],
    version = 15
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
    abstract fun sentShareDao(): SentShareDao
    abstract fun sharePaymentDao(): SharePaymentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sent_shares (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fileId TEXT NOT NULL,
                        folderId TEXT NOT NULL,
                        recipientEmail TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        threshold INTEGER NOT NULL,
                        senderAddress TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS share_payments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fileId TEXT NOT NULL,
                        folderId TEXT NOT NULL,
                        txid TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        paidAt TEXT NOT NULL,
                        payerRefundAddress TEXT,
                        result TEXT NOT NULL DEFAULT 'PAID'
                    )
                    """.trimIndent()
                )
            }
        }

        // ✅ received_files に「ダウンロード完了フラグ」を追加
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE received_files
                    ADD COLUMN isDownloaded INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                ).addMigrations(MIGRATION_13_14, MIGRATION_14_15)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}