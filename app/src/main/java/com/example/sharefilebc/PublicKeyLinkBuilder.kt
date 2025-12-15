package com.example.sharefilebc

import android.net.Uri

data class PublicKeyLink(
    val email: String,
    val derivedPublicKey: String,
    val trustLayerPublicKey: String,
    val folderId: String,
)

object PublicKeyLinkBuilder {
    private const val BASE_URL = "https://sharefilebcapp.web.app"

    fun build(email: String, derivedPublicKey: String, trustLayerPublicKey: String, folderId: String): String {
        val encodedSegments = listOf(email, derivedPublicKey, trustLayerPublicKey, folderId)
            .joinToString("/") { Uri.encode(it) }
        return "$BASE_URL/pubkey/$encodedSegments"
    }

    fun parse(uri: Uri?): PublicKeyLink? {
        if (uri == null) return null
        val segments = uri.pathSegments
        if (segments.isEmpty() || segments.first() != "pubkey") return null
        if (segments.size < 5) return null

        return try {
            PublicKeyLink(
                email = Uri.decode(segments[1]),
                derivedPublicKey = Uri.decode(segments[2]),
                trustLayerPublicKey = Uri.decode(segments[3]),
                folderId = Uri.decode(segments[4])
            )
        } catch (e: Exception) {
            null
        }
    }
}