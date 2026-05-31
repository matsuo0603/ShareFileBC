package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.chaintope.tapyrus.wallet.Contract
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.BlockedSenderEntity
import com.example.sharefilebc.data.BlockedSenderSource
import com.example.sharefilebc.data.ReceivedFileEntity
import com.example.sharefilebc.data.RefundTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 共有ファイルの送金検証・返金処理
 * Swift版の TapyrusWalletManager.processReceivedShare / refundShare に相当
 */
object ShareProcessor {

    private const val TAG = "ShareProcessor"
    private const val DL_TAG = "DL_DEBUG"

    /**
     * 受信した共有の検証処理
     */
    suspend fun processReceivedShare(
        context: Context,
        uuid: String,
        txids: List<String>,
        senderPublicKey: String,
        refundAddress: String?,
        threshold: ULong,
        colorId: String = Constants.Strings.tokenColorId
    ): ShareProcessResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "🔍 processReceivedShare: uuid=$uuid, txids=${txids.size}, sender=${senderPublicKey.take(8)}...")

        val walletManager = WalletManager.getInstance(context)
        val db = AppDatabase.getDatabase(context)

        // ✅ DBガード（二重処理防止）
        //
        // 注意：HomeActivity / IncomingFilesSyncer は deep link や Drive 同期のタイミングで
        // received_files に "プレースホルダ行" を先に作ることがある。
        // そのため「行が存在するだけ」で processed 扱いにすると、
        // 本来走るべき processReceivedShare が永遠にスキップされ、
        // ・トークン反映が走らない
        // ・DownloadScreen（received_folders）に何も出ない
        // という状態になる。
        //
        // ✅ processed とみなす条件：
        // - refund_tasks が存在する（返金フローに入った/入るべき状態）
        // - received_files のフラグが更新済み（everAllowed / blocked / nameMetadataError）
        val received = db.receivedFileDao().findByShareId(uuid)
        val hasRefundTask = db.refundTaskDao().findByShareId(uuid) != null
        val hasProcessedFlags =
            received?.isDownloadEverAllowed == true ||
                    received?.isDownloadBlocked == true ||
                    !received?.nameMetadataError.isNullOrBlank()

        if (hasRefundTask || hasProcessedFlags) {
            Log.d(DL_TAG, "[ShareProcessor] DB guard hit. already processed uuid=$uuid")
            return@withContext ShareProcessResult.Success(0UL)
        }

        try {
            // 1. 自分の公開鍵取得（候補を両方持つ）
            val myKeyEntity = db.myPublicKeyDao().getPrimary()
            val derived = myKeyEntity?.derivedPublicKey
            val trustLayer = myKeyEntity?.trustLayerPublicKey

            if (derived.isNullOrBlank() && trustLayer.isNullOrBlank()) {
                Log.e(TAG, "❌ 自分の公開鍵が未登録（derived/trustlayer 共にnull）")
                return@withContext ShareProcessResult.Error("公開鍵が未登録です")
            }

            // ✅ paymentBase候補（null/空は除外）
            val paymentBases = listOfNotNull(
                derived?.takeIf { it.isNotBlank() },
                trustLayer?.takeIf { it.isNotBlank() }
            ).distinct()

            // ✅ contract候補（送受の揺れ吸収）
            val contractCandidates = listOf(
                uuid,
                "shared-file-$uuid"
            ).distinct()

            Log.d(TAG, "🔑 paymentBase candidates=${paymentBases.joinToString { it.take(16) + "..." }}")
            Log.d(TAG, "🧾 contract candidates=$contractCandidates")

            // 2. P2C候補を全探索して「実際にunspentが付く」ものを採用
            data class Candidate(
                val paymentBase: String,
                val contract: String,
                val address: String,
                val total: ULong,
                val validTxs: List<Pair<String, String>>
            )

            var best: Candidate? = null

            for (pb in paymentBases) {
                for (contractStr in contractCandidates) {

                    val p2c: String = try {
                        walletManager.generateP2CAddress(
                            publicKey = pb,
                            contract = contractStr,
                            colorId = colorId
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ P2C calc failed: paymentBase=${pb.take(16)}... contract=$contractStr", e)
                        continue
                    }

                    var totalAmount = 0UL
                    val validTransactions = mutableListOf<Pair<String, String>>()

                    for (txid in txids) {
                        try {
                            val tx = walletManager.getTransaction(txid)
                            val outputs = walletManager.getTxOutByAddress(tx, p2c)

                            val unspentAmount = outputs
                                .filter { it.unspent }
                                .sumOf { it.amount.toLong() }
                                .toULong()

                            if (unspentAmount > 0UL) {
                                totalAmount += unspentAmount
                                validTransactions.add(txid to tx)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "  ⚠️ txid=$txid: failed to verify for p2c=$p2c", e)
                        }
                    }

                    Log.d(TAG, "🧪 candidate p2c=$p2c total=$totalAmount (pb=${pb.take(16)}..., contract=$contractStr)")

                    val cand = Candidate(
                        paymentBase = pb,
                        contract = contractStr,
                        address = p2c,
                        total = totalAmount,
                        validTxs = validTransactions
                    )

                    val bestTotal = best?.total ?: 0UL
                    if (best == null || cand.total > bestTotal) {
                        best = cand
                    }
                }
            }

            val chosen = best
            if (chosen == null) {
                Log.e(TAG, "❌ No candidate P2C could be generated")
                return@withContext ShareProcessResult.Error("P2C生成に失敗しました")
            }

            Log.d(TAG, "✅ P2C selected: ${chosen.address}")
            Log.d(TAG, "✅ selected paymentBase=${chosen.paymentBase.take(16)}... contract=${chosen.contract}")
            Log.d(TAG, "📊 Total received: ${chosen.total} (threshold: $threshold)")

            // 3. 送信者ブロック確認
            val senderIsBlocked = isSenderBlocked(context, senderPublicKey)

            // 4. 閾値判定
            val underpaid = chosen.total < threshold

            // ✅ contractId も「同じ文字列」で固定（ズレ事故を防ぐ）
            val contractId = chosen.contract

            if (underpaid || senderIsBlocked) {
                Log.w(TAG, "🚫 Share rejected: underpaid=$underpaid, blocked=$senderIsBlocked")

                // Contract保存（payable=true）
                val contract = Contract(
                    contractId = contractId,
                    contract = chosen.contract,
                    paymentBase = chosen.paymentBase,
                    payable = true
                )
                if (!storeContractBestEffort(walletManager, contract)) {
                    // ❗ここで止めないと UIだけ進んで詰む
                    return@withContext ShareProcessResult.Error("contract保存に失敗しました（paymentBaseがwalletに存在しません）")
                }

                updateReceivedFile(
                    context = context,
                    shareID = uuid,
                    senderPublicKey = senderPublicKey,
                    isDownloadAllowed = false,
                    isDownloadBlocked = true,
                    isDownloadEverAllowed = false
                )

                if (!senderIsBlocked) {
                    registerFraudulentSender(context, senderPublicKey, "Payment below threshold")
                }

                // ✅ payable=true の時点で残高に反映されないケースがあるため、P2C UTXO を自己送金で claim
                try {
                    val (claimTxid, claimAmount) = claimP2cUtxosToSelf(
                        walletManager = walletManager,
                        paymentBases = paymentBases,
                        contract = chosen.contract,
                        transactions = chosen.validTxs.map { it.second },
                        colorId = colorId
                    )
                    Log.d(TAG, "[CLAIM] underpaid claimTxid=$claimTxid amount=$claimAmount")
                } catch (e: Exception) {
                    Log.w(TAG, "[CLAIM] underpaid sweep failed (continue): ${e.message}")
                }

                walletManager.sync()
                return@withContext ShareProcessResult.BelowThreshold(chosen.total, threshold)
            }

            // ✅ 正常判定
            Log.d(TAG, "✅ Share accepted: amount=${chosen.total} >= threshold=$threshold")

            // 正常受信時は payable=false（返金のためにロック）
            val contract = Contract(
                contractId = contractId,
                contract = chosen.contract,
                paymentBase = chosen.paymentBase,
                payable = false
            )
            if (!storeContractBestEffort(walletManager, contract)) {
                // ❗ここで止めないと UIだけ進んで詰む
                return@withContext ShareProcessResult.Error("contract保存に失敗しました（paymentBaseがwalletに存在しません）")
            }

            updateReceivedFile(
                context = context,
                shareID = uuid,
                senderPublicKey = senderPublicKey,
                isDownloadAllowed = true,
                isDownloadBlocked = false,
                isDownloadEverAllowed = true
            )

            // 返金タスク保存
            if (refundAddress != null && chosen.validTxs.isNotEmpty()) {
                saveRefundTask(
                    context = context,
                    shareID = uuid,
                    contractId = contractId,
                    refundAddress = refundAddress,
                    transactions = chosen.validTxs,
                    amount = chosen.total,
                    senderPublicKey = senderPublicKey,
                    paymentThreshold = threshold
                )
            }

            walletManager.sync()
            return@withContext ShareProcessResult.Success(chosen.total)

        } catch (e: CancellationException) {
            // ✅ 画面遷移・タスク終了・再Compose 等で起きるキャンセルは正常系として扱い、上位に伝播させる
            Log.d(TAG, "🟡 processReceivedShare cancelled: uuid=$uuid")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ processReceivedShare failed", e)
            return@withContext ShareProcessResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * ✅ storeContract は paymentBase が wallet 側で「自分の鍵として認識」されていないと
     * `invalid payment base` で失敗する。
     */
    private fun storeContractBestEffort(walletManager: WalletManager, contract: Contract): Boolean {
        val first = runCatching { walletManager.storeContract(contract) }
        if (first.isSuccess) {
            Log.d(TAG, "✅ storeContract OK: contractId=${contract.contractId}")
            return true
        }

        val e1 = first.exceptionOrNull()
        val msg1 = e1?.message.orEmpty()

        if (msg1.contains("already exists", ignoreCase = true)) {
            Log.w(TAG, "🟡 storeContract already exists -> treated as OK: contractId=${contract.contractId}")
            return true
        }

        val invalidPb = msg1.contains("invalid payment base", ignoreCase = true)
        if (!invalidPb) {
            Log.e(TAG, "🔴 storeContract failed: ${e1?.message}", e1)
            return false
        }

        val pb = contract.paymentBase
        Log.w(TAG, "🟠 storeContract invalid payment base -> try ensurePublicKeyAvailable: pb=${pb.take(16)}...", e1)

        val ensured = runCatching { walletManager.ensurePublicKeyAvailable(pb) }
        if (ensured.isFailure) {
            Log.e(TAG, "🔴 ensurePublicKeyAvailable failed: ${ensured.exceptionOrNull()?.message}", ensured.exceptionOrNull())
            return false
        }

        val second = runCatching { walletManager.storeContract(contract) }
        if (second.isSuccess) {
            Log.d(TAG, "✅ storeContract retry OK: contractId=${contract.contractId}")
            return true
        }

        val e2 = second.exceptionOrNull()
        val msg2 = e2?.message.orEmpty()

        if (msg2.contains("already exists", ignoreCase = true)) {
            Log.w(TAG, "🟡 storeContract retry -> already exists -> treated as OK: contractId=${contract.contractId}")
            return true
        }

        Log.e(TAG, "🔴 storeContract retry failed: ${e2?.message}", e2)
        return false
    }

    /**
     * payable=true にしても残高に反映されない場合があるため、
     * P2C UTXO を自分の通常アドレスへ sweep して確実に取り込む
     *
     * @return Pair(txid, amount)
     */
    private suspend fun claimP2cUtxosToSelf(
        walletManager: WalletManager,
        paymentBases: List<String>,
        contract: String,
        transactions: List<String>,
        colorId: String
    ): Pair<String, ULong> {

        // ✅ getAddress() は存在しないので getNewAddress() を使う
        val to = walletManager.getNewAddress(colorId = null)

        var bestUtxos: List<com.chaintope.tapyrus.wallet.TxOut> = emptyList()
        var bestTotal = 0UL

        for (pb in paymentBases) {
            val p2c = walletManager.generateP2CAddress(
                publicKey = pb,
                contract = contract,
                colorId = colorId
            )

            val utxos = mutableListOf<com.chaintope.tapyrus.wallet.TxOut>()
            var total = 0UL

            for (tx in transactions) {
                runCatching {
                    val outs = walletManager.getTxOutByAddress(tx, p2c)
                    outs.filter { it.unspent }.forEach { out ->
                        utxos.add(out)
                        total += out.amount.toULong()
                    }
                }
            }

            if (total > bestTotal) {
                bestTotal = total
                bestUtxos = utxos
            }
        }

        if (bestUtxos.isEmpty() || bestTotal == 0UL) {
            return "" to 0UL
        }

        val txid = walletManager.transferToken(
            toAddress = to,
            amount = bestTotal,
            colorId = colorId,
            utxos = bestUtxos.toMutableList()
        )

        return txid to bestTotal
    }

    suspend fun refundShare(
        context: Context,
        uuid: String,
        refundAddress: String,
        contractStr: String,
        transactions: List<Pair<String, String>>,
        colorId: String = Constants.Strings.tokenColorId
    ): RefundResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "💸 refundShare: uuid=$uuid, refundAddress=$refundAddress")

        val walletManager = WalletManager.getInstance(context)
        val db = AppDatabase.getDatabase(context)

        try {
            walletManager.updateContractPayable(contractStr, payable = true)

            val myKeyEntity = db.myPublicKeyDao().getPrimary()
            val derived = myKeyEntity?.derivedPublicKey
            val trustLayer = myKeyEntity?.trustLayerPublicKey

            if (derived.isNullOrBlank() && trustLayer.isNullOrBlank()) return@withContext RefundResult.Error("公開鍵が未登録です")

            val paymentBases = listOfNotNull(
                derived?.takeIf { it.isNotBlank() },
                trustLayer?.takeIf { it.isNotBlank() }
            ).distinct()

            var chosenUtxos = mutableListOf<com.chaintope.tapyrus.wallet.TxOut>()
            var chosenTotal = 0UL

            for (pb in paymentBases) {
                val p2c = walletManager.generateP2CAddress(
                    publicKey = pb,
                    contract = contractStr,
                    colorId = colorId
                )

                val utxos = mutableListOf<com.chaintope.tapyrus.wallet.TxOut>()
                var totalAmount = 0UL

                for ((_, tx) in transactions) {
                    val outputs = walletManager.getTxOutByAddress(tx, p2c)
                    outputs.filter { it.unspent }.forEach { out ->
                        utxos.add(out)
                        totalAmount += out.amount.toULong()
                    }
                }

                if (totalAmount > chosenTotal) {
                    chosenTotal = totalAmount
                    chosenUtxos = utxos
                }
            }

            if (chosenUtxos.isEmpty() || chosenTotal == 0UL) {
                return@withContext RefundResult.Error("返金可能な出力がありません")
            }

            val txid = walletManager.transferToken(
                toAddress = refundAddress,
                amount = chosenTotal,
                colorId = colorId,
                utxos = chosenUtxos
            )

            Log.d(TAG, "✅ Refund success: txid=$txid, amount=$chosenTotal")

            walletManager.sync()

            runCatching {
                db.refundTaskDao().markStatusByShareId(uuid, "COMPLETED")
            }

            return@withContext RefundResult.Success(txid, chosenTotal)

        } catch (e: Exception) {
            Log.e(TAG, "❌ refundShare failed", e)
            return@withContext RefundResult.Error(e.message ?: "返金に失敗しました")
        }
    }

    /**
     * 返金拒否処理（payable=true → 残高反映。反映されない場合は claim で確実に取り込む）
     */
    suspend fun declineRefund(
        context: Context,
        uuid: String,
        contractId: String,
        senderPublicKey: String,
        colorId: String = Constants.Strings.tokenColorId
    ): RefundResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "🚫 declineRefund: uuid=$uuid contractId=$contractId")

        val walletManager = WalletManager.getInstance(context)
        val db = AppDatabase.getDatabase(context)

        try {
            val refundTask = db.refundTaskDao().findByShareId(uuid)
            val transactions: List<String> = if (refundTask != null) {
                val contextJson = JSONObject(refundTask.contextJSON ?: "{}")
                val txArray = contextJson.optJSONArray("transactions") ?: JSONArray()
                buildList {
                    for (i in 0 until txArray.length()) {
                        val txObj = txArray.getJSONObject(i)
                        add(txObj.getString("transaction"))
                    }
                }
            } else {
                emptyList()
            }

            runCatching {
                val before = walletManager.getBalance(colorId = colorId)
                Log.d(TAG, "[CLAIM] before payable=true balance(colorId)=$before")
            }

            walletManager.updateContractPayable(contractId, payable = true)
            walletManager.sync()

            if (transactions.isNotEmpty()) {
                val myKeyEntity = db.myPublicKeyDao().getPrimary()
                val derived = myKeyEntity?.derivedPublicKey
                val trustLayer = myKeyEntity?.trustLayerPublicKey
                val paymentBases = listOfNotNull(
                    derived?.takeIf { it.isNotBlank() },
                    trustLayer?.takeIf { it.isNotBlank() }
                ).distinct()

                try {
                    val (claimTxid, claimAmount) = claimP2cUtxosToSelf(
                        walletManager = walletManager,
                        paymentBases = paymentBases,
                        contract = contractId,
                        transactions = transactions,
                        colorId = colorId
                    )
                    Log.d(TAG, "[CLAIM] declineRefund claimTxid=$claimTxid amount=$claimAmount")
                } catch (e: Exception) {
                    Log.w(TAG, "[CLAIM] declineRefund sweep failed (continue): ${e.message}")
                }

                walletManager.sync()
            }

            registerFraudulentSender(context, senderPublicKey, "Refund declined by recipient")
            db.refundTaskDao().deleteByShareId(uuid)

            runCatching {
                val after = walletManager.getBalance(colorId = colorId)
                Log.d(TAG, "[CLAIM] after decline balance(colorId)=$after")
            }

            Log.d(TAG, "✅ Refund declined and sender blocked")
            return@withContext RefundResult.Declined

        } catch (e: Exception) {
            Log.e(TAG, "❌ declineRefund failed", e)
            return@withContext RefundResult.Error(e.message ?: "返金拒否処理に失敗しました")
        }
    }

    // ============================================================
    // Private Helper Functions
    // ============================================================

    private suspend fun updateReceivedFile(
        context: Context,
        shareID: String,
        senderPublicKey: String,
        isDownloadAllowed: Boolean,
        isDownloadBlocked: Boolean,
        isDownloadEverAllowed: Boolean
    ) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.receivedFileDao()

        val existing = dao.findByShareId(shareID)
        val entity = if (existing != null) {
            existing.copy(
                senderPublicKey = senderPublicKey,
                isDownloadAllowed = isDownloadAllowed,
                isDownloadBlocked = isDownloadBlocked,
                isDownloadEverAllowed = isDownloadEverAllowed
            )
        } else {
            ReceivedFileEntity(
                shareID = shareID,
                senderPublicKey = senderPublicKey,
                isDownloadAllowed = isDownloadAllowed,
                isDownloadBlocked = isDownloadBlocked,
                isDownloadEverAllowed = isDownloadEverAllowed
            )
        }

        dao.insert(entity)
    }

    private suspend fun registerFraudulentSender(
        context: Context,
        senderPublicKey: String,
        reason: String
    ) {
        val db = AppDatabase.getDatabase(context)
        val emailKeyDao = db.emailKeyDao()
        val blockedSenderDao = db.blockedSenderDao()

        val emailKey = emailKeyDao.findByTrustLayerPublicKey(senderPublicKey)
        if (emailKey != null) {
            val entity = BlockedSenderEntity(
                email = emailKey.email,
                reason = reason,
                source = BlockedSenderSource.AUTO_THRESHOLD.name,
                createdAt = nowIsoString()
            )
            blockedSenderDao.upsert(entity)
            Log.d(TAG, "🚫 Blocked sender: ${emailKey.email}")
        } else {
            Log.w(TAG, "⚠️ Could not find email for public key: ${senderPublicKey.take(16)}...")
        }
    }

    private suspend fun isSenderBlocked(
        context: Context,
        senderPublicKey: String
    ): Boolean {
        val db = AppDatabase.getDatabase(context)
        val emailKey = db.emailKeyDao().findByTrustLayerPublicKey(senderPublicKey)
        if (emailKey != null) {
            val count = db.blockedSenderDao().countByEmail(emailKey.email)
            return count > 0
        }
        return false
    }

    private suspend fun saveRefundTask(
        context: Context,
        shareID: String,
        contractId: String,
        refundAddress: String,
        transactions: List<Pair<String, String>>,
        amount: ULong,
        senderPublicKey: String,
        paymentThreshold: ULong
    ) {
        val db = AppDatabase.getDatabase(context)

        val contextJson = JSONObject().apply {
            put("uuid", shareID)
            put("contractId", contractId)
            put("refundAddress", refundAddress)
            put("amount", amount.toLong())
            put("threshold", paymentThreshold.toLong())
            put("senderPublicKey", senderPublicKey)
            put("transactions", JSONArray().apply {
                transactions.forEach { (txid, tx) ->
                    put(JSONObject().apply {
                        put("txid", txid)
                        put("transaction", tx)
                    })
                }
            })
        }

        val entity = RefundTaskEntity(
            shareID = shareID,
            senderPublicKey = senderPublicKey,
            contextJSON = contextJson.toString(),
            createdAt = nowIsoString(),
            status = "PENDING",
            detectedAmount = amount.toLong(),
            paymentThreshold = paymentThreshold.toLong()
        )

        db.refundTaskDao().insert(entity)
        Log.d(TAG, "💾 Refund task saved: shareID=$shareID, amount=$amount")
    }

    private fun nowIsoString(): String {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
        return formatter.format(Instant.now())
    }
}

// ============================================================
// Result Types
sealed class ShareProcessResult {
    data class Success(val total: ULong) : ShareProcessResult()
    data class BelowThreshold(val total: ULong, val threshold: ULong) : ShareProcessResult()
    data class Error(val message: String) : ShareProcessResult()
}

sealed class RefundResult {
    data class Success(val txid: String, val amount: ULong) : RefundResult()
    object Declined : RefundResult()
    data class Error(val message: String) : RefundResult()
}
