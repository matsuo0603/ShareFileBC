package com.example.sharefilebc

import android.content.Context
import android.content.Intent
import android.text.Html
import android.util.Log
import android.net.Uri

object EmailSender {
    fun sendEmailWithDriveLink(
        context: Context,
        recipientEmail: String,
        fileName: String,
        folderId: String
    ) {
        val subject = "ファイル共有: $fileName"

        // ✅ 新URL形式: https://sharefilebcapp.web.app/folder/<ID>
        val link = "https://sharefilebcapp.web.app/folder/${Uri.encode(folderId)}"

        val bodyHtml = """
            <p>以下のリンクをタップすると、共有フォルダを開けます：</p>
            <p><a href="$link" style="color:blue;text-decoration:underline;">$link</a></p>
            <p>※ アプリ未インストール時はWebページが開きます。</p>
        """.trimIndent()

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY))
                // 必要なら Gmail 固定を外してOK
                // setPackage("com.google.android.gm")
            }

            Log.d("EmailSender", "📨 メール作成を起動: $link")
            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e("EmailSender", "❌ メールアプリ起動失敗、chooserに切り替え", e)

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
