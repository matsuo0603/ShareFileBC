package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.EmailKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object PublicKeySyncer {

    private const val TAG = "PublicKeySyncer"

    private data class Candidate(
        val email: String,
        val derived: String,
        val trustLayer: String,
        val folderId: String,
        val timeMillis: Long,
        val fileId: String
    )

    suspend fun syncOnce(context: Context): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "▶ syncOnce start")

        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e(TAG, "❌ Drive 取得失敗", it) }
            .getOrNull() ?: return@withContext 0

        val db = AppDatabase.getDatabase(context)
        val emailKeyDao = db.emailKeyDao()

        // ✅ modifiedTime/createdTime を取得して “最新” を選ぶ
        val list = drive.files().list()
            .setQ("name='pubkey.json' and sharedWithMe and trashed=false")
            .setFields("files(id, name, parents, owners(emailAddress), modifiedTime, createdTime)")
            .execute()

        val files = list.files ?: emptyList()
        Log.d(TAG, "🔍 pubkey.json 検索結果: ${files.size} 件")

        val bestByEmail = mutableMapOf<String, Candidate>()

        for (file in files) {
            val content = drive.files().get(file.id)
                .executeMediaAsInputStream()
                .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

            val json = runCatching { JSONObject(content) }.getOrNull()
            if (json == null) {
                Log.w(TAG, "⚠️ pubkey.json の JSON 解析に失敗: ${file.id}")
                continue
            }

            val ownerEmail = json.optString("ownerEmail")
                .ifBlank { file.owners?.firstOrNull()?.emailAddress.orEmpty() }
                .trim()

            if (ownerEmail.isBlank()) {
                Log.w(TAG, "⚠️ ownerEmail 不明: ${file.id}")
                continue
            }

            val derived = json.optString("senderDerivedPublicKeyHex")
                .ifBlank { json.optString("derivedPublicKey") }
                .trim()

            val trustLayer = json.optString("trustLayerPublicKey")
                .ifBlank { json.optString("senderMasterPublicKeyHex") }
                .trim()

            if (derived.isBlank() || trustLayer.isBlank()) {
                Log.w(TAG, "⚠️ 公開鍵が不足: email=$ownerEmail fileId=${file.id}")
                continue
            }

            val folderId = file.parents?.firstOrNull().orEmpty()
            val timeMillis = file.modifiedTime?.value
                ?: file.createdTime?.value
                ?: 0L

            val cand = Candidate(
                email = ownerEmail,
                derived = derived,
                trustLayer = trustLayer,
                folderId = folderId,
                timeMillis = timeMillis,
                fileId = file.id
            )

            val prev = bestByEmail[ownerEmail]
            if (prev == null || cand.timeMillis > prev.timeMillis) {
                bestByEmail[ownerEmail] = cand
            }
        }

        var upsertCount = 0
        for ((email, cand) in bestByEmail) {
            val existing = emailKeyDao.findByEmail(email)

            val current = existing?.let {
                it.derivedPublicKey.trim() == cand.derived &&
                        it.trustLayerPublicKey.trim() == cand.trustLayer &&
                        it.folderIDFromPartner == cand.folderId
            } ?: false

            if (!current) {
                emailKeyDao.upsert(
                    EmailKeyEntity(
                        email = cand.email,
                        derivedPublicKey = cand.derived,
                        trustLayerPublicKey = cand.trustLayer,
                        folderIDFromPartner = cand.folderId,
                        isRefundRejected = existing?.isRefundRejected ?: false
                    )
                )
                upsertCount++

                Log.d(
                    TAG,
                    "✅ upsert: email=$email derived=${cand.derived.take(16)}... time=${cand.timeMillis} fileId=${cand.fileId}"
                )
            } else {
                Log.d(TAG, "↩ skip(current): email=$email")
            }
        }

        Log.d(TAG, "✅ DB upsert 件数: $upsertCount")
        Log.d(TAG, "✅ syncOnce end")
        upsertCount
    }
}
