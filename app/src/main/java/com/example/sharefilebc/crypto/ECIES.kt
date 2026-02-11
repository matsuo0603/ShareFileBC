package com.example.sharefilebc.crypto

import android.util.Base64
import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
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
 * ✅ Swift(P256K) 互換の実装
 *
 * Swift版(ShareFileBC/Crypto/ECIES.swift) は ECDH の sharedSecret を
 * `sharedSecret.withUnsafeBytes { Data($0) }` としてバイト列化し、
 * そのバイト列を SHA-256 して AES-GCM の鍵として使っています。
 *
 * 重要: P256K の sharedSecretBytes は「共有点の圧縮形式(33byte)」ではありません。
 * 実体は ECDH の共有点から導かれる 32byte のバイト列（実装上は X座標相当）です。
 *
 * 以前の Android 実装では sharedPoint を compressed(33B) にして SHA-256 していたため、
 * iOS と derivedKey が一致せず、AES-GCM の mac check failed / authenticationFailure が
 * 発生していました。
 *
 * ここでは Swift と同じく、共有点の X 座標を 32byte big-endian に正規化して SHA-256 します。
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
        // 1) 受信者公開鍵
        val recipientPubKeyBytes = recipientPublicKeyHex.hexToByteArray()
        val recipientPublicKey = createECPublicKey(recipientPubKeyBytes) as ECPublicKey

        // 2) エフェメラル鍵ペア生成（BCの secp256k1）
        val ephemeralKeyPair = generateEphemeralKeyPair()
        val ephPrivateKey = ephemeralKeyPair.private as ECPrivateKey
        val ephPublicKey = ephemeralKeyPair.public as ECPublicKey

        // 3) sharedSecret(32B) を使って derivedKey を作る（Swift互換）
        val sharedBytes = computeSharedSecret32(
            privateKey = ephPrivateKey,
            publicKey = recipientPublicKey
        )
        val derivedKey = sha256(sharedBytes)

        // 4) 派生鍵で AES鍵(32B) を AES-GCM 暗号化
        val enc = AESGCMCrypto.encrypt(aesKey, derivedKey)

        val ephCompressed = ephPublicKey.q.getEncoded(true)
        println("🔁 ECIES encrypt recipientPubKey: $recipientPublicKeyHex")
        println("🔁 ECIES encrypt ephemeralPubKey: ${ephCompressed.toHexString()}")
        println("🔁 ECIES encrypt sharedSecret(len=${sharedBytes.size}): ${Base64.encodeToString(sharedBytes, Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt sharedSecretHash: ${Base64.encodeToString(derivedKey, Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt nonce: ${Base64.encodeToString(enc.nonce, Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt ciphertext: ${Base64.encodeToString(enc.ciphertext, Base64.NO_WRAP)}")
        println("🔁 ECIES encrypt tag: ${Base64.encodeToString(enc.tag, Base64.NO_WRAP)}")

        return EncryptedResult(
            // DER(X.509) ではなく圧縮形式（33byte）の公開鍵を格納する
            ephemeralPublicKey = ephCompressed,
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
        // 1) 受信者秘密鍵
        val privKeyBytes = recipientPrivateKeyHex.hexToByteArray()
        val recipientPrivateKey = createECPrivateKey(privKeyBytes) as ECPrivateKey

        // 2) エフェメラル公開鍵（圧縮33B）
        val ephemeralPublicKey = createECPublicKeyFromEncoded(result.ephemeralPublicKey) as ECPublicKey

        // 3) sharedSecret(32B) → derivedKey（Swift互換）
        val sharedBytes = computeSharedSecret32(
            privateKey = recipientPrivateKey,
            publicKey = ephemeralPublicKey
        )
        val derivedKey = sha256(sharedBytes)

        val fingerprint = sha256(privKeyBytes)
        println("🔁 ECIES decrypt recipientPrivKeyFingerprint: ${Base64.encodeToString(fingerprint, Base64.NO_WRAP)}")
        println("🔁 ECIES decrypt ephemeralPubKey(len=${result.ephemeralPublicKey.size}): ${result.ephemeralPublicKey.toHexString()}")
        println("🔁 ECIES decrypt sharedSecret(len=${sharedBytes.size}): ${Base64.encodeToString(sharedBytes, Base64.NO_WRAP)}")
        println("🔁 ECIES decrypt sharedSecretHash: ${Base64.encodeToString(derivedKey, Base64.NO_WRAP)}")
        println("🔁 ECIES decrypt nonce: ${Base64.encodeToString(result.nonce, Base64.NO_WRAP)}")
        println("🔁 ECIES decrypt ciphertext: ${Base64.encodeToString(result.encryptedAESKey, Base64.NO_WRAP)}")
        println("🔁 ECIES decrypt tag: ${Base64.encodeToString(result.tag, Base64.NO_WRAP)}")

        // 4) 派生鍵で AES鍵を復号
        val aesKey = AESGCMCrypto.decrypt(
            ciphertext = result.encryptedAESKey,
            nonce = result.nonce,
            tag = result.tag,
            key = derivedKey
        )

        println("🔁 ECIES decrypt recoveredAESKey: ${Base64.encodeToString(aesKey, Base64.NO_WRAP)}")
        return aesKey
    }

    // ===== 内部ユーティリティ =====

    private fun generateEphemeralKeyPair(): KeyPair {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val provider = BouncyCastleInitializer.ensure()
        val kpg = KeyPairGenerator.getInstance("EC", provider)
        kpg.initialize(params, secureRandom)
        return kpg.generateKeyPair()
    }

    private fun createECPublicKey(compressed: ByteArray): java.security.PublicKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val curve = params.curve
        val point = curve.decodePoint(compressed)
        val pubSpec = ECPublicKeySpec(point, params)

        val provider = BouncyCastleInitializer.ensure()
        val kf = KeyFactory.getInstance("EC", provider)
        return kf.generatePublic(pubSpec)
    }

    private fun createECPublicKeyFromEncoded(encoded: ByteArray): java.security.PublicKey {
        // 今回は圧縮公開鍵(33B)を想定
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

    /**
     * ECDH の共有秘密を 32byte で返す。
     * Swift(P256K) の sharedSecret.withUnsafeBytes { Data($0) } と互換にするため、
     * 共有点の X 座標を 32byte big-endian に正規化する。
     */
    private fun computeSharedSecret32(privateKey: ECPrivateKey, publicKey: ECPublicKey): ByteArray {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)

        val d = privateKey.d
        val q = publicKey.q

        val privParams = ECPrivateKeyParameters(d, domain)
        val pubParams = ECPublicKeyParameters(q, domain)

        val sharedPoint = pubParams.q.multiply(privParams.d).normalize()
        val x = sharedPoint.affineXCoord.toBigInteger()
        return bigIntToFixed32(x)
    }

    private fun bigIntToFixed32(v: BigInteger): ByteArray {
        // v.toByteArray() は符号ビットが入るので調整して 32byte に揃える
        val raw = v.toByteArray()

        // 先頭の 0x00（符号用）を落とす
        val unsigned = if (raw.isNotEmpty() && raw[0] == 0.toByte() && raw.size > 32) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }

        // 32byte に左詰め（big-endian）
        return when {
            unsigned.size == 32 -> unsigned
            unsigned.size < 32 -> ByteArray(32 - unsigned.size) { 0 } + unsigned
            else -> unsigned.copyOfRange(unsigned.size - 32, unsigned.size)
        }
    }

    private fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }
}
