package com.kaiodelphino.cobudget.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiodelphino.cobudget.data.CobudgetDao
import com.kaiodelphino.cobudget.data.TransactionRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class TransactionsViewModel(dao: CobudgetDao) : ViewModel() {
    val rows: StateFlow<List<TransactionRow>> =
        dao.observeTransactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nicknames: StateFlow<Map<String, String>> =
        dao.observeNicknames().map(::nicknameMap).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
