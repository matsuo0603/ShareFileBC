package com.example.sharefilebc.crypto

import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 暗号化 / 復号
 * Swift の AESGCMCrypto と同じ役割。
 */
object AESGCMCrypto {

    private const val TAG = "AESGCMCrypto"
    private const val KEY_SIZE_BYTES = 32       // 256 bit
    private const val NONCE_SIZE_BYTES = 12     // GCM 標準の 96bit
    private const val TAG_LENGTH_BITS = 128     // 16 byte

    private val secureRandom = SecureRandom()

    data class CipherResult(
        val ciphertext: ByteArray,
        val nonce: ByteArray,
        val tag: ByteArray
    )

    /**
     * AES-256 用のランダムな 32 バイト鍵を生成
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * AES-256-GCM で暗号化
     * @return 暗号文 / nonce / tag
     */
    fun encrypt(data: ByteArray, key: ByteArray): CipherResult {
        require(key.size == KEY_SIZE_BYTES) { "Invalid AES key size (need 32 bytes)" }

        val nonce = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        // Java の GCM は ciphertext+tag が一塊で返ってくる
        val cipherWithTag = cipher.doFinal(data)

        val tagSizeBytes = TAG_LENGTH_BITS / 8
        val ciphertext = cipherWithTag.copyOfRange(0, cipherWithTag.size - tagSizeBytes)
        val tag = cipherWithTag.copyOfRange(cipherWithTag.size - tagSizeBytes, cipherWithTag.size)

        Log.d(TAG, "🔐 AES-GCM encrypt: dataSize=${data.size}, nonceSize=${nonce.size}, tagSize=${tag.size}, cipherSize=${ciphertext.size}")

        return CipherResult(
            ciphertext = ciphertext,
            nonce = nonce,
            tag = tag
        )
    }

    /**
     * AES-256-GCM で復号
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, tag: ByteArray, key: ByteArray): ByteArray {
        Log.d(TAG, "🔓 AES-GCM decrypt start: cipherSize=${ciphertext.size}, nonceSize=${nonce.size}, tagSize=${tag.size}, keySize=${key.size}")

        require(key.size == KEY_SIZE_BYTES) { "Invalid AES key size (need 32 bytes, got ${key.size})" }
        require(nonce.size == NONCE_SIZE_BYTES) { "Invalid nonce size (need 12 bytes, got ${nonce.size})" }

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            // Swift 側と合わせるため、ciphertext + tag を結合してから渡す
            val cipherWithTag = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, cipherWithTag, 0, ciphertext.size)
            System.arraycopy(tag, 0, cipherWithTag, ciphertext.size, tag.size)

            val result = cipher.doFinal(cipherWithTag)
            Log.d(TAG, "✅ AES-GCM decrypt success: resultSize=${result.size}")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "❌ AES-GCM decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "   nonce: ${android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)}")
            Log.e(TAG, "   tag: ${android.util.Base64.encodeToString(tag, android.util.Base64.NO_WRAP)}")
            Log.e(TAG, "   cipher: ${android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)}")
            Log.e(TAG, "   key: ${android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)}")
            throw e
        }
    }
}