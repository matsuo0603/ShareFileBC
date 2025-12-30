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
        Log.d(TAG, "▶ Worker 実行開始")
        return try {
            val count = IncomingFilesSyncer.syncOnce(applicationContext)
            Log.d(TAG, "✅ syncOnce upsert 件数: $count")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker 実行エラー", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "IncomingFilesSyncWorker"
    }
}
