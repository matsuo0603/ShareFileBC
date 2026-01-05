package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.chaintope.tapyrus.wallet.Config
import com.chaintope.tapyrus.wallet.HdWallet
import com.chaintope.tapyrus.wallet.Network
import com.chaintope.tapyrus.wallet.TransferParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tapyrus Wallet 機能（あなたが開いている HdWallet/Config の実定義に合わせた版）
 *
 * - Config は 9引数版（networkId/genesisHash/esploraUrl/masterKey/dbFilePath）
 * - balance / getNewAddress は colorId: String? を渡す必要がある
 */
class WalletManager private constructor(
    private val appContext: Context
) {

    companion object {
        private const val TAG = "WalletManager"

        @Volatile
        private var instance: WalletManager? = null

        fun getInstance(context: Context): WalletManager =
            instance ?: synchronized(this) {
                instance ?: WalletManager(context.applicationContext).also { instance = it }
            }
    }

    @Volatile private var wallet: HdWallet? = null
    @Volatile private var config: Config? = null

    /**
     * 今は「通常のTPC（無色）」として扱うので null
     * もし色付きで運用するなら、ここに colorId 文字列を入れる。
     */
    private val colorId: String? = null

    private fun getOrCreateWallet(): HdWallet {
        wallet?.let { return it }

        val masterKey = KeyManager.getInstance(appContext).getOrCreateMasterXprv()
        val dbFilePath = File(appContext.filesDir, "tapyrus_wallet.db").absolutePath

        val networkMode = Network.PROD
        val networkId = 1939510133u
        val genesisHash =
            "038b114875c2f78f5a2fd7d8549a905f38ea5faee6e29a3d79e547151d6bdd8a"
        val esploraUrl = "https://esplora.tapyrus.dev"

        // Config の実定義（あなたが貼った 9引数版）に合わせる
        config = Config(
            networkMode = networkMode,
            networkId = networkId,
            genesisHash = genesisHash,
            esploraUrl = esploraUrl,
            esploraUser = null,
            esploraPassword = null,
            masterKeyPath = null,
            masterKey = masterKey,
            dbFilePath = dbFilePath
        )

        wallet = HdWallet(config!!)
        Log.d(TAG, "HdWallet initialized")
        return wallet!!
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        getOrCreateWallet().sync()
        Log.d(TAG, "sync completed")
    }

    /**
     * 残高（satoshi）
     * ※この SDK は colorId: String? を要求する
     */
    suspend fun getBalance(): ULong = withContext(Dispatchers.IO) {
        val bal = getOrCreateWallet().balance(colorId)
        Log.d(TAG, "balance=$bal colorId=$colorId")
        bal
    }

    /**
     * 新しい受信用アドレス
     * ※この SDK は colorId: String? を要求する
     */
    suspend fun getNewAddress(): String = withContext(Dispatchers.IO) {
        val res = getOrCreateWallet().getNewAddress(colorId)
        val address = res.address
        Log.d(TAG, "newAddress=$address colorId=$colorId")
        address
    }

    /**
     * 送金
     * transfer の署名はあなたが貼った定義と一致：
     * transfer(params: List<TransferParams>, utxos: List<TxOut>): String
     */
    suspend fun transfer(toAddress: String, amountSat: ULong): String = withContext(Dispatchers.IO) {
        val p = TransferParams(
            amount = amountSat,
            toAddress = toAddress
        )
        val txid = getOrCreateWallet().transfer(
            params = listOf(p),
            utxos = listOf()
        )
        Log.d(TAG, "transfer txid=$txid")
        txid
    }

    fun cleanup() {
        try {
            wallet?.close()
            config?.close()
        } catch (e: Exception) {
            Log.e(TAG, "cleanup error", e)
        } finally {
            wallet = null
            config = null
        }
    }
}
