package com.example.sharefilebc.crypto

import com.example.sharefilebc.crypto.HexUtils.toHexString
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ECIESAndroidCompatTest {

    @Test
    fun encryptAndDecryptAesKeyWithCompressedPublicKey() {
        val recipientPrivate = BigInteger.ONE
        val recipientPrivateHex = "%064x".format(recipientPrivate)
        val recipientPublicHex = PublicKeyUtils.compressedPublicKeyFromPrivate(recipientPrivate).toHexString()

        val aesKey = AESGCMCrypto.generateKey()
        val encrypted = ECIES.encryptAESKey(aesKey, recipientPublicHex)
        val decrypted = ECIES.decryptAESKey(encrypted, recipientPrivateHex)

        assertArrayEquals(aesKey, decrypted)
        assertEquals(32, decrypted.size)
    }

    @Test
    fun securePackageCreateAndUnpackRoundTrip() {
        val recipientPrivate = BigInteger("2")
        val recipientPrivateHex = "%064x".format(recipientPrivate)
        val recipientPublicHex = PublicKeyUtils.compressedPublicKeyFromPrivate(recipientPrivate).toHexString()
        val signingPrivateHex = "%064x".format(BigInteger("3"))

        val data = "hello, world".toByteArray()
        val fileName = "test.txt"

        val packageBytes = SecurePackage.create(
            data = data,
            fileName = fileName,
            recipientPublicKeyHex = recipientPublicHex,
            signingPrivateKeyHex = signingPrivateHex,
            signerPublicKeyHex = null
        )

        val (unpackedData, unpackedFileName) = SecurePackage.unpack(
            packageData = packageBytes,
            recipientPrivateKeyHex = recipientPrivateHex,
            signerPublicKeyHex = null
        )

        assertArrayEquals(data, unpackedData)
        assertEquals(fileName, unpackedFileName)
    }
}