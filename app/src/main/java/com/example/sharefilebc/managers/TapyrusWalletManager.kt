package com.example.sharefilebc.managers

import android.content.Context
import android.util.Log
import com.example.sharefilebc.KeyManager
import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import com.example.sharefilebc.crypto.KeyDerivation
import com.example.sharefilebc.crypto.PublicKeyUtils
import java.security.MessageDigest

/**
 * Swift版の TapyrusWalletManager に対応するクラス（最小機能版）。
 *
 * 今は「master xprv から受取アドレスを 1つ生成する」こと＋
 * 「同じパスの公開鍵HEX / 秘密鍵HEXを返す」ことだけに専念する。
 *
 * - master xprv の取得は KeyManager に委譲
 * - 子鍵派生は KeyDerivation に委譲
 * - hash160 は PublicKeyUtils.hash160 を必ず通す
 *
 * 将来的には残高やトークン、返金コンテキストなどもここに集約していく想定。
 */
class TapyrusWalletManager private constructor(context: Context) {

    companion object {
        private const val TAG = "TapyrusWalletManager"
        private const val DEFAULT_PATH = "m/44'/0'/0'/0/0"

        @Volatile
        private var instance: TapyrusWalletManager? = null

        fun getInstance(context: Context): TapyrusWalletManager =
            instance ?: synchronized(this) {
                instance ?: TapyrusWalletManager(context.applicationContext).also { instance = it }
            }
    }

    private val keyManager: KeyManager = KeyManager.getInstance(context.applicationContext)

    // ============================================================
    //  公開インターフェース
    // ============================================================

    /**
     * 現在の受取アドレスを返す。
     *
     * Swift版の `currentAddress` に対応するイメージ。
     * 今は常に m/44'/0'/0'/0/0 のアドレス 1つだけを使う。
     */
    fun getCurrentAddress(): String {
        // 1. 子 xprv を取得
        val childXprv = getChildXprvForPath(DEFAULT_PATH)

        // 2. 子 xprv から圧縮公開鍵（hex）を取得
        val compressedPubKeyHex = PublicKeyUtils.compressedPublicKeyHexFromXprv(childXprv)
        val compressedPubKeyBytes = compressedPubKeyHex.hexToByteArray()

        // 3. 圧縮公開鍵 → P2PKH アドレス文字列
        val address = pubKeyToP2PKHAddress(compressedPubKeyBytes)

        Log.d(
            TAG,
            "currentAddress(path=$DEFAULT_PATH) = $address (pubKey=$compressedPubKeyHex)"
        )
        return address
    }

    /**
     * 現在パス (m/44'/0'/0'/0/0) の圧縮公開鍵HEXを返す。
     *
     * SecurePackage / ECIES で「相手に渡す公開鍵」として使う想定。
     */
    fun getCurrentPublicKeyHex(): String {
        val childXprv = getChildXprvForPath(DEFAULT_PATH)
        val compressedPubKeyHex = PublicKeyUtils.compressedPublicKeyHexFromXprv(childXprv)
        Log.d(
            TAG,
            "currentPublicKeyHex(path=$DEFAULT_PATH) = $compressedPubKeyHex"
        )
        return compressedPubKeyHex
    }

    /**
     * 現在パス (m/44'/0'/0'/0/0) の秘密鍵HEX（32byte）を返す。
     *
     * SecurePackage の署名用（ECDSA）や ECIES 復号用に使用。
     *
     * 注意:
     *  - xprv(Base58Check) をデコードして、BIP32 レイアウト
     *    [version(4) | depth(1) | parentFP(4) | childNum(4) | chainCode(32) | 0x00 | privKey(32)]
     *    から「末尾の 32 byte 秘密鍵」を取り出している。
     */
    fun getCurrentPrivateKeyHex(): String {
        val childXprv = getChildXprvForPath(DEFAULT_PATH)
        val privKeyBytes = extractRawPrivateKeyFromXprv(childXprv)
        val privKeyHex = privKeyBytes.toHexString()

        Log.d(
            TAG,
            "currentPrivateKeyHex(path=$DEFAULT_PATH) = $privKeyHex"
        )
        return privKeyHex
    }

    // ============================================================
    //  内部: xprv / pubkey / address ヘルパー
    // ============================================================

    /**
     * 指定パスの子 xprv を取得する共通ヘルパー。
     *  - master xprv の取得は KeyManager に委譲
     *  - その上で KeyDerivation でパスを解決
     */
    private fun getChildXprvForPath(path: String): String {
        // 1. master xprv を取得（なければ KeyManager 側で生成される）
        val masterXprv = keyManager.getOrCreateMasterXprv()

        // 2. 規定パスの子 xprv を導出
        return KeyDerivation.deriveChildXprv(masterXprv, path)
    }

    /**
     * 圧縮公開鍵 (33 byte) から P2PKH アドレス文字列を生成する。
     *
     * 手順:
     *  1. hash160(pubkey) = RIPEMD160(SHA-256(pubkey))
     *     → ここは PublicKeyUtils.hash160 を必ず使う（RIPEMD160 実装を一元化）
     *  2. version byte (0x00) + hash160 → payload (21 byte)
     *  3. payload を SHA-256 で2回ハッシュして先頭4byteを checksum にする
     *  4. [payload + checksum] を Base58 エンコード
     */
    private fun pubKeyToP2PKHAddress(compressedPubKey: ByteArray): String {
        require(compressedPubKey.size == 33) {
            "Compressed public key must be 33 bytes but was ${compressedPubKey.size}"
        }

        // 1. hash160(pubkey) — ここで PublicKeyUtils を利用（前に直した実装を再利用）
        val hash160 = PublicKeyUtils.hash160(compressedPubKey)
        require(hash160.size == 20) { "hash160 must be 20 bytes but was ${hash160.size}" }

        // 2. version(0x00) + hash160
        // ※ ここでは Bitcoin/Tapyrus メインネットと同じ 0x00 を使用
        val version: Byte = 0x00
        val payload = ByteArray(1 + hash160.size)
        payload[0] = version
        System.arraycopy(hash160, 0, payload, 1, hash160.size)

        // 3. checksum = SHA-256(SHA-256(payload)) の先頭 4 byte
        val checksumFull = sha256(sha256(payload))
        val checksum = checksumFull.copyOfRange(0, 4)

        // 4. payload + checksum を Base58 エンコード
        val full = ByteArray(payload.size + checksum.size)
        System.arraycopy(payload, 0, full, 0, payload.size)
        System.arraycopy(checksum, 0, full, payload.size, checksum.size)

        return base58Encode(full)
    }

    // ============================================================
    //  内部: xprv(Base58Check) → 生の32byte秘密鍵
    // ============================================================

    /**
     * xprv (Base58Check エンコードされた拡張秘密鍵) から
     * 生の 32byte 秘密鍵を取り出す。
     *
     * BIP32 のレイアウト:
     *  [version(4) | depth(1) | parentFingerprint(4) | childNumber(4)
     *   | chainCode(32) | 0x00 | privKey(32)]
     *
     * Base58Check では、上記 78byte の payload + 4byte checksum を
     * Base58 で文字列化している。
     */
    private fun extractRawPrivateKeyFromXprv(xprv: String): ByteArray {
        val decoded = base58Decode(xprv)
        require(decoded.size >= 82) {
            "Invalid xprv length (need >= 82 bytes including checksum but was ${decoded.size})"
        }

        // payload(78) + checksum(4)
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)

        // チェックサム検証: sha256(sha256(payload)).prefix(4)
        val expectedChecksum = sha256(sha256(payload)).copyOfRange(0, 4)
        require(checksum.contentEquals(expectedChecksum)) {
            "Invalid xprv checksum"
        }

        require(payload.size == 78) {
            "Invalid xprv payload length (expected 78 bytes but was ${payload.size})"
        }

        // version(4) + depth(1) + parentFP(4) + childNum(4) + chainCode(32) = 45byte
        val keyData = payload.copyOfRange(45, payload.size)
        require(keyData.size == 33) {
            "Invalid keyData length in xprv (expected 33 bytes but was ${keyData.size})"
        }
        require(keyData[0] == 0.toByte()) {
            "Invalid xprv keyData prefix (expected 0x00)"
        }

        // 先頭 0x00 を除いた 32byte が secp256k1 の秘密鍵
        return keyData.copyOfRange(1, keyData.size)
    }

    // ============================================================
    //  内部: ハッシュ / Base58
    // ============================================================

    // ---- ハッシュ系（RIPEMD160 は PublicKeyUtils に集約）----

    private fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    // ---- Base58 エンコード / デコード（Bitcoin/Tapyrus 用アルファベット）----

    private val BASE58_ALPHABET_CHARS =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE58_ALPHABET = BASE58_ALPHABET_CHARS.toCharArray()

    /**
     * payload+checksum のバイト列を Base58Check で文字列化する。
     *
     * ここでは「すでに checksum 付与済み」のバイト列を受け取り、
     * それをそのまま Base58 に変換している。
     */
    private fun base58Encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) {
            zeros++
        }

        val encoded = StringBuilder()
        val copy = input.copyOf()
        var start = zeros

        // 256進を58進に変換
        while (start < copy.size) {
            var carry = 0
            for (i in start until copy.size) {
                val value = copy[i].toInt() and 0xFF
                val newValue = value + carry * 256
                copy[i] = (newValue / 58).toByte()
                carry = newValue % 58
            }
            encoded.append(BASE58_ALPHABET[carry])

            while (start < copy.size && copy[start].toInt() == 0) {
                start++
            }
        }

        repeat(zeros) {
            encoded.append(BASE58_ALPHABET[0]) // '1'
        }

        return encoded.reverse().toString()
    }

    /**
     * Base58Check 文字列をバイト列に戻す。
     * （payload + checksum の生バイト列）
     */
    private fun base58Decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        // 文字 → 0..57 の値に変換
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = BASE58_ALPHABET_CHARS.indexOf(c)
            require(digit >= 0) { "Invalid Base58 character: '$c'" }
            input58[i] = digit.toByte()
        }

        // 先頭の '1' は 0x00 バイトとして数える
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) {
            zeros++
        }

        val decoded = ByteArray(input.length)
        var outputStart = decoded.size

        var inputStart = zeros
        while (inputStart < input58.size) {
            var carry = input58[inputStart].toInt() and 0xFF
            var i = decoded.size - 1
            while (i >= outputStart) {
                val value = decoded[i].toInt() and 0xFF
                val newValue = value * 58 + carry
                decoded[i] = (newValue and 0xFF).toByte()
                carry = newValue ushr 8
                i--
            }
            while (carry > 0) {
                outputStart--
                decoded[outputStart] = (carry and 0xFF).toByte()
                carry = carry ushr 8
            }
            inputStart++
        }

        // 先頭の 0x00 をスキップ
        var resultZeros = 0
        while (resultZeros < decoded.size && decoded[resultZeros].toInt() == 0) {
            resultZeros++
        }

        val result = ByteArray(decoded.size - resultZeros + zeros)
        var j = 0
        repeat(zeros) {
            result[j++] = 0
        }
        System.arraycopy(decoded, resultZeros, result, j, decoded.size - resultZeros)

        return result
    }
}
