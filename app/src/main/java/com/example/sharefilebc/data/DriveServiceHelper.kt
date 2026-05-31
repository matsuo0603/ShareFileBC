package com.example.sharefilebc.data

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File

object DriveServiceHelper {

    private const val TAG = "DriveServiceHelper"

    data class PublicKeyPayload(
        val ownerEmail: String,
        val senderMasterPublicKeyHex: String,
        val senderDerivedPublicKeyHex: String,
        val trustLayerPublicKey: String,
        val updatedAt: String,
    )

    /**
     * Android 端末/OS によっては NetHttpTransport() の TLS 初期化で落ちることがある。
     * ただし AndroidHttp は依存が無いとクラスが存在しないため、Reflection で存在確認してから使う。
     *
     * - クラスが存在する場合: AndroidHttp.newCompatibleTransport()
     * - 存在しない場合: NetHttpTransport()
     */
    private fun createHttpTransport(): HttpTransport {
        return try {
            // com.google.api.client.extensions:google-api-client-android が入っている場合のみ成功する
            val clazz = Class.forName("com.google.api.client.extensions.android.http.AndroidHttp")
            val method = clazz.getMethod("newCompatibleTransport")
            val transport = method.invoke(null) as HttpTransport
            Log.d(TAG, "✅ Using AndroidHttp.newCompatibleTransport()")
            transport
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ AndroidHttp not available -> fallback to NetHttpTransport(): ${t.javaClass.simpleName}: ${t.message}")
            NetHttpTransport()
        }
    }

    fun getDriveService(context: Context): Drive {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("Googleアカウントにログインしていません")

        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            createHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ShareFileBC").build()
    }

    fun createUserFolder(context: Context, userName: String): String {
        val drive = getDriveService(context)
        val appFolderId = getOrCreateFolder(drive, "ShareFileBCApp", "root")
        return getOrCreateFolder(drive, userName, appFolderId)
    }

    fun getOrCreateFolder(drive: Drive, name: String, parentId: String): String {
        val existing = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='$name' and '$parentId' in parents and trashed=false")
            .setFields("files(id)")
            .execute()

        return if (existing.files.isNotEmpty()) {
            existing.files[0].id
        } else {
            val metadata = File().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            val created = drive.files().create(metadata)
                .setFields("id")
                .execute()
            created.id
        }
    }

    fun createOrUpdatePublicKeyFile(
        context: Context,
        parentFolderId: String,
        payload: PublicKeyPayload,
        recipientEmail: String,
    ): String {
        val drive = getDriveService(context)
        val fileName = "pubkey.json"
        val existing = drive.files().list()
            .setQ("name='$fileName' and '$parentFolderId' in parents and trashed=false")
            .setFields("files(id)")
            .execute()

        val content = """
            {
              \"ownerEmail\": \"${payload.ownerEmail}\",
              \"senderMasterPublicKeyHex\": \"${payload.senderMasterPublicKeyHex}\",
              \"senderDerivedPublicKeyHex\": \"${payload.senderDerivedPublicKeyHex}\",
              \"trustLayerPublicKey\": \"${payload.trustLayerPublicKey}\",
              \"updatedAt\": \"${payload.updatedAt}\"
            }
        """.trimIndent()

        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val mediaContent = com.google.api.client.http.ByteArrayContent("application/json", contentBytes)

        val fileId = if (existing.files.isNotEmpty()) {
            val existingId = existing.files.first().id
            drive.files().update(existingId, null, mediaContent)
                .setFields("id")
                .execute()
                .id
        } else {
            val metadata = File().apply {
                name = fileName
                parents = listOf(parentFolderId)
            }
            drive.files().create(metadata, mediaContent)
                .setFields("id")
                .execute()
                .id
        }

        grantReaderPermission(drive, fileId, recipientEmail)
        return fileId
    }

    fun grantReaderPermission(
        drive: Drive,
        targetId: String,
        recipientEmail: String
    ) {
        val permission = com.google.api.services.drive.model.Permission().apply {
            type = "user"
            role = "reader"
            emailAddress = recipientEmail
        }
        drive.permissions().create(targetId, permission)
            .setSendNotificationEmail(false)
            .execute()
    }
}
