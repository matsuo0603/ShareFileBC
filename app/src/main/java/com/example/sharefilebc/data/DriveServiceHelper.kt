package com.example.sharefilebc.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File

object DriveServiceHelper {
    data class PublicKeyPayload(
        val ownerEmail: String,
        val senderMasterPublicKeyHex: String,
        val senderDerivedPublicKeyHex: String,
        val trustLayerPublicKey: String,
        val updatedAt: String,
    )

    fun getDriveService(context: Context): Drive {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("Googleアカウントにログインしていません")

        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            NetHttpTransport(),
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
              "ownerEmail": "${payload.ownerEmail}",
              "senderMasterPublicKeyHex": "${payload.senderMasterPublicKeyHex}",
              "senderDerivedPublicKeyHex": "${payload.senderDerivedPublicKeyHex}",
              "trustLayerPublicKey": "${payload.trustLayerPublicKey}",
              "updatedAt": "${payload.updatedAt}"
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