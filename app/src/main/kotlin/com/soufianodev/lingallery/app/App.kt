package com.soufianodev.lingallery.app

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.soufianodev.lingallery.devices.core.DeviceUiEffect
import com.soufianodev.lingallery.devices.ui.DialogActionKind
import com.soufianodev.lingallery.devices.ui.DeviceIssueDialog
import com.soufianodev.lingallery.gallery.GalleryScreen
import com.soufianodev.lingallery.gallery.Screen
import com.soufianodev.lingallery.gallery.readImageFileInfo
import com.soufianodev.lingallery.ui.component.CloseIconStyle
import com.soufianodev.lingallery.ui.component.LinGallerySnackbar
import com.soufianodev.lingallery.ui.component.SnackbarStyle
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.viewer.ViewerScreen
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import com.soufianodev.lingallery.shared.filesystem.uniqueDestination
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Composable
fun App(module: AppModule) {
    var screen by remember { mutableStateOf<Screen>(Screen.Gallery) }
    val galleryState by module.galleryStateHolder.uiState.collectAsState()
    val viewerState by module.viewerStateHolder.uiState.collectAsState()
    val deviceMounts by remember { derivedStateOf { module.mtpProtocol.mountedRoots.toSet() } }
    val deviceActivity by module.mtpProtocol.deviceActivities.collectAsState()
    val currentEffect by module.deviceConnectionStateHolder.currentEffect.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarIsError by remember { mutableStateOf(false) }
    var snackbarAutoClose by remember { mutableStateOf(true) }
    var snackbarIsDismissible by remember { mutableStateOf(true) }
    var snackbarCloseIconStyle by remember { mutableStateOf(CloseIconStyle.NORMAL) }
    var snackbarShowCloseButton by remember { mutableStateOf(false) }
    var snackbarTitle by remember { mutableStateOf("") }
    var snackbarDetails by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun showSnackbar(msg: String) {
        snackbarTitle = ""
        snackbarDetails = ""
        snackbarIsError = false
        snackbarAutoClose = true
        snackbarIsDismissible = true
        snackbarCloseIconStyle = CloseIconStyle.NORMAL
        snackbarShowCloseButton = false
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun showErrorSnackbar(msg: String) {
        snackbarTitle = ""
        snackbarDetails = ""
        snackbarIsError = true
        snackbarAutoClose = true
        snackbarIsDismissible = true
        snackbarCloseIconStyle = CloseIconStyle.NORMAL
        snackbarShowCloseButton = false
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun showUndoSnackbar(message: String, onUndo: () -> Unit) {
        snackbarTitle = ""
        snackbarDetails = ""
        snackbarIsError = false
        snackbarAutoClose = false
        snackbarIsDismissible = false
        snackbarCloseIconStyle = CloseIconStyle.NORMAL
        snackbarShowCloseButton = false
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = Strings.Buttons.undo,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndo()
            }
        }
    }

    fun showStructuredSnackbar(title: String, details: String) {
        snackbarTitle = title
        snackbarDetails = details
        snackbarIsError = false
        snackbarAutoClose = true
        snackbarIsDismissible = true
        snackbarCloseIconStyle = CloseIconStyle.NORMAL
        snackbarShowCloseButton = false
        scope.launch { snackbarHostState.showSnackbar(title) }
    }

    fun launchDirectoryPicker(op: String) {
        scope.launch {
            val directory = FileKit.openDirectoryPicker(
                dialogSettings = FileKitDialogSettings(
                    parentWindow = module.awtWindow,
                    title = Strings.Dialogs.selectFolder
                )
            )
            if (directory != null) {
                val image = module.viewerStateHolder.uiState.value.currentImage ?: return@launch
                val targetFolder = Paths.get(directory.path)
                val result = withContext(Dispatchers.IO) {
                    try {
                        val destPath = uniqueDestination(targetFolder.resolve(image.name))
                        val opts = java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        if (op == "move") Files.move(image.path, destPath, opts)
                        else Files.copy(image.path, destPath, opts)
                        val imgInfo = readImageFileInfo(destPath)
                        destPath to imgInfo
                    } catch (_: Exception) { null to null }
                }
                val (dest, newImage) = result
                if (dest != null && newImage != null) {
                    if (op == "move") {
                        module.galleryStateHolder.updateState { it.removeImage(image.path.parent, image.path) }
                        screen = Screen.Gallery
                    }
                    module.galleryStateHolder.updateState { it.addImage(targetFolder, newImage) }
                    val srcName = image.path.parent?.name ?: "?"
                    val dstName = targetFolder.name
                    val title = if (op == "move") Strings.Snackbar.transferTitleMoved else Strings.Snackbar.transferTitleCopied
                    val details = Strings.Snackbar.transferDetails(srcName, dstName)
                    showStructuredSnackbar(title, details)
                } else {
                    showErrorSnackbar(Strings.Snackbar.operationFailed)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GalleryScreen(
            stateHolder = module.galleryStateHolder,
            deviceMounts = deviceMounts,
            deviceActivity = deviceActivity,
            activityPresenter = module.activityPresenter,
            onImageSelected = { index ->
                val images = galleryState.currentAlbumImages
                if (index in images.indices) {
                    module.viewerStateHolder.enter(images, index)
                    screen = Screen.Viewer(index)
                }
            }
        )
        if (screen is Screen.Viewer) {
            ViewerScreen(
                stateHolder = module.viewerStateHolder,
                onBack = {
                    module.viewerStateHolder.stopSlideshow()
                    module.galleryStateHolder.refresh()
                    screen = Screen.Gallery
                },
                onShowSnackbar = { showSnackbar(it) },
                onShowErrorSnackbar = { showErrorSnackbar(it) },
                onShowUndoSnackbar = { msg, undo -> showUndoSnackbar(msg, undo) },
                onToggleFullscreen = { isCurrentlyFullscreen ->
                    module.toggleFullscreen(isCurrentlyFullscreen)
                    module.viewerStateHolder.toggleFullscreen()
                },
                onMove = { launchDirectoryPicker("move") },
                onCopyFile = { launchDirectoryPicker("copy") },
                onRestoreFromTrash = { cb -> module.undoDelete(cb) },
                awtWindow = module.awtWindow,
            )
        }

        when (val effect = currentEffect) {
            is DeviceUiEffect.Dialog -> DeviceIssueDialog(
                displayData = effect.displayData,
                onPrimary = {
                    when (effect.displayData.primaryAction.kind) {
                        DialogActionKind.RETRY  -> module.deviceConnectionStateHolder.establish()
                        DialogActionKind.CANCEL -> module.deviceConnectionStateHolder.cancel()
                        DialogActionKind.DISMISS -> module.deviceConnectionStateHolder.dismiss()
                    }
                },
                onSecondary = effect.displayData.secondaryAction?.let { action ->
                    { when (action.kind) {
                        DialogActionKind.RETRY  -> module.deviceConnectionStateHolder.establish()
                        DialogActionKind.CANCEL -> {
                            module.deviceConnectionStateHolder.cancel()
                            showSnackbar(Strings.DeviceIssue.reconnectGuidance)
                        }
                        DialogActionKind.DISMISS -> module.deviceConnectionStateHolder.dismiss()
                    }}
                },
            )
            is DeviceUiEffect.Snackbar -> {
                LaunchedEffect(effect) {
                    showErrorSnackbar(effect.message)
                }
            }
            null -> { }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            val snackbarData = snackbarHostState.currentSnackbarData
            var lastSnackbarData by remember { mutableStateOf<SnackbarData?>(null) }
            var lastDataIsError by remember { mutableStateOf(false) }
            var lastSnackbarTitle by remember { mutableStateOf("") }
            var lastSnackbarDetails by remember { mutableStateOf("") }
            if (snackbarData != null) {
                lastSnackbarData = snackbarData
                lastDataIsError = snackbarIsError
                lastSnackbarTitle = snackbarTitle
                lastSnackbarDetails = snackbarDetails
            }

            AnimatedVisibility(
                visible = snackbarData != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                lastSnackbarData?.let { data ->
                    val hasAction = data.visuals.actionLabel != null
                    val style = when {
                        hasAction && lastDataIsError -> SnackbarStyle.ERROR_ACTION
                        hasAction -> SnackbarStyle.ACTION
                        lastDataIsError -> SnackbarStyle.ERROR
                        else -> SnackbarStyle.SUCCESS
                    }

                    LinGallerySnackbar(
                        message = data.visuals.message,
                        title = lastSnackbarTitle,
                        details = lastSnackbarDetails,
                        style = style,
                        modifier = Modifier.padding(top = (AppConst.TOP_BAR_HEIGHT + 12).dp),
                        actionLabel = data.visuals.actionLabel,
                        onAction = { data.performAction() },
                        onDismiss = { data.dismiss() },
                        autoClose = snackbarAutoClose,
                        isDismissible = snackbarIsDismissible,
                        closeIconStyle = snackbarCloseIconStyle,
                        showCloseButton = snackbarShowCloseButton
                    )
                }
            }

            LaunchedEffect(snackbarData) {
                val effectiveAutoClose = if (!snackbarAutoClose && !snackbarIsDismissible) true else snackbarAutoClose
                if (snackbarData != null && effectiveAutoClose) {
                    val hasAction = snackbarData.visuals.actionLabel != null
                    when (snackbarData.visuals.duration) {
                        SnackbarDuration.Short -> delay(4000L)
                        SnackbarDuration.Long -> delay(10000L)
                        SnackbarDuration.Indefinite -> {
                            if (hasAction) delay(7000L) else return@LaunchedEffect
                        }
                    }
                    snackbarData.dismiss()
                }
            }
        }
    }
}
