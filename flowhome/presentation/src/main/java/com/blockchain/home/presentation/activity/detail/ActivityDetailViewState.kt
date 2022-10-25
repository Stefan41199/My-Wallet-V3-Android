package com.blockchain.home.presentation.activity.detail

import com.blockchain.commonarch.presentation.mvi_v2.ViewState
import com.blockchain.data.DataResource

data class ActivityDetailViewState(
    val activityDetailItems: DataResource<ActivityDetail>
) : ViewState

data class ActivityDetail(
    val itemGroups: List<List<ActivityDetailItemState>>,
    val floatingActions: List<ActivityDetailItemState>
)

sealed interface ActivityDetailItemState {
    data class KeyValue(
        val key: String,
        val value: String,
        val style: ValueStyle
    ) : ActivityDetailItemState

    data class Button(
        val value: String,
        val style: ButtonStyle
    ) : ActivityDetailItemState
}

enum class ValueStyle {
    SuccessBadge,
    GreenText,
    Text
}

enum class ButtonStyle {
    Primary,
    Secondary,
    Tertiary
}
