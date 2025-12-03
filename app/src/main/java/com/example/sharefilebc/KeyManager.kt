package com.example.sharefilebc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chaintope.tapyrus.wallet.Network
import com.chaintope.tapyrus.wallet.generateMasterKey
import com.example.sharefilebc.crypto.BouncyCastleInitializer

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
    fun getOrCreateMasterXprv(): String {
        val existing = prefs.getString(KEY_XPRV, null)
        if (existing != null) {
            Log.d(TAG, "Using existing master xprv (length=${existing.length})")
            return existing
        }

        // Tapyrus ライブラリが secp256k1 を使う前に、BC プロバイダを確実に差し替える
        BouncyCastleInitializer.ensure()

        // Tapyrus ライブラリでマスター鍵を生成（Network.PROD）
        val newXprv = generateMasterKey(Network.PROD)
        Log.d(TAG, "Generated new master xprv (length=${newXprv.length})")
        prefs.edit().putString(KEY_XPRV, newXprv).apply()
        return newXprv
    }

    /** 既にマスター鍵が保存されているかどうか（デバッグ用など） */
    fun hasMasterXprv(): Boolean =
        prefs.contains(KEY_XPRV)

    /** マスター鍵を取得（存在しない場合は null） */
    fun getMasterXprvOrNull(): String? =
        prefs.getString(KEY_XPRV, null)

    /** マスター鍵を削除（再生成テスト用など） */
    fun clearMasterXprv() {
        prefs.edit().remove(KEY_XPRV).apply()
        Log.d(TAG, "Master xprv cleared")
    }
}
