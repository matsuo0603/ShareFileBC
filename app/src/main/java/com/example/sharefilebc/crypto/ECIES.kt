package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ECIES (secp256k1) による AES 鍵のカプセル化
 *
 *  - 受信者公開鍵 + 送信者エフェメラル秘密鍵 → ECDH
 *  - sharedSecret を SHA-256
 *  - その鍵で AES-GCM により AES鍵を暗号化
 */
object ECIES {

    init {
        // BouncyCastle プロバイダ登録を強制（Android 標準 BC を差し替える）
        BouncyCastleInitializer.ensure()
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
     * recipientPublicKeyHex : 圧縮公開鍵33byteのHEX
     */
    fun encryptAESKey(aesKey: ByteArray, recipientPublicKeyHex: String): EncryptedResult {
        // 1. 受信者公開鍵 HEX → byte[] → PublicKey
        val recipientPubKeyBytes = recipientPublicKeyHex.hexToByteArray()
        val recipientPublicKey = createECPublicKey(recipientPubKeyBytes)

        // 2. エフェメラル鍵ペア生成（BCの secp256k1 を使用）
        val ephemeralKeyPair = generateEphemeralKeyPair()
        val ephPrivateKey = ephemeralKeyPair.private
        val ephPublicKey = ephemeralKeyPair.public as ECPublicKey

        // 3. ECDH 共有秘密
        val sharedSecret = generateSharedSecret(ephPrivateKey, recipientPublicKey)

        // 4. sharedSecret → SHA-256 → 派生 AES 鍵
        val derivedKey = sha256(sharedSecret)

        // 5. 派生 AES 鍵で aesKey を AES-GCM 暗号化
        val enc = AESGCMCrypto.encrypt(aesKey, derivedKey)

        println("🔁 ECIES encrypt recipientPubKey: $recipientPublicKeyHex")
        val ephemeralCompressed = ephPublicKey.q.getEncoded(true)
        println("🔁 ECIES encrypt ephemeralPubKey: ${ephemeralCompressed.toHexString()}")
        println("🔁 ECIES encrypt sharedSecretHash: ${
            android.util.Base64.encodeToString(derivedKey, android.util.Base64.NO_WRAP)
        }")
        println("🔁 ECIES encrypt nonce: ${
            android.util.Base64.encodeToString(enc.nonce, android.util.Base64.NO_WRAP)
        }")
        println("🔁 ECIES encrypt ciphertext: ${
            android.util.Base64.encodeToString(enc.ciphertext, android.util.Base64.NO_WRAP)
        }")
        println("🔁 ECIES encrypt tag: ${
            android.util.Base64.encodeToString(enc.tag, android.util.Base64.NO_WRAP)
        }")

        return EncryptedResult(
            // DER(X.509) ではなく圧縮形式（33byte）の公開鍵を格納する
            ephemeralPublicKey = ephemeralCompressed,
            encryptedAESKey = enc.ciphertext,
            nonce = enc.nonce,
            tag = enc.tag
        )
    }

    /**
     * ECIES で暗号化された AES 鍵を復号
     * recipientPrivateKeyHex: 32byte 秘密鍵 HEX
     */
    fun decryptAESKey(result: EncryptedResult, recipientPrivateKeyHex: String): ByteArray {
        // 1. 秘密鍵 HEX → PrivateKey
        val privKeyBytes = recipientPrivateKeyHex.hexToByteArray()
        val recipientPrivateKey = createECPrivateKey(privKeyBytes)

        // 切り分け用：この秘密鍵に対応する「圧縮公開鍵(33byte)」を計算して出す
        val pubFromPrivHex = runCatching {
            val d = BigInteger(1, privKeyBytes)
            PublicKeyUtils.compressedPublicKeyFromPrivate(d).toHexString()
        }.getOrElse { "(failed)" }

        // 2. エフェメラル公開鍵 byte[] → PublicKey
        val ephemeralPublicKey = createECPublicKeyFromEncoded(result.ephemeralPublicKey)

        // 3. ECDH 共有秘密
        val sharedSecret = generateSharedSecret(recipientPrivateKey, ephemeralPublicKey)

        // 4. sharedSecret → SHA-256
        val derivedKey = sha256(sharedSecret)

        val fingerprint = sha256(privKeyBytes)
        println("🔁 ECIES decrypt recipientPrivKeyFingerprint: ${
            android.util.Base64.encodeToString(fingerprint, android.util.Base64.NO_WRAP)
        }")
        println("🔁 ECIES decrypt recipientPubFromPriv: $pubFromPrivHex (expectedLen=66 hex chars)")
        println("🔁 ECIES decrypt ephemeralPubKey(len=${result.ephemeralPublicKey.size}): ${result.ephemeralPublicKey.toHexString()}")
        println("🔁 ECIES decrypt sharedSecretHash: ${
            android.util.Base64.encodeToString(derivedKey, android.util.Base64.NO_WRAP)
        }")

        // 5. 派生 AES 鍵で AES 鍵を復号
        val aesKey = AESGCMCrypto.decrypt(
            ciphertext = result.encryptedAESKey,
            nonce = result.nonce,
            tag = result.tag,
            key = derivedKey
        )

        println("🔁 ECIES decrypt recoveredAESKey: ${
            android.util.Base64.encodeToString(aesKey, android.util.Base64.NO_WRAP)
        }")
        return aesKey
    }

    // ===== 内部ユーティリティ =====

    private fun generateEphemeralKeyPair(): KeyPair {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val provider = BouncyCastleInitializer.ensure()
        val kpg = KeyPairGenerator.getInstance("EC", provider)  // ★ AndroidOpenSSL ではなく BC
        kpg.initialize(params, secureRandom)
        return kpg.generateKeyPair()
    }

    private fun createECPublicKey(compressedOrEncoded: ByteArray): java.security.PublicKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val curve = params.curve
        val point = curve.decodePoint(compressedOrEncoded) // 圧縮形式を復元
        val pubSpec = ECPublicKeySpec(point, params)

        val provider = BouncyCastleInitializer.ensure()
        val kf = KeyFactory.getInstance("EC", provider)
        return kf.generatePublic(pubSpec)
    }

    private fun createECPublicKeyFromEncoded(encoded: ByteArray): java.security.PublicKey {
        // 今回は圧縮公開鍵を想定しているので、そのまま上の関数を使う
        return createECPublicKey(encoded)
    }

    private fun createECPrivateKey(raw32: ByteArray): java.security.PrivateKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val d = BigInteger(1, raw32)
        val privSpec = ECPrivateKeySpec(d, params)
        val provider = BouncyCastleInitializer.ensure()
        val kf = KeyFactory.getInstance("EC", provider)
        return kf.generatePrivate(privSpec)
    }

    private fun generateSharedSecret(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey
    ): ByteArray {
        /**
         * ✅ iOS(P256K) の KeyAgreement は libsecp256k1 の ECDH 実装に近い挙動で、
         * 共有秘密が「x座標そのもの」ではなく (x||y) に対する SHA256 になっている可能性が高い。
         *
         * iOS 側は
         *   sharedSecretBytes = sharedSecret.withUnsafeBytes { Data($0) }
         *   derivedKey       = SHA256(sharedSecretBytes)
         *
         * なので Android も
         *   sharedSecretBytes = SHA256(x||y)
         *   derivedKey       = SHA256(sharedSecretBytes)
         * を行うことで iOS と一致させる。
         */

        val bcPriv = privateKey as? ECPrivateKey
            ?: throw IllegalArgumentException("privateKey is not BC ECPrivateKey")
        val bcPub = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("publicKey is not BC ECPublicKey")

        val point = bcPub.q.multiply(bcPriv.d).normalize()
        val x = point.affineXCoord.toBigInteger()
        val y = point.affineYCoord.toBigInteger()

        val x32 = toFixed32(x)
        val y32 = toFixed32(y)
        val xy = ByteArray(64)
        System.arraycopy(x32, 0, xy, 0, 32)
        System.arraycopy(y32, 0, xy, 32, 32)

        // sharedSecretBytes = SHA256(x||y)
        return sha256(xy)
    }



    private fun toFixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        // BigInteger.toByteArray() は符号付きで先頭に 0x00 が付く場合がある
        val unsigned = if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        if (unsigned.size == 32) return unsigned
        if (unsigned.size > 32) return unsigned.copyOfRange(unsigned.size - 32, unsigned.size)
        val out = ByteArray(32)
        System.arraycopy(unsigned, 0, out, 32 - unsigned.size, unsigned.size)
        return out
    }
    private fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }
}
