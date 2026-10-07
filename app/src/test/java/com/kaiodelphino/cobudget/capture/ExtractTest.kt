package com.kaiodelphino.cobudget.capture

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaiodelphino.cobudget.data.AppDatabase
import com.kaiodelphino.cobudget.data.BankTransaction
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

/** Notifications in through the capture pipeline, rows out of the transactions table. Nubank texts are real, names anonymised. */
@RunWith(RobolectricTestRunner::class)
class ExtractTest {

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
            dao.upsertMonitoredApp(MonitoredApp(BANK, "My Bank", 0))
        }
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(zone)
    }

    /** Posts like Nubank does: the same text as content and big text. Returns whether the notification was saved. */
    private fun post(pkg: String, title: String, text: String, whenMs: Long = T, postMs: Long = if (whenMs == 0L) T else whenMs): Boolean =
        runBlocking {
            val notification = Notification.Builder(context, "alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setWhen(whenMs)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .build()
            @Suppress("DEPRECATION")
            val sbn = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), postMs)
            NotificationCaptureService.capture(dao, sbn, ownPackage = context.packageName)
        }

    private fun nubank(text: String, whenMs: Long = T) = post(NUBANK, "Nubank", text, whenMs)

    private fun transactions() = runBlocking { dao.observeTransactions().first() }

    private fun savedNotifications() = runBlocking { dao.observeNotifications().first() }.size

    @Test
    fun `nubank card purchase gives merchant, value and the notification's date and time`() {
        assertTrue(post(NUBANK, "Compra no crédito aprovada", "Compra de R$ 31,50 APROVADA em GIPL COMERCIO DE ALIME para o cartão com final 1234."))

        val t = transactions().single()
        assertEquals("GIPL COMERCIO DE ALIME", t.merchant)
        assertEquals(3150L, t.cents)
        assertEquals(LocalDate.of(2026, 10, 6), t.date)
        assertEquals(LocalTime.of(11, 43), t.time)
        assertFalse(t.refunded)
    }

    @Test
    fun `nubank business card wording, with the padded merchant collapsed`() {
        nubank("Compra de R$ 161,70 APROVADA em DL          *UberRides no seu cartão Nu Empresas.")
        assertEquals("DL *UberRides" to 16170L, transactions().single().let { it.merchant to it.cents })
    }

    @Test
    fun `nubank money out and money in`() {
        nubank("R$ 33,91 em 1x no crédito sem juros com NuPay APROVADO em iFood.", T)
        nubank("Seu pagamento de R$ 845,00 para CENTRO DE EDUCACAO INFANTIL PEQUENO foi realizado com sucesso.", T + MIN)
        nubank("Pix de R$ 10.830,00 da conta Nu Empresas foi realizado com sucesso.", T + 2 * MIN)
        nubank("Você recebeu R$ 10.830,00 de ACME SISTEMAS WEB LTDA na conta Nu Empresas.", T + 3 * MIN)
        nubank("Você recebeu uma transferência de R$ 5.488,70 de 12.345.678 FULANO DE TAL.", T + 4 * MIN)

        assertEquals(
            setOf(
                "iFood" to 3391L,
                "CENTRO DE EDUCACAO INFANTIL PEQUENO" to 84500L,
                "" to 1083000L,
                "ACME SISTEMAS WEB LTDA" to 1083000L,
                "12.345.678 FULANO DE TAL" to 548870L,
            ),
            transactions().map { it.merchant to it.cents }.toSet(),
        )
    }

    @Test
    fun `nubank texts that move no money, or are unknown, give no transaction`() {
        listOf(
            "Kaio, traga seus dados do Banco Inter Pagamentos PF para o Nubank e aumente suas chances de ter mais limite de crédito",
            "Conheça as novidades do seu cartão Nu Empresas Mastercard Business Executive.",
            "Compra no cartão Nu Empresas de R$ 679,33 foi NEGADA, pois o limite disponível não é suficiente.",
            "O boleto CLINICA EXEMPLO LTDA no valor de R$ 235,14 vence amanhã. Clique aqui para pagar ou agendar.",
            "CENTRO DE EDUCACAO INFANTIL PEQUENO - R$ 985,00",
            "O débito automático da sua fatura no valor de R$ 3.351,63 está ativado. Menos uma conta pra você lembrar de pagar!",
            "Recebemos seu pagamento no valor de R$ 5.478,49. Obrigado!",
            "Seu limite aumentou para R$ 5.000,00",
        ).forEachIndexed { i, text -> assertTrue(nubank(text, T + i * MIN)) }

        assertEquals(8, savedNotifications())
        assertTrue(transactions().isEmpty())
    }

    @Test
    fun `a refund flags the latest matching purchase instead of adding a row`() {
        nubank("Compra de R$ 9,94 APROVADA em DL*UberRides no seu cartão Nu Empresas.", T)
        nubank("Compra de R$ 9,94 APROVADA em DL*UberRides no seu cartão Nu Empresas.", T + 3 * MIN)
        nubank("A compra em DL          *UberRides no valor de R$ 9,94 foi estornada no seu cartão Nu Empresas.", T + 6 * MIN)

        val rows = transactions().sortedBy { it.notificationId }
        assertEquals(listOf(false, true), rows.map { it.refunded })
    }

    @Test
    fun `a refund of a purchase we never saw is kept as a refunded purchase`() {
        nubank("A compra em DL*UberRides no valor de R$ 7,02 foi estornada no seu cartão Nu Empresas.")

        val t = transactions().single()
        assertEquals("DL*UberRides" to 702L, t.merchant to t.cents)
        assertTrue(t.refunded)
    }

    @Test
    fun `generic bank reads amount, merchant, date and time from the text`() {
        post(BANK, "Compra aprovada", "Compra de R$ 1.234,56 aprovada em PADARIA SAO JOSE em 05/10 às 14h32.")

        val t = transactions().single()
        assertEquals("PADARIA SAO JOSE", t.merchant)
        assertEquals(123456L, t.cents)
        assertEquals(LocalDate.of(2026, 10, 5), t.date)
        assertEquals(LocalTime.of(14, 32), t.time)
    }

    @Test
    fun `generic amounts - nbsp, first wins, dot decimals, none, absurd`() {
        post(BANK, "Compra", "Compra de R$ 12,00 em LOJA. Seu saldo R$ 95,50", T)
        post(BANK, "SMS", "Em 3 dias vence o seu bonus de R$12.90. Desconto", T + MIN)
        post(BANK, "Card purchase", "You spent $4.50 at Coffee", T + 2 * MIN)
        assertTrue(post(BANK, "Compra", "Compra de R$ 99999999999999999999 em LOJA", T + 3 * MIN))

        assertEquals(4, savedNotifications())
        assertEquals(setOf(1200L, 1290L), transactions().map { it.cents }.toSet())
    }

    @Test
    fun `generic estorno flags the purchase by value when it names no merchant`() {
        post(BANK, "Compra", "Compra de R$ 20,00 em LOJA", T)
        post(BANK, "Estorno", "Estorno de R$ 20,00 efetuado", T + 3 * MIN)

        val t = transactions().single()
        assertEquals("LOJA", t.merchant)
        assertTrue(t.refunded)
    }

    @Test
    fun `a date without year picks the closest year, and when 0 falls back to post time`() {
        post(BANK, "Compra", "Compra de R$ 10,00 em LOJA em 31/12", JAN_2_2027)
        post(BANK, "Compra", "Compra de R$ 11,00 em LOJA em 08/10", T)
        post(BANK, "Compra", "Compra de R$ 12,00 em LOJA", whenMs = 0, postMs = T + MIN)

        val byCents = transactions().associateBy { it.cents }
        assertEquals(LocalDate.of(2026, 12, 31), byCents[1000L]!!.date)
        assertEquals(LocalTime.of(10, 0), byCents[1000L]!!.time)
        assertEquals(LocalDate.of(2026, 10, 8), byCents[1100L]!!.date)
        assertEquals(LocalDate.of(2026, 10, 6) to LocalTime.of(11, 44), byCents[1200L]!!.let { it.date to it.time })
    }

    @Test
    fun `only saved notifications become transactions`() {
        assertFalse(post("com.chat", "Ana", "Me passa R$ 50,00 em dinheiro"))
        nubank("Compra de R$ 31,50 APROVADA em LOJA para o cartão com final 1234.", T)
        assertFalse(nubank("Compra de R$ 31,50 APROVADA em LOJA para o cartão com final 1234.", T))
        assertEquals(1, transactions().size)
    }

    @Test
    fun `re-parse rebuilds the same transactions, dismissed notifications and refund flags included`() {
        nubank("Compra de R$ 31,50 APROVADA em GIPL COMERCIO DE ALIME para o cartão com final 1234.", T)
        nubank("Compra de R$ 9,94 APROVADA em DL*UberRides no seu cartão Nu Empresas.", T + MIN)
        nubank("Compra de R$ 9,94 APROVADA em DL*UberRides no seu cartão Nu Empresas.", T + 4 * MIN)
        nubank("A compra em DL*UberRides no valor de R$ 9,94 foi estornada no seu cartão Nu Empresas.", T + 7 * MIN)
        runBlocking { dao.dismiss(runBlocking { dao.observeNotifications().first() }.last().id) }
        fun normalized(rows: List<BankTransaction>) = rows.map { it.copy(id = 0) }.sortedBy { it.notificationId }
        val before = normalized(transactions())

        assertEquals(3, runBlocking { dao.rebuildTransactions() })
        assertEquals(before, normalized(transactions()))
        assertEquals(1, before.count { it.refunded })
    }

    private companion object {
        const val NUBANK = "com.nu.production"
        const val BANK = "com.bank"
        const val MIN = 60_000L
        /** 2026-10-06 11:43:59 in São Paulo. */
        const val T = 1_791_297_839_102L
        /** 2027-01-02 10:00 in São Paulo. */
        const val JAN_2_2027 = 1_798_894_800_000L
    }
}
