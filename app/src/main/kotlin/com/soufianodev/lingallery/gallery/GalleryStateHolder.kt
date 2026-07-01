package com.soufianodev.lingallery.gallery

import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.gallery.data.FileEvent
import com.soufianodev.lingallery.gallery.data.ScanEvent
import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.model.ImageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension

sealed interface ScrollEffect {
    data object ScrollToTop : ScrollEffect
}

sealed interface GalleryAction {
    data class SelectAlbum(val index: Int) : GalleryAction
    data object ToggleSlideshow : GalleryAction
    data object ClearDeletion : GalleryAction
}

class GalleryStateHolder(
    private val repository: GalleryRepository,
    private val scope: CoroutineScope
) {
    private val _statusMessage = MutableStateFlow(Strings.Status.scanning)
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _scrollEffects = Channel<ScrollEffect>(Channel.BUFFERED)
    val scrollEffects: Flow<ScrollEffect> = _scrollEffects.receiveAsFlow()

    fun requestScrollToTop() {
        _scrollEffects.trySend(ScrollEffect.ScrollToTop)
    }

    fun onAction(action: GalleryAction) {
        when (action) {
            is GalleryAction.SelectAlbum -> {
                updateState { it.copy(currentAlbumIndex = action.index) }
            }
            GalleryAction.ToggleSlideshow -> {
                updateState { it.copy(isSlideshowActive = !it.isSlideshowActive) }
            }
            GalleryAction.ClearDeletion -> {
                updateState { it.copy(deletionRecord = null) }
                _statusMessage.value = ""
            }
        }
    }

    fun updateState(transform: (GalleryUiState) -> GalleryUiState) {
        _uiState.update(transform)
    }

    fun onImageModified(imagePath: Path) {
        val albumPath = imagePath.parent
        val info = repository.readImageFileInfo(imagePath)
        if (info != null) {
            updateState { it.modifyImage(albumPath, imagePath, info) }
        }
    }

    fun setStatus(msg: String) {
        _statusMessage.value = msg
    }

    fun refresh() {
        updateState { it.sortAlbums().pruneEmptyAlbums() }
    }

    fun init(scope: CoroutineScope) {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { repository.loadCachedState() }
            if (cached != null && cached.albums.isNotEmpty()) {
                _uiState.value = cached
                _statusMessage.value = Strings.Status.loadedCache(cached.albums.size)
            } else {
                updateState { it.copy(isScanning = true) }
                _statusMessage.value = Strings.Status.scanning
            }

            val roots = AppConst.DEFAULT_SCAN_ROOTS.map {
                Paths.get(it.replace("~", System.getProperty("user.home")))
            }

            val priorityPath = _uiState.value.currentAlbum?.path
                ?: roots.firstOrNull { it.fileName.toString().contains("Pictures", ignoreCase = true) }

            repository.progressiveScan(roots, priorityPath).collect { event ->
                when (event) {
                    is ScanEvent.AlbumFound -> {
                        updateState { it.addAlbum(event.album) }
                    }
                    is ScanEvent.ProgressUpdate -> {
                        updateState {
                            it.copy(
                                scannedDirs = event.scannedDirs,
                                totalImagesScanned = event.totalImages
                            )
                        }
                        _statusMessage.value = Strings.Status.progress(event.scannedDirs, event.totalImages)
                    }
                    is ScanEvent.ScanComplete -> {
                        updateState {
                            it.sortAlbums().pruneEmptyAlbums().copy(
                                isScanning = false,
                                totalImagesScanned = event.totalImages
                            )
                        }
                        val s = _uiState.value
                        _statusMessage.value = Strings.Status.summary(s.albums.size, event.totalImages)
                        withContext(Dispatchers.IO) { repository.saveState(_uiState.value) }
                    }
                }
            }
        }

        scope.launch {
            repository.startWatcher(scope)
            repository.events.collect { event ->
                when (event) {
                    is FileEvent.AlbumCreated -> {
                        val album = withContext(Dispatchers.IO) { repository.scanSingleDir(event.path) }
                        if (album != null) {
                            updateState { it.addAlbum(album) }
                            _statusMessage.value = Strings.Status.newAlbum(album.name)
                        }
                    }
                    is FileEvent.AlbumDeleted -> {
                        updateState { it.removeAlbum(event.path) }
                        _statusMessage.value = Strings.Status.albumRemoved
                    }
                    is FileEvent.AlbumRenamed -> {
                        updateState { it.renameAlbum(event.oldPath, event.newPath) }
                        _statusMessage.value = Strings.Status.albumRenamed
                    }
                    is FileEvent.AlbumModified -> {
                        val path = event.path
                        val fresh = withContext(Dispatchers.IO) { repository.scanSingleDir(path) }
                        updateState { if (fresh == null) it.removeAlbum(path) else it.addAlbum(fresh) }
                    }
                    is FileEvent.ImageCreated -> {
                        val path = event.albumPath
                        val fresh = withContext(Dispatchers.IO) { repository.scanSingleDir(path) }
                        updateState { if (fresh == null) it.removeAlbum(path) else it.addAlbum(fresh) }
                    }
                    is FileEvent.ImageDeleted -> {
                        val path = event.albumPath
                        val fresh = withContext(Dispatchers.IO) { repository.scanSingleDir(path) }
                        updateState { if (fresh == null) it.removeAlbum(path) else it.addAlbum(fresh) }
                    }
                    is FileEvent.ImageModified -> {
                        val path = event.albumPath
                        val fresh = withContext(Dispatchers.IO) { repository.scanSingleDir(path) }
                        updateState { if (fresh == null) it.removeAlbum(path) else it.addAlbum(fresh) }
                    }
                }
            }
        }
    }
}
