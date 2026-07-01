package com.soufianodev.lingallery.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.ui.icons.AppIcons
import com.soufianodev.lingallery.ui.component.TooltipIconButton

@Composable
fun FloatingZoomControl(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percentage = ((scale * 100).toInt()).coerceIn(1, 9999)
    val label = if (percentage >= 1000) "${percentage / 100}.${(percentage % 100) / 10}K" else "$percentage%"

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TooltipIconButton(
                icon = AppIcons.ZoomOut,
                tooltip = Strings.Tooltips.zoomOutCaps,
                onClick = onZoomOut,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(2.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(min = 46.dp)
                        .clickable { onReset() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            TooltipIconButton(
                icon = AppIcons.ZoomIn,
                tooltip = Strings.Tooltips.zoomInCaps,
                onClick = onZoomIn,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
