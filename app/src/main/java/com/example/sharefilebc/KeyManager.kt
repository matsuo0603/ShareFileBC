package com.example.sharefilebc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chaintope.tapyrus.wallet.Network
import com.chaintope.tapyrus.wallet.generateMasterKey
import com.example.sharefilebc.crypto.BouncyCastleInitializer
import java.security.MessageDigest

/**
 * マスター鍵(xprv)の管理だけを行うクラス。
 *
 * ※ 今は開発を進めるために、暗号化なしの SharedPreferences で保存している。
 *    本番運用時には EncryptedSharedPreferences 等での暗号化保存に差し替えること。
 *
 * - 初回だけ Tapyrus の generateMasterKey(Network.PROD) で xprv を生成
 * - 生成した xprv は SharedPreferences に保存
 * - 2回目以降は保存済みの xprv をそのまま再利用
 *
 * ウォレット（残高・送金など）の機能はここでは一切扱わない。
 */
class KeyManager private constructor(context: Context) {

    companion object {
        private const val TAG = "KeyManager"
        private const val PREF_FILE = "wallet_master_key_prefs"
        private const val KEY_XPRV = "master_xprv"
        private const val KEY_NETWORK = "master_network"

        @Volatile
        private var instance: KeyManager? = null

        fun getInstance(context: Context): KeyManager =
            instance ?: synchronized(this) {
                instance ?: KeyManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /**
     * マスター鍵を取得する。
     * - 既に保存済みならそれを返す
     * - なければ新規生成して保存してから返す
     */
    fun getOrCreateMasterXprv(networkMode: Network = Network.PROD): String {
        val existing = prefs.getString(KEY_XPRV, null)
        if (existing != null) {
            val savedNet = prefs.getString(KEY_NETWORK, null)
            if (savedNet != null && savedNet != networkMode.name) {
                // ネットワーク設定が変わっても、勝手にマスター鍵を作り直すと
                // 暗号・署名・ウォレットDBの整合が崩壊するので「警告だけ」出す。
                Log.w(
                    TAG,
                    "⚠️ Master xprv already exists but network changed: saved=$savedNet current=${networkMode.name} (keep existing)"
                )
            }
            Log.d(TAG, "Using existing master xprv (length=${existing.length}) fp=${fingerprintOfXprv(existing)}")
            return existing
        }

        // Tapyrus ライブラリが secp256k1 を使う前に、BC プロバイダを確実に差し替える
        BouncyCastleInitializer.ensure()

        // Tapyrus ライブラリでマスター鍵を生成
        val newXprv = generateMasterKey(networkMode)
        val fp = fingerprintOfXprv(newXprv)
        Log.d(TAG, "Generated new master xprv (length=${newXprv.length}) fp=$fp network=${networkMode.name}")
        prefs.edit()
            .putString(KEY_XPRV, newXprv)
            .putString(KEY_NETWORK, networkMode.name)
            .apply()
        return newXprv
    }

    /**
     * master xprv の "指紋"（復号トラブルの切り分け用）
     *
     * - 秘密情報そのものをログに出さない
     * - 端末内で master が変わっていないかを判定するための短い値
     */
    fun getMasterXprvFingerprintOrNull(): String? {
        val xprv = prefs.getString(KEY_XPRV, null) ?: return null
        return fingerprintOfXprv(xprv)
    }

    /** 既にマスター鍵が保存されているかどうか（デバッグ用など） */
    fun hasMasterXprv(): Boolean =
        prefs.contains(KEY_XPRV)

    /** マスター鍵を取得（存在しない場合は null） */
    fun getMasterXprvOrNull(): String? =
        prefs.getString(KEY_XPRV, null)

    /** マスター鍵を削除（再生成テスト用など） */
    fun clearMasterXprv() {
        val fp = getMasterXprvFingerprintOrNull()
        prefs.edit().remove(KEY_XPRV).apply()
        Log.d(TAG, "Master xprv cleared (previous fp=$fp)")
    }

    private fun fingerprintOfXprv(xprv: String): String {
        // Base58デコード等までは不要。
        // "同じ文字列かどうか" を安全に比較するための hash を作る。
        val digest = MessageDigest.getInstance("SHA-256").digest(xprv.toByteArray(Charsets.UTF_8))
        // 先頭12byteだけ出す（短くて十分）
        val short = digest.copyOfRange(0, 12)
        return android.util.Base64.encodeToString(short, android.util.Base64.NO_WRAP)
    }
}
