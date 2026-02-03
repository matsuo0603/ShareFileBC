package com.example.sharefilebc

import android.content.Context
import android.util.Log
import com.chaintope.tapyrus.wallet.Contract
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.BlockedSenderEntity
import com.example.sharefilebc.data.BlockedSenderSource
import com.example.sharefilebc.data.ReceivedFileEntity
import com.example.sharefilebc.data.RefundTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 共有ファイルの送金検証・返金処理
 * Swift版のTapyrusWalletManager.processReceivedShare / refundShareに相当
 */
object ShareProcessor {

    private const val TAG = "ShareProcessor"

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

        try {
            // 1. 自分の公開鍵取得（TrustLayer用）
            val myKeyEntity = db.myPublicKeyDao().getPrimary()
            val myPublicKey = myKeyEntity?.trustLayerPublicKey
            if (myPublicKey == null) {
                Log.e(TAG, "❌ 自分の公開鍵が未登録")
                return@withContext ShareProcessResult.Error("公開鍵が未登録です")
            }

            // 2. P2Cアドレス生成
            val p2cAddress = walletManager.generateP2CAddress(
                publicKey = myPublicKey,
                contract = uuid,
                colorId = colorId
            )
            Log.d(TAG, "✅ P2C address generated: $p2cAddress")

            // 3. トランザクション検証と金額集計
            var totalAmount = 0UL
            val validTransactions = mutableListOf<Pair<String, String>>()

            for (txid in txids) {
                try {
                    val tx = walletManager.getTransaction(txid)
                    val outputs = walletManager.getTxOutByAddress(tx, p2cAddress)

                    val unspentAmount = outputs
                        .filter { it.unspent }
                        .sumOf { it.amount.toLong() }
                        .toULong()

                    if (unspentAmount > 0UL) {
                        totalAmount += unspentAmount
                        validTransactions.add(txid to tx)
                        Log.d(TAG, "  ✅ txid=$txid: unspent=$unspentAmount")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠️ txid=$txid: failed to verify", e)
                }
            }

            Log.d(TAG, "📊 Total received: $totalAmount (threshold: $threshold)")

            // 4. 送信者がブロックリストに登録されているか確認
            val senderIsBlocked = isSenderBlocked(context, senderPublicKey)

            // 5. 閾値チェックと判定
            val payable = totalAmount < threshold
            val contractId = "shared-file-$uuid"

            if (payable || senderIsBlocked) {
                // ❌ 不正判定：閾値未満 or ブロック済み
                Log.w(TAG, "🚫 Share rejected: payable=$payable, blocked=$senderIsBlocked")

                // Contract保存（payable=true: 自由に使える）
                val contract = Contract(
                    contractId = contractId,
                    contract = uuid,
                    paymentBase = myPublicKey,
                    payable = true
                )
                walletManager.storeContract(contract)

                // ReceivedFileEntity更新（ダウンロード不可）
                updateReceivedFile(
                    context = context,
                    shareID = uuid,
                    senderPublicKey = senderPublicKey,
                    isDownloadAllowed = false,
                    isDownloadBlocked = true,
                    isDownloadEverAllowed = false
                )

                // 不正送信者として登録
                if (!senderIsBlocked) {
                    registerFraudulentSender(context, senderPublicKey, "Payment below threshold")
                }

                return@withContext ShareProcessResult.BelowThreshold(totalAmount, threshold)
            }

            // ✅ 正常判定：閾値以上
            Log.d(TAG, "✅ Share accepted: amount=$totalAmount >= threshold=$threshold")

            // Contract保存（payable=false: 返金専用にロック）
            val contract = Contract(
                contractId = contractId,
                contract = uuid,
                paymentBase = myPublicKey,
                payable = false
            )
            walletManager.storeContract(contract)

            // ReceivedFileEntity更新（ダウンロード可能）
            updateReceivedFile(
                context = context,
                shareID = uuid,
                senderPublicKey = senderPublicKey,
                isDownloadAllowed = true,
                isDownloadBlocked = false,
                isDownloadEverAllowed = true
            )

            // 返金タスク保存
            if (refundAddress != null && validTransactions.isNotEmpty()) {
                saveRefundTask(
                    context = context,
                    shareID = uuid,
                    contractId = contractId,
                    refundAddress = refundAddress,
                    transactions = validTransactions,
                    amount = totalAmount,
                    senderPublicKey = senderPublicKey
                )
            }

            // ウォレット同期
            walletManager.sync()

            return@withContext ShareProcessResult.Success(totalAmount)

        } catch (e: Exception) {
            Log.e(TAG, "❌ processReceivedShare failed", e)
            return@withContext ShareProcessResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 返金処理
     */
    suspend fun refundShare(
        context: Context,
        uuid: String,
        contractId: String,
        refundAddress: String,
        colorId: String = Constants.Strings.tokenColorId
    ): RefundResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "💰 refundShare: uuid=$uuid, contractId=$contractId")

        val walletManager = WalletManager.getInstance(context)
        val db = AppDatabase.getDatabase(context)

        try {
            // 1. RefundTaskEntityから返金情報取得
            val refundTask = db.refundTaskDao().findByShareId(uuid)
            if (refundTask == null) {
                Log.e(TAG, "❌ Refund task not found")
                return@withContext RefundResult.Error("返金情報が見つかりません")
            }

            // 2. ContextJSONをパース
            val contextJson = JSONObject(refundTask.contextJSON ?: "{}")
            val transactions = mutableListOf<Pair<String, String>>()
            val txArray = contextJson.optJSONArray("transactions") ?: JSONArray()
            for (i in 0 until txArray.length()) {
                val txObj = txArray.getJSONObject(i)
                transactions.add(txObj.getString("txid") to txObj.getString("transaction"))
            }

            // 3. 自分の公開鍵取得
            val myKeyEntity = db.myPublicKeyDao().getPrimary()
            val myPublicKey = myKeyEntity?.trustLayerPublicKey
            if (myPublicKey == null) {
                return@withContext RefundResult.Error("公開鍵が未登録です")
            }

            // 4. P2Cアドレス再生成
            val p2cAddress = walletManager.generateP2CAddress(
                publicKey = myPublicKey,
                contract = uuid,
                colorId = colorId
            )

            // 5. UTXO収集
            val utxos = mutableListOf<com.chaintope.tapyrus.wallet.TxOut>()
            var totalAmount = 0UL

            for ((txid, tx) in transactions) {
                val outputs = walletManager.getTxOutByAddress(tx, p2cAddress)
                outputs.filter { it.unspent }.forEach { out ->
                    utxos.add(out)
                    totalAmount += out.amount.toULong()
                }
            }

            if (utxos.isEmpty()) {
                Log.w(TAG, "⚠️ No UTXOs available for refund")
                return@withContext RefundResult.Error("返金可能な出力がありません")
            }

            // 6. トークン送金（返金）
            val txid = walletManager.transferToken(
                toAddress = refundAddress,
                amount = totalAmount,
                colorId = colorId,
                utxos = utxos
            )

            Log.d(TAG, "✅ Refund success: txid=$txid, amount=$totalAmount")

            // 7. ウォレット同期
            walletManager.sync()

            // 8. RefundTask削除
            db.refundTaskDao().deleteByShareId(uuid)

            return@withContext RefundResult.Success(txid, totalAmount)

        } catch (e: Exception) {
            Log.e(TAG, "❌ refundShare failed", e)
            return@withContext RefundResult.Error(e.message ?: "返金に失敗しました")
        }
    }

    /**
     * 返金拒否処理
     */
    suspend fun declineRefund(
        context: Context,
        uuid: String,
        contractId: String,
        senderPublicKey: String
    ): RefundResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "🚫 declineRefund: uuid=$uuid")

        val walletManager = WalletManager.getInstance(context)
        val db = AppDatabase.getDatabase(context)

        try {
            // 1. Contractをpayable=trueに更新（自由に使える状態へ）
            walletManager.updateContractPayable(contractId, payable = true)

            // 2. 不正送信者として登録
            registerFraudulentSender(context, senderPublicKey, "Refund declined by recipient")

            // 3. RefundTask削除
            db.refundTaskDao().deleteByShareId(uuid)

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

    /**
     * ✅ 修正版：copyを使ってReceivedFileEntityを更新
     */
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

        // TrustLayer公開鍵からメールアドレスを逆引き
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
        senderPublicKey: String
    ) {
        val db = AppDatabase.getDatabase(context)

        // JSONで保存
        val contextJson = JSONObject().apply {
            put("uuid", shareID)
            put("contractId", contractId)
            put("refundAddress", refundAddress)
            put("amount", amount.toLong())
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
            createdAt = nowIsoString()
        )

        db.refundTaskDao().insert(entity)
        Log.d(TAG, "💾 Refund task saved: shareID=$shareID, amount=$amount")
    }

    private fun nowIsoString(): String {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault())
        return formatter.format(Instant.now())
    }
}

// ============================================================
// Result Types
// ============================================================

sealed class ShareProcessResult {
    data class Success(val amount: ULong) : ShareProcessResult()
    data class BelowThreshold(val received: ULong, val threshold: ULong) : ShareProcessResult()
    data class Error(val message: String) : ShareProcessResult()
}

sealed class RefundResult {
    data class Success(val txid: String, val amount: ULong) : RefundResult()
    object Declined : RefundResult()
    data class Error(val message: String) : RefundResult()
}
