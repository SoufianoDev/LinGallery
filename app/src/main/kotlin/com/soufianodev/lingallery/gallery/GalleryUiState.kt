package com.soufianodev.lingallery.gallery

import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.devices.core.DeviceActivityMap
import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.model.ImageFile
import com.soufianodev.lingallery.shared.filesystem.normalizePath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension

sealed class Screen {
    data object Gallery : Screen()
    data class Viewer(val imageIndex: Int) : Screen()
}

data class CropRect(
    val x: Float, val y: Float,
    val width: Float, val height: Float
) {
    fun isValid(): Boolean = width > 0f && height > 0f
}

data class DeletionRecord(
    val trashPath: Path,
    val imageFile: ImageFile,
    val albumPath: Path,
    val albumIndex: Int,
    val imageIndex: Int
)

data class GalleryUiState(
    val albums: List<Album> = emptyList(),
    val currentAlbumIndex: Int = 0,
    val searchQuery: String = "",
    val isScanning: Boolean = false,
    val scannedDirs: Int = 0,
    val totalImagesScanned: Int = 0,
    val screen: Screen = Screen.Gallery,
    val isSlideshowActive: Boolean = false,
    val isDarkTheme: Boolean = true,
    val deletionRecord: DeletionRecord? = null,
    val deviceActivity: DeviceActivityMap = DeviceActivityMap(),
    val pendingPhoneSort: Set<Path> = emptySet(),
) {
    val currentAlbum: Album?
        get() = albums.getOrNull(currentAlbumIndex)
    val currentAlbumImages: List<ImageFile>
        get() = currentAlbum?.images ?: emptyList()
    val currentImage: ImageFile?
        get() = currentAlbumImages.getOrNull(0)

    private val PRIORITY_NAMES = listOf("desktop", "camera", "pictures", "downloads", "screenshots")

    private fun sortKey(name: String, isDevice: Boolean = false): Pair<Int, String> {
        if (isDevice) return Pair(-1, "")
        val idx = PRIORITY_NAMES.indexOf(name.lowercase())
        return if (idx >= 0) Pair(idx, "") else Pair(PRIORITY_NAMES.size, name.lowercase())
    }

    private fun albumCompare(a: Album, b: Album): Int {
        val (pa, sa) = sortKey(a.name, a.isDeviceAlbum)
        val (pb, sb) = sortKey(b.name, b.isDeviceAlbum)
        return pa.compareTo(pb).let { if (it != 0) it else sa.compareTo(sb) }
    }

    fun sortAlbums(): GalleryUiState {
        val selectedPath = currentAlbum?.path
        val sorted = albums.sortedWith(Comparator(::albumCompare))
        val newIndex = if (selectedPath != null) {
            sorted.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
        } else {
            sorted.indexOfFirst { it.name.equals("Pictures", ignoreCase = true) }.coerceAtLeast(0)
        }
        return copy(albums = sorted, currentAlbumIndex = newIndex)
    }

    fun pruneEmptyAlbums(): GalleryUiState {
        val nonEmpty = albums.filter { it.images.isNotEmpty() || it.isDeviceAlbum }
        if (nonEmpty.size == albums.size) return this
        val newIndex = currentAlbumIndex.coerceIn(0, nonEmpty.size - 1)
        return copy(albums = nonEmpty, currentAlbumIndex = newIndex)
    }

    fun syncAlbum(albumPath: Path): GalleryUiState {
        val fresh = scanSingleDir(albumPath)
        if (fresh == null) return removeAlbum(albumPath)
        return addAlbum(fresh)
    }

    fun addImage(albumPath: Path, image: ImageFile): GalleryUiState {
        val normalizedAlbum = normalizePath(albumPath)
        val albumIdx = albums.indexOfFirst { normalizePath(it.path) == normalizedAlbum }
        if (albumIdx < 0) {
            val dirAlbum = scanSingleDir(albumPath)
                ?: Album(normalizedAlbum, albumPath.fileName.toString(), listOf(image), image.path)
            return addAlbum(dirAlbum)
        }
        val album = albums[albumIdx]
        val normalizedImagePath = normalizePath(image.path)
        val existingIdx = album.images.indexOfFirst { normalizePath(it.path) == normalizedImagePath }
        val newImages = if (existingIdx >= 0) {
            album.images.toMutableList().also { it[existingIdx] = image }
        } else {
            (album.images + image)
                .distinctBy { normalizePath(it.path) }
                .sortedByDescending { it.lastModified }
        }
        val newPreview = newImages.first().path
        val newAlbums = albums.toMutableList()
        newAlbums[albumIdx] = album.copy(images = newImages, previewPath = newPreview)
        return copy(albums = newAlbums)
    }

    fun removeImage(albumPath: Path, imagePath: Path): GalleryUiState {
        val normalizedAlbum = normalizePath(albumPath)
        val albumIdx = albums.indexOfFirst { normalizePath(it.path) == normalizedAlbum }
        if (albumIdx < 0) return this
        val album = albums[albumIdx]
        val normalizedImage = normalizePath(imagePath)
        var imgIdx = album.images.indexOfFirst { normalizePath(it.path) == normalizedImage }
        if (imgIdx < 0) {
            val absPath = imagePath.toAbsolutePath().normalize().toString()
            imgIdx = album.images.indexOfFirst {
                it.path.toAbsolutePath().normalize().toString() == absPath
            }
        }
        if (imgIdx < 0) {
            val reScanned = scanSingleDir(albumPath)
            if (reScanned == null) return removeAlbum(normalizedAlbum)
            val newAlbums = albums.toMutableList()
            newAlbums[albumIdx] = reScanned
            return copy(albums = newAlbums)
        }

        val newImages = album.images.toMutableList()
        newImages.removeAt(imgIdx)

        if (newImages.isEmpty()) {
            return removeAlbum(normalizedAlbum)
        }

        val newPreview = newImages.first().path
        val newAlbums = albums.toMutableList()
        newAlbums[albumIdx] = album.copy(images = newImages, previewPath = newPreview)

        val newScreen = if (screen is Screen.Viewer && albumIdx == currentAlbumIndex) {
            val viewerIdx = screen.imageIndex
            val adjustedIdx = when {
                viewerIdx < imgIdx -> viewerIdx
                viewerIdx > imgIdx -> (viewerIdx - 1).coerceAtLeast(0)
                else -> viewerIdx.coerceAtMost(newImages.size - 1)
            }
            Screen.Viewer(adjustedIdx)
        } else screen

        return copy(
            albums = newAlbums,
            screen = newScreen,
        )
    }

    fun addAlbum(album: Album): GalleryUiState {
        val dedupAlbum = if (album.images.size != album.images.distinctBy { normalizePath(it.path) }.size) {
            val deduped = album.images.distinctBy { normalizePath(it.path) }
            album.copy(images = deduped, previewPath = deduped.firstOrNull()?.path ?: album.previewPath)
        } else album
        val normalizedAlbum = normalizePath(dedupAlbum.path)
        if (albums.any { normalizePath(it.path) == normalizedAlbum }) {
            val idx = albums.indexOfFirst { normalizePath(it.path) == normalizedAlbum }
            val existing = albums[idx]
            if (existing.images.size != dedupAlbum.images.size || existing.name != dedupAlbum.name) {
                val newAlbums = albums.toMutableList()
                newAlbums[idx] = dedupAlbum
                return copy(albums = newAlbums)
            }
            return this
        }
        val insertionIndex = albums.indexOfFirst { albumCompare(dedupAlbum, it) < 0 }
            .let { if (it < 0) albums.size else it }
        val newAlbums = albums.toMutableList()
        newAlbums.add(insertionIndex, dedupAlbum)
        val newIndex = if (currentAlbumIndex >= insertionIndex) currentAlbumIndex + 1 else currentAlbumIndex
        return copy(albums = newAlbums, currentAlbumIndex = newIndex)
    }

    fun removeAlbum(albumPath: Path): GalleryUiState {
        val normalized = normalizePath(albumPath)
        val idx = albums.indexOfFirst { normalizePath(it.path) == normalized }
        if (idx < 0) return this
        val newAlbums = albums.toMutableList()
        newAlbums.removeAt(idx)

        val newIndex = when {
            newAlbums.isEmpty() -> 0
            currentAlbumIndex == idx -> 0.coerceAtMost(newAlbums.size - 1)
            currentAlbumIndex > idx -> currentAlbumIndex - 1
            else -> currentAlbumIndex
        }

        val newScreen = if (screen is Screen.Viewer && currentAlbumIndex == idx) {
            Screen.Gallery
        } else screen

        return copy(
            albums = newAlbums,
            currentAlbumIndex = newIndex,
            screen = newScreen
        )
    }

    fun removeAlbumsByPrefix(prefix: Path): GalleryUiState {
        val filtered = albums.filterNot { it.path.startsWith(prefix) }
        if (filtered.size == albums.size) return this
        val newIndex = currentAlbumIndex.coerceIn(0, filtered.size - 1)
        val newScreen = if (screen is Screen.Viewer) {
            val albumPath = albums.getOrNull(currentAlbumIndex)?.path
            if (albumPath != null && albumPath.startsWith(prefix)) Screen.Gallery else screen
        } else screen
        return copy(albums = filtered, currentAlbumIndex = newIndex, screen = newScreen)
    }

    fun markPendingPhoneSort(albumPath: Path): GalleryUiState =
        copy(pendingPhoneSort = pendingPhoneSort + albumPath)

    fun clearPendingPhoneSort(albumPath: Path): GalleryUiState =
        copy(pendingPhoneSort = pendingPhoneSort - albumPath)

    fun appendPhoneImages(albumPath: Path, newImages: List<ImageFile>): GalleryUiState {
        val idx = albums.indexOfFirst { it.path == albumPath }
        if (idx < 0) return this
        val album = albums[idx]
        val combined = (album.images + newImages)
            .distinctBy { it.path }
            .sortedByDescending { it.lastModified }
        val newPreview = combined.firstOrNull()?.path ?: album.previewPath
        val newAlbums = albums.toMutableList()
        newAlbums[idx] = album.copy(images = combined, previewPath = newPreview)
        return copy(albums = newAlbums)
    }

    fun sortPhoneByRecent(albumPath: Path): GalleryUiState {
        val idx = albums.indexOfFirst { it.path == albumPath }
        if (idx < 0) return this
        val album = albums[idx]
        if (!album.isDeviceAlbum) return this
        val sorted = album.images
            .sortedByDescending { it.lastModified }
            .distinctBy { it.path }
        val preview = sorted.firstOrNull()?.path ?: album.previewPath
        val newAlbums = albums.toMutableList()
        newAlbums[idx] = album.copy(images = sorted, previewPath = preview)
        return copy(albums = newAlbums, pendingPhoneSort = pendingPhoneSort - albumPath)
    }

    fun updatePhoneImageMetadata(albumPath: Path, enriched: List<ImageFile>): GalleryUiState {
        val idx = albums.indexOfFirst { it.path == albumPath }
        if (idx < 0) return this
        val album = albums[idx]
        val updateMap = enriched.associateBy { it.path }
        val newImages = album.images.map { updateMap[it.path] ?: it }
        val newAlbums = albums.toMutableList()
        newAlbums[idx] = album.copy(images = newImages)
        return copy(albums = newAlbums)
    }

    fun updatePhoneImageThumbnail(albumPath: Path, imagePath: Path, thumbnailPath: Path): GalleryUiState {
        val idx = albums.indexOfFirst { it.path == albumPath }
        if (idx < 0) return this
        val album = albums[idx]
        val imgIdx = album.images.indexOfFirst { it.path == imagePath }
        if (imgIdx < 0) return this
        val updatedImage = album.images[imgIdx].copy(thumbnailPath = thumbnailPath)
        val newImages = album.images.toMutableList()
        newImages[imgIdx] = updatedImage
        val newAlbums = albums.toMutableList()
        newAlbums[idx] = album.copy(images = newImages)
        return copy(albums = newAlbums)
    }

    fun modifyImage(albumPath: Path, imagePath: Path, updatedImage: ImageFile): GalleryUiState {
        val normalizedAlbum = normalizePath(albumPath)
        val albumIdx = albums.indexOfFirst { normalizePath(it.path) == normalizedAlbum }
        if (albumIdx < 0) return this
        val album = albums[albumIdx]
        val normalizedImage = normalizePath(imagePath)
        val imgIdx = album.images.indexOfFirst { normalizePath(it.path) == normalizedImage }
        if (imgIdx < 0) return this

        val newImages = album.images.toMutableList()
        newImages[imgIdx] = updatedImage
        val newAlbums = albums.toMutableList()
        newAlbums[albumIdx] = album.copy(images = newImages)
        return copy(albums = newAlbums)
    }

    fun renameAlbum(oldPath: Path, newPath: Path): GalleryUiState {
        val newAlbum = scanSingleDir(newPath)
        return if (newAlbum != null) removeAlbum(oldPath).addAlbum(newAlbum) else removeAlbum(oldPath)
    }
}

fun readImageFileInfo(path: Path): ImageFile? {
    return try {
        val name = path.fileName.toString()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        val ext = path.extension
        ImageFile(
            path = path,
            name = name,
            extension = if (ext.startsWith(".")) ext else ".$ext",
            size = attrs.size(),
            lastModified = attrs.lastModifiedTime().toMillis()
        )
    } catch (_: Exception) { null }
}

fun scanSingleDir(dir: Path): Album? {
    if (!Files.isDirectory(dir)) return null
    val images = mutableListOf<ImageFile>()
    try {
        Files.list(dir).forEach { path ->
            if (!Files.isDirectory(path)) {
                val name = path.fileName.toString()
                val dot = name.lastIndexOf('.')
                val ext = if (dot >= 0) name.substring(dot).lowercase() else ""
                if (ext in AppConst.SUPPORTED_FORMATS) {
                    try {
                        val size = Files.size(path)
                        val mtime = Files.getLastModifiedTime(path)
                        images.add(
                            ImageFile(
                                path = path,
                                name = name,
                                extension = ext,
                                size = size,
                                lastModified = mtime.toMillis()
                            )
                        )
                    } catch (_: Exception) { }
                }
            }
        }
    } catch (_: Exception) { return null }
    val sortedImages = images.distinctBy { it.path }.sortedByDescending { it.lastModified }
    if (sortedImages.isEmpty()) return null
    return Album(
        path = dir,
        name = dir.fileName.toString(),
        images = sortedImages,
        previewPath = sortedImages.first().path
    )
}
