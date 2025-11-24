package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Security
import java.security.Signature

/**
 * secp256k1 / SHA256withECDSA で署名 & 検証
 * Swift の ECDSA.swift と役割は同じ。
 */
object ECDSA {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    private const val CURVE_NAME = "secp256k1"

    /**
     * message に対して privateKeyHex（32byte）で DER 署名する。
     */
    fun sign(message: ByteArray, privateKeyHex: String): ByteArray {
        val privBytes = privateKeyHex.hexToByteArray()
        val privateKey = createECPrivateKey(privBytes)

        // まず message を SHA-256
        val hash = sha256(message)

        val sig = Signature.getInstance("SHA256withECDSA", "BC")
        sig.initSign(privateKey)
        sig.update(hash)
        return sig.sign()  // DER 形式
    }

    /**
     * message に対して signature(DER) が publicKeyHex で正しいか検証
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKeyHex: String): Boolean {
        val pubBytes = publicKeyHex.hexToByteArray()
        val publicKey = createECPublicKey(pubBytes)

        val hash = sha256(message)

        val sig = Signature.getInstance("SHA256withECDSA", "BC")
        sig.initVerify(publicKey)
        sig.update(hash)
        return sig.verify(signature)
    }

    // ===== 内部ユーティリティ =====

    private fun createECPrivateKey(raw32: ByteArray): java.security.PrivateKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val d = BigInteger(1, raw32)
        val privSpec = ECPrivateKeySpec(d, params)
        val kf = KeyFactory.getInstance("EC", "BC")
        return kf.generatePrivate(privSpec)
    }

    private fun createECPublicKey(compressedOrEncoded: ByteArray): java.security.PublicKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val curve = params.curve
        val point = curve.decodePoint(compressedOrEncoded)
        val pubSpec = ECPublicKeySpec(point, params)
        val kf = KeyFactory.getInstance("EC", "BC")
        return kf.generatePublic(pubSpec)
    }

    private fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }
}
