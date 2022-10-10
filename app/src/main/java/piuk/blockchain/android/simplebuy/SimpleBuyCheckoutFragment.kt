package piuk.blockchain.android.simplebuy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.blockchain.commonarch.presentation.mvi.MviFragment
import com.blockchain.componentlib.viewextensions.gone
import com.blockchain.componentlib.viewextensions.invisible
import com.blockchain.componentlib.viewextensions.setOnClickListenerDebounced
import com.blockchain.componentlib.viewextensions.visible
import com.blockchain.componentlib.viewextensions.visibleIf
import com.blockchain.core.custodial.models.Availability
import com.blockchain.core.custodial.models.Promo
import com.blockchain.deeplinking.processor.DeeplinkProcessorV2.Companion.DIFFERENT_PAYMENT_URL
import com.blockchain.domain.common.model.ServerErrorAction
import com.blockchain.domain.common.model.ServerSideUxErrorInfo
import com.blockchain.domain.paymentmethods.model.BankPartner
import com.blockchain.domain.paymentmethods.model.GooglePayAddress
import com.blockchain.domain.paymentmethods.model.PaymentMethod.Companion.GOOGLE_PAY_PAYMENT_ID
import com.blockchain.domain.paymentmethods.model.PaymentMethodType
import com.blockchain.domain.paymentmethods.model.SettlementReason
import com.blockchain.extensions.exhaustive
import com.blockchain.koin.scopedInject
import com.blockchain.nabu.datamanagers.OrderState
import com.blockchain.nabu.models.data.RecurringBuyFrequency
import com.blockchain.nabu.models.data.RecurringBuyState
import com.blockchain.payments.googlepay.interceptor.OnGooglePayDataReceivedListener
import com.blockchain.payments.googlepay.interceptor.response.PaymentDataResponse
import com.blockchain.payments.googlepay.manager.GooglePayViewUtils
import com.blockchain.payments.googlepay.manager.request.BillingAddressParameters
import com.blockchain.payments.googlepay.manager.request.GooglePayRequestBuilder
import com.blockchain.payments.googlepay.manager.request.defaultAllowedAuthMethods
import com.blockchain.payments.googlepay.manager.request.defaultAllowedCardNetworks
import com.blockchain.utils.secondsToDays
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import info.blockchain.balance.isCustodialOnly
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.time.ZonedDateTime
import kotlin.math.max
import org.koin.android.ext.android.inject
import piuk.blockchain.android.R
import piuk.blockchain.android.databinding.FragmentSimplebuyCheckoutBinding
import piuk.blockchain.android.databinding.PromoLayoutBinding
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.INSUFFICIENT_FUNDS
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.INTERNET_CONNECTION_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.NABU_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.OVER_MAXIMUM_SOURCE_LIMIT
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.PENDING_ORDERS_LIMIT_REACHED
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SERVER_SIDE_HANDLED_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SETTLEMENT_GENERIC_ERROR
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SETTLEMENT_INSUFFICIENT_BALANCE
import piuk.blockchain.android.simplebuy.ClientErrorAnalytics.Companion.SETTLEMENT_STALE_BALANCE
import piuk.blockchain.android.simplebuy.sheets.SimpleBuyCancelOrderBottomSheet
import piuk.blockchain.android.ui.base.ErrorButtonCopies
import piuk.blockchain.android.ui.base.ErrorDialogData
import piuk.blockchain.android.ui.base.ErrorSlidingBottomDialog
import piuk.blockchain.android.ui.customviews.BlockchainListDividerDecor
import piuk.blockchain.android.urllinks.ORDER_PRICE_EXPLANATION
import piuk.blockchain.android.urllinks.PRIVATE_KEY_EXPLANATION
import piuk.blockchain.android.urllinks.TRADING_ACCOUNT_LOCKS
import piuk.blockchain.android.urllinks.URL_OPEN_BANKING_PRIVACY_POLICY
import piuk.blockchain.android.util.StringAnnotationClickEvent
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.animateChange
import piuk.blockchain.android.util.disableBackPress
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy

class SimpleBuyCheckoutFragment :
    MviFragment<SimpleBuyModel, SimpleBuyIntent, SimpleBuyState, FragmentSimplebuyCheckoutBinding>(),
    SimpleBuyScreen,
    SimpleBuyCancelOrderBottomSheet.Host,
    OnGooglePayDataReceivedListener {

    override val model: SimpleBuyModel by scopedInject()
    private val googlePayViewUtils: GooglePayViewUtils by inject()
    private var updateRecurringBuy: Boolean = false

    private var lastState: SimpleBuyState? = null
    private val checkoutAdapterDelegate = CheckoutAdapterDelegate(
        onToggleChanged = { updateRecurringBuy = it },
        onTooltipClicked = { expandableType ->
            if (expandableType == SimpleBuyCheckoutItem.ExpandableType.PRICE) {
                analytics.logEvent(BuyPriceTooltipClickedEvent)
            } else if (expandableType == SimpleBuyCheckoutItem.ExpandableType.FEE) {
                analytics.logEvent(BuyBlockchainComFeeClickedEvent)
            }
        },
    )

    private var countDownTimer: CountDownTimer? = null
    private var chunksCounter = mutableListOf<Int>()

    private val isForPendingPayment: Boolean by unsafeLazy {
        arguments?.getBoolean(PENDING_PAYMENT_ORDER_KEY, false) ?: false
    }

    private val showOnlyOrderData: Boolean by unsafeLazy {
        arguments?.getBoolean(SHOW_ONLY_ORDER_DATA, false) ?: false
    }

    private val compositeDisposable = CompositeDisposable()

    override fun initBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSimplebuyCheckoutBinding =
        FragmentSimplebuyCheckoutBinding.inflate(inflater, container, false)

    override fun onAttach(context: Context) {
        super.onAttach(context)

        // force disable back press when it's pending payment
        requireActivity().disableBackPress(owner = this, callbackEnabled = isForPendingPayment)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = checkoutAdapterDelegate
            addItemDecoration(BlockchainListDividerDecor(requireContext()))
        }

        if (!showOnlyOrderData) {
            setupToolbar()
        }
        model.process(SimpleBuyIntent.GetRecurringBuyFrequencyRemote)
        model.process(SimpleBuyIntent.FetchWithdrawLockTime)
        model.process(SimpleBuyIntent.GetSafeConnectTermsOfServiceLink)
    }

    private fun setupToolbar() {
        activity.updateToolbar(
            toolbarTitle = if (isForPendingPayment) {
                getString(R.string.order_details)
            } else {
                getString(R.string.checkout)
            },
            backAction = {
                analytics.logEvent(BuyCheckoutScreenBackClickedEvent)
                activity.onBackPressedDispatcher.onBackPressed()
            }
        )
    }

    override fun navigator(): SimpleBuyNavigator =
        (activity as? SimpleBuyNavigator) ?: throw IllegalStateException(
            "Parent must implement SimpleBuyNavigator"
        )

    private fun startCounter(quote: BuyQuote, remainingTime: Int) {
        binding.buttonAction.isEnabled = true
        countDownTimer = object : CountDownTimer(remainingTime * 1000L, COUNT_DOWN_INTERVAL_TIMER) {
            override fun onTick(millisUntilFinished: Long) {
                if (millisUntilFinished > 0) {
                    val formattedTime = DateUtils.formatElapsedTime(max(0, millisUntilFinished / 1000))
                    binding.quoteExpiration.apply {
                        text = getString(
                            R.string.simple_buy_quote_message,
                            formattedTime
                        )
                        progress = (millisUntilFinished / 1000L) / remainingTime.toFloat()
                    }
                }
            }

            override fun onFinish() {
                if (chunksCounter.isNotEmpty()) chunksCounter.removeAt(0)
                if (chunksCounter.size > 0) {
                    countDownTimer?.cancel()
                    startCounter(quote, chunksCounter.first())
                } else {
                    countDownTimer?.cancel()
                    countDownTimer = null
                    binding.buttonAction.isEnabled = false
                }
            }
        }
        countDownTimer?.start()
    }

    override fun render(newState: SimpleBuyState) {

        if (newState.featureFlagSet.buyQuoteRefreshFF) {
            binding.quoteExpiration.visible()
            if (countDownTimer == null && newState.quote != null &&
                !isPendingOrAwaitingFunds(newState.orderState)
            ) {
                chunksCounter = newState.quote.chunksTimeCounter
                startCounter(newState.quote, chunksCounter.first())
            }
            if (newState.hasQuoteChanged && !isPendingOrAwaitingFunds(newState.orderState)) {
                binding.amount.animateChange {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(binding.amount.context, R.color.grey_800)
                    )
                }
                checkoutAdapterDelegate.items = getCheckoutFields(newState)
            }
        }

        showAmountForMethod(newState)

        // Event should be triggered only the first time a state is rendered
        if (lastState == null) {
            analytics.logEvent(BuyCheckoutScreenViewedEvent)
            analytics.logEvent(
                eventWithPaymentMethod(
                    SimpleBuyAnalytics.CHECKOUT_SUMMARY_SHOWN,
                    newState.selectedPaymentMethod?.paymentMethodType?.toAnalyticsString().orEmpty()
                )
            )
            checkoutAdapterDelegate.items = getCheckoutFields(newState)
        } else if (newState.recurringBuyForExperiment != RecurringBuyFrequency.ONE_TIME) {
            checkoutAdapterDelegate.items = getCheckoutFields(newState)
        }

        lastState = newState

        newState.selectedCryptoAsset?.let { renderPrivateKeyLabel(it) }
        val payment = newState.selectedPaymentMethod
        val note = when {
            payment?.isCard() == true -> showWithdrawalPeriod(newState)
            payment?.isFunds() == true -> getString(R.string.purchase_funds_note)
            payment?.isBank() == true -> showWithdrawalPeriod(newState)
            else -> ""
        }

        binding.purchaseNote.apply {
            if (note.isBlank())
                gone()
            else {
                visible()
                movementMethod = LinkMovementMethod.getInstance()
                text = note
            }
        }

        binding.termsAndPrivacy.apply {
            if (newState.isOpenBankingTransfer()) {
                visible()

                val linksMap = mapOf(
                    "terms" to StringAnnotationClickEvent.OpenUri(Uri.parse(newState.safeConnectTosLink.orEmpty())),
                    "privacy" to StringAnnotationClickEvent.OpenUri(Uri.parse(URL_OPEN_BANKING_PRIVACY_POLICY))
                )

                text = StringUtils.getStringWithMappedAnnotations(
                    context = requireContext(),
                    stringId = R.string.open_banking_permission_confirmation_buy,
                    linksMap = linksMap
                )
                movementMethod = LinkMovementMethod.getInstance()
            } else {
                gone()
            }
        }

        if (newState.buyErrorState != null) {
            showErrorState(newState.buyErrorState)
            binding.buttonGooglePay.hideLoading()
            model.process(SimpleBuyIntent.ClearError)
            return
        }

        updateStatusPill(newState)

        if (newState.paymentOptions.availablePaymentMethods.isEmpty()) {
            model.process(
                SimpleBuyIntent.FetchPaymentDetails(
                    fiatCurrency = newState.fiatCurrency,
                    selectedPaymentMethodId = newState.selectedPaymentMethod?.id.orEmpty()
                )
            )
        }

        configureButtons(newState)

        when (newState.order.orderState) {
            OrderState.FINISHED, // Funds orders are getting finished right after confirmation
            OrderState.AWAITING_FUNDS -> {
                if (newState.confirmationActionRequested) {
                    navigator().goToPaymentScreen(
                        showRecurringBuySuggestion = newState.recurringBuySuggestionHasNotBeenEnabled(),
                        recurringBuyFrequencyRemote = newState.recurringBuyForExperiment
                    )
                }
            }
            OrderState.FAILED -> {
                binding.buttonAction.isEnabled = false
                val errorDescription = newState.failureReason ?: getString(R.string.purchase_description_error)
                showBottomSheet(
                    ErrorSlidingBottomDialog.newInstance(
                        ErrorDialogData(
                            title = getString(R.string.purchase_title_error),
                            description = errorDescription,
                            errorButtonCopies = ErrorButtonCopies(
                                primaryButtonText = getString(R.string.common_ok)
                            ),
                            error = newState.order.orderState.toString(),
                            errorDescription = errorDescription,
                            action = ClientErrorAnalytics.ACTION_BUY,
                            analyticsCategories = emptyList()
                        )
                    )
                )
            }
            OrderState.CANCELED -> {
                if (activity is SmallSimpleBuyNavigator) {
                    (activity as SmallSimpleBuyNavigator).exitSimpleBuyFlow()
                } else {
                    navigator().exitSimpleBuyFlow()
                }
            }
            else -> {
                // do nothing
            }
        }

        newState.googlePayDetails?.let { googlePayInfo ->
            googlePayInfo.tokenizationInfo?.let { tokenizationMap ->
                if (tokenizationMap.isNotEmpty()) {
                    googlePayViewUtils.requestPayment(
                        GooglePayRequestBuilder.buildForPaymentRequest(
                            allowedAuthMethods = googlePayInfo.allowedAuthMethods ?: defaultAllowedAuthMethods,
                            allowedCardNetworks = googlePayInfo.allowedCardNetworks ?: defaultAllowedCardNetworks,
                            gatewayTokenizationParameters = tokenizationMap,
                            totalPrice = newState.amount.toNetworkString(),
                            countryCode = googlePayInfo.merchantBankCountryCode.orEmpty(),
                            currencyCode = newState.fiatCurrency.networkTicker,
                            allowPrepaidCards = googlePayInfo.allowPrepaidCards,
                            allowCreditCards = googlePayInfo.allowCreditCards,
                            billingAddressRequired = googlePayInfo.billingAddressRequired ?: true,
                            billingAddressParameters = googlePayInfo.billingAddressParameters
                                ?: BillingAddressParameters()
                        ),
                        requireActivity()
                    )

                    model.process(SimpleBuyIntent.ClearGooglePayTokenizationInfo)
                }
            }
        }
    }

    private fun getSettlementReason(
        plaidFFEnabled: Boolean,
        quote: BuyQuote?,
        selectedPaymentMethod: SelectedPaymentMethod?,
        partner: BankPartner?
    ): SettlementReason {
        val isValidBankTransfer = selectedPaymentMethod?.paymentMethodType == PaymentMethodType.BANK_TRANSFER &&
            selectedPaymentMethod.id.isNotEmpty()
        val isYodleeUpgradeRequired =
            partner == BankPartner.YODLEE && quote?.settlementReason == SettlementReason.REQUIRES_UPDATE
        val shouldProcessReason = (quote?.availability == Availability.UNAVAILABLE && quote.settlementReason != null) ||
            isYodleeUpgradeRequired

        if (plaidFFEnabled && quote?.settlementReason != null && isValidBankTransfer && shouldProcessReason) {
            return quote.settlementReason
        }
        return SettlementReason.NONE
    }

    private fun renderPrivateKeyLabel(selectedCryptoAsset: AssetInfo) {
        if (selectedCryptoAsset.isCustodialOnly) {
            val map = mapOf("learn_more_link" to Uri.parse(PRIVATE_KEY_EXPLANATION))
            val learnMoreLink = StringUtils.getStringWithMappedAnnotations(
                requireContext(),
                R.string.common_linked_learn_more,
                map
            )

            val sb = SpannableStringBuilder()
            val privateKeyExplanation =
                getString(
                    R.string.checkout_item_private_key_wallet_explanation_1,
                    selectedCryptoAsset.displayTicker
                )
            sb.append(privateKeyExplanation)
                .append(learnMoreLink)
                .setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(activity, R.color.blue_600)),
                    privateKeyExplanation.length, privateKeyExplanation.length + learnMoreLink.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

            binding.privateKeyExplanation.apply {
                setText(sb, TextView.BufferType.SPANNABLE)
                movementMethod = LinkMovementMethod.getInstance()
                visible()
            }
        }
    }

    private fun showWithdrawalPeriod(newState: SimpleBuyState) =
        newState.withdrawalLockPeriod.secondsToDays().takeIf { it > 0 }?.let {
            StringUtils.getResolvedStringWithAppendedMappedLearnMore(
                getString(R.string.security_locked_funds_bank_transfer_explanation_1, it.toString()),
                R.string.common_linked_learn_more, TRADING_ACCOUNT_LOCKS, requireActivity(), R.color.blue_600
            )
        } ?: getString(R.string.security_no_lock_bank_transfer_explanation)

    private fun showAmountForMethod(newState: SimpleBuyState) {
        binding.amount.text = newState.orderValue?.toStringWithSymbol()
        binding.amountFiat.text = newState.order.amount?.toStringWithSymbol()
    }

    private fun updateStatusPill(newState: SimpleBuyState) {
        with(binding.status) {
            when {
                isPendingOrAwaitingFunds(newState.orderState) -> {
                    text = getString(R.string.order_pending)
                    background =
                        ContextCompat.getDrawable(requireContext(), R.drawable.bkgd_status_unconfirmed)
                    setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.grey_800)
                    )
                }
                newState.orderState == OrderState.FINISHED -> {
                    text = getString(R.string.order_complete)
                    background =
                        ContextCompat.getDrawable(requireContext(), R.drawable.bkgd_green_100_rounded)
                    setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.green_600)
                    )
                }
                else -> gone()
            }
        }
    }

    private fun getCheckoutFields(state: SimpleBuyState): List<SimpleBuyCheckoutItem> {

        require(state.selectedCryptoAsset != null)

        val priceExplanation = StringUtils.getResolvedStringWithAppendedMappedLearnMore(
            staticText = if (state.coinHasZeroMargin)
                getString(
                    R.string.checkout_item_price_blurb_zero_margin,
                    state.selectedCryptoAsset.displayTicker
                ) else getString(R.string.checkout_item_price_blurb),
            textToMap = R.string.learn_more_annotated,
            url = ORDER_PRICE_EXPLANATION,
            context = requireContext(),
            linkColour = R.color.blue_600
        )

        return listOfNotNull(
            SimpleBuyCheckoutItem.ExpandableCheckoutItem(
                label = getString(R.string.quote_price, state.selectedCryptoAsset.displayTicker),
                title = state.exchangeRate?.toStringWithSymbol().orEmpty(),
                expandableContent = priceExplanation,
                hasChanged = state.hasQuoteChanged && state.featureFlagSet.buyQuoteRefreshFF,
                expandableType = SimpleBuyCheckoutItem.ExpandableType.PRICE
            ),
            buildPaymentMethodItem(state),
            if (state.recurringBuyFrequency != RecurringBuyFrequency.ONE_TIME) {
                SimpleBuyCheckoutItem.ComplexCheckoutItem(
                    label = getString(R.string.recurring_buy_frequency_label_1),
                    title = state.recurringBuyFrequency.toHumanReadableRecurringBuy(requireContext()),
                    subtitle = state.recurringBuyFrequency.toHumanReadableRecurringDate(
                        requireContext(), ZonedDateTime.now()
                    )
                )
            } else null,

            SimpleBuyCheckoutItem.SimpleCheckoutItem(
                label = getString(R.string.purchase),
                title = state.purchasedAmount().toStringWithSymbol(),
                isImportant = state.purchasedAmount() != lastState?.purchasedAmount(),
                hasChanged = false
            ),
            buildPaymentFee(
                state,
                getString(R.string.checkout_item_price_fee)
            ),
            SimpleBuyCheckoutItem.SimpleCheckoutItem(
                getString(R.string.common_total),
                state.amount.toStringWithSymbol(),
                isImportant = true,
                hasChanged = false
            ),
            if (!isPendingOrAwaitingFunds(state.orderState) && state.suggestEnablingRecurringBuyFrequency()) {
                SimpleBuyCheckoutItem.ToggleCheckoutItem(
                    title = state.recurringBuyForExperiment.toRecurringBuySuggestionTitle(requireContext()),
                    subtitle = getString(
                        R.string.checkout_rb_subtitle,
                        state.recurringBuyForExperiment.toHumanReadableRecurringBuy(requireContext()).lowercase(),
                        state.recurringBuyForExperiment.toHumanReadableRecurringDate(
                            requireContext(), ZonedDateTime.now()
                        )
                    )
                )
            } else null
        )
    }

    private fun SimpleBuyState.suggestEnablingRecurringBuyFrequency(): Boolean =
        featureFlagSet.rbFrequencySuggestionFF &&
            this.recurringBuyFrequency == RecurringBuyFrequency.ONE_TIME &&
            this.isSelectedPaymentMethodRecurringBuyEligible() &&
            this.recurringBuyForExperiment != RecurringBuyFrequency.ONE_TIME

    private fun SimpleBuyState.recurringBuySuggestionHasNotBeenEnabled(): Boolean =
        featureFlagSet.rbFrequencySuggestionFF && this.recurringBuyState == RecurringBuyState.UNINITIALISED

    private fun buildPaymentMethodItem(state: SimpleBuyState): SimpleBuyCheckoutItem? =
        state.selectedPaymentMethod?.let {
            val paymentMethodType = if (state.selectedPaymentMethodDetails?.id == GOOGLE_PAY_PAYMENT_ID)
                PaymentMethodType.GOOGLE_PAY else it.paymentMethodType

            when (paymentMethodType) {
                PaymentMethodType.FUNDS -> SimpleBuyCheckoutItem.SimpleCheckoutItem(
                    label = getString(R.string.payment_method),
                    title = state.fiatCurrency.name,
                    hasChanged = false
                )
                PaymentMethodType.BANK_TRANSFER,
                PaymentMethodType.BANK_ACCOUNT,
                PaymentMethodType.PAYMENT_CARD,
                -> {
                    state.selectedPaymentMethodDetails?.let { details ->
                        SimpleBuyCheckoutItem.ComplexCheckoutItem(
                            getString(R.string.payment_method),
                            details.methodDetails(),
                            details.methodName()
                        )
                    }
                }
                PaymentMethodType.GOOGLE_PAY -> {
                    state.selectedPaymentMethodDetails?.let { details ->
                        SimpleBuyCheckoutItem.SimpleCheckoutItem(
                            label = getString(R.string.payment_method),
                            title = details.methodDetails(),
                            isImportant = false,
                            hasChanged = false
                        )
                    }
                }
                PaymentMethodType.UNKNOWN -> null
            }
        }

    private fun buildPaymentFee(state: SimpleBuyState, feeExplanation: CharSequence): SimpleBuyCheckoutItem? =
        state.quote?.feeDetails?.let { feeDetails ->
            SimpleBuyCheckoutItem.ExpandableCheckoutItem(
                label = getString(R.string.blockchain_fee),
                title = feeDetails.fee.toStringWithSymbol(),
                expandableContent = feeExplanation,
                promoLayout = viewForPromo(feeDetails),
                hasChanged = state.hasQuoteChanged && state.featureFlagSet.buyQuoteRefreshFF,
                expandableType = SimpleBuyCheckoutItem.ExpandableType.FEE
            )
        }

    private fun isPendingOrAwaitingFunds(orderState: OrderState) =
        isForPendingPayment || orderState == OrderState.AWAITING_FUNDS

    private fun configureButtons(state: SimpleBuyState) {
        val isOrderAwaitingFunds = state.orderState == OrderState.AWAITING_FUNDS
        val isGooglePay = state.selectedPaymentMethod?.id == GOOGLE_PAY_PAYMENT_ID &&
            !isForPendingPayment && !isOrderAwaitingFunds

        with(binding) {
            buttonAction.apply {
                analytics.logEvent(BuyCheckoutScreenSubmittedEvent)
                if (!isForPendingPayment && !isOrderAwaitingFunds) {
                    text = getString(R.string.buy_asset_now, state.orderValue?.toStringWithSymbol())
                    setOnClickListener {
                        when (
                            getSettlementReason(
                                plaidFFEnabled = state.featureFlagSet.plaidFF,
                                quote = state.quote,
                                selectedPaymentMethod = state.selectedPaymentMethod,
                                partner = state.linkedBank?.partner
                            )
                        ) {
                            SettlementReason.INSUFFICIENT_BALANCE ->
                                showErrorState(ErrorState.SettlementInsufficientBalance)
                            SettlementReason.STALE_BALANCE ->
                                showErrorState(ErrorState.SettlementStaleBalance)
                            SettlementReason.REQUIRES_UPDATE ->
                                showErrorState(
                                    ErrorState.SettlementRefreshRequired(state.selectedPaymentMethod?.id.orEmpty())
                                )
                            SettlementReason.GENERIC ->
                                showErrorState(ErrorState.SettlementGenericError)
                            SettlementReason.UNKNOWN,
                            SettlementReason.NONE -> {
                                if (state.featureFlagSet.buyQuoteRefreshFF) quoteExpiration.invisible()

                                if (updateRecurringBuy) {
                                    model.process(
                                        SimpleBuyIntent.RecurringBuySuggestionAccepted(
                                            recurringBuyFrequency = state.recurringBuyForExperiment
                                        )
                                    )
                                } else {
                                    model.process(SimpleBuyIntent.ConfirmOrder)
                                }
                                analytics.logEvent(
                                    eventWithPaymentMethod(
                                        SimpleBuyAnalytics.CHECKOUT_SUMMARY_CONFIRMED,
                                        state.selectedPaymentMethod?.paymentMethodType?.toAnalyticsString()
                                            .orEmpty()
                                    )
                                )
                            }
                        }
                    }
                } else {
                    text = if (isOrderAwaitingFunds && !isForPendingPayment) {
                        getString(R.string.complete_payment)
                    } else {
                        getString(R.string.common_ok)
                    }
                    setOnClickListener {
                        if (isForPendingPayment) {
                            navigator().exitSimpleBuyFlow()
                        } else {
                            navigator().goToPaymentScreen(
                                showRecurringBuySuggestion = state.suggestEnablingRecurringBuyFrequency() &&
                                    !updateRecurringBuy,
                                recurringBuyFrequencyRemote = state.recurringBuyForExperiment
                            )
                        }
                    }
                }
                visibleIf { !showOnlyOrderData && !isGooglePay }
                isEnabled = !state.isLoading
            }

            buttonCancel.visibleIf {
                isOrderAwaitingFunds && state.selectedPaymentMethod?.isBank() == true
            }
            buttonCancel.setOnClickListenerDebounced {
                analytics.logEvent(SimpleBuyAnalytics.CHECKOUT_SUMMARY_PRESS_CANCEL)
                showBottomSheet(SimpleBuyCancelOrderBottomSheet.newInstance())
            }
            buttonGooglePay.apply {
                visibleIf { isGooglePay }
                setOnClickListener {
                    buttonGooglePay.showLoading()
                    model.process(SimpleBuyIntent.GooglePayInfoRequested)
                }
            }
        }
    }

    private fun showErrorState(errorState: ErrorState) {
        when (errorState) {
            ErrorState.DailyLimitExceeded ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_daily_limit_title),
                    description = getString(R.string.sb_checkout_daily_limit_blurb),
                    error = OVER_MAXIMUM_SOURCE_LIMIT
                )
            ErrorState.WeeklyLimitExceeded ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_weekly_limit_title),
                    description = getString(R.string.sb_checkout_weekly_limit_blurb),
                    error = OVER_MAXIMUM_SOURCE_LIMIT
                )
            ErrorState.YearlyLimitExceeded ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_yearly_limit_title),
                    description = getString(R.string.sb_checkout_yearly_limit_blurb),
                    error = OVER_MAXIMUM_SOURCE_LIMIT
                )
            ErrorState.ExistingPendingOrder ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.sb_checkout_pending_order_title),
                    description = getString(R.string.sb_checkout_pending_order_blurb),
                    error = PENDING_ORDERS_LIMIT_REACHED
                )
            ErrorState.InsufficientCardFunds ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardInsufficientFunds),
                    description = getString(R.string.msg_cardInsufficientFunds),
                    error = INSUFFICIENT_FUNDS
                )
            ErrorState.CardBankDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardBankDecline),
                    description = getString(R.string.msg_cardBankDecline),
                    error = errorState.toString()
                )
            ErrorState.CardDuplicated ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardDuplicate),
                    description = getString(R.string.msg_cardDuplicate),
                    error = errorState.toString()
                )
            ErrorState.CardBlockchainDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardBlockchainDecline),
                    description = getString(R.string.msg_cardBlockchainDecline),
                    error = errorState.toString()
                )
            ErrorState.CardAcquirerDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardAcquirerDecline),
                    description = getString(R.string.msg_cardAcquirerDecline),
                    error = errorState.toString()
                )
            ErrorState.CardPaymentNotSupported ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardPaymentNotSupported),
                    description = getString(R.string.msg_cardPaymentNotSupported),
                    error = errorState.toString()
                )
            ErrorState.CardCreateFailed ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateFailed),
                    description = getString(R.string.msg_cardCreateFailed),
                    error = errorState.toString()
                )
            ErrorState.CardPaymentFailed ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardPaymentFailed),
                    description = getString(R.string.msg_cardPaymentFailed),
                    error = errorState.toString()
                )
            ErrorState.CardCreateAbandoned ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateAbandoned),
                    description = getString(
                        R.string.msg_cardCreateAbandoned,
                    ),
                    error = errorState.toString()
                )
            ErrorState.CardCreateExpired ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateExpired),
                    description = getString(R.string.msg_cardCreateExpired),
                    error = errorState.toString()
                )
            ErrorState.CardCreateBankDeclined ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateBankDeclined),
                    description = getString(R.string.msg_cardCreateBankDeclined),
                    error = errorState.toString()
                )
            ErrorState.CardCreateDebitOnly ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateDebitOnly),
                    description = getString(R.string.msg_cardCreateDebitOnly),
                    serverSideUxErrorInfo = ServerSideUxErrorInfo(
                        id = null,
                        title = getString(R.string.title_cardCreateDebitOnly),
                        description = getString(R.string.msg_cardCreateDebitOnly),
                        iconUrl = getString(R.string.empty),
                        statusUrl = getString(R.string.empty),
                        actions = listOf(
                            ServerErrorAction(
                                getString(R.string.sb_checkout_card_debit_only_cta), DIFFERENT_PAYMENT_URL
                            )
                        ),
                        categories = emptyList()
                    ),
                    error = errorState.toString()
                )
            ErrorState.CardPaymentDebitOnly ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardPaymentDebitOnly),
                    description = getString(R.string.msg_cardPaymentDebitOnly),
                    error = errorState.toString()
                )
            ErrorState.CardNoToken ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardCreateNoToken),
                    description = getString(R.string.msg_cardCreateNoToken),
                    error = errorState.toString()
                )
            is ErrorState.UnhandledHttpError ->
                navigator().showErrorInBottomSheet(
                    title = getString(
                        R.string.common_http_error_with_message, errorState.nabuApiException.getErrorDescription()
                    ),
                    description = errorState.nabuApiException.getErrorDescription(),
                    error = NABU_ERROR,
                    nabuApiException = errorState.nabuApiException
                )
            ErrorState.InternetConnectionError ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.executing_connection_error),
                    description = getString(R.string.something_went_wrong_try_again),
                    error = INTERNET_CONNECTION_ERROR
                )
            is ErrorState.ApprovedBankUndefinedError ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.payment_failed_title_with_reason),
                    description = getString(R.string.something_went_wrong_try_again),
                    error = errorState.toString()
                )
            is ErrorState.BankLinkMaxAccountsReached ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.bank_linking_max_accounts_title),
                    description = getString(R.string.bank_linking_max_accounts_subtitle),
                    error = errorState.toString(),
                    nabuApiException = errorState.error
                )
            is ErrorState.BankLinkMaxAttemptsReached ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.bank_linking_max_attempts_title),
                    description = getString(R.string.bank_linking_max_attempts_subtitle),
                    error = errorState.toString(),
                    nabuApiException = errorState.error
                )
            is ErrorState.ServerSideUxError ->
                navigator().showErrorInBottomSheet(
                    title = errorState.serverSideUxErrorInfo.title,
                    description = errorState.serverSideUxErrorInfo.description,
                    error = SERVER_SIDE_HANDLED_ERROR,
                    serverSideUxErrorInfo = errorState.serverSideUxErrorInfo
                )
            is ErrorState.SettlementInsufficientBalance ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.title_cardInsufficientFunds),
                    description = getString(R.string.trading_deposit_description_insufficient),
                    error = SETTLEMENT_INSUFFICIENT_BALANCE
                )
            is ErrorState.SettlementStaleBalance ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.trading_deposit_title_stale_balance),
                    description = getString(R.string.trading_deposit_description_stale),
                    error = SETTLEMENT_STALE_BALANCE
                )
            is ErrorState.SettlementGenericError ->
                navigator().showErrorInBottomSheet(
                    title = getString(R.string.common_oops_bank),
                    description = getString(R.string.trading_deposit_description_generic),
                    error = SETTLEMENT_GENERIC_ERROR
                )
            is ErrorState.SettlementRefreshRequired ->
                navigator().showBankRefreshError(errorState.accountId)
            ErrorState.ApproveBankInvalid,
            ErrorState.ApprovedBankAccountInvalid,
            ErrorState.ApprovedBankDeclined,
            ErrorState.ApprovedBankExpired,
            ErrorState.ApprovedBankFailed,
            ErrorState.ApprovedBankFailedInternal,
            ErrorState.ApprovedBankInsufficientFunds,
            ErrorState.ApprovedBankLimitedExceed,
            ErrorState.BankLinkingTimeout,
            ErrorState.ApprovedBankRejected,
            is ErrorState.PaymentFailedError,
            ErrorState.UnknownCardProvider,
            ErrorState.ProviderIsNotSupported,
            ErrorState.Card3DsFailed,
            ErrorState.LinkedBankNotSupported,
            ErrorState.BuyPaymentMethodsUnavailable,
            -> throw IllegalStateException(
                "Error $errorState should not be presented in the checkout screen"
            )
        }.exhaustive
    }

    override fun cancelOrderConfirmAction(cancelOrder: Boolean, orderId: String?) {
        if (cancelOrder) {
            model.process(SimpleBuyIntent.CancelOrder)
            analytics.logEvent(
                eventWithPaymentMethod(
                    SimpleBuyAnalytics.CHECKOUT_SUMMARY_CANCELLATION_CONFIRMED,
                    lastState?.selectedPaymentMethod?.paymentMethodType?.toAnalyticsString().orEmpty()
                )
            )
        } else {
            analytics.logEvent(SimpleBuyAnalytics.CHECKOUT_SUMMARY_CANCELLATION_GO_BACK)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.buttonGooglePay.hideLoading()
    }

    override fun onPause() {
        super.onPause()
        model.process(SimpleBuyIntent.NavigationHandled)
    }

    override fun onDestroy() {
        compositeDisposable.clear()
        countDownTimer?.cancel()
        super.onDestroy()
    }

    override fun onSheetClosed() {
        binding.buttonGooglePay.hideLoading()
    }

    private fun getGooglePayAddress(address: PaymentDataResponse.Address?): GooglePayAddress? =
        address?.let {
            GooglePayAddress(
                address1 = it.address1.orEmpty(),
                address2 = it.address2.orEmpty(),
                address3 = it.address3.orEmpty(),
                administrativeArea = it.administrativeArea.orEmpty(),
                countryCode = it.countryCode.orEmpty(),
                locality = it.locality.orEmpty(),
                name = it.name.orEmpty(),
                postalCode = it.postalCode.orEmpty(),
                sortingCode = it.sortingCode.orEmpty()
            )
        }

    override fun onGooglePayTokenReceived(
        token: String,
        address: PaymentDataResponse.Address?,
    ) {
        val googlePayAddress = getGooglePayAddress(address)

        if (updateRecurringBuy) {
            model.process(
                SimpleBuyIntent.RecurringBuySuggestionAccepted(
                    recurringBuyFrequency = lastState?.recurringBuyForExperiment ?: RecurringBuyFrequency.ONE_TIME,
                    googlePayPayload = token,
                    googlePayAddress = googlePayAddress
                ),
            )
        } else {
            model.process(
                SimpleBuyIntent.ConfirmGooglePayOrder(
                    googlePayPayload = token,
                    googlePayAddress = googlePayAddress
                )
            )
        }
        binding.buttonGooglePay.showLoading()
    }

    override fun onGooglePayCancelled() {
        binding.buttonGooglePay.hideLoading()
    }

    override fun onGooglePaySheetClosed() {
        binding.buttonGooglePay.hideLoading()
    }

    override fun onGooglePayError(e: Throwable) {
        binding.buttonGooglePay.hideLoading()
    }

    private fun viewForPromo(buyFees: BuyFees): View? {
        return buyFees.takeIf { it.promo != Promo.NO_PROMO }?.let { promotedFees ->
            val promoBinding = PromoLayoutBinding.inflate(LayoutInflater.from(context), null, false)
            return when (promotedFees.promo) {
                Promo.NEW_USER -> configureNewUserPromo(promoBinding, buyFees)
                Promo.NO_PROMO -> throw IllegalStateException("No Promo available")
            }
        }
    }

    private fun configureNewUserPromo(promoBinding: PromoLayoutBinding, fees: BuyFees): View =
        promoBinding.apply {
            feeWaiverPromo.setBackgroundResource(R.drawable.bkgd_green_100_rounded)
            label.text = getString(R.string.new_user_fee_waiver)
            afterPromoFee.text = fees.fee.toStringWithSymbolOrFree()
            val strikedThroughFee = SpannableString(fees.feeBeforePromo.toStringWithSymbol()).apply {
                setSpan(StrikethroughSpan(), 0, this.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            beforePromoFee.text = strikedThroughFee
        }.root

    private fun FiatValue.toStringWithSymbolOrFree(): String =
        if (isPositive) toStringWithSymbol() else getString(R.string.common_free)

    companion object {
        private const val COUNT_DOWN_INTERVAL_TIMER = 1000L
        private const val PENDING_PAYMENT_ORDER_KEY = "PENDING_PAYMENT_KEY"
        private const val SHOW_ONLY_ORDER_DATA = "SHOW_ONLY_ORDER_DATA"

        fun newInstance(
            isForPending: Boolean = false,
            showOnlyOrderData: Boolean = false,
        ): SimpleBuyCheckoutFragment {
            val fragment = SimpleBuyCheckoutFragment()
            fragment.arguments = Bundle().apply {
                putBoolean(PENDING_PAYMENT_ORDER_KEY, isForPending)
                putBoolean(SHOW_ONLY_ORDER_DATA, showOnlyOrderData)
            }
            return fragment
        }
    }
}

private fun SimpleBuyState.purchasedAmount(): Money {
    val fee = quote?.feeDetails?.fee ?: FiatValue.zero(fiatCurrency)
    return amount - fee
}
