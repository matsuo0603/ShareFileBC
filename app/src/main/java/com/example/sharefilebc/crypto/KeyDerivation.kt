package com.example.sharefilebc.crypto

import android.util.Log
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Security
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * BIP32 の子鍵派生を担当するクラス。
 * Swift の BIP32.swift と同じ責務で、Tapyrus 版の xprv を扱う。
 */
object KeyDerivation {

    private const val TAG = "KeyDerivation"
    private const val HARDENED_OFFSET = 0x80000000L
    private const val VERSION_XPRV_MAINNET = 0x0488ADE4

    private const val CURVE_NAME = "secp256k1"
    private val CURVE_PARAMS = ECNamedCurveTable.getParameterSpec(CURVE_NAME)

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * パス（例: m/44'/0'/0'/0/0）に従って子 xprv を導出する。
     */
    fun deriveChildXprv(masterXprv: String, path: String): String {
        require(path.startsWith("m")) { "Path must start with 'm'" }
        var extKey = decodeExtendedPrivateKey(masterXprv)

        val segments = path.split("/").drop(1).filter { it.isNotEmpty() }
        for (segment in segments) {
            val hardened = segment.endsWith("'")
            val indexPart = if (hardened) segment.dropLast(1) else segment
            val indexValue = indexPart.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid path segment: $segment")
            require(indexValue in 0..0x7FFFFFFFL) { "Path index out of range: $indexValue" }

            val childIndex = (indexValue or if (hardened) HARDENED_OFFSET else 0).toInt()
            extKey = deriveChild(extKey, childIndex)
        }

        val preview = extKey.toXprvString().take(16)
        Log.d(TAG, "Derived xprv (preview) at $path = ${preview}...")
        return extKey.toXprvString()
    }

    /**
     * Base58Check の xprv 文字列を ExtendedPrivateKey にデコードする。
     * 公開鍵生成で再利用するため public にしている。
     */
    fun decodeExtendedPrivateKey(xprv: String): ExtendedPrivateKey {
        val decoded = Base58Check.decode(xprv)
        require(decoded.size == 78) { "Invalid extended key length" }

        val buffer = ByteBuffer.wrap(decoded)
        val version = buffer.int
        val depth = buffer.get().toInt() and 0xFF
        val parentFingerprint = buffer.int
        val childNumber = buffer.int
        val chainCode = ByteArray(32)
        buffer.get(chainCode)
        val keyPrefix = buffer.get()
        require(keyPrefix.toInt() == 0) { "Invalid private key prefix" }
        val keyBytes = ByteArray(32)
        buffer.get(keyBytes)

        return ExtendedPrivateKey(
            version = version,
            depth = depth,
            parentFingerprint = parentFingerprint,
            childNumber = childNumber,
            chainCode = chainCode,
            key = BigInteger(1, keyBytes)
        )
    }

    private fun deriveChild(parent: ExtendedPrivateKey, index: Int): ExtendedPrivateKey {
        val hardened = (index and 0x80000000.toInt()) != 0
        val data = if (hardened) {
            byteArrayOf(0) + parent.key.to32Bytes() + ser32(index)
        } else {
            PublicKeyUtils.compressedPublicKeyFromPrivate(parent.key) + ser32(index)
        }

        val i = hmacSha512(parent.chainCode, data)
        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)

        val ilInt = BigInteger(1, il)
        val curveN = CURVE_PARAMS.n
        require(ilInt < curveN) { "Invalid derived key (IL >= n)" }

        val childKeyInt = ilInt.add(parent.key).mod(curveN)
        require(childKeyInt != BigInteger.ZERO) { "Derived private key is zero" }

        val fingerprint = PublicKeyUtils.hash160(PublicKeyUtils.compressedPublicKeyFromPrivate(parent.key))
        val parentFp = ByteBuffer.wrap(fingerprint, 0, 4).int

        return ExtendedPrivateKey(
            version = parent.version,
            depth = (parent.depth + 1) and 0xFF,
            parentFingerprint = parentFp,
            childNumber = index,
            chainCode = ir,
            key = childKeyInt
        )
    }

    // ===== Base58Check encode/decode =====
    private object Base58Check {
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val ALPHABET_INDICES = IntArray(128) { -1 }.apply {
            ALPHABET.forEachIndexed { index, c ->
                this[c.code] = index
            }
        }

        fun decode(input: String): ByteArray {
            require(input.isNotEmpty()) { "Input cannot be empty" }
            var num = BigInteger.ZERO
            for (ch in input) {
                val digit = if (ch.code < ALPHABET_INDICES.size) ALPHABET_INDICES[ch.code] else -1
                if (digit < 0) throw IllegalArgumentException("Invalid Base58 character: $ch")
                num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
            }

            val bytes = num.toByteArray().stripLeadingZero()
            var leadingZeros = 0
            for (c in input) {
                if (c == '1') leadingZeros++ else break
            }
            val payloadWithChecksum = ByteArray(leadingZeros + bytes.size)
            System.arraycopy(bytes, 0, payloadWithChecksum, leadingZeros, bytes.size)

            require(payloadWithChecksum.size >= 4) { "Base58 string too short" }
            val payload = payloadWithChecksum.copyOf(payloadWithChecksum.size - 4)
            val checksum = payloadWithChecksum.copyOfRange(payloadWithChecksum.size - 4, payloadWithChecksum.size)
            val expectedChecksum = checksum(payload)
            require(checksum.contentEquals(expectedChecksum)) { "Invalid checksum" }
            return payload
        }

        fun encode(payload: ByteArray): String {
            val withChecksum = payload + checksum(payload)
            var num = BigInteger(1, withChecksum)
            val sb = StringBuilder()
            while (num > BigInteger.ZERO) {
                val divRem = num.divideAndRemainder(BigInteger.valueOf(58))
                num = divRem[0]
                sb.append(ALPHABET[divRem[1].toInt()])
            }
            for (b in withChecksum) {
                if (b.toInt() == 0) sb.append('1') else break
            }
            return sb.reverse().toString()
        }

        private fun checksum(payload: ByteArray): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            val first = sha.digest(payload)
            val second = sha.digest(first)
            return second.copyOfRange(0, 4)
        }
    }

    private fun ser32(i: Int): ByteArray = ByteBuffer.allocate(4).putInt(i).array()

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    private fun BigInteger.to32Bytes(): ByteArray {
        val bytes = this.toByteArray().stripLeadingZero()
        return if (bytes.size >= 32) {
            bytes.copyOfRange(bytes.size - 32, bytes.size)
        } else {
            ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        var offset = 0
        while (offset < this.size && this[offset].toInt() == 0) {
            offset++
        }
        return this.copyOfRange(offset, this.size)
    }

    data class ExtendedPrivateKey(
        val version: Int = VERSION_XPRV_MAINNET,
        val depth: Int,
        val parentFingerprint: Int,
        val childNumber: Int,
        val chainCode: ByteArray,
        val key: BigInteger
    ) {
        fun toXprvString(): String {
            val buffer = ByteBuffer.allocate(78)
            buffer.putInt(version)
            buffer.put(depth.toByte())
            buffer.putInt(parentFingerprint)
            buffer.putInt(childNumber)
            buffer.put(chainCode)
            buffer.put(0)
            buffer.put(key.to32Bytes())
            return Base58Check.encode(buffer.array())
        }
    }
}