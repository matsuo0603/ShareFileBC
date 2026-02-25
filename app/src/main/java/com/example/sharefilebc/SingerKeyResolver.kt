package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.example.sharefilebc.data.AppDatabase

/**
 * ✅ 署名検証用公開鍵の解決器（仕様反映版）。
 *
 * 【仕様】
 * - 共有URLの sender= には「TrustLayer 公開鍵」を載せる（sender 識別子）。
 * - nameMeta / vpfs の署名検証は「派生公開鍵（derived）」で行う（暗号処理の本体側）。
 * - そのため受信時のみ、sender(TrustLayer) -> derivedPublicKey へのマッピングが必要。
 * - 未登録の場合のみフォールバックで sender(TrustLayer) をそのまま検証鍵として試す。
 */
object SignerKeyResolver {
    private const val TAG = "SignerKeyResolver"

    /**
     * senderParamPubKeyHex（= TrustLayer 公開鍵）から「署名検証候補の公開鍵」を優先順で返す。
     *
     * 優先順:
     *  1) EmailKey(trustLayerPublicKey == senderParam) の derivedPublicKey
     *  2) （senderEmail が分かる場合のみ）EmailKey(email) の derivedPublicKey
     *  3) senderParam 自体（未登録時のフォールバック）
     */
    suspend fun resolveCandidates(
        context: Context,
        senderParamPubKeyHex: String,
        senderEmailOrNull: String? = null
    ): List<String> {
        val senderParam = senderParamPubKeyHex.trim()
        if (senderParam.isBlank()) return emptyList()

        val db = AppDatabase.getDatabase(context)

        // 仕様通り: senderParam(TrustLayer) で逆引きして derived を得る
        val byTrust = runCatching {
            db.emailKeyDao().findByTrustLayerPublicKey(senderParam)
        }.getOrNull()

        // 送信者メールが分かるときだけ、メールでも補助的に探す（sender と不整合な場合の救済）
        val byEmail = if (!senderEmailOrNull.isNullOrBlank()) {
            runCatching { db.emailKeyDao().findByEmail(senderEmailOrNull.trim()) }.getOrNull()
        } else null

        val candidates = linkedSetOf<String>()

        val derivedFromTrust = byTrust?.derivedPublicKey?.trim().orEmpty()
        if (derivedFromTrust.isNotBlank()) candidates.add(derivedFromTrust)

        val derivedFromEmail = byEmail?.derivedPublicKey?.trim().orEmpty()
        if (derivedFromEmail.isNotBlank()) candidates.add(derivedFromEmail)

        // 最後に senderParam 自体（未登録フォールバック）
        candidates.add(senderParam)

        val out = candidates.toList()
        Log.d(
            TAG,
            "resolveCandidates sender(TrustLayer)=${senderParam.take(16)}... email=${senderEmailOrNull ?: "(none)"} -> ${out.map { it.take(16) + "..." }}"
        )
        return out
    }
}
