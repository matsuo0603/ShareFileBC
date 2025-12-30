package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.DriveServiceHelper
import com.example.sharefilebc.data.EmailKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Swift版の「イベント駆動・即反映」に寄せるための同期処理本体。
 *
 * - sharedWithMe に存在する pubkey.json を検索
 * - 中身を解析して EmailKeyEntity に upsert
 *
 * WorkManager(Periodic) と UIイベント(ログイン直後/画面表示) の両方から同じ処理を呼べるように、
 * Worker からロジックを分離した。
 */
object PublicKeySyncer {

    private const val TAG = "PublicKeySyncer"

    /**
     * 公開鍵を 1 回同期する。
     * @return DB upsert 件数
     */
    suspend fun syncOnce(context: Context): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "▶ syncOnce start")

        val drive = runCatching { DriveServiceHelper.getDriveService(context) }
            .onFailure { Log.e(TAG, "❌ Drive 取得失敗", it) }
            .getOrNull() ?: return@withContext 0

        val db = AppDatabase.getDatabase(context)
        val emailKeyDao = db.emailKeyDao()

        val list = drive.files().list()
            .setQ("name='pubkey.json' and sharedWithMe and trashed=false")
            .setFields("files(id, name, parents, owners(emailAddress))")
            .execute()

        val files = list.files ?: emptyList()
        Log.d(TAG, "🔍 pubkey.json 検索結果: ${files.size} 件")

        var upsertCount = 0
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
                .ifBlank { "" }
            if (ownerEmail.isBlank()) {
                Log.w(TAG, "⚠️ ownerEmail 不明: ${file.id}")
                continue
            }

            val derived = json.optString("senderDerivedPublicKeyHex")
                .ifBlank { json.optString("derivedPublicKey") }
            val trustLayer = json.optString("trustLayerPublicKey")
                // 旧フィールド互換（Swift側/既存データとの整合のため）
                .ifBlank { json.optString("senderMasterPublicKeyHex") }

            if (derived.isBlank() || trustLayer.isBlank()) {
                Log.w(TAG, "⚠️ 公開鍵が不足: email=$ownerEmail")
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

        Log.d(TAG, "✅ DB upsert 件数: $upsertCount")
        Log.d(TAG, "✅ syncOnce end")
        upsertCount
    }
}
