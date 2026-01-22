// AccountViewModel.kt
package com.example.sharefilebc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sharefilebc.data.EmailKeyDao
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.jcajce.provider.digest.RIPEMD160

class AccountViewModel(
    private val emailKeyDao: EmailKeyDao
) : ViewModel() {

    private val _publicKeys = MutableStateFlow<List<RegisteredPublicKey>>(emptyList())
    val publicKeys: StateFlow<List<RegisteredPublicKey>> = _publicKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadPublicKeys()
    }

    fun loadPublicKeys() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            emailKeyDao.getAll().collect { list ->
                _publicKeys.value = list.map { entity ->
                    // derivedPublicKey が hex 公開鍵っぽい場合は Base58Check アドレスへ変換して表示用にする
                    val derivedDisplay = toAddressIfHexPublicKey(entity.derivedPublicKey)

                    RegisteredPublicKey(
                        email = entity.email,
                        trustLayerPublicKey = entity.trustLayerPublicKey,
                        derivedPublicKey = derivedDisplay
                    )
                }
                _isLoading.value = false
            }
        }
    }

    /**
     * derivedPublicKey が hex 公開鍵っぽい場合は Base58Check アドレスへ変換して返す。
     * それ以外（すでに Base58 っぽい等）ならそのまま返す。
     */
    private fun toAddressIfHexPublicKey(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return v

        // 既に Base58 っぽいなら変換しない（hexっぽい時だけ変換）
        if (!isHexString(v)) return v

        val pubKeyBytes = runCatching { hexToBytes(v) }.getOrNull() ?: return v

        // 公開鍵の長さが典型（圧縮33 / 非圧縮65）でなければ、そのまま返す
        if (pubKeyBytes.size != 33 && pubKeyBytes.size != 65) return v

        return runCatching {
            pubKeyToBase58Address(pubKeyBytes, version = 0x00.toByte()) // "1..." になる版（必要なら後でTapyrus用に調整）
        }.getOrElse { v }
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
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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

data class RegisteredPublicKey(
    val email: String,
    val trustLayerPublicKey: String,
    val derivedPublicKey: String
)

class AccountViewModelFactory(
    private val emailKeyDao: com.example.sharefilebc.data.EmailKeyDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AccountViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AccountViewModel(emailKeyDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
