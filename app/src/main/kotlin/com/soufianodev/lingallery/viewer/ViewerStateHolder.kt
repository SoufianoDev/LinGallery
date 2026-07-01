package com.soufianodev.lingallery.viewer

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.SingletonSketch
import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.gallery.CropRect
import com.soufianodev.lingallery.gallery.GalleryRepository
import com.soufianodev.lingallery.model.ImageFile
import com.soufianodev.lingallery.native.MemoryManager
import com.soufianodev.lingallery.native.NativeImagePipeline
import com.soufianodev.lingallery.native.NativeSvgPipeline
import com.soufianodev.lingallery.shared.desktop.copyImageToClipboard
import com.soufianodev.lingallery.shared.desktop.copyToClipboard
import com.soufianodev.lingallery.shared.filesystem.uniqueDestination
import com.soufianodev.lingallery.shared.imaging.ImageEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

data class TrashContext(
    val trashPath: Path,
    val originalPath: Path,
    val wasCurrent: Boolean
)

data class ViewerUiState(
    val images: List<ImageFile> = emptyList(),
    val currentIndex: Int = 0,
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val isCropping: Boolean = false,
    val cropRect: CropRect? = null,
    val isFullscreen: Boolean = false,
    val slideshowActive: Boolean = false,
    val svgDocumentHandle: Long = -1L,
    val svgEditVersion: Int = 0,
    val svgSourceWidth: Float = 0f,
    val svgSourceHeight: Float = 0f,
    val svgCanUndo: Boolean = false,
    val svgCanRedo: Boolean = false,
) {
    val currentImage: ImageFile?
        get() = images.getOrNull(currentIndex)
    val prevEnabled: Boolean get() = currentIndex > 0
    val nextEnabled: Boolean get() = currentIndex < images.size - 1
}

class ViewerStateHolder(
    private val galleryRepository: GalleryRepository,
    private val scope: CoroutineScope,
    private val onImageRemovedFromAlbum: (albumPath: Path, imagePath: Path) -> Unit = { _, _ -> },
    private val onImageAddedToAlbum: (albumPath: Path, imagePath: Path) -> Unit = { _, _ -> },
    private val onImageModified: (imagePath: Path) -> Unit = {},
) {
    private val slideshowController = SlideshowController(onAdvance = { navigateImage(1) })
    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var _lastTrashPath: Path? = null
    private var _lastImagePath: Path? = null
    private var _lastAlbumWasCurrent: Boolean = false

    fun consumeTrashContext(): TrashContext? {
        val t = _lastTrashPath ?: return null
        val i = _lastImagePath ?: return null
        val wasCurrent = _lastAlbumWasCurrent
        _lastTrashPath = null
        _lastImagePath = null
        _lastAlbumWasCurrent = false
        return TrashContext(trashPath = t, originalPath = i, wasCurrent = wasCurrent)
    }

    private var svgEditHistory = SvgEditHistory()
    private var currentSvgPath: Path? = null

    private fun loadSvgDocument(path: Path): Long {
        val handle = NativeSvgPipeline.load(path)
        // Always update state — even on failure — so a stale handle from the
        // previous image is never reused for this file.
        val size = if (handle >= 0L) NativeSvgPipeline.getSize(handle) else null
        val sw = size?.width ?: 0f
        val sh = size?.height ?: 0f
        _uiState.value = _uiState.value.copy(
            svgDocumentHandle = handle,   // will be -1L on failure, which is correct
            svgEditVersion = 0,
            svgSourceWidth = sw,
            svgSourceHeight = sh,
            svgCanUndo = false,
            svgCanRedo = false,
        )
        currentSvgPath = path
        svgEditHistory = SvgEditHistory()
        return handle
    }

    private fun releaseSvg() {
        val prev = _uiState.value.svgDocumentHandle
        if (prev >= 0L) {
            NativeSvgPipeline.release(prev)
        }
        _uiState.value = _uiState.value.copy(
            svgDocumentHandle = -1L,
            svgEditVersion = 0,
            svgSourceWidth = 0f,
            svgSourceHeight = 0f,
            svgCanUndo = false,
            svgCanRedo = false,
        )
        currentSvgPath = null
    }

    fun enter(images: List<ImageFile>, index: Int) {
        releaseSvg()
        val img = images.getOrNull(index)
        if (img != null && img.extension == ".svg") {
            loadSvgDocument(img.path)
        }
        _uiState.value = _uiState.value.copy(
            images = images,
            currentIndex = index.coerceIn(0, images.size - 1)
        )
    }

    fun navigateImage(delta: Int) {
        val s = _uiState.value
        val newIndex = (s.currentIndex + delta).coerceIn(0, s.images.size - 1)
        if (newIndex != s.currentIndex) {
            releaseSvg()
            val img = s.images.getOrNull(newIndex)
            if (img != null && img.extension == ".svg") {
                loadSvgDocument(img.path)
            }
            _uiState.value = _uiState.value.copy(currentIndex = newIndex, panX = 0f, panY = 0f)
        }
    }

    fun zoom(factor: Float) {
        val s = _uiState.value
        _uiState.value = s.copy(scale = (s.scale * factor).coerceIn(AppConst.ZOOM_MIN.toFloat(), AppConst.ZOOM_MAX.toFloat()))
    }

    fun setScale(scale: Float) {
        _uiState.value = _uiState.value.copy(scale = scale)
    }

    fun pan(dx: Float, dy: Float) {
        val s = _uiState.value
        _uiState.value = s.copy(panX = s.panX + dx, panY = s.panY + dy)
    }

    fun panDelta(px: Float, py: Float) {
        _uiState.value = _uiState.value.copy(panX = px, panY = py)
    }

    fun resetView() {
        _uiState.value = _uiState.value.copy(scale = 1f, panX = 0f, panY = 0f)
    }

    fun toggleCrop() {
        val s = _uiState.value
        _uiState.value = s.copy(isCropping = !s.isCropping, cropRect = null)
    }

    fun cancelCrop() {
        _uiState.value = _uiState.value.copy(isCropping = false, cropRect = null)
    }

    fun setCropRect(rect: CropRect?) {
        _uiState.value = _uiState.value.copy(cropRect = rect)
    }

    private suspend fun refreshCurrentImage() {
        val s = _uiState.value
        val idx = s.currentIndex
        val path = s.images.getOrNull(idx)?.path ?: return
        val info = withContext(Dispatchers.IO) {
            try {
                val attrs = Files.readAttributes(path, "*")
                val newSize = attrs["size"] as? Long
                val newMtime = (attrs["lastModifiedTime"] as? FileTime)?.toMillis()
                if (newSize != null && newMtime != null)
                    s.images[idx].copy(size = newSize, lastModified = newMtime)
                else null
            } catch (_: Exception) { null }
        } ?: return
        val images = s.images.toMutableList().also { it[idx] = info }
        _uiState.value = s.copy(images = images)
        SingletonSketch.get(PlatformContext.INSTANCE).memoryCache.clear()
        NativeImagePipeline.trim()
        MemoryManager.requestSkiaCleanup()
        org.jetbrains.skia.Graphics.purgeResourceCache()
        onImageModified(path)
    }

    private suspend fun persistEdit(path: Path, extension: String): Boolean {
        return if (extension == ".svg") {
            val handle = _uiState.value.svgDocumentHandle
            if (handle >= 0L) {
                val saved = withContext(Dispatchers.IO) {
                    NativeSvgPipeline.saveToFile(handle, path)
                }
                if (saved) {
                    _uiState.value = _uiState.value.copy(svgEditVersion = _uiState.value.svgEditVersion + 1)
                    refreshCurrentImage()
                }
                saved
            } else false
        } else {
            refreshCurrentImage()
            true
        }
    }

    fun rotate(degrees: Int, onResult: (Boolean) -> Unit = {}) {
        val image = _uiState.value.currentImage ?: return
        scope.launch {
            val ok = if (image.extension == ".svg") {
                val handle = _uiState.value.svgDocumentHandle
                if (handle < 0L) false
                else {
                    pushSvgUndoState()
                    withContext(Dispatchers.IO) { NativeSvgPipeline.editRotate(handle, degrees.toFloat()) }
                }
            } else {
                withContext(Dispatchers.IO) { ImageEditor.rotate(image.path, degrees) }
            }
            if (ok) {
                persistEdit(image.path, image.extension)
                resetView()
            }
            onResult(ok)
        }
    }

    fun flip(onResult: (Boolean) -> Unit = {}) {
        val image = _uiState.value.currentImage ?: return
        scope.launch {
            val ok = if (image.extension == ".svg") {
                val handle = _uiState.value.svgDocumentHandle
                if (handle < 0L) false
                else {
                    pushSvgUndoState()
                    withContext(Dispatchers.IO) { NativeSvgPipeline.editFlipH(handle) }
                }
            } else {
                withContext(Dispatchers.IO) { ImageEditor.flipHorizontal(image.path) }
            }
            if (ok) {
                persistEdit(image.path, image.extension)
                resetView()
            }
            onResult(ok)
        }
    }

    fun applyCrop(onResult: (Boolean) -> Unit = {}) {
        val s = _uiState.value
        val rect = s.cropRect ?: return
        val image = s.currentImage ?: return
        if (!rect.isValid()) return
        scope.launch {
            val ok = if (image.extension == ".svg") {
                val handle = _uiState.value.svgDocumentHandle
                if (handle < 0L) false
                else {
                    pushSvgUndoState()
                    withContext(Dispatchers.IO) {
                        val edited = NativeSvgPipeline.editCrop(handle, rect.x, rect.y, rect.width, rect.height)
                        // Persist the cropped document to disk immediately.
                        if (edited) NativeSvgPipeline.saveToFile(handle, image.path) else false
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    ImageEditor.crop(image.path, rect.x, rect.y, rect.width, rect.height)
                }
            }
            if (ok) {
                refreshCurrentImage()
                _uiState.value = _uiState.value.copy(
                    svgEditVersion = if (image.extension == ".svg") _uiState.value.svgEditVersion + 1 else _uiState.value.svgEditVersion,
                    isCropping = false, cropRect = null,
                    scale = 1f, panX = 0f, panY = 0f
                )
            } else {
                _uiState.value = _uiState.value.copy(isCropping = false, cropRect = null)
            }
            onResult(ok)
        }
    }

    fun toggleFullscreen() {
        _uiState.value = _uiState.value.copy(isFullscreen = !_uiState.value.isFullscreen)
    }

    fun toggleSlideshow() {
        val active = !_uiState.value.slideshowActive
        if (active) slideshowController.start() else slideshowController.stop()
        _uiState.value = _uiState.value.copy(slideshowActive = active)
    }

    fun stopSlideshow() {
        slideshowController.stop()
        _uiState.value = _uiState.value.copy(slideshowActive = false)
    }

    fun copyImageName(onResult: (Boolean) -> Unit = {}) {
        val name = _uiState.value.currentImage?.name ?: return
        try { copyToClipboard(name); onResult(true) }
        catch (_: Exception) { onResult(false) }
    }

    fun copyImagePath(onResult: (Boolean) -> Unit = {}) {
        val path = _uiState.value.currentImage?.path?.toString() ?: return
        try { copyToClipboard(path); onResult(true) }
        catch (_: Exception) { onResult(false) }
    }

    fun copyImage(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val image = _uiState.value.currentImage ?: return@launch
            if (image.extension == ".svg") {
                val content = withContext(Dispatchers.IO) {
                    try { java.nio.file.Files.readString(image.path) }
                    catch (_: Exception) { null }
                }
                if (content != null) {
                    try { copyToClipboard(content); onResult(true) }
                    catch (_: Exception) { onResult(false) }
                } else {
                    onResult(false)
                }
            } else {
                val img = withContext(Dispatchers.IO) {
                    try { javax.imageio.ImageIO.read(image.path.toFile()) }
                    catch (_: Exception) { null }
                }
                if (img != null) {
                    try {
                        java.awt.EventQueue.invokeAndWait { copyImageToClipboard(img) }
                        onResult(true)
                    } catch (_: Exception) { onResult(false) }
                } else {
                    onResult(false)
                }
            }
        }
    }

    fun moveToTrash(onDone: (Boolean) -> Unit) {
        val image = _uiState.value.currentImage ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { galleryRepository.moveToTrash(image.path) }
            if (result.isSuccess) {
                _lastTrashPath = result.getOrNull()
                _lastImagePath = image.path
                _lastAlbumWasCurrent = true
                onImageRemovedFromAlbum(image.path.parent, image.path)
                navigateImage(1)
            }
            onDone(result.isSuccess)
        }
    }



    fun deletePermanently(onDone: (Boolean) -> Unit) {
        val image = _uiState.value.currentImage ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { java.nio.file.Files.deleteIfExists(image.path); true }
                catch (_: Exception) { false }
            }
            if (ok) {
                onImageRemovedFromAlbum(image.path.parent, image.path)
                navigateImage(1)
            }
            onDone(ok)
        }
    }

    fun rename(newName: String, onResult: (Boolean) -> Unit) {
        val image = _uiState.value.currentImage ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val parent = image.path.parent
                    val dest = parent.resolve(newName)
                    Files.move(image.path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    val info = galleryRepository.readImageFileInfo(dest)
                    if (info != null) {
                        val images = _uiState.value.images.toMutableList()
                        images[_uiState.value.currentIndex] = info
                        _uiState.value = _uiState.value.copy(images = images)
                        onImageAddedToAlbum(dest.parent, dest)
                    }
                    true
                } catch (_: Exception) { false }
            }
            onResult(ok)
        }
    }

    fun readExif(onResult: (Map<String, String>) -> Unit) {
        scope.launch {
            val path = _uiState.value.currentImage?.path ?: return@launch
            val exif = withContext(Dispatchers.IO) { galleryRepository.readExifCached(path) }
            onResult(exif)
        }
    }

    fun saveCropCopy(onResult: (Boolean) -> Unit) {
        val s = _uiState.value
        val rect = s.cropRect ?: return
        val image = s.currentImage ?: return
        if (!rect.isValid()) return
        scope.launch {
            if (image.extension == ".svg") {
                val handle = _uiState.value.svgDocumentHandle
                if (handle < 0L) { onResult(false); return@launch }
                // Apply the crop to the in-memory document before saving.
                val cropped = withContext(Dispatchers.IO) {
                    NativeSvgPipeline.editCrop(handle, rect.x, rect.y, rect.width, rect.height)
                }
                if (!cropped) {
                    _uiState.value = _uiState.value.copy(isCropping = false, cropRect = null)
                    onResult(false)
                    return@launch
                }
                val parent = image.path.parent
                val stem = image.name.substringBeforeLast('.')
                val dest = uniqueDestination(parent.resolve("${stem}_cropped.svg"))
                val ok = withContext(Dispatchers.IO) {
                    NativeSvgPipeline.saveToFile(handle, dest)
                }
                if (ok) {
                    val newImage = withContext(Dispatchers.IO) {
                        ImageFile(
                            path = dest,
                            name = dest.fileName.toString(),
                            extension = ".svg",
                            size = Files.size(dest),
                            lastModified = Files.getLastModifiedTime(dest).toMillis()
                        )
                    }
                    val currentImages = _uiState.value.images
                    val newIndex = currentImages.size
                    val newImages = currentImages + newImage
                    // Release the current SVG document before navigating away.
                    // releaseSvg() must be called BEFORE the state update so it can
                    // still read the current svgDocumentHandle from _uiState.value.
                    releaseSvg()
                    _uiState.value = _uiState.value.copy(
                        isCropping = false, cropRect = null,
                        images = newImages, currentIndex = newIndex,
                        scale = 1f, panX = 0f, panY = 0f
                    )
                    // Load the newly saved file as the current SVG document.
                    loadSvgDocument(dest)
                    onImageAddedToAlbum(image.path.parent, dest)
                } else {
                    _uiState.value = _uiState.value.copy(isCropping = false, cropRect = null)
                }
                onResult(ok)
            } else {
                val parent = image.path.parent
                val stem = image.name.substringBeforeLast('.')
                val ext = image.name.substringAfterLast('.', "png")
                val dest = uniqueDestination(parent.resolve("${stem}_cropped.$ext"))
                val ok = withContext(Dispatchers.IO) {
                    ImageEditor.crop(image.path, rect.x, rect.y, rect.width, rect.height, dest)
                }
                if (ok) {
                    val newImage = withContext(Dispatchers.IO) {
                        val name = dest.fileName.toString()
                        val dot = name.lastIndexOf('.')
                        val ext2 = if (dot >= 0) name.substring(dot).lowercase() else ""
                        ImageFile(
                            path = dest,
                            name = name,
                            extension = ext2,
                            size = Files.size(dest),
                            lastModified = Files.getLastModifiedTime(dest).toMillis()
                        )
                    }
                    val currentImages = _uiState.value.images
                    val newIndex = currentImages.size
                    val newImages = currentImages + newImage
                    _uiState.value = _uiState.value.copy(
                        isCropping = false, cropRect = null,
                        images = newImages, currentIndex = newIndex,
                        scale = 1f, panX = 0f, panY = 0f
                    )
                    onImageAddedToAlbum(image.path.parent, dest)
                } else {
                    _uiState.value = _uiState.value.copy(isCropping = false, cropRect = null)
                }
                onResult(ok)
            }
        }
    }

    // ── SVG Undo/Redo ───────────────────────────────────────────────

    private fun pushSvgUndoState() {
        val handle = _uiState.value.svgDocumentHandle
        if (handle < 0L) return
        val svg = NativeSvgPipeline.serialize(handle) ?: return
        svgEditHistory.pushUndo(svg)
        _uiState.value = _uiState.value.copy(svgCanUndo = true, svgCanRedo = false)
    }

    fun svgUndo(onResult: (Boolean) -> Unit = {}) {
        val handle = _uiState.value.svgDocumentHandle
        if (handle < 0L || !svgEditHistory.canUndo()) { onResult(false); return }
        scope.launch {
            val current = withContext(Dispatchers.IO) { NativeSvgPipeline.serialize(handle) }
            val previous = svgEditHistory.popUndo() ?: return@launch

            if (current != null) svgEditHistory.pushRedo(current)
            NativeSvgPipeline.release(handle)

            val newHandle = withContext(Dispatchers.IO) { NativeSvgPipeline.loadFromString(previous) }
            if (newHandle < 0L) { onResult(false); return@launch }

            val svgPath = _uiState.value.currentImage?.path ?: return@launch
            val saved = withContext(Dispatchers.IO) {
                NativeSvgPipeline.saveToFile(newHandle, svgPath)
            }
            if (!saved) { onResult(false); return@launch }

            val size = NativeSvgPipeline.getSize(newHandle)
            _uiState.value = _uiState.value.copy(
                svgDocumentHandle = newHandle,
                svgEditVersion = _uiState.value.svgEditVersion + 1,
                svgSourceWidth = size?.width ?: 0f,
                svgSourceHeight = size?.height ?: 0f,
                svgCanUndo = svgEditHistory.canUndo(),
                svgCanRedo = svgEditHistory.canRedo(),
            )
            refreshCurrentImage()
            onResult(true)
        }
    }

    fun svgRedo(onResult: (Boolean) -> Unit = {}) {
        val handle = _uiState.value.svgDocumentHandle
        if (handle < 0L || !svgEditHistory.canRedo()) { onResult(false); return }
        scope.launch {
            val current = withContext(Dispatchers.IO) { NativeSvgPipeline.serialize(handle) }
            val next = svgEditHistory.popRedo() ?: return@launch

            if (current != null) svgEditHistory.pushUndo(current)
            NativeSvgPipeline.release(handle)

            val newHandle = withContext(Dispatchers.IO) { NativeSvgPipeline.loadFromString(next) }
            if (newHandle < 0L) { onResult(false); return@launch }

            val svgPath = _uiState.value.currentImage?.path ?: return@launch
            val saved = withContext(Dispatchers.IO) {
                NativeSvgPipeline.saveToFile(newHandle, svgPath)
            }
            if (!saved) { onResult(false); return@launch }

            val size = NativeSvgPipeline.getSize(newHandle)
            _uiState.value = _uiState.value.copy(
                svgDocumentHandle = newHandle,
                svgEditVersion = _uiState.value.svgEditVersion + 1,
                svgSourceWidth = size?.width ?: 0f,
                svgSourceHeight = size?.height ?: 0f,
                svgCanUndo = svgEditHistory.canUndo(),
                svgCanRedo = svgEditHistory.canRedo(),
            )
            refreshCurrentImage()
            onResult(true)
        }
    }

    fun saveSvgAsCopy(onResult: (Boolean) -> Unit) {
        val image = _uiState.value.currentImage ?: return
        if (image.extension != ".svg") { onResult(false); return }
        val handle = _uiState.value.svgDocumentHandle
        if (handle < 0L) { onResult(false); return }
        scope.launch {
            val parent = image.path.parent
            val stem = image.name.substringBeforeLast('.')
            val dest = uniqueDestination(parent.resolve("${stem}_edited.svg"))
            val ok = withContext(Dispatchers.IO) {
                NativeSvgPipeline.saveToFile(handle, dest)
            }
            if (ok) {
                val newImage = withContext(Dispatchers.IO) {
                    ImageFile(
                        path = dest,
                        name = dest.fileName.toString(),
                        extension = ".svg",
                        size = Files.size(dest),
                        lastModified = Files.getLastModifiedTime(dest).toMillis()
                    )
                }
                val currentImages = _uiState.value.images
                val newImages = currentImages + newImage
                // Release the current document before navigating, then load the new file.
                // releaseSvg() must be called BEFORE the state update.
                releaseSvg()
                _uiState.value = _uiState.value.copy(
                    images = newImages, currentIndex = newImages.size - 1
                )
                loadSvgDocument(dest)
                onImageAddedToAlbum(image.path.parent, dest)
            }
            onResult(ok)
        }
    }

    fun overwriteSvg(onResult: (Boolean) -> Unit) {
        val image = _uiState.value.currentImage ?: return
        if (image.extension != ".svg") { onResult(false); return }
        val handle = _uiState.value.svgDocumentHandle
        if (handle < 0L) { onResult(false); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                NativeSvgPipeline.saveToFile(handle, image.path)
            }
            if (ok) {
                refreshCurrentImage()
            }
            onResult(ok)
        }
    }
}

class SvgEditHistory(private val maxDepth: Int = 32) {
    private val undoStack = mutableListOf<String>()
    private val redoStack = mutableListOf<String>()

    fun pushUndo(svgContent: String) {
        undoStack.add(svgContent)
        if (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
    }

    fun pushRedo(svgContent: String) {
        redoStack.add(svgContent)
        if (redoStack.size > maxDepth) redoStack.removeFirst()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun popUndo(): String? = undoStack.removeLastOrNull()

    fun popRedo(): String? = redoStack.removeLastOrNull()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
