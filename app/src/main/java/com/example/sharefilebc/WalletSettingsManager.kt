package com.example.sharefilebc

import android.content.Context
import android.content.SharedPreferences
import com.chaintope.tapyrus.wallet.Network

class WalletSettingsManager private constructor(context: Context) {

    companion object {
        private const val PREF_FILE = "wallet_settings"
        private const val KEY_NETWORK_MODE = "network_mode"
        private const val KEY_NETWORK_ID = "network_id"
        private const val KEY_GENESIS_HASH = "genesis_hash"
        private const val KEY_ESPLORA_URL = "esplora_url"
        private const val KEY_PRESET = "network_preset"
        private const val KEY_TOKEN_TRANSFER_AMOUNT = "token_transfer_amount"
        private const val KEY_PAYMENT_THRESHOLD = "payment_threshold"

        private const val DEFAULT_PRESET = "PROD"
        private const val DEFAULT_NETWORK_ID = 1195501765u
        private const val DEFAULT_GENESIS_HASH =
            "529fc8b00a65d3f9679052d5f5c63bee961e955ce2e78f47d715c2d357fbdbe5"
        private const val DEFAULT_ESPLORA_URL = "https://index-lab.msc.trustlayer.jp"
        private const val DEFAULT_TOKEN_TRANSFER_AMOUNT = 1L
        private const val DEFAULT_PAYMENT_THRESHOLD = 1L
        @Volatile
        private var INSTANCE: WalletSettingsManager? = null

        fun getInstance(context: Context): WalletSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalletSettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun getNetworkConfig(): WalletNetworkConfig {
        val preset = WalletNetworkPreset.valueOf(prefs.getString(KEY_PRESET, DEFAULT_PRESET)!!)
        return if (preset == WalletNetworkPreset.PROD) {
            defaultNetworkConfig()
        } else {
            WalletNetworkConfig(
                preset = preset,
                networkMode = readNetworkMode(),
                networkId = prefs.getLong(KEY_NETWORK_ID, DEFAULT_NETWORK_ID.toLong()).toUInt(),
                genesisHash = prefs.getString(KEY_GENESIS_HASH, DEFAULT_GENESIS_HASH)!!,
                esploraUrl = prefs.getString(KEY_ESPLORA_URL, DEFAULT_ESPLORA_URL)!!
            )
        }
    }

    fun defaultNetworkConfig(): WalletNetworkConfig {
        return WalletNetworkConfig(
            preset = WalletNetworkPreset.PROD,
            networkMode = Network.PROD,
            networkId = DEFAULT_NETWORK_ID,
            genesisHash = DEFAULT_GENESIS_HASH,
            esploraUrl = DEFAULT_ESPLORA_URL
        )
    }

    fun saveNetworkConfig(config: WalletNetworkConfig) {
        prefs.edit()
            .putString(KEY_PRESET, config.preset.name)
            .putString(KEY_NETWORK_MODE, config.networkMode.name)
            .putLong(KEY_NETWORK_ID, config.networkId.toLong())
            .putString(KEY_GENESIS_HASH, config.genesisHash)
            .putString(KEY_ESPLORA_URL, config.esploraUrl)
            .apply()
    }

    fun getTokenTransferAmount(): ULong {
        val value = prefs.getLong(KEY_TOKEN_TRANSFER_AMOUNT, DEFAULT_TOKEN_TRANSFER_AMOUNT)
        return value.toULong()
    }

    fun setTokenTransferAmount(amount: Long) {
        prefs.edit()
            .putLong(KEY_TOKEN_TRANSFER_AMOUNT, amount)
            .apply()
    }

    fun getPaymentThreshold(): ULong {
        val value = prefs.getLong(KEY_PAYMENT_THRESHOLD, DEFAULT_PAYMENT_THRESHOLD)
        return value.toULong()
    }

    fun setPaymentThreshold(amount: Long) {
        prefs.edit()
            .putLong(KEY_PAYMENT_THRESHOLD, amount)
            .apply()
    }

    private fun readNetworkMode(): Network {
        val mode = prefs.getString(KEY_NETWORK_MODE, Network.PROD.name)
        return runCatching { Network.valueOf(mode!!) }.getOrDefault(Network.PROD)
    }
}

enum class WalletNetworkPreset {
    PROD,
    CUSTOM
}

data class WalletNetworkConfig(
    val preset: WalletNetworkPreset,
    val networkMode: Network,
    val networkId: UInt,
    val genesisHash: String,
    val esploraUrl: String
)