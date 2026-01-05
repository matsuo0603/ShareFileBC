package com.example.sharefilebc

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DebugWalletActivity : AppCompatActivity() {

    private lateinit var wallet: WalletManager
    private lateinit var logView: TextView
    private lateinit var addressInput: EditText
    private lateinit var amountInput: EditText

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

        addressInput = EditText(this).apply {
            hint = "送金先アドレス"
        }

        amountInput = EditText(this).apply {
            hint = "送金額（satoshi）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        logView = TextView(this)

        layout.addView(button("Sync") { sync() })
        layout.addView(button("Balance") { balance() })
        layout.addView(button("New Address") { newAddress() })
        layout.addView(addressInput)
        layout.addView(amountInput)
        layout.addView(button("Transfer") { transfer() })
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

    private fun transfer() {
        val addr = addressInput.text.toString().trim()
        if (addr.isEmpty()) return

        val amount = amountInput.text.toString().trim().toULongOrNull() ?: return

        launchIO("transfer") {
            val txid = wallet.transfer(addr, amount)
            append("txid = $txid")
        }
    }

    private fun launchIO(label: String, block: suspend () -> Unit) {
        append("▶ $label")
        lifecycleScope.launch {
            runCatching { block() }
                .onFailure { append("❌ ${it.message}") }
        }
    }

    private fun append(msg: String) {
        logView.append(msg + "\n")
    }
}
