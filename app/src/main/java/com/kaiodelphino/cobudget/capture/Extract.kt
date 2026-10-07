package com.kaiodelphino.cobudget.capture

import android.util.Log
import com.kaiodelphino.cobudget.data.BankTransaction
import com.kaiodelphino.cobudget.data.CapturedNotification
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reads money out of one notification, as a suggestion for the accept page. cents == 0 means "no
 * value found"; refunded (refundedBy == the notification's id) means "this is an estorno of the
 * purchase described here".
 */
fun interface Extractor {
    fun extract(n: CapturedNotification): BankTransaction
}

/** The suggestion the accept page starts from. Never throws: a parser bug just suggests nothing (cents 0). */
fun suggest(n: CapturedNotification): BankTransaction =
    runCatching { extractorFor(n.packageName).extract(n) }
        .onFailure { Log.e("Extract", "extract failed for notification ${n.id}", it) }
        .getOrElse { tx(n, "", "0", "", "") }

private val TYPED_VALUE = Regex("^$WS*(?:R\\$$WS*)?(\\d{1,3}(?:\\.\\d{3}){1,4}|\\d{1,15})(?:[,.](\\d{1,2}))?$WS*$")

/** A value typed by the user ("31,50", "1.234,56", "R$ 12") in centavos; 0 when it isn't one. */
fun parseBrl(text: String): Long {
    val m = TYPED_VALUE.matchEntire(text) ?: return 0
    return m.groupValues[1].replace(".", "").toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
}

/** Banks with their own extractor never fall back to the generic one: an unknown format is not a transaction. */
fun extractorFor(packageName: String): Extractor = when (packageName) {
    "com.nu.production" -> NubankExtractor
    else -> GenericExtractor
}

/** Strict patterns taken from real Nubank pushes; anything else (declined, reminders, marketing) yields nothing. */
private object NubankExtractor : Extractor {
    private val PURCHASE = Regex("^Compra de $AMOUNT APROVADA em (.+?) (?:para o cartão com final \\d{4}|no seu cartão [^.]+)\\.$")
    private val NUPAY = Regex("^$AMOUNT em \\d+x .+ APROVADO em (.+?)\\.$")
    private val BILL_PAID = Regex("^Seu pagamento de $AMOUNT para (.+?) foi realizado com sucesso\\.$")
    private val PIX_SENT = Regex("^Pix de $AMOUNT da conta .+ foi realizado com sucesso\\.$")
    private val RECEIVED = Regex("^Você recebeu (?:uma transferência de )?$AMOUNT de (.+?)(?: na conta [^.]+)?\\.$")
    private val REFUND = Regex("^A compra em (.+?) no valor de $AMOUNT foi estornada.*\\.$")

    override fun extract(n: CapturedNotification): BankTransaction {
        val body = n.body ?: ""
        PURCHASE.find(body)?.let { return tx(n, body, it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        NUPAY.find(body)?.let { return tx(n, body, it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        BILL_PAID.find(body)?.let { return tx(n, body, it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        PIX_SENT.find(body)?.let { return tx(n, body, it.groupValues[1], it.groupValues[2], "") }
        RECEIVED.find(body)?.let { return tx(n, body, it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        REFUND.find(body)?.let { return tx(n, body, it.groupValues[2], it.groupValues[3], it.groupValues[1], refunded = true) }
        return tx(n, body, "0", "", "")
    }
}

/** Best effort for any bank: the first R$ amount, the name after "em"/"para", and "estorn" marks a refund. */
private object GenericExtractor : Extractor {
    private val FIRST_AMOUNT = Regex(AMOUNT)
    private val MERCHANT = Regex(
        "(?:\\bem|\\bpara)\\s+(?!\\d)(.+?)" +
            "(?=\\s+(?:para o cartão|no cartão|com o cartão|em \\d|no dia|dia \\d|às|as \\d)|[;!\\n]|\\.(?:\\s|$)|$)"
    )

    override fun extract(n: CapturedNotification): BankTransaction {
        val text = listOfNotNull(n.title, n.body).joinToString("\n")
        val amount = FIRST_AMOUNT.find(text) ?: return tx(n, text, "0", "", "")
        val merchant = MERCHANT.find(text, amount.range.last + 1)?.groupValues?.get(1) ?: ""
        return tx(n, text, amount.groupValues[1], amount.groupValues[2], merchant, refunded = text.contains("estorn", ignoreCase = true))
    }
}

/** Whitespace incl. NBSP / narrow NBSP: banks format R$ amounts with them, and JVM `\s` doesn't match them. */
private const val WS = "[\\s\\u00A0\\u202F]"

/** Integer part (max 15 digits, so cents can't overflow) in group 1, decimals in group 2. */
private const val AMOUNT = "R\\$$WS*(\\d{1,3}(?:\\.\\d{3}){1,4}|\\d{1,15})(?!\\d)(?:[,.](\\d{1,2})(?!\\d))?"

private val DATE = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}|\\d{2}))?(?!\\d)")
private val TIME = Regex("(?<!\\d)([01]?\\d|2[0-3])[:h]([0-5]\\d)(?!\\d)")
private val SPACES = Regex("$WS+")

/** Builds the row: amount from its regex groups, date/time from the text or else the notification's time. */
private fun tx(n: CapturedNotification, text: String, int: String, dec: String, merchant: String, refunded: Boolean = false): BankTransaction {
    val fallback = Instant.ofEpochMilli(if (n.notificationWhen != 0L) n.notificationWhen else n.postedAt)
        .atZone(ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.MINUTES)
    return BankTransaction(
        notificationId = n.id,
        merchant = merchant.replace(SPACES, " ").trim(),
        date = dateIn(text, fallback),
        time = TIME.find(text)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: fallback.toLocalTime(),
        cents = int.replace(".", "").toLong() * 100 + dec.padEnd(2, '0').toLong(),
        refundedBy = if (refunded) n.id else 0,
    )
}

/** dd/MM[/yy[yy]]; without a year, the valid date closest to the notification (31/12 seen on 2 Jan is last year's). */
private fun dateIn(text: String, fallback: ZonedDateTime): LocalDate {
    val base = fallback.toLocalDate()
    val m = DATE.find(text) ?: return base
    val day = m.groupValues[1].toInt()
    val month = m.groupValues[2].toInt()
    val year = m.groupValues[3]
    val years = when (year.length) {
        4 -> listOf(year.toInt())
        2 -> listOf(2000 + year.toInt())
        else -> listOf(base.year - 1, base.year, base.year + 1)
    }
    return years.mapNotNull { runCatching { LocalDate.of(it, month, day) }.getOrNull() }
        .minByOrNull { abs(it.toEpochDay() - base.toEpochDay()) } ?: base
}
