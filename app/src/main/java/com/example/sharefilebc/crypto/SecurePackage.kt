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

    private const val NAME_META_TAG = "NameMetaDecrypt"

    /**
     * nameMeta(Base64url(JSON)) だけで「元のファイル名」を復号する。
     *
     * - iOS/Swift の uploadedNameMetadata と互換
     * - 受信一覧で securePackage.vpfs のままになる問題の解消用
     *
     * 復号手順:
     *  1) nameMeta を Base64url でデコード -> JSON
     *  2) JSON の keyEphemeral/keyCipher/keyNonce/keyTag で AESKey を ECIES 復号
     *  3) 署名検証（送信者公開鍵）: signingPayload = keyEphemeral+keyCipher+keyNonce+keyTag+nameNonce+nameTag+nameCipher
     *  4) nameCipher を AES-GCM 復号 -> UTF-8 の fileName
     */
    fun decryptFileNameFromNameMeta(
        nameMetaBase64Url: String,
        recipientPrivateKeyHex: String,
        signerPublicKeyHex: String
    ): String {
        println("🔍 decryptFileNameFromNameMeta START")
        println("   nameMetaLength=${nameMetaBase64Url.length}")
        println("   recipientPrivKeyHex=${recipientPrivateKeyHex.take(16)}...")
        println("   signerPubKeyHex=${signerPublicKeyHex.take(16)}...")

        val metaJsonBytes = decodeBase64UrlNoPadding(nameMetaBase64Url.trim())
        val jsonStr = metaJsonBytes.toString(Charsets.UTF_8)
        println("📝 JSON: ${jsonStr.take(200)}...")

        val json = org.json.JSONObject(jsonStr)

        val keyEphemeral = decodeBase64Any(json.getString("ephemeralPublicKey"))
        val keyCipher = decodeBase64Any(json.getString("encryptedAESKey"))
        val keyNonce = decodeBase64Any(json.getString("nonce"))
        val keyTag = decodeBase64Any(json.getString("tag"))

        val nameNonce = decodeBase64Any(json.getString("nameNonce"))
        val nameTag = decodeBase64Any(json.getString("nameTag"))
        val nameCipher = decodeBase64Any(json.getString("nameCipher"))

        val signature = decodeBase64Any(json.getString("signature"))

        println("📊 Parsed metadata:")
        println("   keyEphemeral: ${keyEphemeral.toHexString()} (len=${keyEphemeral.size})")
        println("   keyCipher: ${android.util.Base64.encodeToString(keyCipher, android.util.Base64.NO_WRAP)} (len=${keyCipher.size})")
        println("   keyNonce: ${android.util.Base64.encodeToString(keyNonce, android.util.Base64.NO_WRAP)} (len=${keyNonce.size})")
        println("   keyTag: ${android.util.Base64.encodeToString(keyTag, android.util.Base64.NO_WRAP)} (len=${keyTag.size})")
        println("   nameNonce: ${android.util.Base64.encodeToString(nameNonce, android.util.Base64.NO_WRAP)} (len=${nameNonce.size})")
        println("   nameTag: ${android.util.Base64.encodeToString(nameTag, android.util.Base64.NO_WRAP)} (len=${nameTag.size})")
        println("   nameCipher: ${android.util.Base64.encodeToString(nameCipher, android.util.Base64.NO_WRAP)} (len=${nameCipher.size})")
        println("   signature(base64): ${android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)} (len=${signature.size})")

        // signingPayload: keyEphemeral + keyCipher + keyNonce + keyTag + nameNonce + nameTag + nameCipher
        val message = ByteArrayOutputStream().use { bout ->
            bout.write(keyEphemeral)
            bout.write(keyCipher)
            bout.write(keyNonce)
            bout.write(keyTag)
            bout.write(nameNonce)
            bout.write(nameTag)
            bout.write(nameCipher)
            bout.toByteArray()
        }

        println("🔐 Verifying signature...")
        println("   signerPubKeyHex=$signerPublicKeyHex")
        println("   signingPayload=${bytesSummary(message)}")
        val ok = runCatching { ECDSA.verify(message, signature, signerPublicKeyHex) }.getOrElse { false }
        if (!ok) {
            println("❌ 署名検証に失敗しました（nameMeta）")
            throw IllegalStateException("署名検証に失敗しました（nameMeta）")
        }
        println("✅ 署名検証成功")

        // ECIES: AESKey 復号（※ EncryptedResult を作って渡す）
        println("🔓 Decrypting AES key with ECIES...")
        val aesKey = ECIES.decryptAESKey(
            result = ECIES.EncryptedResult(
                ephemeralPublicKey = keyEphemeral,
                encryptedAESKey = keyCipher,
                nonce = keyNonce,
                tag = keyTag
            ),
            recipientPrivateKeyHex = recipientPrivateKeyHex
        )
        println("✅ AES key decrypted: ${android.util.Base64.encodeToString(aesKey, android.util.Base64.NO_WRAP)}")

        println("🔓 Decrypting file name with AES-GCM...")
        val decName = AESGCMCrypto.decrypt(
            nonce = nameNonce,
            tag = nameTag,
            ciphertext = nameCipher,
            key = aesKey
        )

        val fileName = decName.toString(Charsets.UTF_8)
        println("✅ File name decrypted: $fileName")
        // iOS/Android共通仕様: 表示用ファイル名にコンテナ拡張子(.vpfs)は付けない
        return if (fileName.endsWith(".vpfs", ignoreCase = true)) {
            fileName.removeSuffix(".vpfs")
        } else {
            fileName
        }
    }

    private fun decodeBase64UrlNoPadding(input: String): ByteArray {
        // URL_SAFE + NO_PADDING で作られている想定だが、padding が混ざっても吸収
        var s = input.trim()
        // '+' がスペースに化けて届く事故を吸収（Uri.getQueryParameter 経由など）
        s = s.replace(" ", "+")
        val pad = (4 - (s.length % 4)) % 4
        if (pad != 0) s += "=".repeat(pad)
        return android.util.Base64.decode(s, android.util.Base64.URL_SAFE)
    }

    private fun bytesSummary(bytes: ByteArray, max: Int = 24): String {
        // ログが長すぎて Logcat で途中欠けするのを防ぐための要約
        val head = bytes.take(max).toByteArray()
        return "len=${bytes.size} headHex=${head.toHexString()}"
    }

    private fun decodeBase64Any(input: String): ByteArray {
        val s = input.trim().replace(" ", "+")
        // Swift(JSONEncoder) の Data は通常 Base64（+ / を含む）で出力される。
        // 先に URL_SAFE を試すと、環境によっては誤デコードや想定外の動作になる可能性があるため、
        // DEFAULT を優先し、失敗時のみ URL_SAFE を試す。
        return runCatching { android.util.Base64.decode(s, android.util.Base64.DEFAULT) }
            .recoverCatching { android.util.Base64.decode(s, android.util.Base64.URL_SAFE) }
            .getOrThrow()
    }

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

        // 3. 署名用メッセージ（Swift と完全一致）
        // Swift の SecurePackage.NameMetadata.signingPayload と同じ:
        //   keyEphemeral + keyCipher + keyNonce + keyTag + nameNonce + nameTag + nameCipher
        val message = ByteArrayOutputStream().use { bout ->
            bout.write(encKeyResult.ephemeralPublicKey)
            bout.write(encKeyResult.encryptedAESKey)
            bout.write(encKeyResult.nonce)
            bout.write(encKeyResult.tag)
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
        val aesKey = AESGCMCrypto.generateKey()

        val encBody = AESGCMCrypto.encrypt(data, aesKey)
        val encName = AESGCMCrypto.encrypt(fileName.toByteArray(Charsets.UTF_8), aesKey)

        println("🔐 key: ${android.util.Base64.encodeToString(aesKey, android.util.Base64.NO_WRAP)}")

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

        val message = ByteArrayOutputStream().use { bout ->
            bout.write(encKeyResult.ephemeralPublicKey)
            bout.write(encKeyResult.encryptedAESKey)
            bout.write(encKeyResult.nonce)
            bout.write(encKeyResult.tag)
            bout.write(encName.nonce)
            bout.write(encName.tag)
            bout.write(encName.ciphertext)
            bout.toByteArray()
        }

        val signature = ECDSA.sign(message, signingPrivateKeyHex)
        println("✍️ signature: ${android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)}")

        val signerPubKeyToEmbed = signerPublicKeyHex
            ?: derivePublicKeyHexFromPrivate(signingPrivateKeyHex)

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
                val bytes = zis.readBytesCompat()
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

        val encResult = ECIES.EncryptedResult(
            ephemeralPublicKey = epk,
            encryptedAESKey = kc,
            nonce = kn,
            tag = kt
        )
        val aesKey = ECIES.decryptAESKey(encResult, recipientPrivateKeyHex)

        val message = ByteArrayOutputStream().use { bout ->
            bout.write(epk)
            bout.write(kc)
            bout.write(kn)
            bout.write(kt)
            bout.write(nn)
            bout.write(nt)
            bout.write(nc)
            bout.toByteArray()
        }

        val resolvedSignerPubKey = signerPublicKeyHex ?: signerPubKey
        require(!resolvedSignerPubKey.isNullOrBlank()) { "署名者の公開鍵を取得できません" }

        println("🔐 Verifying signature (vpfs unpack)...")
        println("   signerPubKeyHex=$resolvedSignerPubKey")
        println("   signingPayload=${bytesSummary(message)}")
        println("   signature(base64)=${android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)} (len=${sig.size})")

        val verified = ECDSA.verify(message, sig, resolvedSignerPubKey)
        if (!verified) {
            println("❌ 署名検証失敗")
            throw IllegalStateException("署名検証に失敗しました")
        }
        println("✅ 署名検証成功")

        val nameBytes = AESGCMCrypto.decrypt(nc, nn, nt, aesKey)
        val fileName = nameBytes.toString(Charsets.UTF_8)
        val fileData = AESGCMCrypto.decrypt(bc, bn, bt, aesKey)

        println("🔓 decrypted file name: $fileName")
        println("🔓 decrypted file size: ${fileData.size} bytes")

        return fileData to fileName
    }

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