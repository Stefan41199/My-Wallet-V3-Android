package com.blockchain.transactions.swap.selecttarget

import com.blockchain.commonarch.presentation.mvi_v2.ViewState
import com.blockchain.componentlib.tablerow.BalanceChange
import com.blockchain.data.DataResource
import com.blockchain.walletmode.WalletMode

data class SelectTargetViewState(
    val showModeFilter: Boolean,
    val selectedModeFilter: WalletMode?,
    val prices: DataResource<List<BalanceChange>>
) : ViewState
