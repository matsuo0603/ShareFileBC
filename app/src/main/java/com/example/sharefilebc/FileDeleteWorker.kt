package com.example.sharefilebc

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FileDeleteWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        Log.d("FileDeleteWorker", "▶ Worker 実行開始")
        FileDeleter.deleteExpiredFiles(applicationContext)
        Log.d("FileDeleteWorker", "✅ Worker 正常終了")
        Result.success()
    } catch (e: Exception) {
        Log.e("FileDeleteWorker", "❌ Worker 実行エラー", e)
        Result.failure()
    }
}
