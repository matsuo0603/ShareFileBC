package com.example.sharefilebc.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * BC プロバイダを確実に bcprov-jdk18on 版へ差し替える初期化ヘルパー。
 * Android 標準の "BC" が secp256k1 を未対応のまま保持しているケースを避ける。
 */
object BouncyCastleInitializer {

    private const val PROVIDER_NAME = BouncyCastleProvider.PROVIDER_NAME

    @Volatile
    private var initialized = false

    fun ensure(): String {
        if (initialized && Security.getProvider(PROVIDER_NAME)?.isTargetBc() == true) {
            return PROVIDER_NAME
        }

        synchronized(this) {
            val current = Security.getProvider(PROVIDER_NAME)
            if (!current.isTargetBc()) {
                // 既存の "BC"（Android 標準など）を外し、依存に含めた bcprov を先頭に差し込む
                if (current != null) {
                    Security.removeProvider(PROVIDER_NAME)
                }
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            initialized = true
        }
        return PROVIDER_NAME
    }

    private fun Provider?.isTargetBc(): Boolean = this is BouncyCastleProvider
}