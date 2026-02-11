package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature

/**
 * secp256k1 / SHA256withECDSA で署名 & 検証
 * Swift の ECDSA.swift と役割は同じ。
 */
object ECDSA {

    init {
        BouncyCastleInitializer.ensure()
    }

    private const val CURVE_NAME = "secp256k1"

    /**
     * message に対して privateKeyHex（32byte）で DER 署名する。
     */
    fun sign(message: ByteArray, privateKeyHex: String): ByteArray {
        val privBytes = privateKeyHex.hexToByteArray()
        val privateKey = createECPrivateKey(privBytes)

        val provider = BouncyCastleInitializer.ensure()
        val sig = Signature.getInstance("SHA256withECDSA", provider)
        sig.initSign(privateKey)
        // iOS(P256K) 実装は「message をそのまま渡し、内部で SHA-256 を行う」前提。
        // ここで事前に sha256(message) してしまうと "SHA256(SHA256(message))" になり
        // iOS と不一致になって署名検証が必ず失敗する。
        sig.update(message)
        return sig.sign()  // DER 形式
    }

    /**
     * message に対して signature(DER) が publicKeyHex で正しいか検証
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKeyHex: String): Boolean {
        val pubBytes = publicKeyHex.hexToByteArray()
        val publicKey = createECPublicKey(pubBytes)

        val provider = BouncyCastleInitializer.ensure()
        val sig = Signature.getInstance("SHA256withECDSA", provider)
        sig.initVerify(publicKey)

        // iOS と同じく「message をそのまま渡す（内部で SHA-256）」
        sig.update(message)
        val ok = sig.verify(signature)

        // 互換性のため、過去に二重ハッシュで署名してしまったデータが存在する可能性もある。
        // その場合だけフォールバック検証を試す（ログ解析用）。
        if (!ok) {
            return runCatching {
                val hash = sha256(message)
                val sig2 = Signature.getInstance("SHA256withECDSA", provider)
                sig2.initVerify(publicKey)
                sig2.update(hash)
                sig2.verify(signature)
            }.getOrDefault(false)
        }
        return true
    }

    // ===== 内部ユーティリティ =====

    private fun createECPrivateKey(raw32: ByteArray): java.security.PrivateKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val d = BigInteger(1, raw32)
        val privSpec = ECPrivateKeySpec(d, params)
        val provider = BouncyCastleInitializer.ensure()
        val kf = KeyFactory.getInstance("EC", provider)
        return kf.generatePrivate(privSpec)
    }

    private fun createECPublicKey(compressedOrEncoded: ByteArray): java.security.PublicKey {
        val params = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
        val curve = params.curve
        val point = curve.decodePoint(compressedOrEncoded)
        val pubSpec = ECPublicKeySpec(point, params)
        val provider = BouncyCastleInitializer.ensure()
        val kf = KeyFactory.getInstance("EC", provider)
        return kf.generatePublic(pubSpec)
    }

    private fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }
}
