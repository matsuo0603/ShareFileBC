package com.example.sharefilebc

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import java.util.concurrent.TimeUnit

object FileDeleteScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FileDeleteWorker>(15, TimeUnit.MINUTES).build() // ✅ 15分ごとに変更
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "file_deletion_work",
            //TODO 本番ではREPLACEをKEEPに変更
            ExistingPeriodicWorkPolicy.REPLACE, // ← テスト中はこれがおすすめ
            request
        )
        Log.d("FileDeleteScheduler", "📅 削除スケジュール登録（15分ごと）")
    }
}
