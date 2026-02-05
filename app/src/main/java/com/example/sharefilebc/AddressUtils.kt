package com.example.sharefilebc

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.jcajce.provider.digest.RIPEMD160

/**
 * 公開鍵(hex) → Base58Check アドレス変換ユーティリティ。
 *
 * NOTE:
 * - Tapyrus のアドレスversionは環境によって異なる可能性があるが、
 *   本アプリでは「受信者のアドレスを公開鍵から一意に求める」用途で使う。
 * - もし Tapyrus 用の version が別であることが判明した場合は、
 *   version を差し替えるだけで全体に反映できる。
 */
object AddressUtils {

    /**
     * 公開鍵(hex)から Base58Check の P2PKH アドレスを生成する。
     *
     * @param pubKeyHex 圧縮(33bytes) / 非圧縮(65bytes) 公開鍵の hex
     * @param version   先頭version (P2PKH). デフォルトは 0x00。
     */
    fun pubKeyHexToBase58Address(pubKeyHex: String, version: Byte = 0x00.toByte()): String {
        val clean = pubKeyHex.trim()
        require(isHexString(clean)) { "pubKeyHex is not hex" }

        val pubKeyBytes = hexToBytes(clean)
        require(pubKeyBytes.size == 33 || pubKeyBytes.size == 65) {
            "pubKey size is not 33/65 bytes"
        }

        return pubKeyToBase58Address(pubKeyBytes, version)
    }

    private fun pubKeyToBase58Address(pubKey: ByteArray, version: Byte): String {
        val sha256 = sha256(pubKey)
        val hash160 = ripemd160(sha256)

        val payload = ByteArray(1 + hash160.size)
        payload[0] = version
        System.arraycopy(hash160, 0, payload, 1, hash160.size)

        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)

        val addressBytes = ByteArray(payload.size + 4)
        System.arraycopy(payload, 0, addressBytes, 0, payload.size)
        System.arraycopy(checksum, 0, addressBytes, payload.size, 4)

        return base58Encode(addressBytes)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun ripemd160(data: ByteArray): ByteArray =
        RIPEMD160.Digest().digest(data)

    private fun isHexString(s: String): Boolean =
        s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && (s.length % 2 == 0)

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58Encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // 先頭の 0x00 の数（Base58では '1' に相当）
        var zeroCount = 0
        while (zeroCount < input.size && input[zeroCount].toInt() == 0) zeroCount++

        var value = BigInteger(1, input)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)

        while (value > BigInteger.ZERO) {
            val divRem = value.divideAndRemainder(base)
            val rem = divRem[1].toInt()
            sb.append(BASE58_ALPHABET[rem])
            value = divRem[0]
        }

        repeat(zeroCount) { sb.append('1') }
        return sb.reverse().toString()
    }
}
