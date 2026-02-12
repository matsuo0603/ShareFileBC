package com.example.sharefilebc

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.chaintope.tapyrus.wallet.Config
import com.chaintope.tapyrus.wallet.Contract
import com.chaintope.tapyrus.wallet.HdWallet
import com.chaintope.tapyrus.wallet.NewException
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
    private val syncMutex = Mutex()

    // ✅ master key は KeyManager に一本化（暗号/署名/公開鍵導出のズレを防ぐ）
    private val keyManager: KeyManager = KeyManager.getInstance(context)

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

        // ✅ KeyManager に一本化
        val masterKey = keyManager.getOrCreateMasterXprv(networkConfig.networkMode)
        Log.d(tag, "🔑 Master key ready (fp=${keyManager.getMasterXprvFingerprintOrNull()})")

        val dbPath = context.getDatabasePath("tapyrus_wallet.db").absolutePath
        val config = Config(
            networkMode = networkConfig.networkMode,
            networkId = networkConfig.networkId,
            genesisHash = networkConfig.genesisHash,
            esploraUrl = networkConfig.esploraUrl,
            masterKey = masterKey,
            dbFilePath = dbPath
        )

        // ✅ ここで最も多い致命傷：
        // - KeyManager が保持する master xprv と
        // - tapyrus_wallet.db が過去に初期化された master key
        // が一致しないと MasterKeyDoesNotMatch で死ぬ。
        // これは「アンインストール/再インストールしてもデータが残る」「KeyManager の保存先だけ残る」
        // などで簡単に発生する。
        // → ここで検知したら DB をリセットして 1 回だけ再初期化する。
        try {
            wallet = HdWallet(config)
            Log.d(tag, "✅ Wallet initialized successfully")
        } catch (e: NewException.MasterKeyDoesNotMatch) {
            Log.w(tag, "⚠️ MasterKeyDoesNotMatch. Resetting wallet DB and retrying once...", e)
            Companion.resetWalletDatabaseIfNeeded(context)
            wallet = HdWallet(config)
            Log.d(tag, "✅ Wallet initialized successfully (after DB reset)")
        }

        sync()
        ensureMyPublicKeyExists()
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        // ✅ fullSync の多重実行は不安定＆遅延の原因になりやすいので直列化
        syncMutex.withLock {
            val w = wallet ?: throw IllegalStateException("Wallet not initialized")
            w.fullSync()
            Log.d(tag, "[SYNC] 🔄 Wallet synced")
        }
    }

    /**
     * ✅ 「同期 → 残高取得」を順番保証で実行する。
     * - 受信処理／返金反映の遅延が「syncとbalanceの順番競合」で起きるのを避ける。
     */
    suspend fun getBalanceAfterSync(colorId: String? = null): ULong = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val w = wallet ?: throw IllegalStateException("Wallet not initialized")
            w.fullSync()
            Log.d(tag, "[SYNC] 🔄 Wallet synced (for balance)")

            val bal = w.balance(colorId = colorId)
            Log.d(tag, "[BALANCE] ✅ balance(colorId=${colorId ?: "null"})=$bal")
            bal
        }
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

    /**
     * paymentBase(publicKeyHex) が wallet 側で「自分の鍵として認識」されていないと
     * storeContract が `invalid payment base` で失敗する。
     *
     * その原因の大半は、
     * - tapyrus_wallet.db を削除した/初期化し直した
     * - しかし共有相手に渡した paymentBase は「過去に発行した受取鍵（アドレスインデックスが進んだ状態）」
     *
     * という “インデックスのズレ” で、wallet 側が該当公開鍵をまだ生成/登録していないこと。
     *
     * そこで、この関数では getNewAddressWithPublicKey() を繰り返してインデックスを進め、
     * 目的の publicKeyHex が出るまで wallet の鍵プールを前進させる。
     *
     * NOTE:
     * - maxTries 以上回しても一致しない場合は false
     */
    fun ensurePublicKeyAvailable(publicKeyHex: String, maxTries: Int = 50): Boolean {
        val target = publicKeyHex.trim()
        if (target.isBlank()) return false

        for (i in 0 until maxTries) {
            val (_, pub) = getNewAddressWithPublicKey(colorId = null)
            if (pub.equals(target, ignoreCase = true)) {
                Log.d(tag, "✅ ensurePublicKeyAvailable: matched at i=$i pub=${pub.take(16)}...")
                return true
            }
        }

        Log.e(tag, "❌ ensurePublicKeyAvailable: not found within maxTries=$maxTries target=${target.take(16)}...")
        return false
    }

    fun updateContractPayable(contractId: String, payable: Boolean) {
        val w = wallet ?: throw IllegalStateException("Wallet not initialized")
        w.updateContract(contractId = contractId, payable = payable)
    }

    fun resetWallet() {
        wallet = null
    }

    // 旧: wallet_prefs/master_key は廃止（KeyManager に一本化）

    /**
     * ✅ my_public_keys を「固定パスの公開鍵」で必ず埋める。
     * - 以前の実装は getNewAddressWithPublicKey() を2回呼んでおり、起動のたびに値が揺れる/一致しない可能性があった
     * - Swift版同様に、master key から決め打ちパスで導出した公開鍵を保存する
     */
    private suspend fun ensureMyPublicKeyExists() = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.myPublicKeyDao()

        val kd = com.example.sharefilebc.KeyDerivation.getInstance(context)
        val trustLayerPublicKey = kd.getCurrentPublicKeyHex(com.example.sharefilebc.KeyDerivation.TRUST_LAYER_PATH)
        val derivedPublicKey = kd.getCurrentPublicKeyHex(com.example.sharefilebc.KeyDerivation.DERIVED_KEY_PATH)

        val existing = dao.getPrimary()
        val needsUpsert = existing == null || existing.trustLayerPublicKey.isNullOrBlank() || existing.derivedPublicKey.isNullOrBlank()
        if (needsUpsert) {
            val entity = MyPublicKeyEntity(
                id = 1,
                trustLayerPublicKey = trustLayerPublicKey,
                derivedPublicKey = derivedPublicKey
            )
            dao.upsert(entity)
            Log.d(tag, "🔑 My public keys saved/updated (trust=${trustLayerPublicKey.take(16)}... derived=${derivedPublicKey.take(16)}... fp=${keyManager.getMasterXprvFingerprintOrNull()})")
        } else {
            Log.d(tag, "🔑 My public keys already exist (trust=${existing!!.trustLayerPublicKey!!.take(16)}... derived=${existing.derivedPublicKey!!.take(16)}...)")
        }
    }

    /**
     * HomeScreen などから明示的に呼ぶ用。
     * "MyPublicKeyEntity missing" が出たときに即復旧できる。
     */
    suspend fun ensureMyPublicKeysPersisted() {
        initializeIfNeeded()
        ensureMyPublicKeyExists()
    }
}
