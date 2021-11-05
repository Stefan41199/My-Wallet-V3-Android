package com.blockchain.componentlib.alert.abstract

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.content.res.ResourcesCompat
import com.blockchain.componentlib.alert.SuccessToastAlert

class SuccessToastAlertView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    var text by mutableStateOf("")
    var startIconDrawableRes by mutableStateOf(ResourcesCompat.ID_NULL)

    @Composable
    override fun Content() {
        SuccessToastAlert(
            text = text,
            startIconDrawableRes = startIconDrawableRes
        )
    }
}