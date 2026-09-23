package com.kaiodelphino.cobudget.capture

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaiodelphino.cobudget.data.AppDatabase
import com.kaiodelphino.cobudget.data.DUPLICATE_WINDOW_MS
import com.kaiodelphino.cobudget.data.MonitoredApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Drives the capture pipeline end to end: StatusBarNotification in, rows in a real Room database out. */
@RunWith(RobolectricTestRunner::class)
class CaptureTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private val dao get() = db.dao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { dao.upsertMonitoredApp(MonitoredApp(BANK, "My Bank", 0)) }
    }

    @After
    fun tearDown() = db.close()

    private fun post(
        pkg: String = BANK,
        text: String = "You spent $4.50 at Coffee",
        postTime: Long = 1_000_000,
        whenTime: Long = postTime,
        configure: Notification.Builder.() -> Unit = {},
    ): Boolean = runBlocking {
        val notification = Notification.Builder(context, "alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenTime)
            .setContentTitle("Card purchase")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text. Available balance $95.50"))
            .apply(configure)
            .build()
        val sbn = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), postTime)
        NotificationCaptureService.capture(dao, sbn, ownPackage = context.packageName)
    }

    private fun saved() = runBlocking { dao.observeNotifications().first() }

    @Test
    fun `saves notifications from monitored apps with their text and extras`() {
        assertTrue(post())

        val row = saved().single()
        assertEquals(BANK, row.packageName)
        assertEquals("My Bank", row.appLabel)
        assertEquals("Card purchase", row.title)
        assertEquals("You spent $4.50 at Coffee", row.text)
        assertEquals("You spent $4.50 at Coffee. Available balance $95.50", row.body)
        assertEquals("alerts", row.channelId)
        assertTrue(row.extrasJson.contains("Available balance"))
    }

    @Test
    fun `ignores apps that are not monitored`() {
        assertFalse(post(pkg = "com.chat"))
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `ignores group summaries and ongoing notifications`() {
        assertFalse(post { setGroup("g").setGroupSummary(true) })
        assertFalse(post { setOngoing(true) })
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `re-post within the window is deduped, genuine repeat after it is kept`() {
        assertTrue(post(postTime = 1_000_000))
        assertFalse(post(postTime = 1_000_000 + 5_000))
        assertTrue(post(postTime = 1_000_000 + DUPLICATE_WINDOW_MS))
        assertTrue(post(text = "You spent $12.00 at Grocer", postTime = 1_000_000 + DUPLICATE_WINDOW_MS + 1))
        assertEquals(3, saved().size)
    }

    @Test
    fun `catch-up re-reading posts from the shade saves only the ones missed`() {
        // Captured live before the service got disconnected.
        assertTrue(post(postTime = 1_000_000))

        // On reconnect, much later: the shade still holds that post, plus one posted while we were down.
        assertFalse(post(postTime = 1_000_000))
        assertTrue(post(text = "You spent $30.00 at Fuel", postTime = 1_000_000 + 60 * DUPLICATE_WINDOW_MS))
        assertEquals(2, saved().size)
    }

    @Test
    fun `re-post long after with the same when is deduped, same text with a new when is kept`() {
        assertTrue(post(postTime = 1_000_000, whenTime = 1_000_000))
        // App re-posts the old message after a reboot: new post time, same `when`.
        assertFalse(post(postTime = 1_000_000 + 10 * DUPLICATE_WINDOW_MS, whenTime = 1_000_000))
        // Genuine identical purchase later: new `when`.
        assertTrue(post(postTime = 1_000_000 + 20 * DUPLICATE_WINDOW_MS, whenTime = 1_000_000 + 20 * DUPLICATE_WINDOW_MS))
        assertEquals(2, saved().size)
    }

    @Test
    fun `when of zero never matches`() {
        assertTrue(post(postTime = 1_000_000, whenTime = 0))
        assertTrue(post(postTime = 1_000_000 + 10 * DUPLICATE_WINDOW_MS, whenTime = 0))
        assertEquals(2, saved().size)
    }

    private companion object {
        const val BANK = "com.bank"
    }
}
