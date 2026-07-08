package com.soufianodev.lingallery.viewer

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.gallery.CropRect
import com.soufianodev.lingallery.ui.component.TooltipIconButton
import com.soufianodev.lingallery.ui.component.stablePointerHoverIcon
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.soufianodev.lingallery.ui.icons.AppIcons
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.ui.theme.LightPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(
    stateHolder: ViewerStateHolder,
    onBack: () -> Unit,
    onShowSnackbar: (message: String) -> Unit,
    onShowErrorSnackbar: (message: String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit) -> Unit,
    onToggleFullscreen: (isFullscreen: Boolean) -> Unit,
    onMove: () -> Unit = {},
    onCopyFile: () -> Unit = {},
    onRestoreFromTrash: (onDone: (Boolean, String) -> Unit) -> Unit = {},
    awtWindow: java.awt.Window? = null,
) {
    val state by stateHolder.uiState.collectAsState()
    val currentImage = state.currentImage
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val isDark = true
    val bg = if (isDark) DarkPalette.BACKGROUND else LightPalette.BACKGROUND
    val surface = if (isDark) DarkPalette.SURFACE else LightPalette.SURFACE
    val onSurface = if (isDark) DarkPalette.ON_SURFACE else LightPalette.ON_SURFACE
    val onSurfaceVariant = if (isDark) DarkPalette.ON_SURFACE_VARIANT else LightPalette.ON_SURFACE_VARIANT
    val primary = if (isDark) DarkPalette.PRIMARY else LightPalette.PRIMARY
    val outlineVariant = if (isDark) DarkPalette.OUTLINE_VARIANT else LightPalette.OUTLINE_VARIANT

    var showInfoDialog by remember { mutableStateOf(false) }
    var exifData by remember { mutableStateOf<Map<String, String>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPermanentDeleteWarning by remember { mutableStateOf(false) }
    var permanentDeleteChecked by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showCropDialog by remember { mutableStateOf(false) }
    var cropModeTransitioning by remember { mutableStateOf(false) }

    val anyDialogOpen = showInfoDialog || showDeleteConfirm || showRenameDialog || showCropDialog || showPermanentDeleteWarning

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(anyDialogOpen, state.isFullscreen) {
        if (!anyDialogOpen) {
            focusRequester.requestFocus()
        }
    }

    DisposableEffect(awtWindow) {
        val adapter = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.button == 4 || e.button == 6) {
                    stateHolder.stopSlideshow()
                    onBack()
                }
            }
        }
        awtWindow?.addMouseListener(adapter)
        onDispose { awtWindow?.removeMouseListener(adapter) }
    }

    fun zoomIn() {
        stateHolder.zoom(AppConst.ZOOM_STEP.toFloat())
    }

    fun zoomOut() {
        stateHolder.zoom(1f / AppConst.ZOOM_STEP.toFloat())
    }

    fun showExifInfo() {
        stateHolder.readExif { data ->
            exifData = data
            showInfoDialog = true
        }
    }

    fun showRename() {
        val name = currentImage?.name ?: return
        val dot = name.lastIndexOf('.')
        renameText = if (dot >= 0) name.substring(0, dot) else name
        showRenameDialog = true
    }

    fun executeRename() {
        val newName = renameText.trim().trimEnd('.')
        if (newName.isEmpty()) return
        val img = currentImage ?: return
        showRenameDialog = false
        val ext = img.extension.removePrefix(".")
        val fullName = if (ext.isNotEmpty()) "$newName.$ext" else newName
        if (img.name == fullName) return
        stateHolder.rename(fullName) { ok ->
            if (ok) onShowSnackbar(Strings.Snackbar.imageRenamed)
            else onShowErrorSnackbar(Strings.Snackbar.renameFailed)
        }
    }

    fun deleteCurrentImage() {
        permanentDeleteChecked = false
        showDeleteConfirm = true
    }

    fun confirmDelete() {
        if (permanentDeleteChecked) {
            showDeleteConfirm = false
            showPermanentDeleteWarning = true
            return
        }
        val deletedImageName = currentImage?.name ?: ""
        showDeleteConfirm = false
        stateHolder.moveToTrash { ok ->
            if (ok) {
                onShowUndoSnackbar(Strings.Snackbar.deleted(deletedImageName)) {
                    onRestoreFromTrash { restored, msg ->
                        if (restored) onShowSnackbar(msg)
                        else onShowErrorSnackbar(msg)
                    }
                }
            } else onShowErrorSnackbar(Strings.Snackbar.deleteFailed)
        }
    }

    fun confirmPermanentDelete() {
        showPermanentDeleteWarning = false
        val name = currentImage?.name ?: ""
        stateHolder.deletePermanently { ok ->
            if (ok) onShowSnackbar(Strings.Snackbar.permanentlyDeleted(name))
            else onShowErrorSnackbar(Strings.Snackbar.deleteFailed)
        }
    }

    fun doApplyCrop() {
        showCropDialog = true
    }

    fun executeCrop(choice: String) {
        showCropDialog = false
        cropModeTransitioning = false
        if (choice == "copy") {
            stateHolder.saveCropCopy { ok ->
                if (ok) onShowSnackbar(Strings.Snackbar.savedCopy)
                else onShowErrorSnackbar(Strings.Snackbar.cropFailed)
            }
        } else {
            stateHolder.applyCrop { ok ->
                if (ok) onShowSnackbar(Strings.Snackbar.imageCropped)
                else onShowErrorSnackbar(Strings.Snackbar.cropFailed)
            }
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.Escape -> {
                if (state.isFullscreen) {
                    onToggleFullscreen(true)
                } else if (state.isCropping) {
                    stateHolder.cancelCrop()
                    cropModeTransitioning = false
                } else {
                    stateHolder.stopSlideshow()
                    onBack()
                }
                true
            }
            Key.DirectionLeft, Key.A -> { stateHolder.navigateImage(-1); true }
            Key.DirectionRight, Key.D -> { stateHolder.navigateImage(1); true }
            Key.Spacebar -> { stateHolder.toggleSlideshow(); true }
            Key.F -> { onToggleFullscreen(state.isFullscreen); true }
            Key.Plus, Key.Equals -> { zoomIn(); true }
            Key.Minus -> { zoomOut(); true }
            Key.F2 -> { stateHolder.resetView(); true }
            Key.L -> { stateHolder.rotate(-90); true }
            Key.R -> { stateHolder.rotate(90); true }
            Key.I -> { showExifInfo(); true }
            Key.C -> {
                if (cropModeTransitioning) true
                else {
                    cropModeTransitioning = true
                    stateHolder.toggleCrop()
                    scope.launch { delay(300); cropModeTransitioning = false }
                    true
                }
            }
            Key.Delete -> { deleteCurrentImage(); true }
            Key.Z -> {
                if (event.isCtrlPressed) {
                    if (event.isShiftPressed) stateHolder.svgRedo()
                    else stateHolder.svgUndo()
                    true
                } else false
            }
            Key.Y -> {
                if (event.isCtrlPressed) { stateHolder.svgRedo(); true } else false
            }
            else -> false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clipToBounds()
            .focusTarget()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar) {
                    stateHolder.toggleSlideshow()
                    true
                } else {
                    false
                }
            }
            .onKeyEvent(::handleKeyEvent)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(bg).clipToBounds()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (state.isFullscreen) 0.dp else AppConst.TOP_BAR_HEIGHT.dp)
                    .graphicsLayer { alpha = if (state.isFullscreen) 0f else 1f },
                color = surface
            ) {
                AnimatedContent(
                    targetState = state.isCropping,
                    transitionSpec = {
                        if (targetState) {
                            (slideInVertically(tween(280, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(280, easing = FastOutSlowInEasing))) togetherWith
                            (slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(280, easing = FastOutSlowInEasing)))
                        } else {
                            (slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(280, easing = FastOutSlowInEasing))) togetherWith
                            (slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(280, easing = FastOutSlowInEasing)))
                        }
                    },
                    label = "toolbar_mode"
                ) { isCropping ->
                    if (isCropping) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TooltipIconButton(
                                icon = AppIcons.Crop,
                                tooltip = Strings.Crop.mode,
                                onClick = {},
                                tint = primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = Strings.Crop.mode,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primary
                            )
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { stateHolder.cancelCrop(); cropModeTransitioning = false },
                                shape = RoundedCornerShape(20.dp)
                            ) { Text(Strings.Buttons.cancel) }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { doApplyCrop() },
                                enabled = state.cropRect != null,
                                colors = ButtonDefaults.buttonColors(containerColor = primary),
                                shape = RoundedCornerShape(20.dp)
                            ) { Text(Strings.Buttons.apply) }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TooltipIconButton(
                                icon = AppIcons.ArrowBack,
                                tooltip = Strings.Tooltips.backGallery,
                                onClick = { stateHolder.stopSlideshow(); onBack() },
                                tint = onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = currentImage?.name ?: "",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = Strings.Viewer.counter(state.currentIndex + 1, state.images.size),
                                fontSize = 12.sp,
                                color = onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            TooltipIconButton(
                                icon = AppIcons.ZoomOut,
                                tooltip = Strings.Tooltips.zoomOut,
                                onClick = { zoomOut() },
                                tint = onSurface,
                                preferTooltipAbove = false
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 0.dp
                            ) {
                                val zoomPercentage = ((state.scale * 100).toInt()).coerceIn(1, 9999)
                                val zoomLabel = if (zoomPercentage >= 1000) "${zoomPercentage / 100}.${(zoomPercentage % 100) / 10}K" else "$zoomPercentage%"
                                Text(
                                    text = zoomLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .widthIn(min = 46.dp)
                                        .clickable { stateHolder.resetView() }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            TooltipIconButton(
                                icon = AppIcons.ZoomIn,
                                tooltip = Strings.Tooltips.zoomIn,
                                onClick = { zoomIn() },
                                tint = onSurface,
                                preferTooltipAbove = false
                            )
                            Spacer(Modifier.width(8.dp))
                            TooltipIconButton(
                                icon = if (state.slideshowActive) AppIcons.Pause else AppIcons.PlayArrow,
                                tooltip = Strings.Tooltips.slideshow,
                                onClick = { stateHolder.toggleSlideshow() },
                                tint = if (state.slideshowActive) primary else onSurface,
                                preferTooltipAbove = false
                            )
                            TooltipIconButton(
                                icon = if (state.isFullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                                tooltip = Strings.Tooltips.fullscreen,
                                onClick = { onToggleFullscreen(state.isFullscreen) },
                                tint = onSurface,
                                preferTooltipAbove = false
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = outlineVariant,
                modifier = Modifier
                    .height(if (state.isFullscreen) 0.dp else 1.dp)
                    .graphicsLayer { alpha = if (state.isFullscreen) 0f else 1f }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ImageDisplay(
                    path = currentImage?.path,
                    scale = state.scale,
                    panX = state.panX,
                    panY = state.panY,
                    imageLastModified = currentImage?.lastModified ?: 0L,
                    isCropping = state.isCropping,
                    cropRect = state.cropRect?.let {
                        Rect(it.x, it.y, it.x + it.width, it.y + it.height)
                    },
                    onScaleChange = { scale -> stateHolder.setScale(scale) },
                    onPanChange = { px, py -> stateHolder.panDelta(px, py) },
                    onCropRectChange = { displayCrop ->
                        stateHolder.setCropRect(
                            if (displayCrop != null) CropRect(
                                displayCrop.rect.left, displayCrop.rect.top,
                                displayCrop.rect.width, displayCrop.rect.height
                            ) else null
                        )
                    },
                    images = state.images,
                    currentIndex = state.currentIndex,
                    svgDocumentHandle = state.svgDocumentHandle,
                    svgEditVersion = state.svgEditVersion,
                    svgSourceWidth = state.svgSourceWidth,
                    svgSourceHeight = state.svgSourceHeight,
                    modifier = Modifier.fillMaxSize()
                )

                NavColumnOverlay(
                    visible = !state.isCropping,
                    bg = bg,
                    onSurface = onSurface,
                    prevEnabled = state.prevEnabled,
                    nextEnabled = state.nextEnabled,
                    onPrev = { stateHolder.navigateImage(-1) },
                    onNext = { stateHolder.navigateImage(1) }
                )

                CropZoomControl(
                    visible = state.isCropping,
                    scale = state.scale,
                    onZoomIn = { zoomIn() },
                    onZoomOut = { zoomOut() },
                    onReset = { stateHolder.setScale(1f) }
                )

                if (state.isFullscreen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(40.dp)
                            .stablePointerHoverIcon(PointerIcon.Hand)
                            .clickable { onToggleFullscreen(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.FullscreenExit,
                            contentDescription = Strings.ContentDesc.exitFullscreen,
                            tint = onSurface
                        )
                    }
                }
            }

            HorizontalDivider(
                color = outlineVariant,
                modifier = Modifier
                    .height(if (state.isFullscreen) 0.dp else 1.dp)
                    .graphicsLayer { alpha = if (state.isFullscreen) 0f else 1f }
            )

            AnimatedVisibility(
                visible = !state.isCropping && !state.isFullscreen,
                enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(280, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it }
            ) {
                EditToolbar(
                    isEditableFormat = currentImage?.let { it.extension.lowercase() in AppConst.EDITABLE_FORMATS } ?: false,
                    onRotateLeft = { stateHolder.rotate(-90) { ok ->
                        if (!ok) onShowErrorSnackbar(Strings.Snackbar.rotateFailed)
                    }},
                    onRotateRight = { stateHolder.rotate(90) { ok ->
                        if (!ok) onShowErrorSnackbar(Strings.Snackbar.rotateFailed)
                    }},
                    onFlipH = { stateHolder.flip() { ok ->
                        if (!ok) onShowErrorSnackbar(Strings.Snackbar.flipFailed)
                    }},
                    onCrop = {
                        if (!cropModeTransitioning) {
                            cropModeTransitioning = true
                            stateHolder.toggleCrop()
                            scope.launch { delay(300); cropModeTransitioning = false }
                        }
                    },
                    onCopyClipboard = { stateHolder.copyImage { ok ->
                        if (ok) {
                            val msg = if (currentImage?.extension?.lowercase() == ".svg") Strings.Snackbar.svgCodeCopied else Strings.Snackbar.imageCopied
                            onShowSnackbar(msg)
                        } else {
                            val msg = if (currentImage?.extension?.lowercase() == ".svg") Strings.Snackbar.svgCodeCopyFailed else Strings.Snackbar.copyFailed
                            onShowErrorSnackbar(msg)
                        }
                    }},
                    onCopyName = { stateHolder.copyImageName { ok ->
                        if (ok) onShowSnackbar(Strings.Snackbar.nameCopied)
                        else onShowErrorSnackbar(Strings.Snackbar.nameCopyFailed)
                    }},
                    onCopyPath = { stateHolder.copyImagePath { ok ->
                        if (ok) onShowSnackbar(Strings.Snackbar.pathCopied)
                        else onShowErrorSnackbar(Strings.Snackbar.copyFailed)
                    }},
                    onMove = onMove,
                    onCopyFile = onCopyFile,
                    onRename = { showRename() },
                    onInfo = { showExifInfo() },
                    onDelete = { deleteCurrentImage() },
                    isDark = isDark,
                    isSvg = currentImage?.extension?.lowercase() == ".svg",
                )
            }
        }

        if (showInfoDialog && exifData != null) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text(Strings.Dialogs.details, fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column {
                        Text(Strings.Labels.filenameColon(currentImage?.name ?: ""), fontSize = 12.sp, color = onSurface)
                        Spacer(Modifier.height(8.dp))
                        exifData!!.forEach { (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, fontSize = 12.sp, color = onSurfaceVariant, modifier = Modifier.weight(0.4f))
                                Text(value, fontSize = 12.sp, color = onSurface, modifier = Modifier.weight(0.6f))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text(Strings.Buttons.close) } }
            )
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text(Strings.Dialogs.renameImage, fontWeight = FontWeight.ExtraBold) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(Strings.Labels.filename) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primary,
                            cursorColor = primary
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { executeRename() },
                        colors = ButtonDefaults.buttonColors(containerColor = primary),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text(Strings.Buttons.rename) }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text(Strings.Buttons.cancel) }
                }
            )
        }

        if (showCropDialog) {
            AlertDialog(
                onDismissRequest = { showCropDialog = false },
                title = { Text(Strings.Dialogs.saveCrop, fontWeight = FontWeight.ExtraBold) },
                text = { Text(Strings.Crop.saveQuestion) },
                confirmButton = {
                    Button(
                        onClick = { executeCrop("copy") },
                        colors = ButtonDefaults.buttonColors(containerColor = primary),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text(Strings.Buttons.saveCopy) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showCropDialog = false }) { Text(Strings.Buttons.cancel) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { executeCrop("overwrite") }) { Text(Strings.Buttons.overwrite) }
                    }
                }
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = {
                    permanentDeleteChecked = false
                    showDeleteConfirm = false
                },
                title = { Text(Strings.Dialogs.deleteImage) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            imageVector = AppIcons.Delete,
                            contentDescription = Strings.ContentDesc.delete,
                            modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                            tint = DarkPalette.ON_SURFACE_VARIANT
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            Strings.Dialogs.deleteConfirm(currentImage?.name ?: ""),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = permanentDeleteChecked,
                                onCheckedChange = { permanentDeleteChecked = it }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(Strings.Dialogs.permanentDeleteCheck, fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { confirmDelete() }, colors = ButtonDefaults.buttonColors(containerColor = DarkPalette.ERROR)) {
                        Text(Strings.Buttons.delete)
                    }
                },
                dismissButton = { TextButton(onClick = {
                    permanentDeleteChecked = false
                    showDeleteConfirm = false
                }) { Text(Strings.Buttons.cancel) } }
            )
        }

        if (showPermanentDeleteWarning) {
            AlertDialog(
                onDismissRequest = { showPermanentDeleteWarning = false },
                title = { Text(Strings.Dialogs.permanentlyDelete) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            imageVector = AppIcons.Warning,
                            contentDescription = Strings.ContentDesc.warning,
                            modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                            tint = DarkPalette.ERROR
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            Strings.Dialogs.permanentDeleteWarning(currentImage?.name ?: ""),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { confirmPermanentDelete() }, colors = ButtonDefaults.buttonColors(containerColor = DarkPalette.ERROR)) {
                        Text(Strings.Buttons.deletePermanently)
                    }
                },
                dismissButton = { TextButton(onClick = { showPermanentDeleteWarning = false }) { Text(Strings.Buttons.cancel) } }
            )
        }
    }
}

@Composable
private fun NavColumnOverlay(
    visible: Boolean,
    bg: Color,
    onSurface: Color,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it },
            exit = fadeOut(tween(280, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it }
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                TooltipIconButton(
                    icon = AppIcons.NavigateBefore,
                    tooltip = Strings.Tooltips.previous,
                    onClick = onPrev,
                    enabled = prevEnabled,
                    tint = onSurface
                )
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it },
            exit = fadeOut(tween(280, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it }
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                TooltipIconButton(
                    icon = AppIcons.NavigateNext,
                    tooltip = Strings.Tooltips.next,
                    onClick = onNext,
                    enabled = nextEnabled,
                    tint = onSurface
                )
            }
        }
    }
}

@Composable
private fun CropZoomControl(
    visible: Boolean,
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
            scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.9f),
        exit = fadeOut(tween(200, easing = FastOutSlowInEasing)) +
            scaleOut(tween(200, easing = FastOutSlowInEasing), targetScale = 0.9f)
    ) {
        Box(Modifier.fillMaxSize()) {
            FloatingZoomControl(
                scale = scale,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onReset = onReset,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
            )
        }
    }
}
