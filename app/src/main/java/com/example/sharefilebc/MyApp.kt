package com.example.sharefilebc

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // ✅ WorkManager によるバックグラウンド定期削除（15分ごと）
        FileDeleteScheduler.schedule(this)
        SyncScheduler.schedule(this)
        // ✅ アプリ起動時に即時削除処理を実行（非同期）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("MyApp", "🧹 アプリ起動時の削除処理開始")
                FileDeleter.deleteExpiredFiles(this@MyApp)
                Log.d("MyApp", "✅ アプリ起動時の削除処理完了")
            } catch (e: Exception) {
                Log.e("MyApp", "❌ アプリ起動時の削除処理失敗: ${e.message}")
            }
        }
    }
}
