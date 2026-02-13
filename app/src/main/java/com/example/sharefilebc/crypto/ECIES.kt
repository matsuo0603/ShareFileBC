package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import android.util.Log
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
import javax.crypto.KeyAgreement

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

    private const val TAG = "ECIES"

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

        // 3. ECDH 共有秘密（Swift互換の sharedSecretBytes）
        //    ※Swift(P256K) 側は「ECDH共有秘密(32byte)」を返し、そこへSHA-256を1回だけ当ててGCM鍵にしている。
        //      Android側が sharedSecret 側でもSHAしてしまうと、Swiftと derivedKey が一致せず
        //      keyCipher の復号で AEADBadTagException(mac check in GCM failed) になる。
        val sharedSecret = generateSharedSecretBytes(ephPrivateKey, recipientPublicKey)

        // 4. sharedSecret → SHA-256 → 派生 AES 鍵（SHAはここで1回だけ）
        val derivedKey = sha256(sharedSecret)

        // 5. 派生 AES 鍵で aesKey を AES-GCM 暗号化
        val enc = AESGCMCrypto.encrypt(aesKey, derivedKey)

        Log.d(TAG, "🔁 ECIES encrypt recipientPubKey: $recipientPublicKeyHex")
        val ephemeralCompressed = ephPublicKey.q.getEncoded(true)
        Log.d(TAG, "🔁 ECIES encrypt ephemeralPubKey: ${ephemeralCompressed.toHexString()}")
        Log.d(TAG, "🔁 ECIES encrypt sharedSecretHash: ${
            android.util.Base64.encodeToString(derivedKey, android.util.Base64.NO_WRAP)
        }")
        Log.d(TAG, "🔁 ECIES encrypt nonce: ${
            android.util.Base64.encodeToString(enc.nonce, android.util.Base64.NO_WRAP)
        }")
        Log.d(TAG, "🔁 ECIES encrypt ciphertext: ${
            android.util.Base64.encodeToString(enc.ciphertext, android.util.Base64.NO_WRAP)
        }")
        Log.d(TAG, "🔁 ECIES encrypt tag: ${
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

        // 2. エフェメラル公開鍵 byte[] → PublicKey（圧縮33B）
        val ephemeralPublicKey = createECPublicKeyFromEncoded(result.ephemeralPublicKey)

        // 3. ECDH 共有秘密（Swift互換の sharedSecretBytes）
        //    互換性のため、まずは「共有秘密32byte(x座標)」→SHA-256(1回) を試し、
        //    それでGCMタグ不一致になる場合のみ「旧方式」(x||y を SHA-256 してからさらにSHA) をフォールバックする。
        val sharedSecretV2 = generateSharedSecretBytes(recipientPrivateKey, ephemeralPublicKey)
        val derivedKeyV2 = sha256(sharedSecretV2)

        val sharedSecretV1 = generateSharedSecretBytesX32(recipientPrivateKey, ephemeralPublicKey)
        val derivedKeyV1 = sha256(sharedSecretV1)

        val fingerprint = sha256(privKeyBytes)
        Log.d(TAG, "🔁 ECIES decrypt recipientPrivKeyFingerprint: ${
            android.util.Base64.encodeToString(fingerprint, android.util.Base64.NO_WRAP)
        }")
        Log.d(TAG, "🔁 ECIES decrypt recipientPubFromPriv: $pubFromPrivHex (expectedLen=66 hex chars)")
        Log.d(TAG, "🔁 ECIES decrypt ephemeralPubKey(len=${result.ephemeralPublicKey.size}): ${result.ephemeralPublicKey.toHexString()}")
        Log.d(TAG, "🔁 ECIES decrypt sharedSecretHash(v2-p256k): ${
            android.util.Base64.encodeToString(derivedKeyV2, android.util.Base64.NO_WRAP)
        }")
        Log.d(TAG, "🔁 ECIES decrypt sharedSecretHash(v1-x32): ${
            android.util.Base64.encodeToString(derivedKeyV1, android.util.Base64.NO_WRAP)
        }")

        // 5. 派生 AES 鍵で AES 鍵を復号（まずは v1）
        val aesKey = try {
            AESGCMCrypto.decrypt(
                ciphertext = result.encryptedAESKey,
                nonce = result.nonce,
                tag = result.tag,
                key = derivedKeyV2
            )
        } catch (e: Exception) {
            // v2(P256K互換=compressed shared point) で失敗した場合は、
            // 過去に Android 側だけで作ってしまった方式(v1=x32) を試す。
            Log.w(TAG, "⚠️ ECIES decrypt v2(p256k) failed -> try v1(x32)", e)

            val aesKeyV1 = runCatching {
                AESGCMCrypto.decrypt(
                    ciphertext = result.encryptedAESKey,
                    nonce = result.nonce,
                    tag = result.tag,
                    key = derivedKeyV1
                )
            }.getOrNull()

            if (aesKeyV1 != null) {
                Log.d(TAG, "✅ ECIES decrypt succeeded with v1(x32)")
                aesKeyV1
            } else {
                // それでもダメなら、さらに古い「legacy」(SHA256(x||y) を sharedSecretBytes とみなしてもう一度SHA) を試す
                val sharedSecretLegacy = generateSharedSecretBytesLegacy(recipientPrivateKey, ephemeralPublicKey)
                val derivedKeyLegacy = sha256(sharedSecretLegacy)
                Log.w(TAG, "⚠️ ECIES decrypt v1(x32) failed -> fallback to legacy")
                Log.d(TAG, "🔁 ECIES decrypt sharedSecretHash(legacy): ${
                    android.util.Base64.encodeToString(derivedKeyLegacy, android.util.Base64.NO_WRAP)
                }")
                AESGCMCrypto.decrypt(
                    ciphertext = result.encryptedAESKey,
                    nonce = result.nonce,
                    tag = result.tag,
                    key = derivedKeyLegacy
                )
            }

        }

        Log.d(TAG, "🔁 ECIES decrypt recoveredAESKey: ${
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

    /**
     * Swift(P256K) と互換にするための sharedSecretBytes。
     *
     * - secp256k1 ECDH の共有点(Q*d) を計算
     * - x座標(32byte) を big-endian で返す
     *
     * Swift側は、この 32byte に対して SHA-256 を1回だけ当てて derivedKey(AES-GCM鍵) にしている。
     */
    private fun generateSharedSecretBytes(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey
    ): ByteArray {
        // ✅ Swift(P256K) 互換:
        //   P256K.KeyAgreement.sharedSecretFromKeyAgreement(with:) の withUnsafeBytes { Data($0) }
        //   は「共有点(Q*d) の圧縮形式 (33byte)」を返している。
        //   その 33byte に対して SHA-256 を 1回だけ当てたものが AES-GCM の鍵になる。
        //
        // したがって Android も、共有点を計算して「圧縮公開鍵(33byte)」を sharedSecretBytes とする。
        val bcPriv = privateKey as? ECPrivateKey
            ?: throw IllegalArgumentException("privateKey is not BC ECPrivateKey")
        val bcPub = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("publicKey is not BC ECPublicKey")

        val point = bcPub.q.multiply(bcPriv.d).normalize()
        // shared point を compressed(33B) にして返す
        return point.getEncoded(true)
    }

    /**
     * 互換用（旧実装）:
     * - KeyAgreement(ECDH).generateSecret() が返す「x座標(32byte big-endian)」を sharedSecretBytes とみなす方式。
     * - Swift 側とは一致しないが、過去に Android 側だけで暗号化したデータ救済のため残す。
     */
    private fun generateSharedSecretBytesX32(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey
    ): ByteArray {
        val provider = BouncyCastleInitializer.ensure()
        val ka = KeyAgreement.getInstance("ECDH", provider)
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        val secret = ka.generateSecret()
        return normalizeSecret32(secret)
    }

    private fun normalizeSecret32(secret: ByteArray): ByteArray {
        // generateSecret() の戻りは provider/実装で長さがブレることがある。
        // 互換のため「右詰め32byte」（足りない分は0埋め）に揃える。
        if (secret.size == 32) return secret
        if (secret.size > 32) return secret.copyOfRange(secret.size - 32, secret.size)
        val out = ByteArray(32)
        System.arraycopy(secret, 0, out, 32 - secret.size, secret.size)
        return out
    }

    /**
     * 旧方式（過去にAndroid側だけで作られた vpfs との互換用）。
     *
     * - (x||y) を SHA-256 した 32byte を sharedSecretBytes とみなし、
     * - 呼び出し側でさらに SHA-256 を当てる (= SHA256(SHA256(x||y)))
     */
    private fun generateSharedSecretBytesLegacy(
        privateKey: java.security.PrivateKey,
        publicKey: java.security.PublicKey
    ): ByteArray {
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
