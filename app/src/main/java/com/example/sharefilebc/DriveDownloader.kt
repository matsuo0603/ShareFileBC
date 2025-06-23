package com.example.sharefilebc

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DriveFileInfo(
    val id: String,
    val name: String,
    val mimeType: String?,
    val isFolder: Boolean
)

data class FolderStructure(
    val folderName: String,  // 日付フォルダ名
    val senderName: String,  // 送信者名（親フォルダから推測）
    val files: List<DriveFileInfo>
)

class DriveDownloader(private val context: Context) {

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ShareFileBC").build()
    }

    fun listFilesInFolder(folderId: String): List<File> {
        return try {
            val result = getDriveService()?.files()?.list()
                ?.setQ("'$folderId' in parents and trashed = false")
                ?.setFields("files(id, name, mimeType)")
                ?.execute()
            result?.files ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // フォルダIDから詳細な構造情報を取得
    suspend fun getFolderStructure(folderId: String): FolderStructure? {
        return withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext null

                // フォルダ自体の情報を取得
                val folderInfo = driveService.files().get(folderId)
                    .setFields("id, name, parents")
                    .execute()

                // 親フォルダの情報を取得して送信者名を推測
                val senderName = if (folderInfo.parents != null && folderInfo.parents.isNotEmpty()) {
                    try {
                        val parentFolder = driveService.files().get(folderInfo.parents[0])
                            .setFields("name")
                            .execute()
                        parentFolder.name ?: "Unknown Sender"
                    } catch (e: Exception) {
                        "Unknown Sender"
                    }
                } else {
                    "Unknown Sender"
                }

                // フォルダ内のファイル一覧を取得
                val filesResult = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("files(id, name, mimeType)")
                    .execute()

                val files = filesResult.files?.map { file ->
                    DriveFileInfo(
                        id = file.id,
                        name = file.name,
                        mimeType = file.mimeType,
                        isFolder = file.mimeType == "application/vnd.google-apps.folder"
                    )
                } ?: emptyList()

                FolderStructure(
                    folderName = folderInfo.name ?: "Unknown Date",
                    senderName = senderName,
                    files = files
                )
            } catch (e: Exception) {
                Log.e("DriveDownloader", "Error getting folder structure", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "フォルダ情報の取得に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
    }

    // suspend 関数に変更し、UIスレッドでのToast表示を可能にする
    suspend fun downloadFile(fileId: String): java.io.File? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("DriveDownloader", "📥 ダウンロード開始: $fileId")

                val driveService = getDriveService() ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Google Driveサービスに接続できません。", Toast.LENGTH_LONG).show()
                    }
                    return@withContext null
                }
                val fileMetadata = driveService.files().get(fileId).execute()

                // ✅ 保存先を Download フォルダに変更
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = java.io.File(downloadsDir, fileMetadata.name)

                FileOutputStream(outputFile).use { outputStream ->
                    driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                }

                // ✅ ダウンロード完了後に MediaScanner で反映
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    null,
                    null
                )

                Log.d("DriveDownloader", "✅ ダウンロード完了: ${outputFile.absolutePath}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${fileMetadata.name} をダウンロードしました。", Toast.LENGTH_LONG).show()
                }
                outputFile
            } catch (e: Exception) {
                Log.e("DriveDownloader", "❌ ダウンロードエラー", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "ダウンロードに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
    }
}