package com.soufianodev.lingallery.viewer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.ui.icons.AppIcons
import com.soufianodev.lingallery.ui.component.TooltipIconButton
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.ui.theme.LightPalette

@Composable
fun EditToolbar(
    isEditableFormat: Boolean,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipH: () -> Unit,
    onCrop: () -> Unit,
    onCopyClipboard: () -> Unit,
    onCopyName: () -> Unit,
    onCopyPath: () -> Unit,
    onMove: () -> Unit,
    onCopyFile: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    isDark: Boolean,
    isSvg: Boolean = false,
) {
    val onSurface = if (isDark) DarkPalette.ON_SURFACE else LightPalette.ON_SURFACE
    val surface = if (isDark) DarkPalette.SURFACE else LightPalette.SURFACE
    val outlineVariant = if (isDark) DarkPalette.OUTLINE_VARIANT else LightPalette.OUTLINE_VARIANT
    var showCopyMenu by remember { mutableStateOf(false) }
    var showTransferMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().height(AppConst.BOTTOM_BAR_HEIGHT.dp),
        color = surface
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))

            TooltipIconButton(icon = AppIcons.RotateLeft, tooltip = Strings.Tooltips.rotateLeft, onClick = onRotateLeft, tint = onSurface, enabled = isEditableFormat)
            TooltipIconButton(icon = AppIcons.RotateRight, tooltip = Strings.Tooltips.rotateRight, onClick = onRotateRight, tint = onSurface, enabled = isEditableFormat)
            TooltipIconButton(icon = AppIcons.Flip, tooltip = Strings.Tooltips.flipHorizontal, onClick = onFlipH, tint = onSurface, enabled = isEditableFormat)

            Spacer(Modifier.width(16.dp))

            TooltipIconButton(icon = AppIcons.Crop, tooltip = Strings.Tooltips.crop, onClick = onCrop, tint = onSurface, enabled = isEditableFormat)

            Spacer(Modifier.width(24.dp))

            // Copy button with dropdown
            Box {
                TooltipIconButton(icon = AppIcons.ContentCopy, tooltip = Strings.Tooltips.copyImage, onClick = { showCopyMenu = !showCopyMenu }, tint = onSurface)
                DropdownMenu(
                    expanded = showCopyMenu,
                    onDismissRequest = { showCopyMenu = false },
                    properties = PopupProperties(focusable = false)
                ) {
                    val copyLabel = if (isSvg) Strings.Menu.copySvgCode else Strings.Menu.copyClipboard
                    DropdownMenuItem(text = { Text(copyLabel, fontSize = 13.sp) }, onClick = { showCopyMenu = false; onCopyClipboard() })
                    DropdownMenuItem(text = { Text(Strings.Menu.copyName, fontSize = 13.sp) }, onClick = { showCopyMenu = false; onCopyName() })
                    DropdownMenuItem(text = { Text(Strings.Menu.copyPath, fontSize = 13.sp) }, onClick = { showCopyMenu = false; onCopyPath() })
                }
            }

            // Transfer button with dropdown
            Box {
                TooltipIconButton(icon = AppIcons.Transfer, tooltip = Strings.Tooltips.transferImage, onClick = { showTransferMenu = !showTransferMenu }, tint = onSurface)
                DropdownMenu(
                    expanded = showTransferMenu,
                    onDismissRequest = { showTransferMenu = false },
                    properties = PopupProperties(focusable = false)
                ) {
                    DropdownMenuItem(text = { Text(Strings.Menu.moveFolder, fontSize = 13.sp) }, onClick = { showTransferMenu = false; onMove() })
                    DropdownMenuItem(text = { Text(Strings.Menu.copyFolder, fontSize = 13.sp) }, onClick = { showTransferMenu = false; onCopyFile() })
                }
            }

            TooltipIconButton(icon = AppIcons.Edit, tooltip = Strings.Tooltips.rename, onClick = onRename, tint = onSurface)

            Spacer(Modifier.width(24.dp))

            TooltipIconButton(icon = AppIcons.Info, tooltip = Strings.Tooltips.imageInfo, onClick = onInfo, tint = onSurface)
            TooltipIconButton(icon = AppIcons.Delete, tooltip = Strings.Tooltips.delete, onClick = onDelete, tint = onSurface)

            Spacer(Modifier.width(24.dp))

            Spacer(Modifier.weight(1f))
        }
    }
}
