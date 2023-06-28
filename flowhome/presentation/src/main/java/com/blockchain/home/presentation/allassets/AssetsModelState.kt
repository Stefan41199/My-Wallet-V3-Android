package com.blockchain.home.presentation.allassets

import com.blockchain.commonarch.presentation.mvi_v2.ModelState
import com.blockchain.data.DataResource
import com.blockchain.data.map
import com.blockchain.data.merge
import com.blockchain.domain.paymentmethods.model.FundsLocks
import com.blockchain.home.domain.AssetBalance
import com.blockchain.home.domain.AssetFilter
import com.blockchain.home.domain.SingleAccountBalance
import com.blockchain.home.presentation.SectionSize
import com.blockchain.walletmode.WalletMode
import info.blockchain.balance.FiatCurrency
import info.blockchain.balance.Money
import info.blockchain.balance.total

data class AssetsModelState(
    val accounts: DataResource<List<SingleAccountBalance>> = DataResource.Loading,
    val fundsLocks: DataResource<FundsLocks?> = DataResource.Loading,
    val walletMode: WalletMode,
    private val _accountsForMode: MutableMap<WalletMode, DataResource<List<SingleAccountBalance>>> = mutableMapOf(),
    val sectionSize: SectionSize = SectionSize.All,
    val userFiat: FiatCurrency,
    val filters: List<AssetFilter> = listOf(),
    val lastFreshDataTime: Long = 0
) : ModelState {
    init {
        _accountsForMode[walletMode] = accounts
    }

    fun accountsForMode(walletMode: WalletMode): DataResource<List<SingleAccountBalance>> =
        _accountsForMode[walletMode] ?: DataResource.Loading

    val assets: DataResource<List<AssetBalance>>
        get() {
            return accounts.map {
                it.groupBy { it.singleAccount.currency.networkTicker }
                    .values
                    .mapNotNull {
                        AssetBalance(
                            singleAccount = it.firstOrNull()?.singleAccount ?: return@mapNotNull null,
                            balance = it.map { acc -> acc.balance }.sumAvailableBalances(),
                            fiatBalance = it.map { acc -> acc.fiatBalance }.sumAvailableBalances(),
                            majorCurrencyBalance = it.map { acc -> acc.usdBalance }.sumAvailableBalances(),
                            exchangeRate24hWithDelta = it.firstOrNull()?.exchangeRate24hWithDelta
                                ?: return@mapNotNull null
                        )
                    }
            }
        }
}

private fun List<DataResource<Money>>.sumAvailableBalances(): DataResource<Money> {
    return merge { it.total() }
}
