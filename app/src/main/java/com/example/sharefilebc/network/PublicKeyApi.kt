package com.example.sharefilebc.network

import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

interface PublicKeyApi {
    suspend fun registerMyPublicKey(email: String, pubKeyHex: String): Result<Unit>
    suspend fun fetchPublicKey(email: String): Result<String?>
}

class PublicKeyApiClient(
    private val baseUrl: String = "https://sharefilebcapp.web.app/api/pubkey",
    private val client: OkHttpClient = OkHttpClient()
) : PublicKeyApi {

    override suspend fun registerMyPublicKey(email: String, pubKeyHex: String): Result<Unit> {
        val url = "$baseUrl/register"
        val jsonBody = JSONObject()
            .put("email", email)
            .put("publicKeyHex", pubKeyHex)
            .toString()
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("register failed: HTTP ${response.code}")
                }
                Log.d("PublicKeyApi", "Registered public key for $email")
            }
        }
    }

    override suspend fun fetchPublicKey(email: String): Result<String?> {
        val url = "$baseUrl/fetch?email=${Uri.encode(email)}"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@use null
                }
                if (!response.isSuccessful) {
                    throw IOException("fetch failed: HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null

                val json = JSONObject(body)
                val key = json.optString("publicKeyHex")
                    .ifBlank { json.optString("pubKeyHex") }
                key.takeIf { it.isNotBlank() }
            }
        }
    }
}