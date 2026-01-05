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
import java.lang.reflect.Constructor

/**
 * Tapyrus Wallet 機能
 *
 * 重要:
 * - Config のコンストラクタ定義が AAR の版/混在で変わるため、
 *   Config(...) の直呼びをやめて reflection で生成する。
 * - これで「colorId 必須/不要」のどちらでもコンパイルが通る。
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

    private fun getOrCreateWallet(): HdWallet {
        wallet?.let { return it }

        val masterKey = KeyManager.getInstance(appContext).getOrCreateMasterXprv()
        val dbFilePath = File(appContext.filesDir, "tapyrus_wallet.db").absolutePath

        // Swift版と合わせた想定値
        val networkMode = Network.PROD
        val networkId = 1939510133u
        val genesisHash =
            "038b114875c2f78f5a2fd7d8549a905f38ea5faee6e29a3d79e547151d6bdd8a"
        val esploraUrl = "https://esplora.tapyrus.dev"

        config = createConfigCompat(
            networkMode = networkMode,
            networkId = networkId,
            genesisHash = genesisHash,
            esploraUrl = esploraUrl,
            masterKey = masterKey,
            dbFilePath = dbFilePath
        )

        wallet = HdWallet(config!!)
        Log.d(TAG, "HdWallet initialized")
        return wallet!!
    }

    /**
     * 実際にクラスパスにいる Config のコンストラクタに合わせて生成する。
     *
     * 期待している9引数版（あなたのデコンパイル結果）:
     *  Config(Network, UInt, String, String, String?, String?, String?, String?, String?)
     *
     * もし混ざっている可能性がある colorId 必須版（別版）も保険で試す:
     *  Config(Network, UInt, String, String, Int, String?, String?, String?, String?, String?)
     *  Config(Network, String, Int, String, String) など
     */
    private fun createConfigCompat(
        networkMode: Network,
        networkId: UInt,
        genesisHash: String,
        esploraUrl: String,
        masterKey: String,
        dbFilePath: String
    ): Config {
        val ctors: Array<Constructor<*>> = Config::class.java.constructors

        // まず “9引数（UInt）” を最優先で狙う（あなたが貼った定義）
        runCatching {
            ctors.forEach { c ->
                val p = c.parameterTypes
                if (p.size == 9 &&
                    p[0] == Network::class.java &&
                    p[1].name == "kotlin.UInt" &&
                    p[2] == String::class.java &&
                    p[3] == String::class.java
                ) {
                    return c.newInstance(
                        networkMode,
                        networkId,
                        genesisHash,
                        esploraUrl,
                        null,          // esploraUser
                        null,          // esploraPassword
                        null,          // masterKeyPath
                        masterKey,     // masterKey
                        dbFilePath     // dbFilePath
                    ) as Config
                }
            }
        }

        // UInt が Int に落ちてる版（9引数）も試す
        runCatching {
            ctors.forEach { c ->
                val p = c.parameterTypes
                if (p.size == 9 &&
                    p[0] == Network::class.java &&
                    (p[1] == Int::class.javaPrimitiveType || p[1] == Int::class.java) &&
                    p[2] == String::class.java &&
                    p[3] == String::class.java
                ) {
                    return c.newInstance(
                        networkMode,
                        networkId.toInt(),
                        genesisHash,
                        esploraUrl,
                        null,
                        null,
                        null,
                        masterKey,
                        dbFilePath
                    ) as Config
                }
            }
        }

        // colorId が追加された版（想定外だが保険）: 10引数で Int colorId が途中にあるパターン
        runCatching {
            ctors.forEach { c ->
                val p = c.parameterTypes
                // 例: (Network, UInt/Int, String, String, Int, ... String? x5)
                if (p.size == 10 &&
                    p[0] == Network::class.java &&
                    (p[1].name == "kotlin.UInt" || p[1] == Int::class.javaPrimitiveType || p[1] == Int::class.java) &&
                    p[2] == String::class.java &&
                    p[3] == String::class.java &&
                    (p[4] == Int::class.javaPrimitiveType || p[4] == Int::class.java)
                ) {
                    val nid: Any = if (p[1].name == "kotlin.UInt") networkId else networkId.toInt()
                    val colorId = 1
                    return c.newInstance(
                        networkMode,
                        nid,
                        genesisHash,
                        esploraUrl,
                        colorId,
                        null,
                        null,
                        null,
                        masterKey,
                        dbFilePath
                    ) as Config
                }
            }
        }

        // もっと短い版があるなら試す（6引数など）
        runCatching {
            ctors.forEach { c ->
                val p = c.parameterTypes
                if (p.size == 6 &&
                    p[0] == Network::class.java &&
                    (p[1].name == "kotlin.UInt" || p[1] == Int::class.javaPrimitiveType || p[1] == Int::class.java) &&
                    p[2] == String::class.java &&
                    p[3] == String::class.java &&
                    p[4] == String::class.java &&
                    p[5] == String::class.java
                ) {
                    val nid: Any = if (p[1].name == "kotlin.UInt") networkId else networkId.toInt()
                    return c.newInstance(
                        networkMode,
                        nid,
                        genesisHash,
                        esploraUrl,
                        masterKey,
                        dbFilePath
                    ) as Config
                }
            }
        }

        val sigs = ctors.joinToString("\n") { it.toString() }
        throw IllegalStateException("No compatible Config constructor found.\n$sigs")
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        getOrCreateWallet().sync()
        Log.d(TAG, "sync completed")
    }

    suspend fun getBalance(): ULong = withContext(Dispatchers.IO) {
        val balance = getOrCreateWallet().balance()
        Log.d(TAG, "balance=$balance")
        balance
    }

    suspend fun getNewAddress(): String = withContext(Dispatchers.IO) {
        val address = getOrCreateWallet().getNewAddress().address
        Log.d(TAG, "newAddress=$address")
        address
    }

    suspend fun transfer(toAddress: String, amountSat: ULong): String = withContext(Dispatchers.IO) {
        val transferParams = TransferParams(
            amount = amountSat,
            toAddress = toAddress
        )

        val txid = getOrCreateWallet().transfer(
            params = listOf(transferParams),
            utxos = listOf()
        )

        Log.d(TAG, "transfer txid=$txid")
        txid
    }

    fun cleanup() {
        try {
            wallet?.close()
            config?.close()
            wallet = null
            config = null
            Log.d(TAG, "wallet cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "cleanup error", e)
        }
    }
}
