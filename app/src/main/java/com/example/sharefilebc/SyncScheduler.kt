package com.example.sharefilebc

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val publicKeyRequest = PeriodicWorkRequestBuilder<PublicKeySyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val incomingFilesRequest = PeriodicWorkRequestBuilder<IncomingFilesSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "PublicKeySyncWorker",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            publicKeyRequest
        )

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "IncomingFilesSyncWorker",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            incomingFilesRequest
        )
    }
}