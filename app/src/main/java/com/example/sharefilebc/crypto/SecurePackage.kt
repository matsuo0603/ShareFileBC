package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import com.example.sharefilebc.crypto.PublicKeyUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .vpfs パッケージの作成 / 解凍
 * Swift の SecurePackage.swift と同じ構成：
 *
 *  [create]
 *   1) AES鍵生成 → ファイル本体 & ファイル名を AES-GCM で暗号化
 *   2) AES鍵を ECIES で暗号化（受信者公開鍵）
 *   3) 上記すべてのバイト列を連結して ECDSA 署名（送信者秘密鍵）
 *   4) 署名者公開鍵も含めた 12 個のエントリを ZIP 化して .vpfs にする
 *
 *  [unpack]
 *   1) ZIP 展開してそれぞれの要素を取り出す
 *   2) ECIES で AES鍵を復号
 *   3) 署名検証（送信者公開鍵）
 *   4) AES-GCM でファイル名 & 本文を復号
 */
object SecurePackage {

    /**
     * Swift版の uploadedNameMetadata と同等の nameMeta(JSON) を生成するための構造。
     * URLクエリに乗せる都合上 Base64(JSON) で返す。
     */
    data class CreateResult(
        val packageBytes: ByteArray,
        val nameMetaBase64: String
    )

    /**
     * create() の拡張版。
     * - .vpfs 本体に加えて、受信側が一覧表示に使う nameMeta（Swift: uploadedNameMetadata）を生成する。
     *
     * Swift の PickerDelegate.swift と同じキー名で JSON を作り、Base64(JSON) を返す。
     */
    fun createWithNameMeta(
        data: ByteArray,
        fileName: String,
        recipientPublicKeyHex: String,
        signingPrivateKeyHex: String,
        signerPublicKeyHex: String? = null
    ): CreateResult {
        // 1. ファイル本体 & ファイル名を暗号化する AES 鍵を生成
        val aesKey = AESGCMCrypto.generateKey()

        val encBody = AESGCMCrypto.encrypt(data, aesKey)
        val encName = AESGCMCrypto.encrypt(fileName.toByteArray(Charsets.UTF_8), aesKey)

        // 2. AES鍵を ECIES で暗号化
        val encKeyResult = ECIES.encryptAESKey(aesKey, recipientPublicKeyHex)

        // 3. 署名用メッセージ（Swift と同じ順番）
        val message = ByteArrayOutputStream().use { bout ->
            bout.write(encKeyResult.ephemeralPublicKey)
            bout.write(encKeyResult.encryptedAESKey)
            bout.write(encKeyResult.nonce)
            bout.write(encKeyResult.tag)
            bout.write(encBody.nonce)
            bout.write(encBody.ciphertext)
            bout.write(encBody.tag)
            bout.write(encName.nonce)
            bout.write(encName.tag)
            bout.write(encName.ciphertext)
            bout.toByteArray()
        }

        val signature = ECDSA.sign(message, signingPrivateKeyHex)
        val signerPubKeyToEmbed = signerPublicKeyHex
            ?: derivePublicKeyHexFromPrivate(signingPrivateKeyHex)

        // 4. ZIP(.vpfs)
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zos ->
            fun putEntry(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }

            putEntry("keyEphemeral", encKeyResult.ephemeralPublicKey)
            putEntry("keyCipher", encKeyResult.encryptedAESKey)
            putEntry("keyNonce", encKeyResult.nonce)
            putEntry("keyTag", encKeyResult.tag)
            putEntry("bodyNonce", encBody.nonce)
            putEntry("bodyCipher", encBody.ciphertext)
            putEntry("bodyTag", encBody.tag)
            putEntry("nameNonce", encName.nonce)
            putEntry("nameTag", encName.tag)
            putEntry("nameCipher", encName.ciphertext)
            putEntry("signature", signature)
            putEntry("signerPubKey", signerPubKeyToEmbed.toByteArray())
        }

        // 5. nameMeta(JSON)
        // ✅ iOS SecurePackage.NameMetadata と完全に同じキー・意味に合わせる
        // iOS側の signingPayload:
        //   ephemeralPublicKey + encryptedAESKey + nonce(keyNonce) + tag(keyTag) + nameNonce + nameTag + nameCipher
        // ここがズレると iOS で「署名検証に失敗しました」になる。
        val json = org.json.JSONObject().apply {
            put("ephemeralPublicKey", android.util.Base64.encodeToString(encKeyResult.ephemeralPublicKey, android.util.Base64.NO_WRAP))
            put("encryptedAESKey", android.util.Base64.encodeToString(encKeyResult.encryptedAESKey, android.util.Base64.NO_WRAP))
            put("nonce", android.util.Base64.encodeToString(encKeyResult.nonce, android.util.Base64.NO_WRAP))
            put("tag", android.util.Base64.encodeToString(encKeyResult.tag, android.util.Base64.NO_WRAP))
            put("nameNonce", android.util.Base64.encodeToString(encName.nonce, android.util.Base64.NO_WRAP))
            put("nameTag", android.util.Base64.encodeToString(encName.tag, android.util.Base64.NO_WRAP))
            put("nameCipher", android.util.Base64.encodeToString(encName.ciphertext, android.util.Base64.NO_WRAP))
            put("signature", android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP))
        }

        // ✅ iOS は JSONEncoder の結果を base64url（-_, padding無し）で URL クエリに載せる。
        // Android も同じく URL_SAFE + NO_PADDING にして、OS差で壊れないようにする。
        val nameMetaBase64 = android.util.Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )

        return CreateResult(
            packageBytes = zipBytes.toByteArray(),
            nameMetaBase64 = nameMetaBase64
        )
    }


    fun create(
        data: ByteArray,
        fileName: String,
        recipientPublicKeyHex: String,
        signingPrivateKeyHex: String,
        signerPublicKeyHex: String? = null
    ): ByteArray {
        // 1. ファイル本体 & ファイル名を暗号化する AES 鍵を生成
        val aesKey = AESGCMCrypto.generateKey()

        // ファイル本体暗号化
        val encBody = AESGCMCrypto.encrypt(data, aesKey)
        // ファイル名暗号化
        val encName = AESGCMCrypto.encrypt(fileName.toByteArray(Charsets.UTF_8), aesKey)

        println("🔐 key: ${android.util.Base64.encodeToString(aesKey, android.util.Base64.NO_WRAP)}")

        // 2. AES鍵を ECIES で暗号化
        val encKeyResult = ECIES.encryptAESKey(aesKey, recipientPublicKeyHex)

        println("🔐 bodyNonce: ${android.util.Base64.encodeToString(encBody.nonce, android.util.Base64.NO_WRAP)}")
        println("🔐 bodyTag: ${android.util.Base64.encodeToString(encBody.tag, android.util.Base64.NO_WRAP)}")
        println("🔐 nameNonce: ${android.util.Base64.encodeToString(encName.nonce, android.util.Base64.NO_WRAP)}")
        println("🔐 nameTag: ${android.util.Base64.encodeToString(encName.tag, android.util.Base64.NO_WRAP)}")
        println("🔐 nameCipher: ${android.util.Base64.encodeToString(encName.ciphertext, android.util.Base64.NO_WRAP)}")
        println("🔑 keyEphemeral: ${encKeyResult.ephemeralPublicKey.toHexString()}")
        println("🔑 keyCipher: ${android.util.Base64.encodeToString(encKeyResult.encryptedAESKey, android.util.Base64.NO_WRAP)}")
        println("🔑 keyNonce: ${android.util.Base64.encodeToString(encKeyResult.nonce, android.util.Base64.NO_WRAP)}")
        println("🔑 keyTag: ${android.util.Base64.encodeToString(encKeyResult.tag, android.util.Base64.NO_WRAP)}")

        // 3. 署名用メッセージを作成（Swift と同じ順番で連結）
        val message = ByteArrayOutputStream().use { bout ->
            bout.write(encKeyResult.ephemeralPublicKey)
            bout.write(encKeyResult.encryptedAESKey)
            bout.write(encKeyResult.nonce)
            bout.write(encKeyResult.tag)
            bout.write(encBody.nonce)
            bout.write(encBody.ciphertext)
            bout.write(encBody.tag)
            bout.write(encName.nonce)
            bout.write(encName.tag)
            bout.write(encName.ciphertext)
            bout.toByteArray()
        }

        val signature = ECDSA.sign(message, signingPrivateKeyHex)
        println("✍️ signature: ${android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)}")

        val signerPubKeyToEmbed = signerPublicKeyHex
            ?: derivePublicKeyHexFromPrivate(signingPrivateKeyHex)

        // 4. 11 個のファイルを ZIP に詰める
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zos ->
            fun putEntry(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }

            // Swift の SecurePackage と同じエントリ名
            putEntry("keyEphemeral", encKeyResult.ephemeralPublicKey)
            putEntry("keyCipher", encKeyResult.encryptedAESKey)
            putEntry("keyNonce", encKeyResult.nonce)
            putEntry("keyTag", encKeyResult.tag)
            putEntry("bodyNonce", encBody.nonce)
            putEntry("bodyCipher", encBody.ciphertext)
            putEntry("bodyTag", encBody.tag)
            putEntry("nameNonce", encName.nonce)
            putEntry("nameTag", encName.tag)
            putEntry("nameCipher", encName.ciphertext)
            putEntry("signature", signature)
            putEntry("signerPubKey", signerPubKeyToEmbed.toByteArray())
        }

        return zipBytes.toByteArray()
    }

    /**
     * .vpfs パッケージを解凍して復号
     *
     * @return Pair<ファイル本体, ファイル名>
     */
    fun unpack(
        packageData: ByteArray,
        recipientPrivateKeyHex: String,
        signerPublicKeyHex: String?
    ): Pair<ByteArray, String> {
        // 1. ZIP をメモリ上で展開
        var keyEphemeral: ByteArray? = null
        var keyCipher: ByteArray? = null
        var keyNonce: ByteArray? = null
        var keyTag: ByteArray? = null
        var bodyNonce: ByteArray? = null
        var bodyCipher: ByteArray? = null
        var bodyTag: ByteArray? = null
        var nameNonce: ByteArray? = null
        var nameTag: ByteArray? = null
        var nameCipher: ByteArray? = null
        var signature: ByteArray? = null
        var signerPubKey: String? = null

        ZipInputStream(ByteArrayInputStream(packageData)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val bytes = zis.readBytesCompat()   // ← ここを変更（独自関数を呼ぶ）
                when (entry.name) {
                    "keyEphemeral" -> keyEphemeral = bytes
                    "keyCipher" -> keyCipher = bytes
                    "keyNonce" -> keyNonce = bytes
                    "keyTag" -> keyTag = bytes
                    "bodyNonce" -> bodyNonce = bytes
                    "bodyCipher" -> bodyCipher = bytes
                    "bodyTag" -> bodyTag = bytes
                    "nameNonce" -> nameNonce = bytes
                    "nameTag" -> nameTag = bytes
                    "nameCipher" -> nameCipher = bytes
                    "signature" -> signature = bytes
                    "signerPubKey" -> signerPubKey = bytes.toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }

        // 2. 必須要素チェック
        val epk = keyEphemeral
        val kc = keyCipher
        val kn = keyNonce
        val kt = keyTag
        val bn = bodyNonce
        val bc = bodyCipher
        val bt = bodyTag
        val nn = nameNonce
        val nt = nameTag
        val nc = nameCipher
        val sig = signature

        if (epk == null || kc == null || kn == null || kt == null ||
            bn == null || bc == null || bt == null ||
            nn == null || nt == null || nc == null || sig == null
        ) {
            throw IllegalStateException("パッケージの内容が不足しています")
        }

        // 3. ECIES で AES鍵を復号
        val encResult = ECIES.EncryptedResult(
            ephemeralPublicKey = epk,
            encryptedAESKey = kc,
            nonce = kn,
            tag = kt
        )
        val aesKey = ECIES.decryptAESKey(encResult, recipientPrivateKeyHex)

        // 4. 署名検証
        val message = ByteArrayOutputStream().use { bout ->
            bout.write(epk)
            bout.write(kc)
            bout.write(kn)
            bout.write(kt)
            bout.write(bn)
            bout.write(bc)
            bout.write(bt)
            bout.write(nn)
            bout.write(nt)
            bout.write(nc)
            bout.toByteArray()
        }

        val resolvedSignerPubKey = signerPublicKeyHex ?: signerPubKey
        require(!resolvedSignerPubKey.isNullOrBlank()) { "署名者の公開鍵を取得できません" }

        val verified = ECDSA.verify(message, sig, resolvedSignerPubKey)
        if (!verified) {
            println("❌ 署名検証失敗")
            throw IllegalStateException("署名検証に失敗しました")
        }
        println("✅ 署名検証成功")

        // 5. ファイル名と本文を AES-GCM 復号
        val nameBytes = AESGCMCrypto.decrypt(nc, nn, nt, aesKey)
        val fileName = nameBytes.toString(Charsets.UTF_8)
        val fileData = AESGCMCrypto.decrypt(bc, bn, bt, aesKey)

        println("🔓 decrypted file name: $fileName")
        println("🔓 decrypted file size: ${fileData.size} bytes")

        return fileData to fileName
    }

    // InputStream#readAllBytes() (API 33〜) を使わずに、自前で全部読む互換関数
    private fun ZipInputStream.readBytesCompat(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (true) {
            val read = this.read(tmp)
            if (read <= 0) break
            buffer.write(tmp, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun derivePublicKeyHexFromPrivate(privateKeyHex: String): String {
        val privBytes = privateKeyHex.hexToByteArray()
        val privInt = BigInteger(1, privBytes)
        val compressedPubKey = PublicKeyUtils.compressedPublicKeyFromPrivate(privInt)
        return compressedPubKey.toHexString()
    }
}