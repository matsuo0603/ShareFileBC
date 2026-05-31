package com.example.sharefilebc

import android.util.Log
import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import java.security.MessageDigest

/**
 * 🔎 暗号まわりの「役割の整合性」を切り分けるためのログユーティリティ。
 *
 * 目的:
 * - 「鍵が違う」のか
 * - 「ECIES実装差（sharedSecret/KDF等）」なのか
 * を “推測” ではなくログで確定させる。
 *
 * 注意:
 * - 本番で秘密鍵を全文ログに出すのは危険。
 * - ここでは原則マスク＋fingerprint(SHA-256) を出す。
 */
object CryptoTrace {

    private const val TAG = "CryptoTrace"

    /**
     * どうしても “秘密鍵全文” をログに出す必要がある場合だけ true。
     *
     * ✅ ふだんは false のままにしておくこと。
     */
    private const val ALLOW_FULL_PRIVATE_KEY_LOG: Boolean = false

    fun maskHex(hex: String, keepHead: Int = 10, keepTail: Int = 6): String {
        val s = hex.trim()
        if (s.isEmpty()) return "(empty)"
        if (s.length <= keepHead + keepTail) return s
        return s.take(keepHead) + "..." + s.takeLast(keepTail)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).toHexString()
    }

    fun sha256HexOfHex(hex: String): String = runCatching { sha256Hex(hex.hexToByteArray()) }.getOrElse { "(sha256 failed)" }

    fun logSendKeyRoles(
        event: String,
        recipientEmail: String,
        recipientDerivedKey: String?,
        recipientTrustLayerKey: String?,
        recipientPubKeyUsedForECIES: String,
        signerPath: String,
        signerPubKeyUsed: String,
        senderParamInserted: String
    ) {
        Log.d(
            TAG,
            buildString {
                append("[KEY_ROLE][SEND][$event] ")
                append("recipientEmail=$recipientEmail ")
                append("recipientDerived=${recipientDerivedKey?.let { maskHex(it) } ?: "(null)"} ")
                append("recipientTrust=${recipientTrustLayerKey?.let { maskHex(it) } ?: "(null)"} ")
                append("recipientECIES_USED=${maskHex(recipientPubKeyUsedForECIES)} ")
                append("signerPath=$signerPath ")
                append("signerPub_USED=${maskHex(signerPubKeyUsed)} ")
                append("senderParam=${maskHex(senderParamInserted)}")
            }
        )
    }

    fun logMyKeySnapshot(
        event: String,
        path0: String,
        pub0: String,
        priv0Hex: String,
        path1: String,
        pub1: String,
        priv1Hex: String
    ) {
        val priv0Log = if (ALLOW_FULL_PRIVATE_KEY_LOG) priv0Hex else maskHex(priv0Hex)
        val priv1Log = if (ALLOW_FULL_PRIVATE_KEY_LOG) priv1Hex else maskHex(priv1Hex)

        Log.d(
            TAG,
            buildString {
                append("[KEY_SNAPSHOT][$event] ")
                append("p0=$path0 pub0=${maskHex(pub0)} priv0=$priv0Log priv0fp=${sha256HexOfHex(priv0Hex).take(16)}... ")
                append("p1=$path1 pub1=${maskHex(pub1)} priv1=$priv1Log priv1fp=${sha256HexOfHex(priv1Hex).take(16)}...")
            }
        )
    }

    fun logReceiveKeyRoles(
        event: String,
        senderParam: String,
        signerPubKeyUsedForVerify: String,
        recipientPathUsedForDecrypt: String,
        recipientPrivKeyHexUsed: String,
        recipientPubDerivedFromPriv: String
    ) {
        val privLog = if (ALLOW_FULL_PRIVATE_KEY_LOG) recipientPrivKeyHexUsed else maskHex(recipientPrivKeyHexUsed)

        Log.d(
            TAG,
            buildString {
                append("[KEY_ROLE][RECV][$event] ")
                append("senderParam=${maskHex(senderParam)} ")
                append("signerPub_VERIFY=${maskHex(signerPubKeyUsedForVerify)} ")
                append("recipientPath=$recipientPathUsedForDecrypt ")
                append("recipientPriv_USED=$privLog privfp=${sha256HexOfHex(recipientPrivKeyHexUsed).take(16)}... ")
                append("recipientPubFromPriv=${maskHex(recipientPubDerivedFromPriv)}")
            }
        )
    }
}
