@file:Suppress("DEPRECATION")

package com.example.sharefilebc

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
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
 * - sendAuto(...) は互換用の簡易 API
 * - sendEmailWithDriveLink(...) は fileId / senderPublicKeyHex を含む「新仕様」のリンクを送る
 *   ただしどちらも引数はオプションにして、既存の呼び出しが壊れないようにする。
 */
object EmailSender {

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
            // fileId / senderPublicKeyHex は null のまま（旧仕様フォールバック）
        )
    }

    /**
     * 共有リンクを本文に含めて Gmail API で自動送信する。
     *
     * @param fileId             DeepLink 用のファイルID（無ければ null）
     * @param senderPublicKeyHex 公開鍵自動共有のための送信者公開鍵（無ければ null）
     *
     * fileId と senderPublicKeyHex の **両方が非 null** のときだけ、新しい
     * `/share?sender=...&to=...&fileId=...` 形式のリンクを生成する。
     * どちらかが null の場合は、従来通り「フォルダへのリンク」だけを本文に書く。
     */
    fun sendEmailWithDriveLink(
        context: Context,
        recipientEmail: String,
        fileName: String,
        folderId: String,
        fileId: String? = null,
        senderPublicKeyHex: String? = null
    ) {
        val subject = "ファイル共有: $fileName"

        // 旧仕様のフォルダ直リンク（常に用意しておく）
        val fallbackFolderLink = "https://sharefilebcapp.web.app/folder/${Uri.encode(folderId)}"

        val body = buildString {
            if (fileId != null && senderPublicKeyHex != null) {
                // ✅ 新仕様：公開鍵 + fileId つきの /share リンク
                val shareUrl = buildString {
                    append("https://sharefilebcapp.web.app/share?")
                    append("sender=").append(Uri.encode(senderPublicKeyHex))
                    append("&to=").append(Uri.encode(recipientEmail))
                    append("&fileId=").append(Uri.encode(fileId))
                }

                appendLine("以下のリンクをタップすると、公開鍵登録とダウンロード準備が自動で行われます。")
                appendLine(shareUrl)
                appendLine()
                appendLine("リンクを開くとアプリが起動し、復号・ダウンロード画面へ直接遷移します。")
                appendLine()
                appendLine("ブラウザのみでアクセスしたい場合は、次のフォルダリンクを利用できます：")
                appendLine(fallbackFolderLink)
            } else {
                // ✅ 旧仕様：フォルダへの直接リンクのみ
                appendLine("以下のリンクから共有フォルダにアクセスできます。")
                appendLine(fallbackFolderLink)
            }
        }

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
    // 内部実装
    // ----------------------------------------------------

    private fun sendEmail(
        context: Context,
        recipientEmail: String,
        subject: String,
        body: String
    ) {
        val activity = context as? Activity
        if (activity == null) {
            Log.e("EmailSender", "❌ Activity コンテキストでないため Gmail 送信を開始できません")
            return
        }

        if (hasGmailSendScope(activity)) {
            performSendGmailAsync(activity, recipientEmail, subject, body) { ok ->
                Log.d("EmailSender", if (ok) "📧 Gmail API 送信成功" else "❌ Gmail API 送信失敗")
            }
        } else {
            // 権限同意待ちのメールとして退避
            pendingEmail = PendingEmail(recipientEmail, subject, body)
            requestGmailSendScope(activity)
        }
    }

    /**
     * HomeActivity の onActivityResult から呼び出して、
     * Gmail 送信スコープに同意したあと保留メールを送信する。
     */
    fun onActivityResultBridge(context: Context, requestCode: Int, resultCode: Int) {
        if (requestCode != RC_GMAIL_SEND_SCOPE || resultCode != Activity.RESULT_OK) return

        val mail = pendingEmail ?: return
        pendingEmail = null

        performSendGmailAsync(context, mail.to, mail.subject, mail.body) { ok ->
            Log.d(
                "EmailSender",
                if (ok) "📧 同意後に Gmail 送信成功" else "❌ 同意後に Gmail 送信失敗"
            )
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

            val ok = try {
                service.users().messages().send("me", message).execute()
                true
            } catch (e: Exception) {
                Log.e("EmailSender", "❌ Gmail API 送信失敗: ${e.message}", e)
                false
            }

            withContext(Dispatchers.Main) {
                callback(ok)
            }
        }
    }

    private fun getGmailService(context: Context): Gmail? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null

        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GmailScopes.GMAIL_SEND)
        ).apply {
            selectedAccount = account.account
        }

        return Gmail.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("ShareFileBC")
            .build()
    }

    // ---------------------- 権限まわり ----------------------

    private const val RC_GMAIL_SEND_SCOPE = 0x4753 // "GS"

    private val gmailSendScope = Scope(GmailScopes.GMAIL_SEND)

    private fun hasGmailSendScope(activity: Activity): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(activity) ?: return false
        return GoogleSignIn.hasPermissions(account, gmailSendScope)
    }

    private fun requestGmailSendScope(activity: Activity) {
        val account = GoogleSignIn.getLastSignedInAccount(activity) ?: return
        GoogleSignIn.requestPermissions(
            activity,
            RC_GMAIL_SEND_SCOPE,
            account,
            gmailSendScope
        )
    }

    // ---------------------- 保留メール ----------------------

    private data class PendingEmail(
        val to: String,
        val subject: String,
        val body: String
    )

    private var pendingEmail: PendingEmail? = null

    // ---------------------- Date ヘッダ ----------------------

    private fun formattedCurrentRfc2822Date(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}
