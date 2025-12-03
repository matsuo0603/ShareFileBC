package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.toHexString
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.jce.ECNamedCurveTable
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * xprv から圧縮公開鍵を生成するユーティリティ。
 * Swift 側の ECDSA.swift と対応する形で secp256k1 を利用する。
 */
object PublicKeyUtils {

    private const val CURVE_NAME = "secp256k1"
    private val CURVE_PARAMS = ECNamedCurveTable.getParameterSpec(CURVE_NAME)

    init {
        BouncyCastleInitializer.ensure()
    }

    /**
     * xprv（Base58Check）から圧縮公開鍵の Hex 文字列を返す。
     */
    fun compressedPublicKeyHexFromXprv(xprv: String): String {
        val extKey = KeyDerivation.decodeExtendedPrivateKey(xprv)
        val pubKey = compressedPublicKeyFromPrivate(extKey.key)
        return pubKey.toHexString()
    }

    internal fun compressedPublicKeyFromPrivate(privateKey: BigInteger): ByteArray {
        val point = CURVE_PARAMS.g.multiply(privateKey).normalize()
        return point.getEncoded(true)
    }

    internal fun hash160(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(data)

        return try {
            val provider = BouncyCastleInitializer.ensure()
            MessageDigest.getInstance("RIPEMD160", provider).digest(sha256)
        } catch (_: Exception) {
            try {
                MessageDigest.getInstance("RIPEMD160").digest(sha256)
            } catch (_: NoSuchAlgorithmException) {
                val digest = RIPEMD160Digest()
                digest.update(sha256, 0, sha256.size)
                val out = ByteArray(digest.digestSize)
                digest.doFinal(out, 0)
                out
            } catch (_: Exception) {
                val digest = RIPEMD160Digest()
                digest.update(sha256, 0, sha256.size)
                val out = ByteArray(digest.digestSize)
                digest.doFinal(out, 0)
                out
            }
        }
    }
}
