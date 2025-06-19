import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FileDeleteSchedulerTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun scheduleEnqueuesPeriodicWork() {
        FileDeleteScheduler.schedule(context)
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork("file_deletion_work").get()
        assertEquals(1, infos.size)
        val info = infos[0]
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}
