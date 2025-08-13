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
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.* // ★ 非同期実行に使用

/**
 * ✉️ Gmail API（gmail.send）で自動送信する実装に統一。
 * 旧：Intentでメールアプリを開く機能は削除。
 *
 * 呼び出し方：
 *  - HomeScreen などから sendAuto(...) または sendEmailWithDriveLink(...) を呼ぶ
 *  - 初回は gmail.send の追加同意が必要 → HomeActivity#onActivityResult で結果を受け、
 *    EmailSender.onActivityResultBridge(...) に橋渡し
 */
object EmailSender {

    // 内部用の IO スコープ（プロセス存続中有効）
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // =========================
    // 公開API
    // =========================

    /** 互換用（呼び出し側が Activity を持っている場合はこちらでもOK） */
    fun sendAuto(
        activity: Activity,
        recipientEmail: String,
        fileName: String,
        folderId: String
    ) = sendEmailWithDriveLink(
        context = activity,
        recipientEmail = recipientEmail,
        fileName = fileName,
        folderId = folderId
    )

    /**
     * 共有リンクを本文に含めて Gmail API で自動送信。
     * 権限がなければ追加同意を開始し、同意後に自動送信する。
     */
    fun sendEmailWithDriveLink(
        context: Context,
        recipientEmail: String,
        fileName: String,
        folderId: String
    ) {
        val subject = "ファイル共有: $fileName"
        val link = "https://sharefilebcapp.web.app/folder/${Uri.encode(folderId)}"
        val body = buildString {
            appendLine("以下のリンクをタップすると、共有フォルダを開けます：")
            appendLine(link)
            appendLine()
            append("※ アプリ未インストール時はWebページが開きます。")
        }

        val activity = context as? Activity
        if (activity == null) {
            Log.e("EmailSender", "❌ Activity が取れないため、Gmail送信を開始できません")
            return
        }

        if (hasGmailSendScope(activity)) {
            // すでに権限あり → 非同期で送信
            performSendGmailAsync(activity, recipientEmail, subject, body) { ok ->
                Log.d("EmailSender", if (ok) "📧 Gmail API 送信成功" else "❌ Gmail API 送信失敗")
            }
        } else {
            // 権限なし → 同意フロー開始。完了後に自動送信するため一時保存。
            pendingEmail = PendingEmail(recipientEmail, subject, body)
            requestGmailSendScope(activity)
        }
    }

    /** HomeActivity の onActivityResult から呼ぶ。権限同意OKなら保留メールを送信。 */
    fun onActivityResultBridge(context: Context, requestCode: Int, resultCode: Int) {
        val ok = (requestCode == RC_GMAIL_SEND_SCOPE && resultCode == Activity.RESULT_OK)
        if (!ok) return

        pendingEmail?.let { mail ->
            // 送信後は必ずクリア
            pendingEmail = null
            performSendGmailAsync(context, mail.to, mail.subject, mail.body) { sent ->
                Log.d("EmailSender", if (sent) "📧 同意後にGmail送信成功" else "❌ 同意後にGmail送信失敗")
            }
        }
    }

    // =========================
    // 内部実装
    // =========================

    /** ★ メインスレッドで実行しないよう、IO スレッドで送信して結果だけ Main に返す */
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

            // 件名を MIME ヘッダ用に UTF-8 Base64 エンコード
            val encodedSubject = "=?UTF-8?B?" +
                    Base64.encodeToString(subject.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) +
                    "?="

            val dateHeader = formattedCurrentRfc2822Date()

            // CRLF で MIME 準拠の raw メールを構築
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

            // Gmail API は Base64URL（URL_SAFE | NO_WRAP）を要求
            val base64url = Base64.encodeToString(
                raw.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )

            val message = Message().apply {
                setRaw(base64url)
            }

            val result = try {
                service.users().messages().send("me", message).execute()
                true
            } catch (e: Exception) {
                Log.e("EmailSender", "❌ Gmail API 送信失敗: ${e.message}", e)
                false
            }

            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    private fun getGmailService(context: Context): Gmail? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
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

    // ---- 権限ハンドリング ----
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

    // ---- 同意待ちメール（var にすること。val だと再代入不可） ----
    private data class PendingEmail(val to: String, val subject: String, val body: String)
    private var pendingEmail: PendingEmail? = null

    // ---- Date ヘッダ生成 ----
    private fun formattedCurrentRfc2822Date(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}
