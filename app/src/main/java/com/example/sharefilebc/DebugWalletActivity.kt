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

    private lateinit var wallet: WalletManager
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wallet = WalletManager.getInstance(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun button(label: String, action: () -> Unit) =
            Button(this).apply {
                text = label
                setOnClickListener { action() }
            }

        logView = TextView(this)

        layout.addView(button("Sync") { sync() })
        layout.addView(button("Balance") { balance() })
        layout.addView(button("New Address") { newAddress() })
        layout.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun sync() = launchIO("sync") { wallet.sync() }

    private fun balance() = launchIO("balance") {
        append("balance = ${wallet.getBalance()} sat")
    }

    private fun newAddress() = launchIO("address") {
        append("address = ${wallet.getNewAddress()}")
    }

    private fun launchIO(label: String, block: suspend () -> Unit) {
        append("▶ $label")
        lifecycleScope.launch {
            runCatching { block() }
                .onFailure { append("❌ ${it::class.simpleName}: ${it.message}") }
        }
    }

    private fun append(msg: String) {
        logView.append(msg + "\n")
    }
}
