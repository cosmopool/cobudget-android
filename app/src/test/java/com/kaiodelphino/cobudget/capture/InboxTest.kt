package com.kaiodelphino.cobudget.capture

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaiodelphino.cobudget.data.AppDatabase
import com.kaiodelphino.cobudget.data.MonitoredApp
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone
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

/**
 * The inbox: every captured notification is pending until dismissed or accepted as a tagged
 * transaction (or, for an estorno, accepted as the refund of one).
 */
@RunWith(RobolectricTestRunner::class)
class InboxTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private val dao get() = db.dao()
    private val zone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            dao.upsertMonitoredApp(MonitoredApp(NUBANK, "Nu", 0))
            dao.upsertMonitoredApp(MonitoredApp(CHAT, "Chat", 0))
        }
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(zone)
    }

    /** Captures a notification and returns its id. */
    private fun post(text: String, whenMs: Long = T, pkg: String = NUBANK): Long = runBlocking {
        val notification = Notification.Builder(context, "alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenMs)
            .setContentTitle("Nubank")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), whenMs)
        assertTrue(NotificationCaptureService.capture(dao, sbn, ownPackage = context.packageName))
        dao.observeNotifications().first().maxOf { it.id }
    }

    /** Accepts with exactly what the parser suggested. */
    private fun acceptSuggested(id: Long, tags: List<String> = listOf("Food")): Long = runBlocking {
        val s = suggest(dao.notification(id))
        dao.accept(id, s.merchant, s.cents, s.date, s.time, tags)
    }

    private fun pending() = runBlocking { dao.observePending().first() }.map { it.id }
    private fun transactions() = runBlocking { dao.observeTransactions().first() }
    private fun tags() = runBlocking { dao.observeTags().first() }

    @Test
    fun `every captured notification is pending, capture creates no transaction, dismiss removes it`() {
        val purchase = post(PURCHASE)
        val chat = post("Lunch tomorrow?", T + MIN, pkg = CHAT)
        val ad = post("Conheça as novidades do seu cartão Nu Empresas Mastercard Business Executive.", T + 2 * MIN)

        assertEquals(setOf(purchase, chat, ad), pending().toSet())
        assertTrue(transactions().isEmpty())

        runBlocking { dao.dismiss(ad) }
        assertEquals(setOf(purchase, chat), pending().toSet())
    }

    @Test
    fun `accepting as suggested makes a tagged transaction and processes the notification`() {
        val id = post(PURCHASE)

        val txId = acceptSuggested(id, listOf("Food", " food ", "Lunch", ""))

        assertTrue(txId != 0L)
        assertFalse(id in pending())
        val row = transactions().single()
        assertEquals("GIPL COMERCIO DE ALIME" to 3150L, row.transaction.merchant to row.transaction.cents)
        assertEquals(LocalDate.of(2026, 10, 6) to LocalTime.of(11, 43), row.transaction.date to row.transaction.time)
        assertFalse(row.transaction.ratified)
        assertEquals(listOf("Food", "Lunch"), row.tags)
        assertEquals(listOf("Food", "Lunch"), tags().map { it.name })
    }

    @Test
    fun `accept is refused without a tag, without a value, or twice`() {
        val id = post(PURCHASE)
        val s = runBlocking { suggest(dao.notification(id)) }
        runBlocking {
            assertEquals(0L, dao.accept(id, s.merchant, s.cents, s.date, s.time, listOf(" ")))
            assertEquals(0L, dao.accept(id, s.merchant, 0, s.date, s.time, listOf("Food")))
        }
        assertTrue(transactions().isEmpty())
        assertTrue(id in pending())

        assertTrue(acceptSuggested(id) != 0L)
        assertEquals(0L, acceptSuggested(id))
        assertEquals(1, transactions().size)
    }

    @Test
    fun `changing the suggested values ratifies, and edits replace values and tags`() {
        val chat = post("Paguei o almoço", pkg = CHAT)
        val txId = runBlocking { dao.accept(chat, " Restaurante ", 4500, LocalDate.of(2026, 10, 6), LocalTime.of(12, 30), listOf("Food")) }

        val accepted = transactions().single().transaction
        assertEquals("Restaurante" to 4500L, accepted.merchant to accepted.cents)
        assertTrue(accepted.ratified)

        val purchase = post(PURCHASE, T + MIN)
        val tx2 = acceptSuggested(purchase)
        runBlocking { assertTrue(dao.editTransaction(tx2, "GIPL", 3150, LocalDate.of(2026, 10, 6), LocalTime.of(11, 43), listOf("Market"))) }
        val edited = transactions().single { it.transaction.id == tx2 }
        assertEquals("GIPL", edited.transaction.merchant)
        assertTrue(edited.transaction.ratified)
        assertEquals(listOf("Market"), edited.tags)

        runBlocking { assertFalse(dao.editTransaction(txId, "Restaurante", 4500, LocalDate.of(2026, 10, 6), LocalTime.of(12, 30), emptyList())) }
    }

    @Test
    fun `an estorno is accepted as the refund of the latest matching purchase`() {
        val first = acceptSuggested(post(UBER, T))
        val second = acceptSuggested(post(UBER, T + 3 * MIN))
        val estorno = post("A compra em DL          *UberRides no valor de R$ 9,94 foi estornada no seu cartão Nu Empresas.", T + 6 * MIN)

        val match = runBlocking { dao.refundMatch(dao.notification(estorno)) }
        assertEquals(second, match)
        runBlocking { assertTrue(dao.acceptRefund(estorno, match)) }

        assertFalse(estorno in pending())
        val byId = transactions().associate { it.transaction.id to it.transaction }
        assertEquals(estorno, byId.getValue(second).refundedBy)
        assertFalse(byId.getValue(first).refunded)
        runBlocking { assertFalse(dao.acceptRefund(estorno, first)) }
    }

    @Test
    fun `an estorno with no accepted purchase is accepted as a refunded purchase`() {
        val estorno = post("A compra em DL*UberRides no valor de R$ 7,02 foi estornada no seu cartão Nu Empresas.")
        assertEquals(0L, runBlocking { dao.refundMatch(dao.notification(estorno)) })

        acceptSuggested(estorno, listOf("Transport"))

        val t = transactions().single().transaction
        assertEquals(estorno, t.refundedBy)
        assertTrue(t.refunded)
        assertFalse(estorno in pending())
    }

    @Test
    fun `deleting a transaction makes its notification and its estorno pending again`() {
        val purchase = post(UBER, T)
        val txId = acceptSuggested(purchase)
        val estorno = post("A compra em DL*UberRides no valor de R$ 9,94 foi estornada no seu cartão Nu Empresas.", T + 3 * MIN)
        runBlocking { dao.acceptRefund(estorno, txId) }
        assertTrue(pending().isEmpty())

        runBlocking { dao.deleteTransaction(txId) }

        assertEquals(setOf(purchase, estorno), pending().toSet())
        assertEquals(listOf("Food"), tags().map { it.name })
    }

    @Test
    fun `renaming onto an existing tag merges, deleting a sole tag sends its transactions back to pending`() {
        val a = post(PURCHASE, T)
        val b = post(UBER, T + MIN)
        val txA = acceptSuggested(a, listOf("Food", "Market"))
        val txB = acceptSuggested(b, listOf("Transport"))

        val market = tags().single { it.name == "Market" }
        runBlocking { assertTrue(dao.renameTag(market.id, "food")) }
        assertEquals(listOf("Food", "Transport"), tags().map { it.name })
        assertEquals(listOf("Food"), transactions().single { it.transaction.id == txA }.tags)

        val transport = tags().single { it.name == "Transport" }
        assertEquals(1, runBlocking { dao.soleTagCount(transport.id) })
        runBlocking { dao.deleteTag(transport.id) }

        assertEquals(listOf(txA), transactions().map { it.transaction.id })
        assertEquals(listOf(b), pending())
        assertFalse(txB in transactions().map { it.transaction.id })
    }

    private companion object {
        const val NUBANK = "com.nu.production"
        const val CHAT = "com.chat"
        const val MIN = 60_000L
        /** 2026-10-06 11:43:59 in São Paulo. */
        const val T = 1_791_297_839_102L
        const val PURCHASE = "Compra de R$ 31,50 APROVADA em GIPL COMERCIO DE ALIME para o cartão com final 1234."
        const val UBER = "Compra de R$ 9,94 APROVADA em DL*UberRides no seu cartão Nu Empresas."
    }
}
