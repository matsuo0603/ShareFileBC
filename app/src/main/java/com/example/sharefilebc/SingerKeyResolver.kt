package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase

/**
 * ✅ Swift版互換のための「署名検証用公開鍵」解決器。
 *
 * Swift 側は以下が起こり得る：
 * - SecurePackage.create の signingPrivateKeyHex が「派生鍵（derived）」
 * - しかし共有リンクの sender= が「TrustLayer鍵」になっている（または逆）
 *
 * その結果、Android が sender= の公開鍵だけで verify すると signature verify failed になる。
 *
 * そこで、senderParam（リンクの sender=）に加えて、DB(email_keys) に保存してある
 * derived/trust のペアを使い、検証候補鍵を複数提示する。
 */
object SignerKeyResolver {
    private const val TAG = "SignerKeyResolver"

    /**
     * senderParamPubKeyHex から「検証候補の公開鍵」を優先順で返す。
     * - まず senderParam を先頭
     * - senderParam が email_keys の derived/trust のどちらかに一致する場合、もう片方も候補に追加
     */
    suspend fun resolveCandidates(
        context: Context,
        senderParamPubKeyHex: String,
        senderEmailOrNull: String? = null
    ): List<String> {
        val senderParam = senderParamPubKeyHex.trim()
        if (senderParam.isBlank()) return emptyList()

        val db = AppDatabase.getDatabase(context)

        val entry = runCatching {
            when {
                !senderEmailOrNull.isNullOrBlank() -> db.emailKeyDao().findByEmail(senderEmailOrNull.trim())
                else -> db.emailKeyDao().findByAnyPublicKey(senderParam)
            }
        }.getOrNull()

        val derived = entry?.derivedPublicKey?.trim().orEmpty()
        val trust = entry?.trustLayerPublicKey?.trim().orEmpty()

        val candidates = linkedSetOf<String>()
        candidates.add(senderParam)

        // senderParam と「同一人物の別鍵」を候補に追加
        if (derived.isNotBlank() && !derived.equals(senderParam, ignoreCase = true)) {
            // senderParam が trust なら derived を、senderParam が derived なら trust を優先して追加
            if (trust.isNotBlank() && trust.equals(senderParam, ignoreCase = true)) {
                candidates.add(derived)
            }
        }
        if (trust.isNotBlank() && !trust.equals(senderParam, ignoreCase = true)) {
            if (derived.isNotBlank() && derived.equals(senderParam, ignoreCase = true)) {
                candidates.add(trust)
            }
        }

        // senderParam がどちらとも一致しない場合でも、「同一メールの両方」を追加（senderEmail 指定時）
        if (!senderEmailOrNull.isNullOrBlank()) {
            if (derived.isNotBlank()) candidates.add(derived)
            if (trust.isNotBlank()) candidates.add(trust)
        }

        val out = candidates.toList()
        Log.d(TAG, "resolveCandidates senderParam=${senderParam.take(16)}... email=${senderEmailOrNull ?: "(none)"} -> ${out.map { it.take(16) + "..." }}")
        return out
    }
}
