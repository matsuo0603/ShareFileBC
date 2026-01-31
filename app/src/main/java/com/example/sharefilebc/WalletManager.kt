package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.chaintope.tapyrus.wallet.Config
import com.chaintope.tapyrus.wallet.HdWallet
import com.chaintope.tapyrus.wallet.TransferParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.Security

/**
 * Tapyrus Wallet の Android 側エントリポイント。
 *
 * - Swift版 TapyrusWalletManager.swift に相当
 * - AccountScreen のトークン設定（しきい値/送金量）は「このWalletを呼ぶUI側の話」で、
 *   ネットワーク接続設定（genesisHash/esploraUrl/networkId）とは別概念
 *
 * このプロジェクトで使っている Tapyrus Wallet AAR のAPI仕様に合わせている：
 * - getNewAddress(colorId: String?) で colorId は nullable（TPC= null）
 * - balance(colorId: String?) の返り値は ULong
 * - TransferParams.amount は ULong
 */
class WalletManager private constructor(
    private val appContext: Context
) {

    companion object {
        @Volatile
        private var INSTANCE: WalletManager? = null

        fun getInstance(context: Context): WalletManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalletManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Tapyrus 接続設定
     *
     * - 既定値は WalletSettingsManager の PROD プリセット
     * - AccountScreen の「ネットワーク設定」で CUSTOM を保存した場合はそちらを参照
     */
    private object WalletRuntimeConfig {
        /**
         * TPC（無色コイン）は colorId を渡さず null にする（Tapyrus Wallet SDK の仕様）
         *
         * - ""（空文字）や "00..00"（64桁ゼロ）は InvalidColorId になる
         */
        val TPC_COLOR_ID: String? = null
    }

    private val initMutex = Mutex()
    private var wallet: HdWallet? = null

    private suspend fun getOrCreateWallet(): HdWallet = initMutex.withLock {
        wallet?.let { return it }

        ensureBouncyCastle()

        val masterXprv = KeyManager.getInstance(appContext).getOrCreateMasterXprv()
        val dbPath = appContext.getDatabasePath("tapyrus_wallet.db").absolutePath

        val networkConfig = WalletSettingsManager.getInstance(appContext).getNetworkConfig()
        val config = Config(
            networkMode = networkConfig.networkMode,
            networkId = networkConfig.networkId,
            genesisHash = networkConfig.genesisHash,
            esploraUrl = networkConfig.esploraUrl,
            masterKey = masterXprv,
            dbFilePath = dbPath
        )

        val w = HdWallet(config)
        wallet = w
        Log.d(LogTags.TAG_WALLET, "HdWallet initialized. db=$dbPath")
        w
    }

    /**
     * ウォレット初期化だけを行う（HomeActivity / DebugWalletActivity から呼ぶ用）。
     *
     * - sync まではしない
     * - “setupWallet() が無い” エラー対策
     */
    suspend fun setupWallet() = withContext(Dispatchers.IO) {
        getOrCreateWallet()
        Log.d(LogTags.TAG_WALLET, "setupWallet done")
    }

    /** ブロックチェーン同期 */
    suspend fun sync() = withContext(Dispatchers.IO) {
        val w = getOrCreateWallet()
        w.sync()
        Log.d(LogTags.TAG_WALLET, "sync done")
    }

    /** 新規 TPC 受取アドレス */
    suspend fun getNewAddress(): String = withContext(Dispatchers.IO) {
        val w = getOrCreateWallet()

        // ✅ TPC は colorId = null
        val result = w.getNewAddress(WalletRuntimeConfig.TPC_COLOR_ID)

        Log.d(LogTags.TAG_WALLET, "new address=${result.address}")
        result.address
    }

    /** 残高（TPC） */
    suspend fun getBalance(): ULong = withContext(Dispatchers.IO) {
        val w = getOrCreateWallet()

        // ✅ TPC は colorId = null
        val balance: ULong = w.balance(WalletRuntimeConfig.TPC_COLOR_ID)

        Log.d(LogTags.TAG_WALLET, "balance=$balance")
        balance
    }

    /**
     * 送金（TPC）
     *
     * - UI側は ULong で扱ってOK（AccountScreenの送金量など）
     * - Tapyrus Wallet AAR 側も amount=ULong の定義になっているので変換しない
     */
    suspend fun transfer(
        toAddress: String,
        amountSat: ULong
    ): String = withContext(Dispatchers.IO) {

        val w = getOrCreateWallet()

        val params = listOf(
            TransferParams(
                amount = amountSat,      // ← ULong
                toAddress = toAddress
                // ※ このAARでは TransferParams に colorId が無い（エラーで確定）
            )
        )

        val txid = w.transfer(params, emptyList())
        Log.d(LogTags.TAG_WALLET, "transfer txid=$txid")
        txid
    }

    private fun ensureBouncyCastle() {
        try {
            if (Security.getProvider("BC") != null) return
        } catch (_: Exception) {
        }
    }

    fun resetWallet() {
        wallet = null
    }
}
