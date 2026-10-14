package com.kaiodelphino.margem

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Whitespace incl. NBSP / narrow NBSP: banks format R$ amounts with them, and `\s` doesn't match them. */
private const val WS = "[\\s\\u00A0\\u202F]"

/** Integer part (max 15 digits, so cents can't overflow) in group 1, decimals in group 2. */
private const val AMOUNT = "R\\$$WS*(\\d{1,3}(?:\\.\\d{3}){1,4}|\\d{1,15})(?!\\d)(?:[,.](\\d{1,2})(?!\\d))?"

private val TYPED_VALUE = Regex("^$WS*(?:R\\$$WS*)?(\\d{1,3}(?:\\.\\d{3}){1,4}|\\d{1,15})(?:[,.](\\d{1,2}))?$WS*$")

/** The largest value [parseBrl] can return: 15 integer digits and 2 decimals. Forms and limits are capped to it. */
internal const val MAX_CENTS = 99_999_999_999_999_999L

/** A value typed by the person ("31,50", "1.234,56", "R$ 12") in centavos; Cents(0) when it isn't one. */
fun parseBrl(text: String): Cents {
    val m = TYPED_VALUE.matchEntire(text) ?: return Cents(0)
    return Cents(m.groupValues[1].replace(".", "").toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong())
}

/** Same merchant across spacing and case: "DL *UberRides" and "dl*uberrides" share a key. */
internal fun merchantKey(merchant: String): String = merchant.filterNot(Char::isWhitespace).lowercase()

private const val NUBANK = "com.nu.production"

// Strict patterns taken from real Nubank pushes; anything else (declined, reminders, marketing) yields nothing.
private val NU_PURCHASE = Regex("^Compra de $AMOUNT APROVADA em (.+?) (?:para o cartão com final \\d{4}|no seu cartão [^.]+)\\.$")
private val NU_NUPAY = Regex("^$AMOUNT em \\d+x .+ APROVADO em (.+?)\\.$")
private val NU_BILL_PAID = Regex("^Seu pagamento de $AMOUNT para (.+?) foi realizado com sucesso\\.$")
private val NU_PIX_SENT = Regex("^Pix de $AMOUNT da conta .+ foi realizado com sucesso\\.$")
private val NU_RECEIVED = Regex("^Você recebeu (?:uma transferência de )?$AMOUNT de (.+?)(?: na conta [^.]+)?\\.$")
private val NU_REFUND = Regex("^A compra em (.+?) no valor de $AMOUNT foi estornada.*\\.$")

// Best effort for any bank: the first R$ amount, the name after "em"/"para", and "estorn" marks a refund.
private val FIRST_AMOUNT = Regex(AMOUNT)
private val MERCHANT = Regex(
    "(?:\\bem|\\bpara)\\s+(?!\\d)(.+?)" +
        "(?=\\s+(?:para o cartão|no cartão|com o cartão|em \\d|no dia|dia \\d|às|as \\d)|[;!\\n]|\\.(?:\\s|$)|$)"
)

private val DATE = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}|\\d{2}))?(?!\\d)")
private val TIME = Regex("(?<!\\d)([01]?\\d|2[0-3])[:h]([0-5]\\d)(?!\\d)")
private val SPACES = Regex("$WS+")

/**
 * Reads value, merchant, date and time out of a notice. Banks with their own patterns never fall
 * back to the generic one: an unknown format is not a purchase. Never throws: a parser bug just
 * suggests nothing (cents 0), with the notice's own date and time.
 */
internal fun extract(raw: RawNotice, timeZone: TimeZone): Parsed {
    val fallback = Instant.fromEpochMilliseconds(if (raw.whenMs != 0L) raw.whenMs else raw.postedAt).toLocalDateTime(timeZone)
    val date = fallback.date
    val time = LocalTime(fallback.hour, fallback.minute)
    return runCatching {
        if (raw.packageName == NUBANK) {
            val body = raw.body
            NU_PURCHASE.find(body)?.let { return@runCatching parsed(body, it.groupValues[1], it.groupValues[2], it.groupValues[3], date, time) }
            NU_NUPAY.find(body)?.let { return@runCatching parsed(body, it.groupValues[1], it.groupValues[2], it.groupValues[3], date, time) }
            NU_BILL_PAID.find(body)?.let { return@runCatching parsed(body, it.groupValues[1], it.groupValues[2], it.groupValues[3], date, time) }
            NU_PIX_SENT.find(body)?.let { return@runCatching parsed(body, it.groupValues[1], it.groupValues[2], "", date, time) }
            NU_RECEIVED.find(body)?.let { return@runCatching parsed(body, it.groupValues[1], it.groupValues[2], it.groupValues[3], date, time) }
            NU_REFUND.find(body)?.let { return@runCatching parsed(body, it.groupValues[2], it.groupValues[3], it.groupValues[1], date, time, refund = true) }
            parsed(body, "0", "", "", date, time)
        } else {
            val text = listOf(raw.title, raw.body).filter(String::isNotEmpty).joinToString("\n")
            val amount = FIRST_AMOUNT.find(text) ?: return@runCatching parsed(text, "0", "", "", date, time)
            val merchant = MERCHANT.find(text, amount.range.last + 1)?.groupValues?.get(1) ?: ""
            parsed(text, amount.groupValues[1], amount.groupValues[2], merchant, date, time, refund = text.contains("estorn", ignoreCase = true))
        }
    }.getOrElse { Parsed(date = date, time = time) }
}

/** Amount from its regex groups; date and time from the text, or else the notice's own. */
private fun parsed(text: String, int: String, dec: String, merchant: String, date: LocalDate, time: LocalTime, refund: Boolean = false) =
    Parsed(
        cents = Cents(int.replace(".", "").toLong() * 100 + dec.padEnd(2, '0').toLong()),
        merchant = merchant.replace(SPACES, " ").trim(),
        date = dateIn(text, date),
        time = TIME.find(text)?.let { LocalTime(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: time,
        isRefund = refund,
    )

/** dd/MM[/yy[yy]]; without a year, the valid date closest to the notice (31/12 seen on 2 Jan is last year's). */
private fun dateIn(text: String, base: LocalDate): LocalDate {
    val m = DATE.find(text) ?: return base
    val day = m.groupValues[1].toInt()
    val month = m.groupValues[2].toInt()
    val year = m.groupValues[3]
    val years = when (year.length) {
        4 -> listOf(year.toInt())
        2 -> listOf(2000 + year.toInt())
        else -> listOf(base.year - 1, base.year, base.year + 1)
    }
    return years.mapNotNull { runCatching { LocalDate(it, month, day) }.getOrNull() }
        .minByOrNull { abs(it.toEpochDays() - base.toEpochDays()) } ?: base
}
