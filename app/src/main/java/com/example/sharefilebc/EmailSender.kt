@file:Suppress("DEPRECATION")

package com.example.sharefilebc

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.sharefilebc.data.DriveServiceHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gmail API（gmail.send）を使ってメールを自動送信するためのユーティリティ。
 *
 * ✅ 今回の追加：
 * - 新仕様（uuid/txid あり）のとき、Driveファイルの description に shareメタを保存する。
 * - 受信側の自動同期（SYNC_INCOMING）が description を読んで processReceivedShare を回せるようにする。
 *
 * ✅ 重要：
 * - Gmail送信処理（スコープ同意・送信形式）は絶対に壊さないため、既存ロジックはそのまま。
 * - description 更新が失敗しても、メール送信は失敗させない（独立処理）。
 */
object EmailSender {

    private const val TAG = "EmailSender"

    // バックグラウンド送信用スコープ
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ----------------------------------------------------
    // 公開 API
    // ----------------------------------------------------

    /**
     * 旧来の呼び出し用のラッパー。
     * いまは folderId だけ渡されている呼び出しがあるので、
     * 内部で fileId / senderPublicKeyHex なしで sendEmailWithDriveLink を呼ぶ。
     */
    fun sendAuto(
        activity: Activity,
        recipientEmail: String,
        fileName: String,
        folderId: String
    ) {
        sendEmailWithDriveLink(
            context = activity,
            recipientEmail = recipientEmail,
            fileName = fileName,
            folderId = folderId
        )
    }

    /**
     * 共有リンクを本文に含めて Gmail API で自動送信する。
     *
     * @param fileId             DeepLink 用のファイルID（無ければ null）
     * @param senderPublicKeyHex 公開鍵自動共有のための送信者公開鍵（無ければ null）
     * @param threshold          送金閾値（無ければ null）
     * @param senderAddress      返金用アドレス（無ければ null）
     * @param uuid               共有識別子（契約ID）（無ければ null）
     * @param txid               トークン送金のトランザクションID（無ければ null）
     *
     * uuid と txid の **両方が非 null** の場合、新しい形式のリンクを生成する。
     */
    fun sendEmailWithDriveLink(
        context: Context,
        recipientEmail: String,
        fileName: String,
        folderId: String,
        fileId: String? = null,
        senderPublicKeyHex: String? = null,
        threshold: ULong? = null,
        senderAddress: String? = null,
        uuid: String? = null,
        txid: String? = null,
        /** Swift版互換: URLクエリに付ける nameMeta(Base64(JSON)) */
        nameMetaBase64: String? = null
    ) {
        val subject = "ファイル共有: $fileName"

        // 旧仕様のフォルダ直リンク（常に用意しておく）
        val fallbackFolderLink = "https://sharefilebcapp.web.app/folder/${Uri.encode(folderId)}"

        // --- KEY ROLE DEBUG (送信側) ---
        // sender= に入れる公開鍵が、どのパスの鍵かをログで確定させる
        if (fileId != null && senderPublicKeyHex != null && uuid != null && txid != null) {
            runCatching {
                val kd = KeyDerivation.getInstance(context)
                val PATH0 = KeyDerivation.TRUST_LAYER_PATH
                val PATH1 = KeyDerivation.DERIVED_KEY_PATH
                val myPub0 = kd.getCurrentPublicKeyHex(PATH0)
                val myPriv0 = kd.getCurrentPrivateKeyHex(PATH0)
                val myPub1 = kd.getCurrentPublicKeyHex(PATH1)
                val myPriv1 = kd.getCurrentPrivateKeyHex(PATH1)
                CryptoTrace.logMyKeySnapshot(
                    event = "EmailSender.sendEmailWithDriveLink:newFormat",
                    path0 = PATH0,
                    pub0 = myPub0,
                    priv0Hex = myPriv0,
                    path1 = PATH1,
                    pub1 = myPub1,
                    priv1Hex = myPriv1
                )
                Log.d(TAG, "[KEY_ROLE][SEND] senderParam(full)=$senderPublicKeyHex matchPub0=${senderPublicKeyHex.equals(myPub0, true)} matchPub1=${senderPublicKeyHex.equals(myPub1, true)}")
            }.onFailure {
                Log.w(TAG, "[KEY_ROLE][SEND] snapshot failed: ${it.message}")
            }
        }

        // ✅ 新仕様のときだけ、Driveのdescriptionに shareメタを保存（自動受信用）
        // 失敗してもメール送信は続行する（絶対に壊さない）
        if (fileId != null && senderPublicKeyHex != null && uuid != null && txid != null) {
            // Swift版に合わせて、受信側で必要な最小限のメタだけ保存する
            val descQuery = buildString {
                append("uuid=").append(Uri.encode(uuid))
                append("&refund=").append(Uri.encode(senderAddress ?: ""))
                append("&txid=").append(Uri.encode(txid))
                append("&sender=").append(Uri.encode(senderPublicKeyHex))
                nameMetaBase64?.let { append("&nameMeta=").append(Uri.encode(it)) }
            }

            ioScope.launch {
                updateDriveDescriptionSafely(context, fileId, descQuery)
            }
        }

        val body = buildString {
            if (fileId != null && senderPublicKeyHex != null && uuid != null && txid != null) {
                // ✅ 新仕様：https（青リンクになりやすい）
                val shareUrlHttps = buildString {
                    append("https://sharefilebcapp.web.app/file/${Uri.encode(fileId)}?")
                    append("uuid=").append(Uri.encode(uuid))
                    senderAddress?.let { append("&refund=").append(Uri.encode(it)) }
                    append("&txid=").append(Uri.encode(txid))
                    append("&sender=").append(Uri.encode(senderPublicKeyHex))
                    nameMetaBase64?.let { append("&nameMeta=").append(Uri.encode(it)) }
                }

                // ✅ 新仕様：カスタムスキーム（App Linksが効かない端末/状況のための保険）
                val shareUrlApp = buildString {
                    append("sharefilebc://download?")
                    append("fileId=").append(Uri.encode(fileId))
                    append("&uuid=").append(Uri.encode(uuid))
                    senderAddress?.let { append("&refund=").append(Uri.encode(it)) }
                    append("&txid=").append(Uri.encode(txid))
                    append("&sender=").append(Uri.encode(senderPublicKeyHex))
                    nameMetaBase64?.let { append("&nameMeta=").append(Uri.encode(it)) }
                }

                appendLine("以下のリンクをタップすると、公開鍵登録とダウンロード準備が自動で行われます。")
                appendLine()
                appendLine("【通常リンク（https）】")
                appendLine(shareUrlHttps)
                appendLine()
                appendLine("もし上のリンクでアプリが起動しない場合は、次のリンクをタップしてください（アプリ起動用）：")
                appendLine()
                appendLine("【アプリ起動リンク】")
                appendLine(shareUrlApp)
                appendLine()
                appendLine("ブラウザのみでアクセスしたい場合は、次のフォルダリンクを利用できます：")
                appendLine(fallbackFolderLink)
            } else {
                // ✅ 旧仕様：フォルダへの直接リンクのみ
                appendLine("以下のリンクから共有フォルダにアクセスできます。")
                appendLine(fallbackFolderLink)
            }
        }

        // ✅ ここは既存のまま（送信が壊れないように）
        sendEmail(context, recipientEmail, subject, body)
    }

    /**
     * 公開鍵登録だけを促すメール（必要なら使用）
     */
    fun sendPublicKeyRegistrationEmail(
        context: Context,
        recipientEmail: String,
        registrationUrl: String,
        senderEmail: String? = null
    ) {
        // 切り分け用ログ：この端末が iOS 側に渡す「公開鍵登録リンク」の中身
        runCatching {
            val parsed = PublicKeyLinkBuilder.parse(Uri.parse(registrationUrl))
            val kd = KeyDerivation.getInstance(context)
            val myDerivedPub = kd.getCurrentPublicKeyHex(KeyDerivation.TRUST_LAYER_PATH)
            val km = KeyManager.getInstance(context)
            Log.d(
                TAG,
                "[PUBKEY_DEBUG] sendPublicKeyRegistrationEmail to=$recipientEmail " +
                        "emailParam=${parsed?.email} derived=${parsed?.derivedPublicKey} trust=${parsed?.trustLayerPublicKey} folderId=${parsed?.folderId} " +
                        "myTrustPub(TRUST_LAYER_PATH)=$myDerivedPub masterFp=${km.getMasterXprvFingerprintOrNull()} url=$registrationUrl"
            )
        }.onFailure {
            Log.w(TAG, "[PUBKEY_DEBUG] parse failed url=$registrationUrl")
        }

        val subject = "公開鍵登録のお願い"
        val body = buildString {
            appendLine("以下のリンクをタップして公開鍵を登録してください。")
            appendLine(registrationUrl)
            appendLine()
            appendLine("リンクを開いた後に公開鍵が登録されると、暗号化したファイルを送信できます。")
            senderEmail?.let {
                appendLine()
                appendLine("送信者: $it")
            }
        }

        sendEmail(context, recipientEmail, subject, body)
    }

    // ----------------------------------------------------
    // ✅ Drive description 更新（追加）
    // ----------------------------------------------------

    private suspend fun updateDriveDescriptionSafely(context: Context, fileId: String, description: String) {
        withContext(Dispatchers.IO) {
            // ✅ 共有後の受信側同期で nameMeta 等を取得できるように、Driveの description を確実に更新する。
            // - supportsAllDrives を付ける（共有ドライブ等でも失敗しにくくする）
            // - 短いリトライを入れる（直後の更新が失敗するケースの保険）
            val drive = runCatching { DriveServiceHelper.getDriveService(context) }.getOrNull()
            if (drive == null) {
                Log.e(TAG, "❌ Drive service is null. skip description update. fileId=$fileId")
                return@withContext
            }

            val meta = DriveFile().apply { this.description = description }
            var lastErr: Throwable? = null
            for (attempt in 1..3) {
                val ok = runCatching {
                    val updated = drive.files().update(fileId, meta)
                        .setFields("id, description")
                        .setSupportsAllDrives(true)
                        .execute()
                    Log.d(
                        TAG,
                        "✅ Drive description updated(attempt=$attempt): fileId=$fileId descLen=${updated.description?.length ?: 0}"
                    )
                    true
                }.getOrElse { e ->
                    lastErr = e
                    Log.e(TAG, "❌ Drive description update failed(attempt=$attempt): fileId=$fileId", e)
                    false
                }
                if (ok) break
                // すぐリトライ（短時間）
                Thread.sleep(200)
            }

            if (lastErr != null) {
                // ✅ 失敗してもメール送信は壊さない
                Log.w(TAG, "⚠️ Drive description update ultimately failed: fileId=$fileId", lastErr)
            }
        }
    }

    // ----------------------------------------------------
    // 内部実装（既存）
    // ----------------------------------------------------

    private fun sendEmail(
        context: Context,
        recipientEmail: String,
        subject: String,
        body: String
    ) {
        val activity = context as? Activity
        if (activity == null) {
            Log.e(TAG, "❌ Activity コンテキストでないため Gmail 送信を開始できません (to=$recipientEmail)")
            return
        }

        val account = GoogleSignIn.getLastSignedInAccount(activity)
        Log.d(
            TAG,
            "📧 sendEmail requested: to=$recipientEmail subjectLen=${subject.length} bodyLen=${body.length} " +
                    "account=${account?.email} hasGmailSend=${hasGmailSendScope(activity)}"
        )

        if (hasGmailSendScope(activity)) {
            performSendGmailAsync(activity, recipientEmail, subject, body) { ok ->
                Log.d(TAG, if (ok) "📧 Gmail API 送信成功" else "❌ Gmail API 送信失敗")
            }
        } else {
            // 権限同意待ちのメールとして退避
            pendingEmail = PendingEmail(recipientEmail, subject, body)
            Log.w(TAG, "⚠️ Gmail送信スコープが未同意のため、メールを保留しました。スコープ同意画面を表示します。")
            requestGmailSendScope(activity)
        }
    }

    /**
     * HomeActivity の onActivityResult から呼び出して、
     * Gmail 送信スコープに同意したあと保留メールを送信する。
     */
    fun onActivityResultBridge(context: Context, requestCode: Int, resultCode: Int) {
        Log.d(TAG, "onActivityResultBridge: requestCode=$requestCode resultCode=$resultCode")
        if (requestCode != RC_GMAIL_SEND_SCOPE || resultCode != Activity.RESULT_OK) {
            if (requestCode == RC_GMAIL_SEND_SCOPE) {
                Log.w(TAG, "⚠️ Gmail送信スコープ同意がキャンセル/失敗しました")
            }
            return
        }

        val mail = pendingEmail ?: return
        pendingEmail = null

        performSendGmailAsync(context, mail.to, mail.subject, mail.body) { ok ->
            Log.d(TAG, if (ok) "📧 同意後に Gmail 送信成功" else "❌ 同意後に Gmail 送信失敗")
        }
    }

    // Gmail API 実送信（IO スレッド）
    private fun performSendGmailAsync(
        context: Context,
        to: String,
        subject: String,
        body: String,
        callback: (Boolean) -> Unit
    ) {
        ioScope.launch {
            val service = getGmailService(context)
            if (service == null) {
                Log.e(TAG, "❌ Gmail service is null (not signed in or missing scope?)")
                withContext(Dispatchers.Main) { callback(false) }
                return@launch
            }

            // 件名を UTF-8 Base64 (RFC2047) でエンコード
            val encodedSubject = "=?UTF-8?B?" +
                    Base64.encodeToString(
                        subject.toByteArray(StandardCharsets.UTF_8),
                        Base64.NO_WRAP
                    ) +
                    "?="

            val dateHeader = formattedCurrentRfc2822Date()

            val raw = buildString {
                append("To: ").append(to).append("\r\n")
                append("Subject: ").append(encodedSubject).append("\r\n")
                append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                append("Content-Transfer-Encoding: 7bit\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Date: ").append(dateHeader).append("\r\n")
                append("\r\n")
                append(body)
            }

            // Gmail API は Base64 URL_SAFE + NO_WRAP を要求
            val base64url = Base64.encodeToString(
                raw.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )

            val message = Message().apply {
                setRaw(base64url)
            }

            Log.d(TAG, "📧 Gmail send start: to=$to rawBytes=${raw.toByteArray(StandardCharsets.UTF_8).size}")

            val ok = runCatching {
                service.users().messages().send("me", message).execute()
                true
            }.getOrElse { e ->
                Log.e(TAG, "❌ Gmail send failed", e)
                false
            }

            withContext(Dispatchers.Main) { callback(ok) }
        }
    }

    // ----------------------------------------------------
    // Gmail API セットアップ
    // ----------------------------------------------------

    private fun getGmailService(context: Context): Gmail? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.e(TAG, "❌ getGmailService: lastSignedInAccount is null")
            return null
        }
        Log.d(TAG, "getGmailService: account=${account.email}")
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(GmailScopes.GMAIL_SEND)
        ).apply {
            selectedAccount = account.account
        }

        return Gmail.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ShareFileBC").build()
    }

    private fun hasGmailSendScope(activity: Activity): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(activity)
        if (account == null) {
            Log.w(TAG, "hasGmailSendScope: account is null")
            return false
        }
        val scope = Scope(GmailScopes.GMAIL_SEND)
        val ok = GoogleSignIn.hasPermissions(account, scope)
        Log.d(TAG, "hasGmailSendScope: ${account.email} -> $ok")
        return ok
    }

    private fun requestGmailSendScope(activity: Activity) {
        val account = GoogleSignIn.getLastSignedInAccount(activity)
        if (account == null) {
            Log.e(TAG, "❌ requestGmailSendScope: account is null")
            return
        }
        Log.d(TAG, "requestGmailSendScope: account=${account.email}")
        val scope = Scope(GmailScopes.GMAIL_SEND)
        GoogleSignIn.requestPermissions(activity, RC_GMAIL_SEND_SCOPE, account, scope)
    }

    private data class PendingEmail(val to: String, val subject: String, val body: String)
    private var pendingEmail: PendingEmail? = null

    private const val RC_GMAIL_SEND_SCOPE = 9101

    private fun formattedCurrentRfc2822Date(): String {
        val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        df.timeZone = TimeZone.getDefault()
        return df.format(Date())
    }
}
