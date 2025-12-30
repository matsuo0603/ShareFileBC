package com.example.sharefilebc

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.EmailKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PublicKeySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("PublicKeySyncWorker", "▶ Worker 実行開始")
        val context = applicationContext
        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e("PublicKeySyncWorker", "❌ Drive 取得失敗", it) }
            .getOrNull() ?: return Result.success()

        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val emailKeyDao = db.emailKeyDao()

                val list = drive.files().list()
                    .setQ("name='pubkey.json' and sharedWithMe and trashed=false")
                    .setFields("files(id, name, parents, owners(emailAddress))")
                    .execute()

                val files = list.files ?: emptyList()
                Log.d("PublicKeySyncWorker", "🔍 pubkey.json 検索結果: ${files.size} 件")

                var upsertCount = 0
                for (file in files) {
                    val content = drive.files().get(file.id)
                        .executeMediaAsInputStream()
                        .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

                    val json = runCatching { JSONObject(content) }.getOrNull()
                    if (json == null) {
                        Log.w("PublicKeySyncWorker", "⚠️ pubkey.json の JSON 解析に失敗: ${file.id}")
                        continue
                    }

                    val ownerEmail = json.optString("ownerEmail")
                        .ifBlank { file.owners?.firstOrNull()?.emailAddress.orEmpty() }
                        .ifBlank { "" }
                    if (ownerEmail.isBlank()) {
                        Log.w("PublicKeySyncWorker", "⚠️ ownerEmail 不明: ${file.id}")
                        continue
                    }

                    val derived = json.optString("senderDerivedPublicKeyHex")
                        .ifBlank { json.optString("derivedPublicKey") }
                    val trustLayer = json.optString("trustLayerPublicKey")
                        .ifBlank { json.optString("senderMasterPublicKeyHex") }
                    if (derived.isBlank() || trustLayer.isBlank()) {
                        Log.w("PublicKeySyncWorker", "⚠️ 公開鍵が不足: email=$ownerEmail")
                        continue
                    }

                    val folderId = file.parents?.firstOrNull().orEmpty()
                    val existing = emailKeyDao.findByEmail(ownerEmail)
                    val current = existing?.let {
                        it.derivedPublicKey == derived &&
                                it.trustLayerPublicKey == trustLayer &&
                                it.folderIDFromPartner == folderId
                    } ?: false
                    if (!current) {
                        emailKeyDao.upsert(
                            EmailKeyEntity(
                                email = ownerEmail,
                                derivedPublicKey = derived,
                                trustLayerPublicKey = trustLayer,
                                folderIDFromPartner = folderId,
                                isRefundRejected = existing?.isRefundRejected ?: false
                            )
                        )
                        upsertCount += 1
                    }
                }

                Log.d("PublicKeySyncWorker", "✅ DB upsert 件数: $upsertCount")
                Log.d("PublicKeySyncWorker", "✅ Worker 正常終了")
                Result.success()
            } catch (e: Exception) {
                Log.e("PublicKeySyncWorker", "❌ Worker 実行エラー", e)
                Result.failure()
            }
        }
    }
}
