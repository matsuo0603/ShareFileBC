package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.KeyAgreement   // ★ここを java.security から変更

/**
 * ECIES (secp256k1) による AES 鍵のカプセル化
 * Swift の ECIES.swift と同じ流れ：
 *  - 受信者公開鍵 + 送信者エフェメラル秘密鍵 → ECDH
 *  - sharedSecret を SHA-256
 *  - その鍵で AES-GCM により AES鍵を暗号化
 */
object ECIES {

    init {
        // BouncyCastle プロバイダ登録（Application 起動時に一度だけ呼ばれていればOK）
        Security.addProvider(BouncyCastleProvider())
    }

    class EncryptedResult(
        val ephemeralPublicKey: ByteArray,
        val encryptedAESKey: ByteArray,
        val nonce: ByteArray,
        val tag: ByteArray
    )

    private const val CURVE_NAME = "secp256k1"

    private val secureRandom = SecureRandom()

    /**
     * 受信者の圧縮公開鍵HEX（33byte分）を受け取り、
     * AES鍵(aesKey)を ECIES で暗号化する。
     */
    fun encryptAESKey(aesKey: ByteArray, recipientPublicKeyHex: String): EncryptedResult {
        // 1. 受信者公開鍵 hex → PublicKey
        val recipientPubKeyBytes = recipientPublicKeyHex.hexToByteArray()
        val recipientPublicKey = createECPublicKey(recipientPubKeyBytes)

        // 2. エフェメラル鍵ペア生成
        val ephemeralKeyPair = generateEphemeralKeyPair()
        val ephPrivateKey = ephemeralKeyPair.private
        val ephPublicKey = ephemeralKeyPair.public

        // 3. ECDH 共有秘密
        val sharedSecret = generateSharedSecret(ephPrivateKey, recipientPublicKey)

        // 4. sharedSecret → SHA-256 → 派生AES鍵
        val derivedKey = sha256(sharedSecret)

        // 5. 派生AES鍵で AES鍵(aesKey) を AES-GCM 暗号化
        val enc = AESGCMCrypto.encrypt(aesKey, derivedKey)

        // ログ（必要なら残す）
        println("🔁 ECIES encrypt recipientPubKey: $recipientPublicKeyHex")
        println("🔁 ECIES encrypt ephemeralPubKey: ${ephPublicKey.encoded.toHexString()}")
        println("🔁 ECIES encrypt sharedSecretHash: ${android.util.Base64.encodeToString(derivedKey, android.util.Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt nonce: ${android.util.Base64.encodeToString(enc.nonce, android.util.Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt ciphertext: ${android.util.Base64.encodeToString(enc.ciphertext, android.util.Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt tag: ${android.util.Base64.encodeToString(enc.tag, android.util.Base64.NO_WRAP)}")

        // Swift 版と同等：圧縮公開鍵を使いたい場合は encode/decode を工夫する必要があるが、
        // ここでは簡単のため public.encoded をそのまま使っている。
        // （iOS 側と完全同一形式にするなら、圧縮形式 33byte にそろえる必要あり）
        return EncryptedResult(
            ephemeralPublicKey = ephPublicKey.encoded,
            encryptedAESKey = enc.ciphertext,
            nonce = enc.nonce,
            tag = enc.tag
        )
    }

    /**
     * ECIES で暗号化された AES 鍵を復号
     * recipientPrivateKeyHex: 32byte の秘密鍵HEX
     */
    fun decryptAESKey(result: EncryptedResult, recipientPrivateKeyHex: String): ByteArray {
        // 1. 受信者秘密鍵 HEX → PrivateKey
        val privKeyBytes = recipientPrivateKeyHex.hexToByteArray()
        val recipientPrivateKey = createECPrivateKey(privKeyBytes)

        // 2. エフェメラル公開鍵 byte[] → PublicKey
        val ephemeralPublicKey = createECPublicKeyFromEncoded(result.ephemeralPublicKey)

        // 3. ECDH 共有秘密
        val sharedSecret = generateSharedSecret(recipientPrivateKey, ephemeralPublicKey)

        // 4. sharedSecret → SHA-256
        val derivedKey = sha256(sharedSecret)

        val fingerprint = sha256(privKeyBytes)
        println("🔁 ECIES decrypt recipientPrivKeyFingerprint: ${android.util.Base64.encodeToString(fingerprint, android.util.Base64.NO_WRAP)}")
        println("🔁 ECIES decrypt ephemeralPubKey: ${result.ephemeralPublicKey.toHexString()}")
        println("🔁 ECIES decrypt sharedSecretHash: ${android.util.Base64.encodeToString(derivedKey, android.util.Base64.NO_WRAP)}")

        // 5. 派生AES鍵で AES鍵 を復号
        val aesKey = AESGCMCrypto.decrypt(
            ciphertext = result.encryptedAESKey,
            nonce = result.nonce,
            tag = result.tag,
            key = derivedKey
        )

        println("🔁 ECIES decrypt recoveredAESKey: ${android.util.Base64.encodeToString(aesKey, android.util.Base64.NO_WRAP)}")
        return aesKey
    }

    // ===== 内部ユーティリティ =====

    private fun generateEphemeralKeyPair(): KeyPair {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val kpg = KeyPairGenerator.getInstance("EC", "BC")
        kpg.initialize(params, secureRandom)
        return kpg.generateKeyPair()
    }

    private fun createECPublicKey(compressedOrEncoded: ByteArray): java.security.PublicKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val curve = params.curve

        // 公開鍵が圧縮形式(0x02/0x03..)なら decodePoint で復元可能
        val point = curve.decodePoint(compressedOrEncoded)
        val pubSpec = ECPublicKeySpec(point, params)

        val kf = KeyFactory.getInstance("EC", "BC")
        return kf.generatePublic(pubSpec)
    }

    private fun createECPublicKeyFromEncoded(encoded: ByteArray): java.security.PublicKey {
        // 上と同じ実装にしても良いし、encoded がすでにX.509形式なら
        // KeyFactory.generatePublic(X509EncodedKeySpec(encoded)) でもOK。
        return createECPublicKey(encoded)
    }

    private fun createECPrivateKey(raw32: ByteArray): java.security.PrivateKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val d = BigInteger(1, raw32)
        val privSpec = ECPrivateKeySpec(d, params)
        val kf = KeyFactory.getInstance("EC", "BC")
        return kf.generatePrivate(privSpec)
    }

    private fun generateSharedSecret(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey
    ): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH", "BC")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    private fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }
}
