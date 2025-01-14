package com.blockchain.transactions.sell.enteramount.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.blockchain.componentlib.basic.ComposeColors
import com.blockchain.componentlib.basic.ComposeGravities
import com.blockchain.componentlib.basic.ComposeTypographies
import com.blockchain.componentlib.basic.SimpleText
import com.blockchain.componentlib.sheets.SheetHeader
import com.blockchain.componentlib.theme.AppColors
import com.blockchain.componentlib.theme.AppTheme
import com.blockchain.componentlib.theme.SmallVerticalSpacer
import com.blockchain.componentlib.theme.SmallestVerticalSpacer
import com.blockchain.stringResources.R
import com.blockchain.transactions.sell.enteramount.SellEnterAmountInputError

@Composable
fun SellInputErrorScreen(
    inputError: SellEnterAmountInputError,
    closeClicked: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.background)
    ) {
        SheetHeader(
            onClosePress = closeClicked,
        )

        val title = when (inputError) {
            is SellEnterAmountInputError.AboveBalance ->
                stringResource(R.string.not_enough_funds, inputError.displayTicker)

            is SellEnterAmountInputError.AboveMaximum ->
                stringResource(R.string.maximum_with_value, inputError.maxValue)

            is SellEnterAmountInputError.BelowMinimum ->
                stringResource(R.string.minimum_with_value, inputError.minValue)

            is SellEnterAmountInputError.InsufficientGas ->
                stringResource(R.string.confirm_status_msg_insufficient_gas, inputError.displayTicker)

            is SellEnterAmountInputError.Unknown ->
                stringResource(R.string.common_error)
        }

        val description = when (inputError) {
            is SellEnterAmountInputError.AboveBalance -> stringResource(
                R.string.common_actions_not_enough_funds,
                inputError.displayTicker,
                "sell",
                inputError.balance,
            )

            is SellEnterAmountInputError.AboveMaximum ->
                stringResource(R.string.trading_amount_above_max, inputError.maxValue)

            is SellEnterAmountInputError.BelowMinimum ->
                stringResource(R.string.minimum_sell_error_message, inputError.minValue)

            is SellEnterAmountInputError.InsufficientGas ->
                stringResource(R.string.confirm_status_msg_insufficient_gas_description, inputError.networkName)

            is SellEnterAmountInputError.Unknown ->
                inputError.error ?: stringResource(R.string.common_error)
        }

        SimpleText(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.dimensions.smallSpacing),
            text = title,
            style = ComposeTypographies.Title3,
            color = ComposeColors.Title,
            gravity = ComposeGravities.Start,
        )
        SmallestVerticalSpacer()
        SimpleText(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.dimensions.smallSpacing),
            text = description,
            style = ComposeTypographies.Paragraph1,
            color = ComposeColors.Body,
            gravity = ComposeGravities.Start,
        )
        SmallVerticalSpacer()
    }
}
