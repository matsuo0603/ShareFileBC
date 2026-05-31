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

    // secp256k1 curve order (n)
    // n = FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFE BAAEDCE6 AF48A03B BFD25E8C D0364141
    private val CURVE_N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        16
    )
    private val HALF_CURVE_N: BigInteger = CURVE_N.shiftRight(1)

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
        val der = sig.sign()  // DER 形式

        // ✅ iOS(P256K) 互換のため low-S 正規化(canonical) を行う。
        // P256K の ECDSASignature(derRepresentation:) は high-S を弾く(または isValidSignature が false) ことがある。
        // その場合、iOS 側で「署名検証失敗」になり、nameMeta 復号にも本体復号にも進めない。
        return normalizeDerToLowS(der)
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
        var ok = sig.verify(signature)

        // high-S 署名が来た場合でも検証できるようにフォールバック。
        if (!ok) {
            ok = runCatching {
                val normalized = normalizeDerToLowS(signature)
                val sigN = Signature.getInstance("SHA256withECDSA", provider)
                sigN.initVerify(publicKey)
                sigN.update(message)
                sigN.verify(normalized)
            }.getOrDefault(false)
        }

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

    /**
     * DER(ECDSA) 署名を low-S (canonical) に正規化して DER を返す。
     * - s > n/2 のとき s = n - s
     * - それ以外はそのまま
     */
    private fun normalizeDerToLowS(der: ByteArray): ByteArray {
        val (r, s) = parseDerSignature(der)
        val sNorm = if (s > HALF_CURVE_N) CURVE_N.subtract(s) else s
        if (sNorm == s) return der
        return encodeDerSignature(r, sNorm)
    }

    /**
     * ASN.1 DER 形式の ECDSA 署名 (SEQUENCE { INTEGER r; INTEGER s }) をパースする。
     */
    private fun parseDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var idx = 0
        fun readByte(): Int = der[idx++].toInt() and 0xFF
        fun readLen(): Int {
            val b = readByte()
            if (b and 0x80 == 0) return b
            val n = b and 0x7F
            var len = 0
            repeat(n) { len = (len shl 8) or readByte() }
            return len
        }

        require(readByte() == 0x30) { "Invalid DER: not a SEQUENCE" }
        readLen() // total length

        require(readByte() == 0x02) { "Invalid DER: missing r INTEGER" }
        val rLen = readLen()
        val rBytes = der.copyOfRange(idx, idx + rLen)
        idx += rLen
        val r = BigInteger(1, rBytes)

        require(readByte() == 0x02) { "Invalid DER: missing s INTEGER" }
        val sLen = readLen()
        val sBytes = der.copyOfRange(idx, idx + sLen)
        idx += sLen
        val s = BigInteger(1, sBytes)

        return r to s
    }

    /**
     * r, s を ASN.1 DER 形式へエンコードする。
     */
    private fun encodeDerSignature(r: BigInteger, s: BigInteger): ByteArray {
        fun encodeInt(x: BigInteger): ByteArray {
            var bytes = x.toByteArray()
            // BigInteger.toByteArray() は符号付き。
            // 不要な先頭 0x00 は落とし、必要な 0x00 は付ける。
            if (bytes.size > 1 && bytes[0] == 0.toByte() && (bytes[1].toInt() and 0x80) == 0) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            if ((bytes[0].toInt() and 0x80) != 0) {
                bytes = byteArrayOf(0) + bytes
            }
            return bytes
        }

        fun encodeLen(len: Int): ByteArray {
            if (len < 0x80) return byteArrayOf(len.toByte())
            val tmp = mutableListOf<Byte>()
            var v = len
            while (v > 0) {
                tmp.add(0, (v and 0xFF).toByte())
                v = v ushr 8
            }
            return byteArrayOf((0x80 or tmp.size).toByte()) + tmp.toByteArray()
        }

        val rBytes = encodeInt(r)
        val sBytes = encodeInt(s)
        val rPart = byteArrayOf(0x02) + encodeLen(rBytes.size) + rBytes
        val sPart = byteArrayOf(0x02) + encodeLen(sBytes.size) + sBytes
        val seqBody = rPart + sPart
        return byteArrayOf(0x30) + encodeLen(seqBody.size) + seqBody
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
