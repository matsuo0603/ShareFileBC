package com.example.sharefilebc

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.RefundTaskEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DebugWalletActivity : AppCompatActivity() {

    private lateinit var walletManager: WalletManager
    private lateinit var logView: TextView
    private var lastBalance: ULong? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walletManager = WalletManager.getInstance(this)
        val walletSettingsManager = WalletSettingsManager.getInstance(this)
        val database = AppDatabase.getDatabase(applicationContext)
        val refundTaskDao = database.refundTaskDao()
        val blockedSenderDao = database.blockedSenderDao()
        val sharePaymentDao = database.sharePaymentDao()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val addressInput = EditText(this).apply {
            hint = "送金先アドレス"
        }

        val senderIdInput = EditText(this).apply {
            hint = "送信者ID(email/pubkey 任意)"
        }

        val transferAmountView = TextView(this)
        val thresholdView = TextView(this)

        fun addButton(title: String, onClick: () -> Unit) {
            layout.addView(Button(this).apply {
                text = title
                setOnClickListener { onClick() }
            })
        }

        logView = TextView(this)

        fun refreshSettingsLabels() {
            val transferAmount = walletSettingsManager.getTokenTransferAmount()
            val threshold = walletSettingsManager.getPaymentThreshold()
            transferAmountView.text = "送金量(設定): $transferAmount TPC"
            thresholdView.text = "しきい値(設定): $threshold TPC"
        }

        refreshSettingsLabels()

        layout.addView(transferAmountView)
        layout.addView(thresholdView)
        layout.addView(addressInput)
        layout.addView(senderIdInput)

        // ✅ 受取アドレス(新規発行)
        addButton("Get New Address") {
            lifecycleScope.launch {
                runCatching {
                    walletManager.initializeIfNeeded()
                    walletManager.getNewAddress()
                }
                    .onSuccess { addr ->
                        append("new address=$addr")
                    }
                    .onFailure { e ->
                        append("getNewAddress failed: ${e.message}")
                    }
            }
        }

        /**
         * ✅ 送金
         * 重要:
         * - Claude修正後の WalletManager は transfer() を公開していない
         * - transferToken() は utxos が必須だが、この Debug画面には utxos 収集ロジックが無い
         * → ここでは「送金機能を無効化」してコンパイルを通す（送金は本番画面側でテスト）
         */
        addButton("Send") {
            append("Send は現在無効です（WalletManager のAPI変更により）")
            append("SharedScreen / ShareProcessor 側の送金ルートでテストしてください")
            Toast.makeText(
                this@DebugWalletActivity,
                "Send は現在無効です（Debug画面）",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ✅ 同期
        addButton("Sync") {
            lifecycleScope.launch {
                runCatching {
                    walletManager.initializeIfNeeded()
                    walletManager.sync()
                }
                    .onSuccess { append("sync done") }
                    .onFailure { e -> append("sync failed: ${e.message}") }
            }
        }

        // ✅ 残高
        addButton("Balance") {
            lifecycleScope.launch {
                runCatching {
                    walletManager.initializeIfNeeded()
                    walletManager.getBalance()
                }
                    .onSuccess { balance ->
                        Log.d(LogTags.TAG_PAYMENT, "balance=$balance")
                        append("balance=$balance")
                    }
                    .onFailure { e ->
                        Log.e(LogTags.TAG_PAYMENT, "balance check failed", e)
                        append("balance check failed: ${e.message}")
                    }
            }
        }

        // ✅ 入金チェック(sync -> balance 差分)
        addButton("入金チェック") {
            lifecycleScope.launch {
                val previous = lastBalance
                val current = runCatching {
                    walletManager.initializeIfNeeded()
                    walletManager.sync()
                    walletManager.getBalance()
                }.getOrElse { e ->
                    append("入金チェック失敗: ${e.message}")
                    return@launch
                }

                append("balance=$current")

                if (previous != null) {
                    if (current > previous) {
                        val diff = current - previous
                        append("incoming amount=$diff")

                        val threshold = walletSettingsManager.getPaymentThreshold()
                        val senderId = senderIdInput.text?.toString()?.trim().orEmpty()
                            .ifBlank { "unknown" }

                        val isBlocked = withContext(Dispatchers.IO) {
                            blockedSenderDao.countByEmail(senderId) > 0
                        }

                        if (isBlocked) {
                            append("blocked sender=$senderId (refund task skipped)")
                        } else if (diff >= threshold) {
                            val createdAt = nowIsoString()
                            val refundTaskId = withContext(Dispatchers.IO) {
                                refundTaskDao.insert(
                                    RefundTaskEntity(
                                        senderPublicKey = senderId,
                                        contextJSON = """{"amount":"$diff","threshold":"$threshold"}""",
                                        createdAt = createdAt,
                                        status = "PENDING",
                                        detectedAmount = diff.toLong(),
                                        paymentThreshold = threshold.toLong()
                                    )
                                )
                            }
                            append("refund task created (amount=$diff, threshold=$threshold)")
                            Log.d(LogTags.TAG_REFUND, "saved refundTaskId=$refundTaskId")
                            Toast.makeText(
                                this@DebugWalletActivity,
                                "返金タスクを作成しました",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            append("incoming below threshold (threshold=$threshold)")
                        }
                    } else {
                        append("incoming not detected")
                    }
                } else {
                    append("previous balance not set (set now)")
                }

                lastBalance = current
            }
        }

        /**
         * ✅ 手動返金
         * 送金処理が Debug画面から呼べなくなっているため、ここも無効化してコンパイルを通す。
         */
        addButton("手動返金") {
            append("手動返金は現在無効です（Debug画面）")
            append("WalletManager に utxos 収集APIを追加したら復活できます")
            lifecycleScope.launch {
                val payment = withContext(Dispatchers.IO) {
                    sharePaymentDao.findLatestByResult("UNDERPAID")
                }
                if (payment == null) {
                    append("返金対象(UNDERPAID)がありません")
                } else {
                    append("UNDERPAID は存在しますが、Debug画面からは返金送金できません")
                }
            }
        }

        // ✅ ウォレット再初期化(設定変更後の確認用)
        addButton("Reset Wallet (re-init next call)") {
            runCatching { walletManager.resetWallet() }
                .onSuccess { append("wallet reset") }
                .onFailure { e -> append("reset failed: ${e.message}") }
        }

        layout.addView(logView)

        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
    }

    private fun append(msg: String) {
        logView.append(msg + "\n")
    }

    private fun nowIsoString(): String {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
        return formatter.format(Instant.now())
    }
}
