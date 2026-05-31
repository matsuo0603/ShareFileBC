package com.example.sharefilebc.crypto

import java.lang.StringBuilder

/**
 * HEX 文字列 ⇔ ByteArray の変換ユーティリティ
 */
object HexUtils {

    /**
     * "0a1b2c" → byte[]
     * 先頭 "0x" が付いていたら削除して解釈する。
     */
    fun String.hexToByteArray(): ByteArray {
        var s = this.lowercase()
        if (s.startsWith("0x")) {
            s = s.substring(2)
        }
        require(s.length % 2 == 0) { "Invalid hex string length" }

        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val byteStr = s.substring(i, i + 2)
            out[i / 2] = byteStr.toInt(16).toByte()
            i += 2
        }
        return out
    }

    /**
     * byte[] → "0a1b2c"
     */
    fun ByteArray.toHexString(): String {
        val sb = StringBuilder(this.size * 2)
        for (b in this) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
