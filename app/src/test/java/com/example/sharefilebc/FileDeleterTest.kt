import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.sharefilebc.data.AppDatabase
import com.example.sharefilebc.data.ReceivedFolderEntity
import com.example.sharefilebc.data.SharedFolderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class FileDeleterTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Replace singleton instance via reflection
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun expiredEntriesAreDeleted() = runBlocking {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        formatter.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        val old = formatter.format(Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)))

        db.receivedFolderDao().insert(
            ReceivedFolderEntity(
                folderId = "f1",
                folderName = "name",
                senderName = "sender",
                receivedDate = old,
                lastAccessDate = old,
                uploadDate = old
            )
        )
        db.sharedFolderDao().insert(
            SharedFolderEntity(
                date = old,
                recipientName = "r",
                folderId = "folder",
                fileName = "file",
                fileGoogleDriveId = "g1"
            )
        )

        FileDeleter.deleteExpiredFiles(context, skipDriveDeletion = true)

        assertTrue(db.receivedFolderDao().getAllOnce().isEmpty())
        assertTrue(db.sharedFolderDao().getAllOnce().isEmpty())
    }
}
