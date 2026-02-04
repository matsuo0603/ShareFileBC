package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.chaintope.tapyrus.wallet.Config
import com.chaintope.tapyrus.wallet.Contract
import com.chaintope.tapyrus.wallet.HdWallet
import com.chaintope.tapyrus.wallet.TransferParams
import com.chaintope.tapyrus.wallet.TxOut
import com.chaintope.tapyrus.wallet.generateMasterKey
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.MyPublicKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tapyrusウォレット管理クラス
 * シングルトンパターンで実装
 */
class WalletManager private constructor(
    private val context: Context
) {
    private var wallet: HdWallet? = null
    private val tag = "WalletManager"

    /**
     * initialize() の多重実行を防ぐ。
     * HomeActivity 起動直後に複数コルーチンから wallet を参照すると
     * "Wallet not initialized" で落ちることがあるため、ここで直列化する。
     */
    private val initMutex = Mutex()

    companion object {
        @Volatile
        private var instance: WalletManager? = null

        fun getInstance(context: Context): WalletManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    // 🔧 エラー回避のため、初回起動時にDBをリセット
                    val prefs = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
                    if (!prefs.getBoolean("wallet_initialized", false)) {
                        resetWalletDatabaseIfNeeded(context)
                        prefs.edit().putBoolean("wallet_initialized", true).apply()
                    }

                    WalletManager(context.applicationContext).also {
                        instance = it
                    }
                }
            }
        }

        /**
         * MasterKeyDoesNotMatchエラーを解決するため、
         * ウォレットDBをクリアしてから再初期化する
         */
        private fun resetWalletDatabaseIfNeeded(context: Context) {
            try {
                // tapyrus_wallet.db のパスを取得
                val dbPath = context.getDatabasePath("tapyrus_wallet.db")

                if (dbPath.exists()) {
                    Log.w("WalletManager", "⚠️ Deleting existing wallet database to avoid MasterKeyDoesNotMatch")

                    // データベースファイルを削除
                    dbPath.delete()

                    // 関連ファイルも削除
                    context.getDatabasePath("tapyrus_wallet.db-shm").delete()
                    context.getDatabasePath("tapyrus_wallet.db-wal").delete()

                    Log.d("WalletManager", "✅ Wallet database reset complete")
                }
            } catch (e: Exception) {
                Log.e("WalletManager", "❌ Failed to reset wallet database", e)
            }
        }
    }

    /**
     * ウォレットの初期化（必要なら）。
     *
     * - wallet が null のときだけ初期化する
     * - 多重に呼ばれても initMutex で直列化して安全にする
     */
    suspend fun initializeIfNeeded() {
        if (wallet != null) return
        initMutex.withLock {
            if (wallet != null) return
            initializeInternal()
        }
    }

    /**
     * 強制的に初期化（既存 wallet を破棄して作り直す場合など）
     */
    suspend fun initialize() {
        initMutex.withLock {
            wallet = null
            initializeInternal()
        }
    }

    /**
     * 実際の初期化処理本体
     */
    private suspend fun initializeInternal() = withContext(Dispatchers.IO) {
        try {
            // 設定マネージャーから設定を取得
            val settings = WalletSettingsManager.getInstance(context)
            val networkConfig = settings.getNetworkConfig()

            // マスターキー取得または生成
            var masterKey = loadMasterKeyFromPrefs()
            if (masterKey == null) {
                masterKey = generateMasterKey(networkConfig.networkMode)
                saveMasterKeyToPrefs(masterKey)
                Log.d(tag, "🆕 New master key generated")
            } else {
                Log.d(tag, "🔑 Existing master key loaded")
            }

            // ウォレット設定
            val dbPath = context.getDatabasePath("tapyrus_wallet.db").absolutePath
            val config = Config(
                networkMode = networkConfig.networkMode,
                networkId = networkConfig.networkId,
                genesisHash = networkConfig.genesisHash,
                esploraUrl = networkConfig.esploraUrl,
                masterKey = masterKey,
                dbFilePath = dbPath
            )

            // ウォレット作成
            wallet = HdWallet(config)
            Log.d(tag, "✅ Wallet initialized successfully")

            // 初期同期
            sync()

            // 自分の公開鍵を生成・保存（初回のみ）
            ensureMyPublicKeyExists()

        } catch (e: Exception) {
            Log.e(tag, "❌ Wallet initialization failed", e)
            throw e
        }
    }

    /**
     * ウォレット同期
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
        try {
            wallet?.fullSync()
            Log.d(tag, "🔄 Wallet synced")
        } catch (e: Exception) {
            Log.e(tag, "❌ Sync failed", e)
            throw e
        }
    }

    /**
     * 新しいアドレスと公開鍵を生成
     */
    fun getNewAddressWithPublicKey(colorId: String? = null): Pair<String, String> {
        val result = wallet?.getNewAddress(colorId = colorId)
            ?: throw IllegalStateException("Wallet not initialized")
        return Pair(result.address, result.publicKey)
    }

    /**
     * 新しいアドレスを生成（アドレスのみ）
     */
    fun getNewAddress(colorId: String? = null): String {
        val result = wallet?.getNewAddress(colorId = colorId)
            ?: throw IllegalStateException("Wallet not initialized")
        return result.address
    }

    /**
     * 残高取得（UIスレッドでブロックしないようIOで実行）
     *
     * balance() は内部で DB / I/O を伴う可能性があるため、
     * メインスレッドから直接呼ぶと ANR（フリーズ）になることがある。
     */
    suspend fun getBalance(colorId: String? = null): ULong = withContext(Dispatchers.IO) {
        wallet?.balance(colorId = colorId)
            ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * トークン送金
     */
    suspend fun transferToken(
        toAddress: String,
        amount: ULong,
        colorId: String,
        utxos: List<TxOut> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (amount == 0uL) {
            throw IllegalArgumentException("amount must be > 0")
        }
        Log.d("WalletManager", "💸 transferToken(amount=$amount, colorId=$colorId, to=$toAddress, utxos=${utxos.size})")
        if (amount == 0uL) {
            throw IllegalArgumentException("amount must be > 0")
        }

        // NOTE:
        // - TOKEN送金は「Color付きアドレス（toAddress）」と「Color付きUTXO」で表現される。
        // - このアプリでは Constants.Strings.tokenColorId のTOKENのみを扱う前提。
        // - 手数料（fee）はTPCで自動消費されるため、TPC残高も少量減るのは正常。
        Log.d(tag, "💸 transferToken: amount=$amount colorId=$colorId to=$toAddress utxos=${utxos.size}")

        val params = TransferParams(
            amount = amount,
            toAddress = toAddress
        )

        wallet?.transfer(params = listOf(params), utxos = utxos)
            ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * 送金（簡易版）- DebugWalletActivity用
     * デフォルトでトークンColorIDを使用
     */
    suspend fun transfer(
        toAddress: String,
        amount: ULong,
        colorId: String = Constants.Strings.tokenColorId
    ): String {
        return transferToken(toAddress, amount, colorId)
    }

    /**
     * P2Cアドレス生成
     */
    fun generateP2CAddress(
        publicKey: String,
        contract: String,
        colorId: String?
    ): String {
        return wallet?.calcP2cAddress(
            publicKey = publicKey,
            contract = contract,
            colorId = colorId
        ) ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * トランザクション取得
     */
    fun getTransaction(txid: String): String {
        return wallet?.getTransaction(txid = txid)
            ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * トランザクション出力取得
     */
    fun getTxOutByAddress(tx: String, address: String): List<TxOut> {
        return wallet?.getTxOutByAddress(tx = tx, address = address)
            ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * コントラクト保存
     */
    fun storeContract(contract: Contract): Contract {
        return wallet?.storeContract(contract = contract)
            ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * コントラクトのpayableフラグ更新
     */
    fun updateContractPayable(contractId: String, payable: Boolean) {
        wallet?.updateContract(contractId = contractId, payable = payable)
            ?: throw IllegalStateException("Wallet not initialized")
    }

    /**
     * ウォレットをリセット（ネットワーク設定変更時に使用）
     */
    fun resetWallet() {
        wallet = null
        // 必要に応じて再初期化は呼び出し側で行う
    }

    // ============================================================
    // Private Helper Functions
    // ============================================================

    private fun loadMasterKeyFromPrefs(): String? {
        val prefs = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
        return prefs.getString("master_key", null)
    }

    private fun saveMasterKeyToPrefs(masterKey: String) {
        val prefs = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("master_key", masterKey).apply()
    }

    /**
     * 自分の公開鍵が未登録なら生成して保存
     */
    private suspend fun ensureMyPublicKeyExists() = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.myPublicKeyDao()

        val existing = dao.getPrimary()
        if (existing == null) {
            // TrustLayer用公開鍵を生成
            val (_, trustLayerPublicKey) = getNewAddressWithPublicKey(colorId = null)

            // BIP32派生公開鍵を生成（別途必要な場合）
            val (_, derivedPublicKey) = getNewAddressWithPublicKey(colorId = null)

            val entity = MyPublicKeyEntity(
                id = 1,
                trustLayerPublicKey = trustLayerPublicKey,
                derivedPublicKey = derivedPublicKey
            )

            dao.upsert(entity)
            Log.d(tag, "🔑 My public keys saved")
        }
    }
}
