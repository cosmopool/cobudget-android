package com.kaiodelphino.cobudget.ui.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiodelphino.cobudget.capture.parseBrl
import com.kaiodelphino.cobudget.capture.suggest
import com.kaiodelphino.cobudget.data.BankTransaction
import com.kaiodelphino.cobudget.data.CapturedNotification
import com.kaiodelphino.cobudget.data.CobudgetDao
import com.kaiodelphino.cobudget.data.Tag
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The accept page (a pending notification, [transactionId] == 0) and the edit page (an accepted
 * transaction) share one form: values pre-filled from the parser or the saved row, plus tags.
 */
class TransactionPageViewModel(
    private val dao: CobudgetDao,
    private val notificationId: Long,
    private val transactionId: Long,
) : ViewModel() {

    val isEdit = transactionId != 0L

    var notification: CapturedNotification? by mutableStateOf(null)
        private set
    /** Accept page of an estorno: the accepted purchase it cancels, if any. */
    var refundOf: BankTransaction? by mutableStateOf(null)
        private set
    var refunded by mutableStateOf(false)
        private set
    var merchant by mutableStateOf("")
    var value by mutableStateOf("")
    var date by mutableStateOf("")
    var time by mutableStateOf("")
    var tags: List<String> by mutableStateOf(emptyList())
        private set
    var newTag by mutableStateOf("")
    /** Set when the page's work is done and it should close. */
    var done by mutableStateOf(false)
        private set

    val allTags: StateFlow<List<Tag>> = dao.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            if (isEdit) {
                val t = dao.transaction(transactionId)
                notification = dao.notification(t.notificationId)
                fill(t)
                tags = dao.tagNames(transactionId)
            } else {
                val n = dao.notification(notificationId)
                notification = n
                fill(suggest(n))
                val match = dao.refundMatch(n)
                if (match != 0L) refundOf = dao.transaction(match)
            }
        }
    }

    private fun fill(t: BankTransaction) {
        merchant = t.merchant
        value = if (t.cents > 0) "${t.cents / 100},${"%02d".format(t.cents % 100)}" else ""
        date = t.date.format(dateFormat)
        time = t.time.format(timeFormat)
        refunded = t.refunded
    }

    val cents: Long get() = parseBrl(value)
    val parsedDate: LocalDate? get() = runCatching { LocalDate.parse(date.trim(), dateFormat) }.getOrNull()
    val parsedTime: LocalTime? get() = runCatching { LocalTime.parse(time.trim(), timeFormat) }.getOrNull()
    val canSave: Boolean get() = cents > 0 && parsedDate != null && parsedTime != null && tags.isNotEmpty()

    fun toggleTag(name: String) {
        tags = if (tags.any { it.equals(name, ignoreCase = true) }) tags.filterNot { it.equals(name, ignoreCase = true) } else tags + name
    }

    fun addTag() {
        val name = newTag.trim()
        if (name.isNotEmpty() && tags.none { it.equals(name, ignoreCase = true) }) tags = tags + name
        newTag = ""
    }

    fun save() {
        val d = parsedDate ?: return
        val t = parsedTime ?: return
        viewModelScope.launch {
            done = if (isEdit) {
                dao.editTransaction(transactionId, merchant, cents, d, t, tags)
            } else {
                dao.accept(notificationId, merchant, cents, d, t, tags) != 0L
            }
        }
    }

    fun acceptRefund() {
        val match = refundOf ?: return
        viewModelScope.launch { done = dao.acceptRefund(notificationId, match.id) }
    }

    fun dismiss() {
        viewModelScope.launch {
            dao.dismiss(notificationId)
            done = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            dao.deleteTransaction(transactionId)
            done = true
        }
    }
}
