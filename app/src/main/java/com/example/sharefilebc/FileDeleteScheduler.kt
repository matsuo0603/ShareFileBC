package com.example.sharefilebc

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

object FileDeleteScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FileDeleteWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build() // ✅ 15分ごとに変更
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "file_deletion_work",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.d("FileDeleteScheduler", "📅 削除スケジュール登録（15分ごと）")
    }
}
