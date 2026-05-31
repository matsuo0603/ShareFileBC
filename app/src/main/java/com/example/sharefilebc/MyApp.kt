package com.example.sharefilebc

import android.app.Application
import android.util.Log

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // ✅ WorkManager によるバックグラウンド定期削除（15分ごと）
        FileDeleteScheduler.schedule(this)
        SyncScheduler.schedule(this)

        // ❗️重要: 起動直後（特に初回ログイン前）に Drive/DB を触ると ANR の原因になる。
        // 削除処理は WorkManager（FileDeleteWorker）に任せ、ここでは実行しない。
        Log.d("MyApp", "✅ Schedulers registered")
    }
}
