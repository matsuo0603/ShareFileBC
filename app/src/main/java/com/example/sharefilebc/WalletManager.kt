package com.example.sharefilebc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wallet UI から呼ばれる “窓口” クラス。
 *
 * 重要:
 * - 以前の WalletManager が `com.chaintope.tapyrus.wallet` (uniffi) を直接使う構成だと、
 *   実機で JNA の native ライブラリが見つからずクラッシュ/失敗する
 *   （libjnidispatch.so が無い、など）。
 *
 * - そこで「鍵・アドレス生成」は KeyDerivation（旧 TapyrusWalletManager 相当）に統一し、
 *   残高は HTTP など “JNA不要の方法” で取得する方針にする。
 */
class WalletManager private constructor(private val appContext: Context) {

    private val keyDerivation: KeyDerivation = KeyDerivation.getInstance(appContext)

    companion object {
        @Volatile private var INSTANCE: WalletManager? = null

        fun getInstance(context: Context): WalletManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalletManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "WalletManager"
    }

    /**
     * Swift 版でいう「同期（sync）」の入口。
     * ここでは “JNAなし” 方針のため no-op にしておく。
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
        Log.d(TAG, "sync(): no-op (JNA-free, balance via HTTP)")
    }

    /**
     * UI で表示する “現在の受取アドレス” を返す。
     * 旧 TapyrusWalletManager の currentAddress(m/44'/0'/0'/0/0) 相当。
     */
    suspend fun getNewAddress(): String = withContext(Dispatchers.Default) {
        val addr = keyDerivation.getCurrentAddress()
        Log.d(TAG, "getNewAddress() address=$addr")
        addr
    }

    /**
     * 残高（satoshi）を返す。
     *
     * 注意:
     * - ここは “JNAを使わない” ので、Tapyrus の explorer API / 自前サーバ等の HTTP で取得する想定。
     * - まだ取得先が未確定なら、まずは 0 を返して UI を動かす（落とさない）方が安全。
     */
    suspend fun getBalance(): ULong = withContext(Dispatchers.IO) {
        val address = keyDerivation.getCurrentAddress()
        try {
            fetchBalanceSatoshi(address)
        } catch (e: Exception) {
            Log.e(TAG, "getBalance() failed: ${e.message}", e)
            0UL
        }
    }

    /**
     * 残高取得（HTTP）本体。
     *
     * ここは先輩（Swift版）で使っている “残高取得先” に合わせる必要がある。
     * まだURLやレスポンス形式が不明なら、いったん 0 を返す実装にして UI を先に完成させるのが良い。
     */
    private fun fetchBalanceSatoshi(address: String): ULong {
        // TODO: Swift 版が参照している explorer/endpoint に合わせて実装する。
        // 例（ダミー）:
        // val url = URL("https://example.com/balance?address=$address")
        // val conn = (url.openConnection() as HttpURLConnection).apply {
        //     requestMethod = "GET"
        //     connectTimeout = 10_000
        //     readTimeout = 10_000
        // }
        // conn.inputStream.use { ... JSONをparseして satoshi を返す ... }

        Log.w(TAG, "fetchBalanceSatoshi(): endpoint is not configured yet. address=$address")
        return 0UL
    }

    /**
     * 送金（transfer）
     *
     * DebugWalletActivity が呼ぶなら、関数だけでも用意してコンパイルを通す。
     * 実装は Tapyrus の送金API（Swift版の flow）に合わせて後で詰める。
     */
    suspend fun transfer(toAddress: String, amountSat: ULong): String = withContext(Dispatchers.IO) {
        // TODO: 先輩Swift版の送金処理と同等の仕様を決めて実装する
        Log.w(TAG, "transfer(): not implemented yet. to=$toAddress amountSat=$amountSat")
        "NOT_IMPLEMENTED"
    }
}
