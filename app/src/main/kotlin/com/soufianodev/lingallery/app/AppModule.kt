package com.soufianodev.lingallery.app

import com.soufianodev.lingallery.devices.DeviceConnectionStateHolder
import com.soufianodev.lingallery.devices.core.DeviceDisplayNameResolver
import com.soufianodev.lingallery.native.LinLogger
import com.soufianodev.lingallery.devices.ui.DeviceActivityPresenter
import com.soufianodev.lingallery.devices.ui.DeviceIssuePresenter
import com.soufianodev.lingallery.devices.usb.mtp.MtpProtocol
import com.soufianodev.lingallery.gallery.GalleryRepository
import com.soufianodev.lingallery.gallery.GalleryStateHolder
import com.soufianodev.lingallery.native.MemoryManager
import com.soufianodev.lingallery.native.NativeLibLoader
import com.soufianodev.lingallery.native.mtp.NativeMtpBridge

import com.soufianodev.lingallery.shared.desktop.WindowBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.soufianodev.lingallery.viewer.ViewerStateHolder
import kotlinx.coroutines.CoroutineScope
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.nio.file.Path
import java.nio.file.Paths

class AppModule(
    private val scope: CoroutineScope,
    val awtWindow: java.awt.Window,
    val onNativeLibFailed: () -> Unit = {},
) {
    private val scanRoots: List<Path> = AppConst.DEFAULT_SCAN_ROOTS.map {
        Paths.get(it.replace("~", System.getProperty("user.home")))
    }

    val galleryRepository = GalleryRepository(scope, scanRoots = scanRoots)
    val galleryStateHolder = GalleryStateHolder(repository = galleryRepository, scope = scope)
    val viewerStateHolder = ViewerStateHolder(
        galleryRepository = galleryRepository,
        scope = scope,
        onImageRemovedFromAlbum = { albumPath, imagePath ->
            galleryStateHolder.updateState { it.removeImage(albumPath, imagePath) }
        },
        onImageAddedToAlbum = { albumPath, _ ->
            galleryStateHolder.updateState { it.syncAlbum(albumPath) }
        },
        onImageModified = { imagePath ->
            galleryStateHolder.onImageModified(imagePath)
        }
    )

    private var savedWindowBounds: WindowBounds? = null

    val displayNameResolver = DeviceDisplayNameResolver()

    val mtpProtocol = MtpProtocol(
        scope = scope,
        galleryStateHolder = galleryStateHolder,
        displayNameResolver = displayNameResolver,
    )

    val issuePresenter = DeviceIssuePresenter(
        strings = Strings.DeviceIssue,
        deviceRepository = mtpProtocol,
    )

    val activityPresenter = DeviceActivityPresenter(
        strings = Strings.DeviceActivity,
    )

    val deviceConnectionStateHolder = DeviceConnectionStateHolder(
        scope = scope,
        deviceRepository = mtpProtocol,
        issuePresenter = issuePresenter,
    )

    fun init() {
        LinLogger.init(minLevel = LinLogger.Level.DEBUG, nativeMinLevel = LinLogger.Level.DEBUG)
        galleryStateHolder.init(scope)
        NativeLibLoader.load()
        if (NativeLibLoader.isAvailable) {
            LinLogger.setFileLogging(true)
            MemoryManager.startMonitoring(scope)
            NativeMtpBridge.init()
            mtpProtocol.start()
        } else {
            onNativeLibFailed()
        }
    }

    fun toggleFullscreen(isFullscreen: Boolean) {
        val frame = awtWindow as? Frame ?: return
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        if (!isFullscreen) {
            savedWindowBounds = WindowBounds(
                x = frame.bounds.x, y = frame.bounds.y,
                width = frame.bounds.width, height = frame.bounds.height,
                extendedState = frame.extendedState
            )
            device.fullScreenWindow = frame
        } else {
            device.fullScreenWindow = null
            savedWindowBounds?.let { bounds ->
                frame.extendedState = bounds.extendedState
                if (bounds.extendedState and Frame.MAXIMIZED_BOTH == 0) {
                    frame.bounds = Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
                }
            }
            savedWindowBounds = null
        }
    }

    fun undoDelete(onResult: (Boolean, String) -> Unit) {
        val ctx = viewerStateHolder.consumeTrashContext() ?: run {
            onResult(false, Strings.Snackbar.undoFailed)
            return
        }
        scope.launch {
            val restoreOk = withContext(Dispatchers.IO) {
                galleryRepository.restoreFromTrash(ctx.trashPath, ctx.originalPath).isSuccess
            }
            if (!restoreOk) {
                onResult(false, Strings.Snackbar.undoFailed)
                return@launch
            }
            galleryStateHolder.updateState { it.syncAlbum(ctx.originalPath.parent) }
            if (ctx.wasCurrent) {
                val images = galleryStateHolder.uiState.value.currentAlbumImages
                val idx = images.indexOfFirst { it.path == ctx.originalPath }
                if (idx >= 0) {
                    viewerStateHolder.enter(images, idx)
                }
            }
            onResult(true, Strings.Snackbar.restored(ctx.originalPath.fileName.toString()))
        }
    }

    fun cleanup() {
        MemoryManager.stopMonitoring()
        mtpProtocol.stop()
        galleryRepository.stopWatcher()
        galleryRepository.close()
    }
}
