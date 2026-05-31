package com.example.sharefilebc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Swift版 TokenRequestManager.swift の Android 移植（申請のみ自動化）。
 *
 * 目的:
 * - 初回だけ配布サーバに「初期トークン配布申請」を送る（requests.json）
 * - 申請後、サーバから Gmail に ONE-TIME TOKEN (TLF-...) が届く
 * - TLF-... の Submit は AccountScreen の入力UIで行う（既に実装済み）
 *
 * 重要:
 * - 現状サーバ証明書のチェーン検証に失敗する端末があるため、
 *   app/res/raw/eagle5_chain.pem を同梱した場合のみ、そのホストに限り信頼ストアを上書きする。
 * - “証明書検証無効化”はしない（危険なので）。
 */
object TokenRequestManager {

    private const val TAG = "TokenRequestManager"

    // Swift版と同じエンドポイント
    private const val REQUEST_ENDPOINT =
        "https://eagle5.fu.is.saga-u.ac.jp/blockchain-mail-auth/requests.json"

    // そのホストだけ同梱証明書を適用する
    private const val CERT_TARGET_HOST = "eagle5.fu.is.saga-u.ac.jp"

    // 「一度だけ申請した」フラグ
    private const val PREF_FILE = "token_bootstrap"
    private const val KEY_DID_REQUEST = "did_request_initial_tokens"

    /**
     * 初回トークン配布申請（1回だけ）
     *
     * @return 成功したら true（=申請済みフラグも立つ）
     */
    suspend fun requestInitialTokensIfNeeded(
        context: Context,
        email: String,
        p2pkhAddress: String,
        tokenType: Int = 3,
        lang: String = "ja"
    ): Boolean = withContext(Dispatchers.IO) {

        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DID_REQUEST, false)) {
            Log.d(TAG, "Already requested initial tokens. skip.")
            return@withContext true
        }

        val payload = JSONObject().apply {
            put("email", email)
            put("p2pkh", p2pkhAddress)
            put("token_type", tokenType)
            put("lang", lang)
        }

        val (ok, code, body) = postJson(context, REQUEST_ENDPOINT, payload.toString())

        if (ok) {
            prefs.edit().putBoolean(KEY_DID_REQUEST, true).apply()
            Log.d(TAG, "Initial token request sent. HTTP=$code body=$body")
            true
        } else {
            Log.e(TAG, "Initial token request FAILED. HTTP=$code body=$body")
            false
        }
    }

    /**
     * デバッグ用: フラグを落とす（再申請したいとき）
     */
    fun resetRequestedFlag(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DID_REQUEST, false)
            .apply()
        Log.d(TAG, "resetRequestedFlag done")
    }

    private fun postJson(context: Context, urlString: String, json: String): Triple<Boolean, Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            // ✅ HTTPS かつ対象ホストの場合のみ、同梱PEMから trust store を構築して適用
            if (conn is HttpsURLConnection && url.host == CERT_TARGET_HOST) {
                buildPinnedSslSocketFactoryOrNull(context)?.let { ssl ->
                    (conn as HttpsURLConnection).sslSocketFactory = ssl
                    // HostnameVerifier はデフォルトのまま（ホスト名検証は維持）
                    Log.d(TAG, "Applied pinned SSL socket factory for host=${url.host}")
                }
            }

            BufferedWriter(OutputStreamWriter(conn.outputStream, Charsets.UTF_8)).use { writer ->
                writer.write(json)
                writer.flush()
            }

            val code = conn.responseCode
            val ok = code in 200..299
            val body = runCatching {
                val stream = if (ok) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()

            Triple(ok, code, body)
        } catch (e: Exception) {
            Log.e(TAG, "postJson error", e)
            Triple(false, -1, e.message)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * res/raw/eagle5_chain.pem を trust anchor として読み込み、SSL socket factory を作る。
     * 失敗したら null（=従来通りのシステム検証に任せる）
     */
    private fun buildPinnedSslSocketFactoryOrNull(context: Context): javax.net.ssl.SSLSocketFactory? {
        return try {
            // res/raw/eagle5_chain.pem を開く（存在しなければ例外でnullへ）
            val pemStream: InputStream = context.resources.openRawResource(
                context.resources.getIdentifier("eagle5_chain", "raw", context.packageName)
            )

            pemStream.use { input ->
                val cf = CertificateFactory.getInstance("X.509")
                val certs = cf.generateCertificates(input)
                if (certs.isEmpty()) {
                    Log.e(TAG, "eagle5_chain.pem loaded but contains no certificates.")
                    return null
                }

                val ks = KeyStore.getInstance(KeyStore.getDefaultType())
                ks.load(null, null)

                certs.forEachIndexed { idx, cert ->
                    ks.setCertificateEntry("cert_$idx", cert)
                }

                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(ks)

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, tmf.trustManagers, null)
                sslContext.socketFactory
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildPinnedSslSocketFactoryOrNull failed", e)
            null
        }
    }
}
