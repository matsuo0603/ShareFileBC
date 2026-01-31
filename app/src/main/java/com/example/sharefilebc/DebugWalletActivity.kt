package com.example.sharefilebc

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import android.widget.Toast
import android.widget.EditText
import android.util.Log


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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val addressInput = EditText(this).apply {
            hint = "送金先アドレス"
        }

        val senderIdInput = EditText(this).apply {
            hint = "送信者ID（email/pubkey 任意）"
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
            transferAmountView.text = "送金量(設定): ${transferAmount} TPC"
            thresholdView.text = "しきい値(設定): ${threshold} TPC"
        }

        refreshSettingsLabels()

        layout.addView(transferAmountView)
        layout.addView(thresholdView)
        layout.addView(addressInput)
        layout.addView(senderIdInput)
        // ✅ 受取アドレス（新規発行）
        addButton("Get New Address") {
            lifecycleScope.launch {
                runCatching { walletManager.getNewAddress() }
                    .onSuccess { append("new address=$it") }
                    .onFailure { append("getNewAddress failed: ${it.message}") }
            }
        }

        // ✅ 送金
        addButton("Send") {
            val toAddress = addressInput.text?.toString()?.trim().orEmpty()
            if (toAddress.isBlank()) {
                append("送金先アドレスを入力してください")
                return@addButton
            }
            val amount = walletSettingsManager.getTokenTransferAmount()
            lifecycleScope.launch {
                runCatching { walletManager.transfer(toAddress, amount) }
                    .onSuccess { append("transfer txid=$it") }
                    .onFailure { append("transfer failed: ${it.message}") }
            }
        }

        // ✅ 同期
        addButton("Sync") {
            lifecycleScope.launch {
                runCatching { walletManager.sync() }
                    .onSuccess { append("sync done") }
                    .onFailure { append("sync failed: ${it.message}") }
            }
        }

        // ✅ 残高
        addButton("Balance") {
            lifecycleScope.launch {
                runCatching { walletManager.getBalance() }
                    .onSuccess {
                        Log.d(LogTags.TAG_PAYMENT, "send txid=$it")
                        append("transfer txid=$it")
                    }
                    .onFailure {
                        Log.e(LogTags.TAG_PAYMENT, "transfer failed", it)
                        append("transfer failed: ${it.message}")
                    }
            }
        }

        // ✅ 入金チェック（sync -> balance 差分）
        addButton("入金チェック") {
            lifecycleScope.launch {
                val previous = lastBalance
                val current = runCatching {
                    walletManager.sync()
                    walletManager.getBalance()
                }.getOrElse {
                    append("入金チェック失敗: ${it.message}")
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

        // ✅ ウォレット再初期化（設定変更後の確認用）
        addButton("Reset Wallet (re-init next call)") {
            runCatching { walletManager.resetWallet() }
                .onSuccess { append("wallet reset") }
                .onFailure { append("reset failed: ${it.message}") }
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
