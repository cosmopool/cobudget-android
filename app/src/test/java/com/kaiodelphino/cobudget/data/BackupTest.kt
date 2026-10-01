package com.kaiodelphino.cobudget.data

import androidx.test.core.app.ApplicationProvider
import com.kaiodelphino.cobudget.CobudgetApp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Backs up a real file database, loses a row, restores it — end to end. */
@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private val app = ApplicationProvider.getApplicationContext<CobudgetApp>()
    private val dao get() = app.db.dao()

    @Before
    fun setUp() {
        app.resetDb()
        val file = app.getDatabasePath(AppDatabase.DB_NAME)
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        runBlocking {
            dao.upsertMonitoredApp(MonitoredApp("com.bank", "My Bank", 0))
            dao.insertIfNew(
                CapturedNotification(
                    packageName = "com.bank",
                    appLabel = "My Bank",
                    notificationKey = "key-1",
                    postedAt = 1_000_000,
                    notificationWhen = 1_000_000,
                    title = "Card purchase",
                    text = "You spent $4.50 at Coffee",
                    bigText = null,
                    subText = null,
                    textLines = null,
                    category = null,
                    channelId = "alerts",
                    extrasJson = "{}",
                    contentHash = "hash-1",
                )
            )
        }
    }

    @After
    fun tearDown() = app.resetDb()

    @Test
    fun `export then import restores a dismissed notification`() {
        val backup = ByteArrayOutputStream().also { runBlocking { exportBackup(app.db, it) } }.toByteArray()
        assertTrue(backup.isNotEmpty())

        // Lose the row the way a user would: dismiss it.
        val id = runBlocking { dao.observeNotifications().first() }.single().id
        runBlocking { dao.dismiss(id) }
        assertTrue(runBlocking { dao.observeNotifications().first() }.isEmpty())

        runBlocking { importBackup(app, ByteArrayInputStream(backup)) }

        val restored = runBlocking { dao.observeNotifications().first() }
        assertEquals(1, restored.size)
        assertEquals("Card purchase", restored.single().title)
        assertEquals("You spent $4.50 at Coffee", restored.single().text)
    }
}
