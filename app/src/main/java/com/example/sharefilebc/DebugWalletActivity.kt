package com.example.sharefilebc

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DebugWalletActivity : AppCompatActivity() {

    private lateinit var walletManager: WalletManager
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walletManager = WalletManager.getInstance(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun addButton(title: String, onClick: () -> Unit) {
            layout.addView(Button(this).apply {
                text = title
                setOnClickListener { onClick() }
            })
        }

        logView = TextView(this)

        // ✅ 受取アドレス（新規発行）
        addButton("Get New Address") {
            lifecycleScope.launch {
                runCatching { walletManager.getNewAddress() }
                    .onSuccess { append("new address=$it") }
                    .onFailure { append("getNewAddress failed: ${it.message}") }
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
                    .onSuccess { append("balance=$it") }
                    .onFailure { append("balance failed: ${it.message}") }
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
}
