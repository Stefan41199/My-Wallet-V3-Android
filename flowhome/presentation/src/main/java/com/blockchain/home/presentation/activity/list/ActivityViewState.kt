package com.blockchain.home.presentation.activity.list

import com.blockchain.commonarch.presentation.mvi_v2.ViewState
import com.blockchain.data.DataResource

data class ActivityViewState(
    val activity: DataResource<Map<TransactionGroup, List<TransactionState>>>
) : ViewState

data class TransactionState(
    val transactionTypeIcon: String,
    val transactionCoinIcon: String?,
    val status: TransactionStatus,
    val valueTopStart: String,
    val valueTopEnd: String,
    val valueBottomStart: String?,
    val valueBottomEnd: String?,
)

sealed interface TransactionGroup {
    val name: String

    object Combined : TransactionGroup {
        override val name get() = error("not allowed")
    }

    data class Group(override val name: String) : TransactionGroup
}

sealed interface TransactionStatus {
    data class Pending(val isRbfTransaction: Boolean = false) : TransactionStatus
    object Confirmed : TransactionStatus
    object Canceled : TransactionStatus
    object Declined : TransactionStatus
    object Failed : TransactionStatus
}
