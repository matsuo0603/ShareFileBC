package com.example.sharefilebc

import android.annotation.SuppressLint
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
 * - DownloadScreen などの既存コード互換のため transfer() を残す
 */
class WalletManager private constructor(
    private val context: Context
) {
    private var wallet: HdWallet? = null
    private val tag = "WalletManager"

    private val initMutex = Mutex()

    companion object {
        @Volatile
        @SuppressLint("StaticFieldLeak") // applicationContext を保持するためlint抑制
        private var instance: WalletManager? = null

        fun getInstance(context: Context): WalletManager {
            return instance ?: synchronized(this) {
                instance ?: run {
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

        private fun resetWalletDatabaseIfNeeded(context: Context) {
            try {
                val dbPath = context.getDatabasePath("tapyrus_wallet.db")
                if (dbPath.exists()) {
                    Log.w("WalletManager", "⚠️ Deleting existing wallet database to avoid MasterKeyDoesNotMatch")

                    dbPath.delete()
                    context.getDatabasePath("tapyrus_wallet.db-shm").delete()
                    context.getDatabasePath("tapyrus_wallet.db-wal").delete()

                    Log.d("WalletManager", "✅ Wallet database reset complete")
                }
            } catch (e: Exception) {
                Log.e("WalletManager", "❌ Failed to reset wallet database", e)
            }
        }
    }

    suspend fun initializeIfNeeded() {
        if (wallet != null) return
        initMutex.withLock {
            if (wallet != null) return
            initializeInternal()
        }
    }

    suspend fun initialize() {
        initMutex.withLock {
            wallet = null
            initializeInternal()
        }
    }

    private suspend fun initializeInternal() = withContext(Dispatchers.IO) {
        val settings = WalletSettingsManager.getInstance(context)
        val networkConfig = settings.getNetworkConfig()

        var masterKey = loadMasterKeyFromPrefs()
        if (masterKey == null) {
            masterKey = generateMasterKey(networkConfig.networkMode)
            saveMasterKeyToPrefs(masterKey)
            Log.d(tag, "🆕 New master key generated")
        } else {
            Log.d(tag, "🔑 Existing master key loaded")
        }

        val dbPath = context.getDatabasePath("tapyrus_wallet.db").absolutePath
        val config = Config(
            networkMode = networkConfig.networkMode,
            networkId = networkConfig.networkId,
            genesisHash = networkConfig.genesisHash,
            esploraUrl = networkConfig.esploraUrl,
            masterKey = masterKey,
            dbFilePath = dbPath
        )

        wallet = HdWallet(config)
        Log.d(tag, "✅ Wallet initialized successfully")

        sync()
        ensureMyPublicKeyExists()
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        w.fullSync()
        Log.d(tag, "🔄 Wallet synced")
    }

    fun getNewAddressWithPublicKey(colorId: String? = null): Pair<String, String> {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        val result = w.getNewAddress(colorId = colorId)
        return Pair(result.address, result.publicKey)
    }

    fun getNewAddress(colorId: String? = null): String {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        return w.getNewAddress(colorId = colorId).address
    }

    suspend fun getBalance(colorId: String? = null): ULong = withContext(Dispatchers.IO) {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        w.balance(colorId = colorId)
    }

    /**
     * ✅ 旧API互換：DownloadScreen などが walletManager.transfer(...) を呼んでいるため残す
     *
     * 注意：
     * - あなたの tapyrus-wallet の HdWallet.transfer は colorId 引数を受け取らない
     * - utxos を指定できる版なので、必要なら utxos を渡す
     */
    suspend fun transfer(
        toAddress: String,
        amount: ULong,
        utxos: List<TxOut> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (amount == 0uL) throw IllegalArgumentException("amount must be > 0")
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")

        Log.d(tag, "💸 transfer: amount=$amount to=$toAddress utxos=${utxos.size}")

        val params = TransferParams(
            amount = amount,
            toAddress = toAddress
        )

        val txid = w.transfer(
            params = listOf(params),
            utxos = utxos
        )

        Log.d(tag, "✅ transfer txid=$txid")
        txid
    }

    suspend fun transferToken(
        toAddress: String,
        amount: ULong,
        colorId: String,
        utxos: List<TxOut>
    ): String = withContext(Dispatchers.IO) {
        Log.d(tag, "💸 transferToken: amount=$amount colorId=$colorId to=$toAddress utxos=${utxos.size}")
        transfer(toAddress = toAddress, amount = amount, utxos = utxos)
    }

    fun generateP2CAddress(
        publicKey: String,
        contract: String,
        colorId: String?
    ): String {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        return w.calcP2cAddress(publicKey = publicKey, contract = contract, colorId = colorId)
    }

    fun getTransaction(txid: String): String {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        return w.getTransaction(txid = txid)
    }

    fun getTxOutByAddress(tx: String, address: String): List<TxOut> {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        return w.getTxOutByAddress(tx = tx, address = address)
    }

    fun storeContract(contract: Contract): Contract {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        return w.storeContract(contract = contract)
    }

    fun updateContractPayable(contractId: String, payable: Boolean) {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        w.updateContract(contractId = contractId, payable = payable)
    }

    fun resetWallet() {
        wallet = null
    }

    private fun loadMasterKeyFromPrefs(): String? {
        val prefs = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
        return prefs.getString("master_key", null)
    }

    private fun saveMasterKeyToPrefs(masterKey: String) {
        val prefs = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("master_key", masterKey).apply()
    }

    private suspend fun ensureMyPublicKeyExists() = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.myPublicKeyDao()

        val existing = dao.getPrimary()
        if (existing == null) {
            val (_, trustLayerPublicKey) = getNewAddressWithPublicKey(colorId = null)
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