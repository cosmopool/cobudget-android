package com.kaiodelphino.margem

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capture boundary end to end: a real Notification and StatusBarNotification go through
 * rawNotice → capture into a real Db (AndroidSQLiteDriver, D28) and come out of pending().
 * Plain Application: MargemApp would open the production Db with the bundled driver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NoticeListenerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: Db

    @Before
    fun setUp() = runBlocking {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
        db.addSource(BANK, "Banco Aurora", 0)
        Unit
    }

    @After
    fun tearDown() = db.close()

    /** Posts a purchase from [pkg]; true if it became a notice. */
    private fun post(
        pkg: String = BANK,
        text: String = "Compra aprovada: R$ 23,90 em PADARIA",
        postTime: Long = 1_000_000,
        whenTime: Long = postTime,
        flags: Int = 0,
        configure: Notification.Builder.() -> Unit = {},
    ): Boolean = runBlocking {
        val notification = Notification.Builder(context, "alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenTime)
            .setContentTitle("Cartão")
            .setContentText(text)
            .apply(configure)
            .build()
        notification.flags = notification.flags or flags
        val sbn = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), postTime)
        val raw = rawNotice(sbn, "Banco Aurora") ?: return@runBlocking false
        capture(db, raw)
    }

    private fun pending() = runBlocking { db.pending() }

    @Test
    fun `a purchase from a source becomes a pending notice with what was read`() {
        assertTrue(post(configure = { setStyle(Notification.BigTextStyle().bigText("Compra aprovada: R$ 23,90 em PADARIA. Limite R$ 900,00")) }))

        val n = pending().single()
        assertEquals(BANK, n.raw.packageName)
        assertEquals("Banco Aurora", n.raw.appLabel)
        assertEquals("Cartão", n.raw.title)
        assertEquals("Compra aprovada: R$ 23,90 em PADARIA", n.raw.text)
        assertEquals("Compra aprovada: R$ 23,90 em PADARIA. Limite R$ 900,00", n.raw.body)
        assertEquals("alerts", n.raw.channelId)
        assertEquals(1_000_000L, n.raw.whenMs)
        assertTrue(n.raw.extrasJson.contains("Limite R$ 900,00"))
        assertEquals(Cents(2390), n.parsed.cents)
    }

    @Test
    fun `apps that are not sources are ignored`() {
        assertEquals(false, post(pkg = "com.chat"))
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `our own notifications are ignored, even if chosen as a source`() = runBlocking {
        db.addSource(context.packageName, "Margem", 0)
        assertEquals(false, post(pkg = context.packageName))
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `group summaries, ongoing and foreground service notifications are noise`() {
        assertEquals(false, post { setGroup("g").setGroupSummary(true) })
        assertEquals(false, post { setOngoing(true) })
        assertEquals(false, post(flags = Notification.FLAG_FOREGROUND_SERVICE))
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `a notification without any text is noise`() {
        assertEquals(false, post(text = "  ") { setContentTitle(" ") })
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `InboxStyle lines are read, trimmed, blank ones dropped`() {
        assertTrue(post { setStyle(Notification.InboxStyle().addLine(" Compra R$ 10,00 em A ").addLine(" ").addLine("Compra R$ 5,00 em B")) })
        assertEquals("Compra R$ 10,00 em A\nCompra R$ 5,00 em B", pending().single().raw.textLines)
    }

    @Test
    fun `a re-post is deduped, a real repeat after the window is kept`() {
        assertTrue(post(postTime = 1_000_000))
        assertEquals(false, post(postTime = 1_000_000 + 5_000))
        // After a reboot the app re-posts the old message: new post time, same `when`.
        assertEquals(false, post(postTime = 1_000_000 + 10 * DUPLICATE_WINDOW_MS, whenTime = 1_000_000))
        // The same purchase again, later: new `when`.
        assertTrue(post(postTime = 1_000_000 + 20 * DUPLICATE_WINDOW_MS, whenTime = 1_000_000 + 20 * DUPLICATE_WINDOW_MS))
        assertEquals(2, pending().size)
    }

    @Test
    fun `catch-up re-reading the shade saves only what was missed`() {
        assertTrue(post(postTime = 1_000_000))
        // Reconnected much later: the shade still holds that post, plus one posted while disconnected.
        assertEquals(false, post(postTime = 1_000_000))
        assertTrue(post(text = "Compra aprovada: R$ 30,00 em POSTO", postTime = 1_000_000 + 60 * DUPLICATE_WINDOW_MS))
        assertEquals(2, pending().size)
    }

    private companion object {
        const val BANK = "com.bank"
    }
}
