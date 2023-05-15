package com.blockchain.transactions.swap.confirmation

import androidx.lifecycle.viewModelScope
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.CryptoAccount
import com.blockchain.coincore.NonCustodialAccount
import com.blockchain.coincore.PendingTx
import com.blockchain.coincore.impl.CryptoNonCustodialAccount
import com.blockchain.coincore.impl.makeExternalAssetAddress
import com.blockchain.coincore.impl.txEngine.OnChainTxEngineBase
import com.blockchain.coincore.toUserFiat
import com.blockchain.commonarch.presentation.mvi_v2.ModelConfigArgs
import com.blockchain.commonarch.presentation.mvi_v2.MviViewModel
import com.blockchain.commonarch.presentation.mvi_v2.NavigationEvent
import com.blockchain.componentlib.button.ButtonState
import com.blockchain.core.custodial.BrokerageDataManager
import com.blockchain.core.custodial.data.store.TradingStore
import com.blockchain.core.price.ExchangeRatesDataManager
import com.blockchain.data.doOnData
import com.blockchain.domain.common.model.toSeconds
import com.blockchain.domain.transactions.TransferDirection
import com.blockchain.extensions.safeLet
import com.blockchain.nabu.datamanagers.CustodialOrder
import com.blockchain.nabu.datamanagers.CustodialWalletManager
import com.blockchain.nabu.datamanagers.Product
import com.blockchain.nabu.datamanagers.repositories.swap.SwapTransactionsStore
import com.blockchain.outcome.Outcome
import com.blockchain.outcome.doOnFailure
import com.blockchain.outcome.doOnSuccess
import com.blockchain.outcome.flatMap
import com.blockchain.outcome.map
import com.blockchain.outcome.zipOutcomes
import com.blockchain.transactions.swap.neworderstate.composable.NewOrderState
import com.blockchain.transactions.swap.neworderstate.composable.NewOrderStateArgs
import com.blockchain.utils.awaitOutcome
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.CurrencyPair
import info.blockchain.balance.ExchangeRate
import info.blockchain.balance.FiatValue
import io.reactivex.rxjava3.core.Completable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface ConfirmationNavigation : NavigationEvent {
    data class NewOrderState(val args: NewOrderStateArgs) : ConfirmationNavigation
}

class ConfirmationViewModel(
    private val confirmationArgs: SwapConfirmationArgs,
    private val brokerageDataManager: BrokerageDataManager,
    private val exchangeRatesDataManager: ExchangeRatesDataManager,
    private val custodialWalletManager: CustodialWalletManager,
    private val swapTransactionsStore: SwapTransactionsStore,
    private val tradingStore: TradingStore
) : MviViewModel<
    ConfirmationIntent,
    ConfirmationViewState,
    ConfirmationModelState,
    ConfirmationNavigation,
    ModelConfigArgs.NoArgs
    >(
    ConfirmationModelState()
) {
    private val sourceAccount: CryptoAccount
        get() = confirmationArgs.sourceAccount
    private val targetAccount: CryptoAccount
        get() = confirmationArgs.targetAccount
    private val sourceCryptoAmount: CryptoValue
        get() = confirmationArgs.sourceCryptoAmount
    private val secondPassword: String?
        get() = confirmationArgs.secondPassword

    private var quoteRefreshingJob: Job? = null

    private lateinit var depositTxEngine: OnChainTxEngineBase
    private lateinit var depositPendingTx: PendingTx

    init {
        // Convert Source Crypto Amount to Fiat
        viewModelScope.launch {
            exchangeRatesDataManager.exchangeRateToUserFiatFlow(sourceAccount.currency)
                .doOnData { rate ->
                    updateState {
                        it.copy(sourceToFiatExchangeRate = rate)
                    }
                }
                .collect()
        }

        // Convert Target Crypto Amount to Fiat
        viewModelScope.launch {
            exchangeRatesDataManager.exchangeRateToUserFiatFlow(targetAccount.currency)
                .doOnData { rate ->
                    updateState { it.copy(targetToFiatExchangeRate = rate) }
                }
                .collect()
        }

        startQuoteRefreshing()

        viewModelScope.launch {
            val sourceAccount = sourceAccount
            if (sourceAccount is CryptoNonCustodialAccount) {
                depositTxEngine = sourceAccount.createTxEngine(targetAccount, AssetAction.Swap) as OnChainTxEngineBase
                custodialWalletManager.getCustodialAccountAddress(Product.TRADE, sourceAccount.currency)
                    .awaitOutcome()
                    .flatMap { sampleDepositAddress ->
                        depositTxEngine.start(
                            sourceAccount = sourceAccount,
                            txTarget = makeExternalAssetAddress(
                                asset = sourceAccount.currency,
                                address = sampleDepositAddress
                            ),
                            exchangeRates = exchangeRatesDataManager
                        )
                        depositTxEngine.doInitialiseTx().awaitOutcome()
                    }.flatMap { pendingTx ->
                        depositTxEngine.doUpdateAmount(sourceCryptoAmount, pendingTx).awaitOutcome()
                    }.doOnSuccess { pendingTx ->
                        depositPendingTx = pendingTx
                        updateState {
                            val sourceFee = pendingTx.feeAmount as? CryptoValue
                            it.copy(
                                isStartingDepositOnChainTxEngine = false,
                                sourceNetworkFeeCryptoAmount = sourceFee
                            )
                        }
                    }.doOnFailure { error ->
                        navigate(ConfirmationNavigation.NewOrderState(error.toNewOrderStateArgs()))
                        quoteRefreshingJob?.cancel()
                    }
            }
        }
    }

    override fun viewCreated(args: ModelConfigArgs.NoArgs) {}

    private fun startQuoteRefreshing() {
        quoteRefreshingJob = viewModelScope.launch {
            val thisScope = this
            var secondsUntilQuoteRefresh = 0
            // Looping to update the quote refresh timer and to fetch another quote when the current expires
            while (true) {
                if (secondsUntilQuoteRefresh <= 0) {
                    updateState { it.copy(isFetchQuoteLoading = true) }
                    val pair = CurrencyPair(
                        source = sourceAccount.currency,
                        destination = targetAccount.currency
                    )
                    brokerageDataManager
                        .getSwapQuote(
                            pair = pair,
                            amount = sourceCryptoAmount,
                            direction = transferDirection
                        )
                        .doOnSuccess { quote ->
                            val targetCryptoAmount = quote.resultAmount as CryptoValue
                            secondsUntilQuoteRefresh = (quote.expiresAt - System.currentTimeMillis()).toSeconds()
                                .coerceIn(0, 90)
                                .toInt()
                            updateState {
                                it.copy(
                                    quoteId = quote.id,
                                    isFetchQuoteLoading = false,
                                    targetCryptoAmount = targetCryptoAmount,
                                    quoteRefreshTotalSeconds = secondsUntilQuoteRefresh,
                                    sourceToTargetExchangeRate = ExchangeRate(
                                        rate = quote.rawPrice.toBigDecimal(),
                                        from = sourceAccount.currency,
                                        to = targetAccount.currency
                                    ),
                                    targetNetworkFeeCryptoAmount = quote.networkFee as CryptoValue?
                                )
                            }
                        }
                        .doOnFailure { error ->
                            navigate(ConfirmationNavigation.NewOrderState(error.toNewOrderStateArgs()))
                            // Quote errors are terminal, we'll show an UxError with actions or a
                            // regular error which will navigate the user out of the Swap flow
                            thisScope.cancel()
                        }
                }
                updateState { it.copy(quoteRefreshRemainingSeconds = secondsUntilQuoteRefresh) }
                delay(1_000)
                secondsUntilQuoteRefresh--
            }
        }
    }

    override fun reduce(state: ConfirmationModelState): ConfirmationViewState = ConfirmationViewState(
        isFetchQuoteLoading = state.isFetchQuoteLoading,
        sourceAsset = sourceAccount.currency,
        targetAsset = targetAccount.currency,
        sourceCryptoAmount = sourceCryptoAmount,
        sourceFiatAmount = sourceCryptoAmount.toUserFiat(),
        targetCryptoAmount = state.targetCryptoAmount,
        targetFiatAmount = state.targetCryptoAmount?.toUserFiat(),
        sourceToTargetExchangeRate = state.sourceToTargetExchangeRate,
        sourceNetworkFeeCryptoAmount = state.sourceNetworkFeeCryptoAmount,
        sourceNetworkFeeFiatAmount = state.sourceNetworkFeeCryptoAmount?.toUserFiat(),
        targetNetworkFeeCryptoAmount = state.targetNetworkFeeCryptoAmount,
        targetNetworkFeeFiatAmount = state.targetNetworkFeeCryptoAmount?.toUserFiat(),
        quoteRefreshRemainingPercentage = safeLet(
            state.quoteRefreshRemainingSeconds,
            state.quoteRefreshTotalSeconds
        ) { remaining, total ->
            remaining.toFloat() / total.toFloat()
        },
        quoteRefreshRemainingSeconds = state.quoteRefreshRemainingSeconds,
        submitButtonState = when {
            state.isFetchQuoteLoading || state.isStartingDepositOnChainTxEngine -> ButtonState.Disabled
            state.isSubmittingOrderLoading -> ButtonState.Loading
            else -> ButtonState.Enabled
        }
    )

    override suspend fun handleIntent(modelState: ConfirmationModelState, intent: ConfirmationIntent) {
        when (intent) {
            ConfirmationIntent.SubmitClicked -> {
                updateState { it.copy(isSubmittingOrderLoading = true) }
                val quoteId = modelState.quoteId!!

                // NC->NC
                val requiresDestinationAddress = transferDirection == TransferDirection.ON_CHAIN
                // NC->NC or NC->C
                val requireRefundAddress = transferDirection == TransferDirection.ON_CHAIN ||
                    transferDirection == TransferDirection.FROM_USERKEY

                // TODO(aromano): SWAP
                //                if (requireSecondPassword && secondPassword.isEmpty()) {
                //                    throw IllegalArgumentException("Second password not supplied")
                //                }

                zipOutcomes(
                    sourceAccount.receiveAddress::awaitOutcome,
                    targetAccount.receiveAddress::awaitOutcome
                ).flatMap { (sourceAddress, targetAddress) ->
                    custodialWalletManager.createCustodialOrder(
                        direction = transferDirection,
                        quoteId = quoteId,
                        volume = sourceCryptoAmount,
                        destinationAddress = if (requiresDestinationAddress) targetAddress.address else null,
                        refundAddress = if (requireRefundAddress) sourceAddress.address else null
                    ).awaitOutcome()
                }.flatMap { order ->
                    if (sourceAccount is NonCustodialAccount) {
                        submitDepositTx(order)
                    } else Outcome.Success(order)
                }.doOnSuccess { order ->
                    quoteRefreshingJob?.cancel()
                    swapTransactionsStore.invalidate()
                    tradingStore.invalidate()
                    // TODO(aromano): SWAP ANALYTICS
                    //                    analyticsHooks.onTransactionSuccess(newState)

                    val newOrderStateArgs = NewOrderStateArgs(
                        sourceAmount = order.inputMoney as CryptoValue,
                        targetAmount = order.outputMoney as CryptoValue,
                        orderState = if (sourceAccount is NonCustodialAccount) {
                            NewOrderState.PendingDeposit
                        } else {
                            NewOrderState.Succeeded
                        }
                    )
                    navigate(ConfirmationNavigation.NewOrderState(newOrderStateArgs))
                }.doOnFailure { error ->
                    navigate(ConfirmationNavigation.NewOrderState(error.toNewOrderStateArgs()))
                }
                updateState { it.copy(isSubmittingOrderLoading = false) }
            }
        }
    }

    private suspend fun submitDepositTx(order: CustodialOrder): Outcome<Exception, CustodialOrder> {
        val depositAddress =
            order.depositAddress ?: return Outcome.Failure(IllegalStateException("Missing deposit address"))
        return depositTxEngine.restart(
            txTarget = makeExternalAssetAddress(
                asset = sourceAccount.currency,
                address = depositAddress,
                postTransactions = { Completable.complete() }
            ),
            pendingTx = depositPendingTx
        ).awaitOutcome()
            .flatMap { pendingTx ->
                val depositTxResult = depositTxEngine.doExecute(pendingTx, secondPassword.orEmpty()).awaitOutcome()
                // intentionally ignoring result
                custodialWalletManager.updateOrder(order.id, depositTxResult is Outcome.Success).awaitOutcome()
                depositTxResult.flatMap { txResult ->
                    depositTxEngine.doPostExecute(pendingTx, txResult).awaitOutcome()
                }.doOnSuccess {
                    depositTxEngine.doOnTransactionComplete()
                }.map { order }
            }
    }

    private val transferDirection: TransferDirection
        get() = when {
            sourceAccount is NonCustodialAccount &&
                targetAccount is NonCustodialAccount -> {
                TransferDirection.ON_CHAIN
            }
            sourceAccount is NonCustodialAccount -> {
                TransferDirection.FROM_USERKEY
            }
            // TransferDirection.FROM_USERKEY not supported
            targetAccount is NonCustodialAccount -> {
                throw UnsupportedOperationException()
            }
            else -> {
                TransferDirection.INTERNAL
            }
        }

    private fun Exception.toNewOrderStateArgs(): NewOrderStateArgs = NewOrderStateArgs(
        sourceAmount = sourceCryptoAmount,
        targetAmount = modelState.targetCryptoAmount ?: CryptoValue.zero(targetAccount.currency),
        orderState = NewOrderState.Error(this)
    )

    private fun CryptoValue.toUserFiat(): FiatValue = this.toUserFiat(exchangeRatesDataManager) as FiatValue
}
