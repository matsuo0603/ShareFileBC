package com.example.sharefilebc

import android.content.Context
import android.content.Intent
import android.text.Html
import android.util.Log

object EmailSender {
    fun sendEmailWithDriveLink(
        context: Context,
        recipientEmail: String,
        fileName: String,
        folderId: String
    ) {
        val subject = "ファイル共有: $fileName"
        val link = "https://sharefilebc-52382.web.app/download?folderId=$folderId"

        val bodyHtml = """
            <p>以下のリンクをタップするとファイルを開くことができます：</p>
            <p><a href="$link" style="color:blue;text-decoration:underline;">ここをタップ</a></p>
            <p>リンクが開けない場合は、下記URLをコピーしてブラウザに貼り付けてください：</p>
            <p><code>$link</code></p>
        """.trimIndent()

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY))
                setPackage("com.google.android.gm") // ここは一時的にコメントアウトしてもOK
            }

            Log.d("EmailSender", "📨 Gmail送信画面を呼び出します: $link")
            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e("EmailSender", "❌ Gmail起動失敗、chooserに切り替え", e)

            val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY))
            }
            context.startActivity(Intent.createChooser(fallbackIntent, "メールアプリを選択"))
        }
    }
}
