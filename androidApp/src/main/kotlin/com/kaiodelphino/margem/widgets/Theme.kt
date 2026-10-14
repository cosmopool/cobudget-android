package com.kaiodelphino.margem.widgets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.Theme
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlin.math.abs

// Tokens of docs/design_system.html (§4 cor, §5 tipografia, §6 fundamentos), read through `Margem`.

/** Semantic colors, book §4.2. Names are the book's code tokens. */
@Immutable
class MargemColors(
    val isDark: Boolean,
    val paper: Color, val surface: Color, val surfaceSunken: Color,
    val rule: Color, val ruleStrong: Color,
    val ink: Color, val inkSecondary: Color, val inkTertiary: Color,
    val brand: Color,
    val onPace: Color, val onPaceContainer: Color,
    val ahead: Color, val aheadContainer: Color,
    val overrun: Color, val overrunContainer: Color,
    val info: Color, val infoContainer: Color,
    val action: Color, val onAction: Color, val actionPressed: Color,
    val snackAction: Color, val scrim: Color, val shadow: Color,
    /** Categorical series c1..c6 then c0 (neutral), book §9.1. */
    val series: List<Color>,
    /** Heat scale s0..s4 of the spending calendar, book §9.1. */
    val heat: List<Color>,
)

val LightColors = MargemColors(
    isDark = false,
    paper = Color(0xFFF3EEE4), surface = Color(0xFFFBF8F1), surfaceSunken = Color(0xFFECE5D8),
    rule = Color(0xFFDDD3C2), ruleStrong = Color(0xFF8A7E6A),
    ink = Color(0xFF1E1B16), inkSecondary = Color(0xFF514A40), inkTertiary = Color(0xFF6B6254),
    brand = Color(0xFFA44D29),
    onPace = Color(0xFF3E5E38), onPaceContainer = Color(0xFFE3EADB),
    ahead = Color(0xFF85590F), aheadContainer = Color(0xFFF4E7CC),
    overrun = Color(0xFF9A2A20), overrunContainer = Color(0xFFF6E3DC),
    info = Color(0xFF2D4C6E), infoContainer = Color(0xFFDFE6EE),
    action = Color(0xFF1E1B16), onAction = Color(0xFFFBF8F1), actionPressed = Color(0xFF3A352D),
    snackAction = Color(0xFFE09470), scrim = Color(0x731E1B16), shadow = Color(0x471E1B16),
    series = listOf(0xFF2F5FA8, 0xFFD0682E, 0xFF139488, 0xFF7E2F78, 0xFFB8860B, 0xFFC25C7E, 0xFF8C8270).map(::Color),
    heat = listOf(0xFFF1E4D6, 0xFFE3C3A6, 0xFFCF9871, 0xFFB46A43, 0xFF8A4422).map(::Color),
)

val DarkColors = MargemColors(
    isDark = true,
    paper = Color(0xFF171512), surface = Color(0xFF201D19), surfaceSunken = Color(0xFF2A2621),
    rule = Color(0xFF36312A), ruleStrong = Color(0xFF7C7262),
    ink = Color(0xFFEEE7DA), inkSecondary = Color(0xFFBDB3A3), inkTertiary = Color(0xFF988E7F),
    brand = Color(0xFFE09470),
    onPace = Color(0xFFA3C092), onPaceContainer = Color(0xFF24301F),
    ahead = Color(0xFFDDB063), aheadContainer = Color(0xFF382C17),
    overrun = Color(0xFFEE937E), overrunContainer = Color(0xFF3A221C),
    info = Color(0xFFA3BCDB), infoContainer = Color(0xFF1F2A36),
    action = Color(0xFFEEE7DA), onAction = Color(0xFF171512), actionPressed = Color(0xFFCFC6B6),
    snackAction = Color(0xFFA44D29), scrim = Color(0x99000000), shadow = Color(0xB3000000),
    series = listOf(0xFF5584D0, 0xFFD06A30, 0xFF20A898, 0xFF8E4A8A, 0xFFB98A14, 0xFFCC6487, 0xFF7A7163).map(::Color),
    heat = listOf(0xFF2B231E, 0xFF4E3324, 0xFF7E4A2E, 0xFFB66C44, 0xFFE6A27C).map(::Color),
)

// ---- Type (D16) ----

private val serifFamilies = HashMap<Int, FontFamily>()
private var sansFamily: FontFamily? = null

/**
 * Newsreader at one optical size: every weight of the book, roman and italic, each a `Font` of the
 * variable file with its `wght` and with `opsz` = [size] (6..72), as the book's font-optical-sizing:auto.
 */
@OptIn(ExperimentalTextApi::class)
fun newsreader(size: Int): FontFamily = serifFamilies.getOrPut(size) {
    val opsz = size.coerceIn(6, 72).toFloat()
    FontFamily(
        listOf(300, 400, 500, 600, 700).flatMap { w ->
            listOf(
                Font(R.font.newsreader, FontWeight(w), FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz))),
                Font(R.font.newsreader_italic, FontWeight(w), FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz))),
            )
        },
    )
}

/** Source Sans 3, weights 300..800, roman and italic, from the two variable files. */
@OptIn(ExperimentalTextApi::class)
fun sourceSans(): FontFamily = sansFamily ?: FontFamily(
    listOf(300, 400, 500, 600, 700, 800).flatMap { w ->
        listOf(
            Font(R.font.source_sans, FontWeight(w), FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w))),
            Font(R.font.source_sans_italic, FontWeight(w), FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(w))),
        )
    },
).also { sansFamily = it }

/** Lining, tabular figures: the cents column lines up as in a ledger (book §5.2). */
const val TABULAR = "lnum, tnum"

private val trimNone = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** A Newsreader style off the scale (top bar 21, list amounts 17 in a row, etc.). */
fun serif(size: Int, weight: Int = 400, lineHeight: Int = size * 5 / 4, italic: Boolean = false, tabular: Boolean = false, tracking: Float = 0f) = TextStyle(
    fontFamily = newsreader(size), fontWeight = FontWeight(weight), fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = tracking.em, fontSynthesis = FontSynthesis.None,
    fontFeatureSettings = if (tabular) TABULAR else null, lineHeightStyle = trimNone,
)

/** A Source Sans 3 style off the scale. */
fun sans(size: Float, weight: Int = 400, lineHeight: Float = size * 1.35f, tracking: Float = 0f, tabular: Boolean = false, italic: Boolean = false) = TextStyle(
    fontFamily = sourceSans(), fontWeight = FontWeight(weight), fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = tracking.em, fontSynthesis = FontSynthesis.None,
    fontFeatureSettings = if (tabular) TABULAR else null, lineHeightStyle = trimNone,
)

/** The scale of book §5.1. `overline` text is written in caps by the caller (`.uppercase()`). */
@Immutable
class MargemType(
    val display: TextStyle = serif(40, 400, 44, tracking = -0.01f),
    val headline: TextStyle = serif(29, 400, 34, tracking = -0.01f),
    val title: TextStyle = serif(22, 500, 28),
    val titleSmall: TextStyle = serif(19, 500, 24),
    val body: TextStyle = sans(16f, 400, 24f),
    val bodySmall: TextStyle = sans(14f, 400, 20f),
    val label: TextStyle = sans(15f, 600, 20f, tracking = 0.01f),
    val overline: TextStyle = sans(12f, 600, 16f, tracking = 0.12f),
    val note: TextStyle = serif(15, 400, 20, italic = true),
    val amountHero: TextStyle = serif(46, 400, 52, tabular = true),
    val amount: TextStyle = serif(17, 500, 24, tabular = true),
    val axis: TextStyle = sans(11f, 500, 14f, tabular = true),
)

/** Spacing, rules, radii and touch, book §6. */
object Space {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 24.dp; val s6 = 32.dp; val s7 = 48.dp; val s8 = 64.dp
    /** Fio: list separators. */
    val rule = 1.dp
    /** Fio de ênfase: active tab, totals. */
    val emphasis = 2.dp
    /** Minimum touch target. */
    val touch = 48.dp
    /** Page margin. */
    val page = 24.dp
}

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { MargemType() }

/** The theme: `Margem.colors`, `Margem.type`, `Margem.space`. */
object Margem {
    val colors: MargemColors @Composable get() = LocalColors.current
    val type: MargemType @Composable get() = LocalType.current
    val space = Space
}

/** Light or dark by the Ajustes choice (spec §7.6: sistema · claro · escuro). */
@Composable
fun MargemTheme(theme: Theme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        Theme.SYSTEM -> isSystemInDarkTheme()
        Theme.LIGHT -> false
        Theme.DARK -> true
    }
    MargemTheme(dark, content)
}

@Composable
fun MargemTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTextSelectionColors provides TextSelectionColors(colors.info, colors.infoContainer),
        content = content,
    )
}

// ---- Money and dates (book §5.2) ----

/** U+202F: the narrow no-break space after "R$". */
const val NNBSP = ' '

/** "1.234" and "56": integer part with thousands dots, and the two cents digits. Sign ignored. */
fun moneyParts(c: Cents): Pair<String, String> {
    val v = abs(c.v)
    val int = (v / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return int to (v % 100).toString().padStart(2, '0')
}

/** "R$ 1.234,56". Negative values get U+2212; callers usually say "além" instead (book §5.2). */
fun money(c: Cents): String {
    val (int, cts) = moneyParts(c)
    return (if (c.v < 0) "−" else "") + "R$$NNBSP$int,$cts"
}

/** "1.234,56", for columns that already say R$. */
fun moneyPlain(c: Cents): String = moneyParts(c).let { (int, cts) -> "$int,$cts" }

private val weekdayShort = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
private val weekdayLong = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")
private val monthShort = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
private val monthLong = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

fun weekdayShort(d: DayOfWeek) = weekdayShort[d.ordinal]
fun weekdayLong(d: DayOfWeek) = weekdayLong[d.ordinal]
fun monthShort(m: Month) = monthShort[m.ordinal]
fun monthLong(m: Month) = monthLong[m.ordinal]

/** "sex, 9 out" */
fun dateShort(d: LocalDate) = "${weekdayShort(d.dayOfWeek)}, ${d.day} ${monthShort(d.month)}"

/** "sexta, 9 de outubro" */
fun dateLong(d: LocalDate) = "${weekdayLong(d.dayOfWeek)}, ${d.day} de ${monthLong(d.month)}"

/** "07:42" */
fun time(t: LocalTime) = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"

/** "outubro de 2026" */
fun monthTitle(m: YearMonth) = "${monthLong(m.month)} de ${m.year}"
