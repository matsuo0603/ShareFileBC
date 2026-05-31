package com.example.sharefilebc

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 15分周期の保険（取りこぼし回収）用 Worker。
 *
 * Swift版に寄せた「イベント駆動・即反映」の主役は Syncer 側。
 * Worker は同期処理本体を持たず、[IncomingFilesSyncer] を呼ぶだけにする。
 */
class IncomingFilesSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(LogTags.TAG_SYNC_INCOMING, "worker start")
        return try {
            // ✅ 自動受信ルートはアプリ起動外でも走る。
            // Wallet が未初期化だと processReceivedShare 内で IllegalStateException("Wallet not initialized")
            // になり、P2C検証/トークン反映が進まない。
            WalletManager.getInstance(applicationContext).initializeIfNeeded()

            val count = IncomingFilesSyncer.syncOnce(applicationContext)
            Log.d(LogTags.TAG_SYNC_INCOMING, "worker done upsert=$count")
            Result.success()
        } catch (e: Exception) {
            Log.e(LogTags.TAG_SYNC_INCOMING, "worker error", e)
            Result.failure()
        }
    }
}
