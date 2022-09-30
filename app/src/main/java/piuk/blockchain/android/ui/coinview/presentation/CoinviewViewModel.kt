package piuk.blockchain.android.ui.coinview.presentation

import androidx.lifecycle.viewModelScope
import com.blockchain.charts.ChartEntry
import com.blockchain.coincore.AccountGroup
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.AssetFilter
import com.blockchain.coincore.Coincore
import com.blockchain.coincore.CryptoAccount
import com.blockchain.coincore.CryptoAsset
import com.blockchain.coincore.eth.MultiChainAccount
import com.blockchain.coincore.selectFirstAccount
import com.blockchain.commonarch.presentation.mvi_v2.MviViewModel
import com.blockchain.core.asset.domain.AssetService
import com.blockchain.core.price.HistoricalTimeSpan
import com.blockchain.data.DataResource
import com.blockchain.data.doOnData
import com.blockchain.data.doOnError
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.utils.toFormattedDateWithoutYear
import com.blockchain.wallet.DefaultLabels
import com.blockchain.walletmode.WalletMode
import com.blockchain.walletmode.WalletModeService
import com.github.mikephil.charting.data.Entry
import info.blockchain.balance.FiatCurrency
import info.blockchain.balance.Money
import java.text.DecimalFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import piuk.blockchain.android.R
import piuk.blockchain.android.simplebuy.toHumanReadableRecurringBuy
import piuk.blockchain.android.ui.coinview.domain.GetAccountActionsUseCase
import piuk.blockchain.android.ui.coinview.domain.GetAssetPriceUseCase
import piuk.blockchain.android.ui.coinview.domain.LoadAssetAccountsUseCase
import piuk.blockchain.android.ui.coinview.domain.LoadAssetRecurringBuysUseCase
import piuk.blockchain.android.ui.coinview.domain.LoadQuickActionsUseCase
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewAccount
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewAccounts
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewAssetDetail
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewAssetPrice
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewAssetPriceHistory
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewAssetTotalBalance
import piuk.blockchain.android.ui.coinview.domain.model.CoinviewQuickAction
import piuk.blockchain.android.ui.coinview.presentation.CoinviewAccountsState.Data.CoinviewAccountState.Available
import piuk.blockchain.android.ui.coinview.presentation.CoinviewAccountsState.Data.CoinviewAccountState.Unavailable
import piuk.blockchain.android.ui.coinview.presentation.CoinviewAccountsState.Data.CoinviewAccountsHeaderState
import piuk.blockchain.android.ui.coinview.presentation.CoinviewRecurringBuysState.Data.CoinviewRecurringBuyState

class CoinviewViewModel(
    walletModeService: WalletModeService,
    private val coincore: Coincore,
    private val currencyPrefs: CurrencyPrefs,
    private val labels: DefaultLabels,

    private val getAssetPriceUseCase: GetAssetPriceUseCase,

    private val loadAssetAccountsUseCase: LoadAssetAccountsUseCase,
    private val getAccountActionsUseCase: GetAccountActionsUseCase,

    private val loadAssetRecurringBuysUseCase: LoadAssetRecurringBuysUseCase,

    private val loadQuickActionsUseCase: LoadQuickActionsUseCase,

    private val assetService: AssetService
) : MviViewModel<
    CoinviewIntent,
    CoinviewViewState,
    CoinviewModelState,
    CoinviewNavigationEvent,
    CoinviewArgs>(CoinviewModelState(walletMode = walletModeService.enabledWalletMode())) {

    companion object {
        const val SNACKBAR_MESSAGE_DURATION: Long = 3000L
    }

    private var loadPriceDataJob: Job? = null
    private var accountActionsJob: Job? = null
    private var snackbarMessageJob: Job? = null

    private val fiatCurrency: FiatCurrency
        get() = currencyPrefs.selectedFiatCurrency

    private val defaultTimeSpan = HistoricalTimeSpan.DAY

    override fun viewCreated(args: CoinviewArgs) {
        (coincore[args.networkTicker] as? CryptoAsset)?.let { asset ->
            updateState {
                it.copy(
                    asset = asset
                )
            }
        } ?: error("asset ${args.networkTicker} not found")
    }

    override fun reduce(state: CoinviewModelState): CoinviewViewState = state.run {
        CoinviewViewState(
            assetName = asset?.currency?.name ?: "",
            assetPrice = reduceAssetPrice(this),
            totalBalance = reduceTotalBalance(this),
            accounts = reduceAccounts(this),
            centerQuickAction = reduceCenterQuickActions(this),
            recurringBuys = reduceRecurringBuys(this),
            bottomQuickAction = reduceBottomQuickActions(this),
            assetInfo = reduceAssetInfo(this),

            snackbarError = reduceSnackbarError(this)
        )
    }

    private fun reduceAssetPrice(state: CoinviewModelState): CoinviewPriceState = state.run {
        when (assetPriceHistory) {
            DataResource.Loading -> {
                CoinviewPriceState.Loading
            }

            is DataResource.Error -> {
                CoinviewPriceState.Error
            }

            is DataResource.Data -> {
                // price, priceChange, percentChange
                // will contain values from interactiveAssetPrice to correspond with user interaction

                // intervalName will be empty if user is interacting with the chart

                check(asset != null) { "asset not initialized" }

                with(assetPriceHistory.data) {
                    CoinviewPriceState.Data(
                        assetName = asset.currency.name,
                        assetLogo = asset.currency.logo,
                        fiatSymbol = fiatCurrency.symbol,
                        price = (interactiveAssetPrice ?: priceDetail)
                            .price.toStringWithSymbol(),
                        priceChange = (interactiveAssetPrice ?: priceDetail)
                            .changeDifference.toStringWithSymbol(),
                        percentChange = (interactiveAssetPrice ?: priceDetail).percentChange,
                        intervalName = if (interactiveAssetPrice != null) R.string.empty else
                            when ((priceDetail).timeSpan) {
                                HistoricalTimeSpan.DAY -> R.string.coinview_price_day
                                HistoricalTimeSpan.WEEK -> R.string.coinview_price_week
                                HistoricalTimeSpan.MONTH -> R.string.coinview_price_month
                                HistoricalTimeSpan.YEAR -> R.string.coinview_price_year
                                HistoricalTimeSpan.ALL_TIME -> R.string.coinview_price_all
                            },
                        chartData = when {
                            isChartDataLoading &&
                                requestedTimeSpan != null &&
                                priceDetail.timeSpan != requestedTimeSpan -> {
                                // show chart loading when data is loading and a new timespan is selected
                                CoinviewPriceState.Data.CoinviewChartState.Loading
                            }
                            else -> CoinviewPriceState.Data.CoinviewChartState.Data(
                                historicRates.map { point ->
                                    ChartEntry(
                                        point.timestamp.toFloat(),
                                        point.rate.toFloat()
                                    )
                                }
                            )
                        },
                        selectedTimeSpan = (interactiveAssetPrice ?: priceDetail).timeSpan
                    )
                }
            }
        }
    }

    private fun reduceTotalBalance(state: CoinviewModelState): CoinviewTotalBalanceState = state.run {
        when {
            // not supported for non custodial
            walletMode == WalletMode.NON_CUSTODIAL_ONLY -> {
                CoinviewTotalBalanceState.NotSupported
            }

            assetDetail is DataResource.Loading -> {
                CoinviewTotalBalanceState.Loading
            }

            assetDetail is DataResource.Error -> {
                CoinviewTotalBalanceState.NotSupported
            }

            assetDetail is DataResource.Data && assetDetail.data is CoinviewAssetDetail.Tradeable -> {
                check(asset != null) { "asset not initialized" }

                with(assetDetail.data as CoinviewAssetDetail.Tradeable) {
                    check(totalBalance.totalCryptoBalance.containsKey(AssetFilter.All)) { "balance not initialized" }

                    CoinviewTotalBalanceState.Data(
                        assetName = asset.currency.name,
                        totalFiatBalance = totalBalance.totalFiatBalance.toStringWithSymbol(),
                        totalCryptoBalance = totalBalance.totalCryptoBalance[AssetFilter.All]!!.toStringWithSymbol()
                    )
                }
            }

            else -> {
                CoinviewTotalBalanceState.Loading
            }
        }
    }

    private fun reduceAccounts(state: CoinviewModelState): CoinviewAccountsState = state.run {
        when {
            assetDetail is DataResource.Loading -> {
                CoinviewAccountsState.Loading
            }

            assetDetail is DataResource.Data && assetDetail.data is CoinviewAssetDetail.Tradeable -> {
                check(asset != null) { "asset not initialized" }

                with(assetDetail.data as CoinviewAssetDetail.Tradeable) {
                    CoinviewAccountsState.Data(
                        style = when (accounts) {
                            is CoinviewAccounts.Universal,
                            is CoinviewAccounts.Custodial -> CoinviewAccountsStyle.Simple
                            is CoinviewAccounts.Defi -> CoinviewAccountsStyle.Boxed
                        },
                        header = when (accounts) {
                            is CoinviewAccounts.Universal,
                            is CoinviewAccounts.Custodial -> CoinviewAccountsHeaderState.ShowHeader(
                                SimpleValue.IntResValue(R.string.coinview_accounts_label)
                            )
                            is CoinviewAccounts.Defi -> CoinviewAccountsHeaderState.NoHeader
                        },
                        accounts = accounts.accounts.map { cvAccount ->
                            val account: CryptoAccount = cvAccount.account.let { blockchainAccount ->
                                when (blockchainAccount) {
                                    is CryptoAccount -> blockchainAccount
                                    is AccountGroup -> blockchainAccount.selectFirstAccount()
                                    else -> throw IllegalStateException(
                                        "Unsupported account type for asset details ${cvAccount.account}"
                                    )
                                }
                            }

                            when (cvAccount.isEnabled) {
                                true -> {
                                    when (cvAccount) {
                                        is CoinviewAccount.Universal -> {
                                            Available(
                                                cvAccount = cvAccount,
                                                title = when (cvAccount.filter) {
                                                    AssetFilter.Trading -> labels.getDefaultCustodialWalletLabel()
                                                    AssetFilter.Interest -> labels.getDefaultInterestWalletLabel()
                                                    AssetFilter.NonCustodial -> account.label
                                                    else -> error(
                                                        "Filer ${cvAccount.filter} not supported for account label"
                                                    )
                                                },
                                                subtitle = when (cvAccount.filter) {
                                                    AssetFilter.Trading -> {
                                                        SimpleValue.IntResValue(R.string.coinview_c_available_desc)
                                                    }
                                                    AssetFilter.Interest -> {
                                                        SimpleValue.IntResValue(
                                                            R.string.coinview_interest_with_balance,
                                                            listOf(DecimalFormat("0.#").format(cvAccount.interestRate))
                                                        )
                                                    }
                                                    AssetFilter.NonCustodial -> {
                                                        if (account is MultiChainAccount) {
                                                            SimpleValue.IntResValue(
                                                                R.string.coinview_multi_nc_desc,
                                                                listOf(account.l1Network.networkName)
                                                            )
                                                        } else {
                                                            SimpleValue.IntResValue(R.string.coinview_nc_desc)
                                                        }
                                                    }
                                                    else -> error("${cvAccount.filter} Not a supported filter")
                                                },
                                                cryptoBalance = cvAccount.cryptoBalance.toStringWithSymbol(),
                                                fiatBalance = cvAccount.fiatBalance.toStringWithSymbol(),
                                                logo = LogoSource.Resource(
                                                    when (cvAccount.filter) {
                                                        AssetFilter.Trading -> {
                                                            R.drawable.ic_custodial_account_indicator
                                                        }
                                                        AssetFilter.Interest -> {
                                                            R.drawable.ic_interest_account_indicator
                                                        }
                                                        AssetFilter.NonCustodial -> {
                                                            R.drawable.ic_non_custodial_account_indicator
                                                        }
                                                        else -> error("${cvAccount.filter} Not a supported filter")
                                                    }
                                                ),
                                                assetColor = asset.currency.colour
                                            )
                                        }
                                        is CoinviewAccount.Custodial.Trading -> {
                                            Available(
                                                cvAccount = cvAccount,
                                                title = labels.getDefaultCustodialWalletLabel(),
                                                subtitle = SimpleValue.IntResValue(R.string.coinview_c_available_desc),
                                                cryptoBalance = cvAccount.cryptoBalance.toStringWithSymbol(),
                                                fiatBalance = cvAccount.fiatBalance.toStringWithSymbol(),
                                                logo = LogoSource.Resource(R.drawable.ic_custodial_account_indicator),
                                                assetColor = asset.currency.colour
                                            )
                                        }
                                        is CoinviewAccount.Custodial.Interest -> {
                                            Available(
                                                cvAccount = cvAccount,
                                                title = labels.getDefaultInterestWalletLabel(),
                                                subtitle = SimpleValue.IntResValue(
                                                    R.string.coinview_interest_with_balance,
                                                    listOf(DecimalFormat("0.#").format(cvAccount.interestRate))
                                                ),
                                                cryptoBalance = cvAccount.cryptoBalance.toStringWithSymbol(),
                                                fiatBalance = cvAccount.fiatBalance.toStringWithSymbol(),
                                                logo = LogoSource.Resource(R.drawable.ic_interest_account_indicator),
                                                assetColor = asset.currency.colour
                                            )
                                        }
                                        is CoinviewAccount.Defi -> {
                                            Available(
                                                cvAccount = cvAccount,
                                                title = account.label,
                                                subtitle = SimpleValue.StringValue(account.currency.displayTicker),
                                                cryptoBalance = cvAccount.cryptoBalance.toStringWithSymbol(),
                                                fiatBalance = cvAccount.fiatBalance.toStringWithSymbol(),
                                                logo = LogoSource.Remote(account.currency.logo),
                                                assetColor = asset.currency.colour
                                            )
                                        }
                                    }
                                }

                                false -> {
                                    when (cvAccount) {
                                        is CoinviewAccount.Universal -> {
                                            Unavailable(
                                                cvAccount = cvAccount,
                                                title = when (cvAccount.filter) {
                                                    AssetFilter.Trading -> labels.getDefaultCustodialWalletLabel()
                                                    AssetFilter.Interest -> labels.getDefaultInterestWalletLabel()
                                                    AssetFilter.NonCustodial -> account.label
                                                    else -> error(
                                                        "Filer ${cvAccount.filter} not supported for account label"
                                                    )
                                                },
                                                subtitle = when (cvAccount.filter) {
                                                    AssetFilter.Trading -> {
                                                        SimpleValue.IntResValue(
                                                            R.string.coinview_c_unavailable_desc,
                                                            listOf(asset.currency.name)
                                                        )
                                                    }
                                                    AssetFilter.Interest -> {
                                                        SimpleValue.IntResValue(
                                                            R.string.coinview_interest_no_balance,
                                                            listOf(DecimalFormat("0.#").format(cvAccount.interestRate))
                                                        )
                                                    }
                                                    AssetFilter.NonCustodial -> {
                                                        SimpleValue.IntResValue(R.string.coinview_nc_desc)
                                                    }
                                                    else -> error("${cvAccount.filter} Not a supported filter")
                                                },
                                                logo = LogoSource.Resource(
                                                    when (cvAccount.filter) {
                                                        AssetFilter.Trading -> {
                                                            R.drawable.ic_custodial_account_indicator
                                                        }
                                                        AssetFilter.Interest -> {
                                                            R.drawable.ic_interest_account_indicator
                                                        }
                                                        AssetFilter.NonCustodial -> {
                                                            R.drawable.ic_non_custodial_account_indicator
                                                        }
                                                        else -> error("${cvAccount.filter} Not a supported filter")
                                                    }
                                                )
                                            )
                                        }
                                        is CoinviewAccount.Custodial.Trading -> {
                                            Unavailable(
                                                cvAccount = cvAccount,
                                                title = labels.getDefaultCustodialWalletLabel(),
                                                subtitle = SimpleValue.IntResValue(
                                                    R.string.coinview_c_unavailable_desc,
                                                    listOf(asset.currency.name)
                                                ),
                                                logo = LogoSource.Resource(R.drawable.ic_custodial_account_indicator)
                                            )
                                        }
                                        is CoinviewAccount.Custodial.Interest -> {
                                            Unavailable(
                                                cvAccount = cvAccount,
                                                title = labels.getDefaultInterestWalletLabel(),
                                                subtitle = SimpleValue.IntResValue(
                                                    R.string.coinview_interest_no_balance,
                                                    listOf(DecimalFormat("0.#").format(cvAccount.interestRate))
                                                ),
                                                logo = LogoSource.Resource(R.drawable.ic_interest_account_indicator)
                                            )
                                        }
                                        is CoinviewAccount.Defi -> {
                                            Unavailable(
                                                cvAccount = cvAccount,
                                                title = account.currency.name,
                                                subtitle = SimpleValue.IntResValue(R.string.coinview_nc_desc),
                                                logo = LogoSource.Remote(account.currency.logo)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            else -> {
                CoinviewAccountsState.Loading
            }
        }
    }

    private fun reduceRecurringBuys(state: CoinviewModelState): CoinviewRecurringBuysState = state.run {
        when {
            // not supported for non custodial
            walletMode == WalletMode.NON_CUSTODIAL_ONLY -> {
                CoinviewRecurringBuysState.NotSupported
            }

            recurringBuys is DataResource.Loading -> {
                CoinviewRecurringBuysState.Loading
            }

            recurringBuys is DataResource.Error -> {
                CoinviewRecurringBuysState.Error
            }

            recurringBuys is DataResource.Data -> {
                check(asset != null) { "asset not initialized" }

                with(recurringBuys.data) {
                    when {
                        data.isEmpty() && isAvailableForTrading -> {
                            CoinviewRecurringBuysState.Upsell
                        }

                        data.isEmpty() && isAvailableForTrading.not() -> {
                            CoinviewRecurringBuysState.NotSupported
                        }

                        else -> CoinviewRecurringBuysState.Data(
                            data.map { recurringBuy ->
                                CoinviewRecurringBuyState(
                                    id = recurringBuy.id,
                                    description = SimpleValue.IntResValue(
                                        R.string.dashboard_recurring_buy_item_title_1,
                                        listOf(
                                            recurringBuy.amount.toStringWithSymbol(),
                                            recurringBuy.recurringBuyFrequency.toHumanReadableRecurringBuy()
                                        )
                                    ),

                                    status = if (recurringBuy.state ==
                                        com.blockchain.nabu.models.data.RecurringBuyState.ACTIVE
                                    ) {
                                        SimpleValue.IntResValue(
                                            R.string.dashboard_recurring_buy_item_label,
                                            listOf(recurringBuy.nextPaymentDate.toFormattedDateWithoutYear())
                                        )
                                    } else {
                                        SimpleValue.IntResValue(
                                            R.string.dashboard_recurring_buy_item_label_error
                                        )
                                    },

                                    assetColor = asset.currency.colour
                                )
                            }
                        )
                    }
                }
            }

            else -> {
                CoinviewRecurringBuysState.Loading
            }
        }
    }

    private fun reduceCenterQuickActions(state: CoinviewModelState): CoinviewCenterQuickActionsState = state.run {
        when (quickActions) {
            DataResource.Loading -> {
                CoinviewCenterQuickActionsState.Loading
            }

            is DataResource.Error -> {
                CoinviewCenterQuickActionsState.Data(
                    center = CoinviewQuickAction.None.toViewState()
                )
            }

            is DataResource.Data -> {
                with(quickActions.data) {
                    CoinviewCenterQuickActionsState.Data(
                        center = center.toViewState()
                    )
                }
            }
        }
    }

    private fun reduceBottomQuickActions(state: CoinviewModelState): CoinviewBottomQuickActionsState = state.run {
        when (quickActions) {
            DataResource.Loading -> {
                CoinviewBottomQuickActionsState.Loading
            }

            is DataResource.Error -> {
                CoinviewBottomQuickActionsState.Data(
                    start = CoinviewQuickAction.None.toViewState(),
                    end = CoinviewQuickAction.None.toViewState()
                )
            }

            is DataResource.Data -> {
                with(quickActions.data) {
                    CoinviewBottomQuickActionsState.Data(
                        start = bottomStart.toViewState(),
                        end = bottomEnd.toViewState()
                    )
                }
            }
        }
    }

    private fun reduceAssetInfo(state: CoinviewModelState): CoinviewAssetInfoState = state.run {
        when (assetInfo) {
            DataResource.Loading -> {
                CoinviewAssetInfoState.Loading
            }

            is DataResource.Error -> {
                CoinviewAssetInfoState.Error
            }

            is DataResource.Data -> {
                require(asset != null) { "asset not initialized" }

                with(assetInfo.data) {
                    CoinviewAssetInfoState.Data(
                        assetName = asset.currency.name,
                        description = if (description.isEmpty().not()) description else null,
                        website = if (website.isEmpty().not()) website else null,
                    )
                }
            }
        }
    }

    private fun reduceSnackbarError(state: CoinviewModelState): CoinviewSnackbarAlertState = state.run {
        when (state.error) {
            CoinviewError.ActionsLoadError -> CoinviewSnackbarAlertState.ActionsLoadError
            CoinviewError.None -> CoinviewSnackbarAlertState.None
        }.also {
            // reset to None
            if (it != CoinviewSnackbarAlertState.None) {
                snackbarMessageJob?.cancel()
                snackbarMessageJob = viewModelScope.launch {
                    delay(SNACKBAR_MESSAGE_DURATION)

                    updateState {
                        it.copy(error = CoinviewError.None)
                    }
                }
            }
        }
    }

    override suspend fun handleIntent(modelState: CoinviewModelState, intent: CoinviewIntent) {
        when (intent) {
            is CoinviewIntent.LoadAllData -> {
                check(modelState.asset != null) { "asset not initialized" }
                onIntent(CoinviewIntent.LoadPriceData)
                onIntent(CoinviewIntent.LoadAccountsData)
                onIntent(CoinviewIntent.LoadRecurringBuysData)
                onIntent(CoinviewIntent.LoadAssetInfo)
            }

            CoinviewIntent.LoadPriceData -> {
                check(modelState.asset != null) { "asset not initialized" }

                loadPriceData(
                    asset = modelState.asset,
                    requestedTimeSpan = (modelState.assetPriceHistory as? DataResource.Data)
                        ?.data?.priceDetail?.timeSpan ?: defaultTimeSpan
                )
            }

            CoinviewIntent.LoadAccountsData -> {
                check(modelState.asset != null) { "asset not initialized" }

                loadAccountsData(
                    asset = modelState.asset,
                )
            }

            CoinviewIntent.LoadRecurringBuysData -> {
                check(modelState.asset != null) { "asset not initialized" }

                loadRecurringBuysData(
                    asset = modelState.asset,
                )
            }

            is CoinviewIntent.LoadQuickActions -> {
                check(modelState.asset != null) { "asset not initialized" }

                loadQuickActionsData(
                    asset = modelState.asset,
                    accounts = intent.accounts,
                    totalBalance = intent.totalBalance
                )
            }

            CoinviewIntent.LoadAssetInfo -> {
                check(modelState.asset != null) { "asset not initialized" }

                loadAssetInformation(
                    asset = modelState.asset,
                )
            }

            is CoinviewIntent.UpdatePriceForChartSelection -> {
                check(modelState.assetPriceHistory is DataResource.Data) { "price data not initialized" }

                updatePriceForChartSelection(
                    entry = intent.entry,
                    assetPriceHistory = modelState.assetPriceHistory.data
                )
            }

            is CoinviewIntent.ResetPriceSelection -> {
                resetPriceSelection()
            }

            is CoinviewIntent.NewTimeSpanSelected -> {
                check(modelState.asset != null) { "asset not initialized" }

                updateState { it.copy(requestedTimeSpan = intent.timeSpan) }

                loadPriceData(
                    asset = modelState.asset,
                    requestedTimeSpan = intent.timeSpan,
                )
            }

            is CoinviewIntent.AccountSelected -> {
                check(modelState.asset != null) { "asset not initialized" }

                handleAccountSelected(
                    account = intent.account,
                    asset = modelState.asset
                )
            }

            is CoinviewIntent.AccountExplainerAcknowledged -> {
                check(modelState.accounts != null) { "accounts not initialized" }

                val cvAccount = modelState.accounts!!.accounts.first { it.account == intent.account }
                navigate(
                    CoinviewNavigationEvent.ShowAccountActions(
                        cvAccount = cvAccount,
                        interestRate = cvAccount.interestRate(),
                        actions = intent.actions
                    )
                )
            }

            is CoinviewIntent.AccountActionSelected -> {
                require(modelState.asset != null) { "asset not initialized" }
                require(modelState.accounts != null) { "accounts not initialized" }

                val cvAccount = modelState.accounts!!.accounts.first { it.account == intent.account }

                handleAccountActionSelected(
                    account = cvAccount,
                    asset = modelState.asset,
                    action = intent.action
                )
            }

            is CoinviewIntent.NoBalanceUpsell -> {
                require(modelState.accounts != null) { "accounts not initialized" }

                val cvAccount = modelState.accounts!!.accounts.first { it.account == intent.account }

                navigate(
                    CoinviewNavigationEvent.ShowNoBalanceUpsell(
                        cvAccount,
                        intent.action,
                        true
                    )
                )
            }

            CoinviewIntent.LockedAccountSelected -> {
                navigate(
                    CoinviewNavigationEvent.ShowKycUpgrade
                )
            }

            CoinviewIntent.RecurringBuysUpsell -> {
                require(modelState.asset != null) { "asset not initialized" }

                navigate(
                    CoinviewNavigationEvent.NavigateToRecurringBuyUpsell(modelState.asset)
                )
            }

            is CoinviewIntent.ShowRecurringBuyDetail -> {
                navigate(
                    CoinviewNavigationEvent.ShowRecurringBuyInfo(
                        recurringBuyId = intent.recurringBuyId
                    )
                )
            }

            is CoinviewIntent.QuickActionSelected -> {
                require(modelState.asset != null) { "asset not initialized" }

                when (intent.quickAction) {
                    is CoinviewQuickAction.Buy -> {
                        navigate(
                            CoinviewNavigationEvent.NavigateToBuy(
                                asset = modelState.asset
                            )
                        )
                    }

                    is CoinviewQuickAction.Sell -> {
                        navigate(
                            CoinviewNavigationEvent.NavigateToSell(
                                cvAccount = modelState.actionableAccount
                            )
                        )
                    }

                    is CoinviewQuickAction.Send -> {
                        navigate(
                            CoinviewNavigationEvent.NavigateToSend(
                                cvAccount = modelState.actionableAccount
                            )
                        )
                    }

                    is CoinviewQuickAction.Receive -> {
                        navigate(
                            CoinviewNavigationEvent.NavigateToReceive(
                                cvAccount = modelState.actionableAccount
                            )
                        )
                    }

                    is CoinviewQuickAction.Swap -> {
                        navigate(
                            CoinviewNavigationEvent.NavigateToSwap(
                                cvAccount = modelState.actionableAccount
                            )
                        )
                    }

                    CoinviewQuickAction.None -> error("None action doesn't have an action")
                }
            }
        }
    }

    // //////////////////////
    // Prices
    private fun loadPriceData(
        asset: CryptoAsset,
        requestedTimeSpan: HistoricalTimeSpan
    ) {
        loadPriceDataJob?.cancel()

        loadPriceDataJob = viewModelScope.launch {
            getAssetPriceUseCase(
                asset = asset, timeSpan = requestedTimeSpan, fiatCurrency = fiatCurrency
            ).collectLatest { dataResource ->
                when (dataResource) {
                    DataResource.Loading -> {
                        updateState {
                            it.copy(
                                isChartDataLoading = true,
                                assetPriceHistory = if (it.assetPriceHistory is DataResource.Data) {
                                    it.assetPriceHistory
                                } else {
                                    dataResource
                                }
                            )
                        }
                    }

                    is DataResource.Error -> {
                        updateState {
                            it.copy(
                                isChartDataLoading = false,
                                assetPriceHistory = dataResource,
                            )
                        }
                    }

                    is DataResource.Data -> {
                        if (dataResource.data.historicRates.isEmpty()) {
                            updateState {
                                it.copy(
                                    isChartDataLoading = false,
                                    assetPriceHistory = DataResource.Error(Exception("no historicRates"))
                                )
                            }
                        } else {
                            updateState {
                                it.copy(
                                    isChartDataLoading = false,
                                    assetPriceHistory = dataResource,
                                    requestedTimeSpan = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Build a [CoinviewModelState.interactiveAssetPrice] based on the [entry] selected
     * to update the price information with
     */
    private fun updatePriceForChartSelection(
        entry: Entry,
        assetPriceHistory: CoinviewAssetPriceHistory,
    ) {
        val historicRates = assetPriceHistory.historicRates
        historicRates.firstOrNull { it.timestamp.toFloat() == entry.x }?.let { selectedHistoricalRate ->
            val firstForPeriod = historicRates.first()
            val difference = selectedHistoricalRate.rate - firstForPeriod.rate

            val percentChange = (difference / firstForPeriod.rate)

            val changeDifference = Money.fromMajor(fiatCurrency, difference.toBigDecimal())

            updateState {
                it.copy(
                    interactiveAssetPrice = CoinviewAssetPrice(
                        price = Money.fromMajor(
                            fiatCurrency, selectedHistoricalRate.rate.toBigDecimal()
                        ),
                        timeSpan = assetPriceHistory.priceDetail.timeSpan,
                        changeDifference = changeDifference,
                        percentChange = percentChange
                    )
                )
            }
        }
    }

    /**
     * Reset [CoinviewModelState.interactiveAssetPrice] to update the price information with original value
     */
    private fun resetPriceSelection() {
        updateState { it.copy(interactiveAssetPrice = null) }
    }

    // //////////////////////
    // Accounts
    /**
     * Loads accounts and total balance
     */
    private fun loadAccountsData(asset: CryptoAsset) {
        viewModelScope.launch {
            loadAssetAccountsUseCase(asset = asset).collectLatest { dataResource ->

                updateState {
                    it.copy(
                        assetDetail = if (dataResource is DataResource.Loading && it.assetDetail is DataResource.Data) {
                            // if data is present already - don't show loading
                            it.assetDetail
                        } else {
                            dataResource
                        }
                    )
                }

                // get quick actions
                if (dataResource is DataResource.Data && dataResource.data is CoinviewAssetDetail.Tradeable) {
                    with(dataResource.data as CoinviewAssetDetail.Tradeable) {
                        onIntent(
                            CoinviewIntent.LoadQuickActions(
                                accounts = accounts,
                                totalBalance = totalBalance
                            )
                        )
                    }
                }
            }
        }
    }

    private fun handleAccountSelected(account: CoinviewAccount, asset: CryptoAsset) {
        accountActionsJob?.cancel()
        accountActionsJob = viewModelScope.launch {
            getAccountActionsUseCase(account)
                .doOnData { actions ->
                    getAccountActionsUseCase.getSeenAccountExplainerState(account).let { (hasSeen, markAsSeen) ->
                        if (hasSeen.not()) {
                            // show explainer
                            navigate(
                                CoinviewNavigationEvent.ShowAccountExplainer(
                                    cvAccount = account,
                                    networkTicker = asset.currency.networkTicker,
                                    interestRate = account.interestRate(),
                                    actions = actions
                                )
                            )
                            markAsSeen()
                        } else {
                            navigate(
                                CoinviewNavigationEvent.ShowAccountActions(
                                    cvAccount = account,
                                    interestRate = account.interestRate(),
                                    actions = actions
                                )
                            )
                        }
                    }
                }
                .doOnError {
                    updateState {
                        it.copy(error = CoinviewError.ActionsLoadError)
                    }
                }
        }
    }

    private fun CoinviewAccount.interestRate(): Double {
        val noInterestRate = 0.0
        return when (this) {
            is CoinviewAccount.Universal -> {
                if (filter == AssetFilter.Interest) {
                    interestRate
                } else {
                    noInterestRate
                }
            }
            is CoinviewAccount.Custodial.Interest -> {
                interestRate
            }
            else -> {
                noInterestRate
            }
        }
    }

    private fun handleAccountActionSelected(
        account: CoinviewAccount,
        asset: CryptoAsset,
        action: AssetAction
    ) {
        when (action) {
            AssetAction.Send -> navigate(
                CoinviewNavigationEvent.NavigateToSend(
                    cvAccount = account
                )
            )

            AssetAction.Receive -> navigate(
                CoinviewNavigationEvent.NavigateToReceive(
                    cvAccount = account
                )
            )

            AssetAction.Sell -> navigate(
                CoinviewNavigationEvent.NavigateToSell(
                    cvAccount = account
                )
            )

            AssetAction.Buy -> navigate(
                CoinviewNavigationEvent.NavigateToBuy(
                    asset = asset
                )
            )

            AssetAction.Swap -> navigate(
                CoinviewNavigationEvent.NavigateToSwap(
                    cvAccount = account
                )
            )

            AssetAction.ViewActivity -> navigate(
                CoinviewNavigationEvent.NavigateToActivity(
                    cvAccount = account
                )
            )

            AssetAction.ViewStatement -> navigate(
                CoinviewNavigationEvent.NavigateToInterestStatement(
                    cvAccount = account
                )
            )

            AssetAction.InterestDeposit -> navigate(
                CoinviewNavigationEvent.NavigateToInterestDeposit(
                    cvAccount = account
                )
            )

            AssetAction.InterestWithdraw -> navigate(
                CoinviewNavigationEvent.NavigateToInterestWithdraw(
                    cvAccount = account
                )
            )

            else -> throw IllegalStateException("Action $action is not supported in this flow")
        }
    }

    // //////////////////////
    // Recurring buys
    private fun loadRecurringBuysData(asset: CryptoAsset) {
        viewModelScope.launch {
            loadAssetRecurringBuysUseCase(asset = asset).collectLatest { dataResource ->
                updateState {
                    it.copy(
                        recurringBuys = if (dataResource is DataResource.Loading &&
                            it.recurringBuys is DataResource.Data
                        ) {
                            it.recurringBuys
                        } else {
                            dataResource
                        }
                    )
                }
            }
        }
    }

    // //////////////////////
    // Quick actions
    private fun loadQuickActionsData(
        asset: CryptoAsset,
        accounts: CoinviewAccounts,
        totalBalance: CoinviewAssetTotalBalance
    ) {
        viewModelScope.launch {
            loadQuickActionsUseCase(
                asset = asset,
                accounts = accounts,
                totalBalance = totalBalance
            ).collectLatest { dataResource ->
                updateState {
                    it.copy(
                        quickActions = if (dataResource is DataResource.Loading &&
                            it.quickActions is DataResource.Data
                        ) {
                            it.quickActions
                        } else {
                            dataResource
                        }
                    )
                }
            }
        }
    }

    // //////////////////////
    // Asset info
    private fun loadAssetInformation(asset: CryptoAsset) {
        viewModelScope.launch {
            assetService.getAssetInformation(asset = asset.currency).collectLatest { dataResource ->
                updateState {
                    it.copy(
                        assetInfo = if (dataResource is DataResource.Loading &&
                            it.assetInfo is DataResource.Data
                        ) {
                            it.assetInfo
                        } else {
                            dataResource
                        }
                    )
                }
            }
        }
    }
}
