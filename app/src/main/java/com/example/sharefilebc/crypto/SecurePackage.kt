package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.hexToByteArray
import com.example.sharefilebc.crypto.HexUtils.toHexString
import com.example.sharefilebc.crypto.PublicKeyUtils.compressedPublicKeyHexFromPrivateKeyHex
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

        // ✅ 重要: nameMeta は URL クエリに載るため、
        // - Swift 側が「通常Base64(+/==)」のまま載せるケース
        // - Android 側が「Base64URL(-_ no padding)」で載せるケース
        // - Uri.getQueryParameter 経由で '+' が空白に化けるケース
        // が混在し得る。
        // ここでは「URL_SAFE/DEFAULT 両対応」「padding 自動補完」「空白→+補正」を行い、
        // “iOS↔Androidのどちらの生成物でも”必ず同じ JSON バイト列に戻す。
        val metaJsonBytes = decodeBase64Flexible(nameMetaBase64Url)
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
        // signerPublicKeyHex には "pk1,pk2,..." のように候補をカンマ区切りで渡せる（iOS側の sender が誤っていても復旧できるようにする）
        val signerCandidates = signerPublicKeyHex
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val sigMessageSha256 = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(message)
            .toHexString()

        println("   signerCandidates(count)=${signerCandidates.size}")
        signerCandidates.forEachIndexed { idx, pk ->
            println("   signerCandidates[$idx]=${pk}")
        }
        println("   signingPayload=${bytesSummary(message)}")
        println("   sigMessageSha256=$sigMessageSha256")

        var matchedSigner: String? = null
        for (pk in signerCandidates) {
            val ok = runCatching { ECDSA.verify(message, signature, pk) }.getOrElse { false }
            if (ok) {
                matchedSigner = pk
                break
            }
        }

        if (matchedSigner == null) {
            println("❌ 署名検証に失敗しました（nameMeta）")
            throw IllegalStateException("signature verify failed (nameMeta)")
        }
        println("✅ 署名検証成功 matchedSigner=$matchedSigner")

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

    /**
     * Base64/ Base64URL を「どっちでも」デコードする。
     * - padding(=) が無い/足りない → 自動補完
     * - '+' が空白に化ける → 空白→'+' 補正
     * - '-' '_' を含む → URL_SAFE とみなして補正
     */
    private fun decodeBase64Flexible(input: String): ByteArray {
        var s = input.trim().replace(" ", "+")

        // まず “見た目” で URL_SAFE かどうか判定
        val looksUrlSafe = s.contains('-') || s.contains('_')

        fun withPadding(x: String): String {
            val pad = (4 - (x.length % 4)) % 4
            return if (pad == 0) x else x + "=".repeat(pad)
        }

        // URL_SAFE の場合は URL_SAFE を優先して試す
        if (looksUrlSafe) {
            val padded = withPadding(s)
            return runCatching { android.util.Base64.decode(padded, android.util.Base64.URL_SAFE) }
                .recoverCatching {
                    // 念のため DEFAULT でも試す（互換切り分け用）
                    android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                }
                .getOrThrow()
        }

        // 通常 Base64 の場合は DEFAULT を優先して試す
        val padded = withPadding(s)
        return runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT) }
            .recoverCatching { android.util.Base64.decode(padded, android.util.Base64.URL_SAFE) }
            .getOrThrow()
    }

    private fun bytesSummary(bytes: ByteArray, max: Int = 24): String {
        // ログが長すぎて Logcat で途中欠けするのを防ぐための要約
        val head = bytes.take(max).toByteArray()
        return "len=${bytes.size} headHex=${head.toHexString()}"
    }

    private fun decodeBase64Any(input: String): ByteArray {
        // JSON 内の各フィールドは Swift 側が通常 Base64(+/==) を使う。
        // ただし Android 側は URL_SAFE を使うこともあるため、両対応 + padding 補完。
        return decodeBase64Flexible(input)
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

        // ✅ 送信側で「署名に使った秘密鍵 ↔ 公開鍵」の対応を必ず確定する。
        // ここがズレると iOS は署名検証が100%失敗し、ファイル名復号もダウンロードもできない。
        val signerPubFromPriv = compressedPublicKeyHexFromPrivateKeyHex(signingPrivateKeyHex)
        val providedSignerPub = signerPublicKeyHex?.trim()
        val signerPubKeyToEmbed = when {
            providedSignerPub.isNullOrBlank() -> signerPubFromPriv
            providedSignerPub.equals(signerPubFromPriv, ignoreCase = true) -> providedSignerPub
            else -> {
                android.util.Log.e(
                    "CryptoTrace",
                    "[NAME_META][SIGN_KEY_MISMATCH] providedSignerPub=$providedSignerPub derivedFromPriv=$signerPubFromPriv -> override to derivedFromPriv"
                )
                signerPubFromPriv
            }
        }

        val signature = ECDSA.sign(message, signingPrivateKeyHex)

        // DEBUG: iOS との突き合わせ用（署名対象のSHA256 / DER署名サイズ / 自己検証結果）
        runCatching {
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(message)
            val shaB64 = android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
            val selfVerify = ECDSA.verify(message, signature, signerPubKeyToEmbed)
            android.util.Log.d(
                "CryptoTrace",
                "[NAME_META][SIGN] payloadSha256=$shaB64 sigLen=${signature.size} signerPub=$signerPubKeyToEmbed selfVerify=$selfVerify"
            )
        }

        // 4. vpfs(zip)
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
            put("signerPubKeyHex", signerPubKeyToEmbed)
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

        val encKeyResult = ECIES.encryptAESKey(aesKey, recipientPublicKeyHex)

        // ✅ Swift版互換: 署名対象は NameMetadata.signingPayload のみ。
        // signingPayload = keyEphemeral + keyCipher + keyNonce + keyTag + nameNonce + nameTag + nameCipher
        // ※ ファイル本体(body*)の改ざん検知は AES-GCM(tag) により担保される。
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

    data class Unpacked(
        val bodyPlain: ByteArray,
        val fileName: String,
        val signerPubKeyHex: String
    )

    fun unpack(
        vpfsBytes: ByteArray,
        recipientPrivateKeyHex: String,
        signerPublicKeyHex: String? = null
    ): Unpacked {
        val map = unzipToMap(vpfsBytes)

        val keyEphemeral = map["keyEphemeral"] ?: error("missing keyEphemeral")
        val keyCipher = map["keyCipher"] ?: error("missing keyCipher")
        val keyNonce = map["keyNonce"] ?: error("missing keyNonce")
        val keyTag = map["keyTag"] ?: error("missing keyTag")

        val bodyNonce = map["bodyNonce"] ?: error("missing bodyNonce")
        val bodyCipher = map["bodyCipher"] ?: error("missing bodyCipher")
        val bodyTag = map["bodyTag"] ?: error("missing bodyTag")

        val nameNonce = map["nameNonce"] ?: error("missing nameNonce")
        val nameTag = map["nameTag"] ?: error("missing nameTag")
        val nameCipher = map["nameCipher"] ?: error("missing nameCipher")

        val signature = map["signature"] ?: error("missing signature")
        val embeddedSignerPubKey = map["signerPubKey"]?.toString(Charsets.UTF_8).orEmpty()

        // signerPublicKeyHex は "pk1,pk2,..." のように候補をカンマ区切りで渡せる。
        // iOS 側の sender が誤っている/未保存で別キーが入った場合でも、DB にある鍵などで復旧できるようにする。
        val candidates = mutableListOf<String>()

        val fromArg = signerPublicKeyHex
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        candidates.addAll(fromArg)
        if (embeddedSignerPubKey.isNotBlank() && !candidates.contains(embeddedSignerPubKey)) {
            candidates.add(embeddedSignerPubKey)
        }
        if (candidates.isEmpty()) error("missing signer public key")

        val sigMessageSha256 = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(ByteArrayOutputStream().use { bout ->
                // V1 の signingPayload と同じ内容でハッシュを出す（ログで Swift/Kotlin の差分を追えるように）
                bout.write(keyEphemeral)
                bout.write(keyCipher)
                bout.write(keyNonce)
                bout.write(keyTag)
                bout.write(nameNonce)
                bout.write(nameTag)
                bout.write(nameCipher)
                bout.toByteArray()
            })
            .toHexString()

        println("🔐 unpack(): signerCandidates(count)=${candidates.size}")
        candidates.forEachIndexed { idx, pk ->
            println("   signerCandidates[$idx]=$pk")
        }
        println("🔐 unpack(): sigMessageSha256=$sigMessageSha256")

        // ✅ Swift版互換: まず NameMetadata.signingPayload で検証する。
        // signingPayload = keyEphemeral + keyCipher + keyNonce + keyTag + nameNonce + nameTag + nameCipher
        val signingPayload = ByteArrayOutputStream().use { bout ->
            bout.write(keyEphemeral)
            bout.write(keyCipher)
            bout.write(keyNonce)
            bout.write(keyTag)
            bout.write(nameNonce)
            bout.write(nameTag)
            bout.write(nameCipher)
            bout.toByteArray()
        }

        var matchedSigner: String? = null
        var okV1 = false
        for (pk in candidates) {
            val ok = runCatching { ECDSA.verify(signingPayload, signature, pk) }.getOrElse { false }
            if (ok) {
                matchedSigner = pk
                okV1 = true
                break
            }
        }

        if (!okV1) {
            // 互換性: 以前のAndroid実装（body込み署名）で作られた vpfs を受け取る可能性があるので、旧方式でも検証してみる。
            val legacyMessage = ByteArrayOutputStream().use { bout ->
                bout.write(keyEphemeral)
                bout.write(keyCipher)
                bout.write(keyNonce)
                bout.write(keyTag)
                bout.write(bodyNonce)
                bout.write(bodyCipher)
                bout.write(bodyTag)
                bout.write(nameNonce)
                bout.write(nameTag)
                bout.write(nameCipher)
                bout.toByteArray()
            }
            var okLegacy = false
            for (pk in candidates) {
                val ok = runCatching { ECDSA.verify(legacyMessage, signature, pk) }.getOrElse { false }
                if (ok) {
                    matchedSigner = pk
                    okLegacy = true
                    break
                }
            }
            if (!okLegacy) error("signature verify failed")
        }

        val verifyPubKey = matchedSigner ?: candidates.first()
        println("✅ unpack(): signature verified with=$verifyPubKey")

        val aesKey = ECIES.decryptAESKey(
            result = ECIES.EncryptedResult(
                ephemeralPublicKey = keyEphemeral,
                encryptedAESKey = keyCipher,
                nonce = keyNonce,
                tag = keyTag
            ),
            recipientPrivateKeyHex = recipientPrivateKeyHex
        )

        val bodyPlain = AESGCMCrypto.decrypt(
            nonce = bodyNonce,
            tag = bodyTag,
            ciphertext = bodyCipher,
            key = aesKey
        )

        val namePlain = AESGCMCrypto.decrypt(
            nonce = nameNonce,
            tag = nameTag,
            ciphertext = nameCipher,
            key = aesKey
        )

        val fileName = namePlain.toString(Charsets.UTF_8)

        return Unpacked(
            bodyPlain = bodyPlain,
            fileName = fileName,
            signerPubKeyHex = verifyPubKey
        )
    }

    private fun unzipToMap(zipBytes: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                val bytes = zis.readBytes()
                map[name] = bytes
                zis.closeEntry()
            }
        }
        return map
    }

    private fun derivePublicKeyHexFromPrivate(privateKeyHex: String): String {
        val priv = BigInteger(1, privateKeyHex.hexToByteArray())
        return PublicKeyUtils.compressedPublicKeyFromPrivate(priv).toHexString()
    }
}
