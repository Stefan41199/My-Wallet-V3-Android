package com.blockchain.componentlib.button

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.blockchain.componentlib.basic.ImageResource
import com.blockchain.componentlib.button.common.ButtonStyle
import com.blockchain.componentlib.button.common.Button
import com.blockchain.componentlib.icons.Icons
import com.blockchain.componentlib.icons.Plus
import com.blockchain.componentlib.theme.AppColors

@Composable
fun PrimaryButton(
    modifier: Modifier = Modifier,
    text: String,
    state: ButtonState = ButtonState.Enabled,
    icon: ImageResource.Local? = null,
    onClick: () -> Unit
) {
    PrimaryButton(
        modifier = modifier,
        text = text,
        state = state,
        icon = icon,
        style = ButtonStyle.Default,
        onClick = onClick
    )
}

@Composable
fun SmallPrimaryButton(
    modifier: Modifier = Modifier,
    text: String,
    state: ButtonState = ButtonState.Enabled,
    icon: ImageResource.Local? = null,
    onClick: () -> Unit
) {
    PrimaryButton(
        modifier = modifier,
        text = text,
        state = state,
        icon = icon,
        style = ButtonStyle.Small,
        onClick = onClick
    )
}

@Composable
private fun PrimaryButton(
    modifier: Modifier = Modifier,
    text: String,
    state: ButtonState = ButtonState.Enabled,
    icon: ImageResource.Local? = null,
    style: ButtonStyle,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier,
        text = text,
        textColor = AppColors.backgroundSecondary,
        backgroundColor = AppColors.primary,
        disabledBackgroundColor = AppColors.primaryMuted,
        state = state,
        style = style,
        icon = icon,
        onClick = onClick
    )
}

// ------------ preview

@Preview
@Composable
private fun PreviewPrimaryButton() {
    PrimaryButton(
        text = "Button Text", state = ButtonState.Enabled, icon = Icons.Plus, onClick = {}
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPrimaryButtonDark() {
    PreviewPrimaryButton()
}

@Preview
@Composable
private fun PreviewPrimaryButtonSmall() {
    SmallPrimaryButton(
        text = "Button Text", state = ButtonState.Enabled, icon = Icons.Plus, onClick = {}
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPrimaryButtonSmallDark() {
    PreviewPrimaryButtonSmall()
}

@Preview
@Composable
private fun PreviewPrimaryButtonDisabled() {
    PrimaryButton(
        text = "Button Text", state = ButtonState.Disabled, icon = Icons.Plus, onClick = {}
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPrimaryButtonDisabledDark() {
    PreviewPrimaryButtonDisabled()
}

@Preview
@Composable
private fun PreviewPrimaryButtonSmallDisabled() {
    SmallPrimaryButton(
        text = "Button Text", state = ButtonState.Disabled, icon = Icons.Plus, onClick = {}
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPrimaryButtonSmallDisabledDark() {
    PreviewPrimaryButtonSmallDisabled()
}

@Preview
@Composable
private fun PreviewPrimaryButtonLoading() {
    PrimaryButton(
        text = "Button Text", state = ButtonState.Loading, icon = Icons.Plus, onClick = {}
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPrimaryButtonLoadingDark() {
    PreviewPrimaryButtonLoading()
}

@Preview
@Composable
private fun PreviewPrimaryButtonSmallLoading() {
    SmallPrimaryButton(
        text = "Button Text", state = ButtonState.Loading, icon = Icons.Plus, onClick = {}
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPrimaryButtonSmallLoadingDark() {
    PreviewPrimaryButtonSmallLoading()
}
