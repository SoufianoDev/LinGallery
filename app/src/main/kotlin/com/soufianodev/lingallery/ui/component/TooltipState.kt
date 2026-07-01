package com.soufianodev.lingallery.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

enum class TooltipDisplayState {
    IDLE,
    HOVERING,
    SHOWN,
    HIDE_PENDING
}

@Composable
fun rememberTooltipState(
    interactionSource: MutableInteractionSource,
    showDelayMs: Long = 400L,
    hideDelayMs: Long = 150L
): TooltipDisplayState {
    val isHovered by interactionSource.collectIsHoveredAsState()
    var state by remember { mutableStateOf(TooltipDisplayState.IDLE) }

    LaunchedEffect(isHovered) {
        when (state) {
            TooltipDisplayState.IDLE -> if (isHovered) {
                state = TooltipDisplayState.HOVERING
                delay(showDelayMs)
                state = if (isHovered) TooltipDisplayState.SHOWN else TooltipDisplayState.IDLE
            }
            TooltipDisplayState.HOVERING -> if (!isHovered) {
                state = TooltipDisplayState.IDLE
            }
            TooltipDisplayState.SHOWN -> if (!isHovered) {
                state = TooltipDisplayState.HIDE_PENDING
                delay(hideDelayMs)
                if (isHovered) state = TooltipDisplayState.SHOWN else state = TooltipDisplayState.IDLE
            }
            TooltipDisplayState.HIDE_PENDING -> if (isHovered) {
                state = TooltipDisplayState.SHOWN
            }
        }
    }

    return state
}
