package com.soufianodev.lingallery.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import kotlin.math.max

private class TooltipPositionProvider(
    private val gapPx: Int,
    private val preferAbove: Boolean
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2
        var x = anchorCenterX - popupContentSize.width / 2
        x = x.coerceIn(0, max(0, windowSize.width - popupContentSize.width))

        val spaceAbove = anchorBounds.top
        val spaceBelow = windowSize.height - anchorBounds.bottom
        val showAbove = when {
            preferAbove && spaceAbove >= popupContentSize.height + gapPx -> true
            !preferAbove && spaceBelow >= popupContentSize.height + gapPx -> false
            spaceAbove >= spaceBelow -> true
            else -> false
        }

        val y = if (showAbove) {
            anchorBounds.top - popupContentSize.height - gapPx
        } else {
            anchorBounds.bottom + gapPx
        }.coerceIn(0, max(0, windowSize.height - popupContentSize.height))

        return IntOffset(x, y)
    }
}

@Composable
fun TooltipIconButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    tint: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
    showDelayMs: Long = 400L,
    hideDelayMs: Long = 150L,
    preferTooltipAbove: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tooltipState = rememberTooltipState(interactionSource, showDelayMs, hideDelayMs)
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }

    Box(modifier = modifier.size(buttonSize)) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .matchParentSize()
                .stablePointerHoverIcon(PointerIcon.Hand),
            interactionSource = interactionSource
        ) {
            val iconTint = if (enabled) tint else tint.copy(alpha = 0.38f)
            Icon(imageVector = icon, contentDescription = tooltip, tint = iconTint)
        }

        if (tooltipState == TooltipDisplayState.SHOWN) {
            Popup(
                popupPositionProvider = TooltipPositionProvider(gapPx, preferTooltipAbove),
                onDismissRequest = {},
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF333333),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = tooltip,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
