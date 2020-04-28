package piuk.blockchain.android.ui.activity.detail

import com.blockchain.preferences.CurrencyPrefs
import info.blockchain.balance.CryptoCurrency
import io.reactivex.Single
import piuk.blockchain.android.coincore.Coincore
import piuk.blockchain.android.coincore.NonCustodialActivitySummaryItem
import java.util.Date

class ActivityDetailsInteractor(
    private val coincore: Coincore,
    private val currencyPrefs: CurrencyPrefs,
    private val transactionInputOutputMapper: TransactionInOutMapper
) {

    fun getActivityDetails(
        cryptoCurrency: CryptoCurrency,
        txHash: String
    ): Single<NonCustodialActivitySummaryItem> {
        return Single.just(coincore[cryptoCurrency].findCachedActivityItem(
            txHash) as? NonCustodialActivitySummaryItem)
    }

    fun loadCreationDate(
        nonCustodialActivitySummaryItem: NonCustodialActivitySummaryItem
    ): Single<Date> = Single.just(Date(nonCustodialActivitySummaryItem.timeStampMs))

    fun loadConfirmedItems(
        item: NonCustodialActivitySummaryItem
    ): Single<List<ActivityDetailsType>> {
        val list = mutableListOf<ActivityDetailsType>()
        list.add(Amount(item.totalCrypto))
        return item.fee.singleOrError().flatMap { cryptoValue ->
            list.add(Fee(cryptoValue))
            item.totalFiatWhenExecuted(currencyPrefs.selectedFiatCurrency).flatMap { fiatValue ->
                list.add(Value(fiatValue))
                transactionInputOutputMapper.transformInputAndOutputs(item).map {
                    addSingleOrMultipleAddresses(it, list)
                    list.add(Description())
                    list.add(Action())
                    list
                }
            }
        }
    }

    private fun addSingleOrMultipleAddresses(
        it: TransactionInOutDetails,
        list: MutableList<ActivityDetailsType>
    ) {
        if (it.outputs.size == 1) {
            list.add(To(it.outputs[0].address))
        } else {
            var addresses = ""
            it.outputs.forEach { address -> addresses += "$address\n" }
            list.add(To(addresses))
        }
        if (it.inputs.size == 1) {
            list.add(From(it.inputs[0].address))
        } else {
            var addresses = ""
            it.inputs.forEach { address -> addresses += "$address\n" }
            list.add(From(addresses))
        }
    }
}