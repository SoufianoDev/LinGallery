package com.soufianodev.lingallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.ui.theme.DarkPalette

enum class SnackbarStyle {
    SUCCESS,
    ERROR,
    ACTION,
    ERROR_ACTION
}

enum class CloseIconStyle {
    NORMAL,
    FILLED
}

@Composable
fun LinGallerySnackbar(
    message: String,
    style: SnackbarStyle,
    modifier: Modifier = Modifier,
    title: String = "",
    details: String = "",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    autoClose: Boolean = true,
    isDismissible: Boolean = true,
    closeIconStyle: CloseIconStyle = CloseIconStyle.NORMAL,
    showCloseButton: Boolean = false
) {
    val effectiveAutoClose = if (!autoClose && !isDismissible) true else autoClose
    val borderColor: Color
    val glowColor: Color
    when (style) {
        SnackbarStyle.SUCCESS,
        SnackbarStyle.ACTION -> {
            borderColor = DarkPalette.PRIMARY
            glowColor = DarkPalette.PRIMARY
        }
        SnackbarStyle.ERROR,
        SnackbarStyle.ERROR_ACTION -> {
            borderColor = DarkPalette.SNACKBAR_ERROR_BORDER
            glowColor = DarkPalette.SNACKBAR_ERROR_BORDER
        }
    }

    val cornerRadius = 16.dp
    val glowRadius = 16.dp

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .drawBehind {
                        drawRoundRect(
                            color = glowColor.copy(alpha = 0.35f),
                            cornerRadius = CornerRadius(cornerRadius.toPx())
                        )
                    }
            )

            Box(
                modifier = Modifier
                    .widthIn(min = 200.dp, max = 520.dp)
                    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
                    .background(DarkPalette.SURFACE_VARIANT, RoundedCornerShape(cornerRadius))
            ) {
                when (style) {
                    SnackbarStyle.SUCCESS,
                    SnackbarStyle.ERROR -> {
                        val icon = when (style) {
                            SnackbarStyle.SUCCESS -> Icons.Filled.CheckCircle
                            SnackbarStyle.ERROR   -> Icons.Filled.Cancel
                        }
                        val iconTint = when (style) {
                            SnackbarStyle.SUCCESS -> DarkPalette.PRIMARY
                            SnackbarStyle.ERROR   -> DarkPalette.SNACKBAR_ERROR
                        }

                        if (details.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 24.dp,
                                    vertical   = 14.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector     = icon,
                                    contentDescription = null,
                                    tint            = iconTint,
                                    modifier        = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text  = title.ifEmpty { message },
                                        color = DarkPalette.ON_SURFACE,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text  = details,
                                        color = DarkPalette.ON_SURFACE_VARIANT,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isDismissible && (showCloseButton || !effectiveAutoClose)) {
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { onDismiss?.invoke() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (closeIconStyle == CloseIconStyle.FILLED) Icons.Filled.Cancel else Icons.Filled.Close,
                                            contentDescription = Strings.ContentDesc.dismiss,
                                            tint = borderColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 24.dp,
                                    vertical   = 18.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector     = icon,
                                    contentDescription = null,
                                    tint            = iconTint,
                                    modifier        = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text  = message,
                                    color = DarkPalette.ON_SURFACE,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isDismissible && (showCloseButton || !effectiveAutoClose)) {
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { onDismiss?.invoke() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (closeIconStyle == CloseIconStyle.FILLED) Icons.Filled.Cancel else Icons.Filled.Close,
                                            contentDescription = Strings.ContentDesc.dismiss,
                                            tint = borderColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SnackbarStyle.ACTION -> {
                        Row(
                            modifier = Modifier.padding(
                                start  = 24.dp,
                                end    = 12.dp,
                                top    = 6.dp,
                                bottom = 6.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text  = message,
                                color = DarkPalette.ON_SURFACE,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.width(24.dp))
                            val interactionSource = remember { MutableInteractionSource() }
                            val isHovered by interactionSource.collectIsHoveredAsState()
                            TextButton(
                                onClick           = { onAction?.invoke() },
                                interactionSource = interactionSource,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = DarkPalette.PRIMARY
                                ),
                                modifier = Modifier
                                    .then(
                                        if (isHovered) Modifier.background(
                                            DarkPalette.PRIMARY.copy(alpha = 0.08f),
                                            RoundedCornerShape(20.dp)
                                        ) else Modifier
                                    )
                            ) {
                                Text(
                                    text       = actionLabel ?: "",
                                    style      = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (isDismissible && (showCloseButton || !effectiveAutoClose)) {
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { onDismiss?.invoke() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (closeIconStyle == CloseIconStyle.FILLED) Icons.Filled.Cancel else Icons.Filled.Close,
                                        contentDescription = Strings.ContentDesc.dismiss,
                                        tint = borderColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    SnackbarStyle.ERROR_ACTION -> {
                        Row(
                            modifier = Modifier.padding(
                                start  = 24.dp,
                                end    = 12.dp,
                                top    = 6.dp,
                                bottom = 6.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text  = message,
                                color = DarkPalette.ON_SURFACE,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.width(24.dp))
                            val interactionSource = remember { MutableInteractionSource() }
                            val isHovered by interactionSource.collectIsHoveredAsState()
                            TextButton(
                                onClick           = { onAction?.invoke() },
                                interactionSource = interactionSource,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor      = DarkPalette.SNACKBAR_ERROR,
                                    containerColor    = DarkPalette.SNACKBAR_ERROR.copy(alpha = 0.18f)
                                ),
                                modifier = Modifier.then(
                                    if (isHovered) Modifier.background(
                                        DarkPalette.SNACKBAR_ERROR.copy(alpha = 0.14f),
                                        RoundedCornerShape(20.dp)
                                    ) else Modifier
                                )
                            ) {
                                Text(
                                    text       = actionLabel ?: "",
                                    style      = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (isDismissible && (showCloseButton || !effectiveAutoClose)) {
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { onDismiss?.invoke() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (closeIconStyle == CloseIconStyle.FILLED) Icons.Filled.Cancel else Icons.Filled.Close,
                                        contentDescription = Strings.ContentDesc.dismiss,
                                        tint = borderColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
