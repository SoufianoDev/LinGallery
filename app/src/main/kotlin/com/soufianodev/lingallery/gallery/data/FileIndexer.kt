package com.soufianodev.lingallery.gallery.data

import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.model.ImageFile
import com.soufianodev.lingallery.native.LinLogger
import com.soufianodev.lingallery.native.NativeScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.LinkedList

sealed class ScanEvent {
    data class AlbumFound(val album: Album) : ScanEvent()
    data class ProgressUpdate(val scannedDirs: Int, val totalImages: Int) : ScanEvent()
    data class ScanComplete(val totalImages: Int) : ScanEvent()
}

class FileIndexer(
    private val roots: List<String> = AppConst.DEFAULT_SCAN_ROOTS
) {
    data class ScanProgress(
        val scannedDirs: Int,
        val totalImages: Int
    )

    data class ScanResult(
        val albums: List<Album>,
        val totalAlbums: Int,
        val totalImages: Int
    )

    suspend fun scan(
        onProgress: (ScanProgress) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        val perRootResults = coroutineScope {
            roots.map { rootStr ->
                async {
                    val root = Paths.get(rootStr.replace("~", System.getProperty("user.home")))
                    if (!Files.exists(root)) return@async listOf<Album>() to 0
                    scanRoot(root)
                }
            }.awaitAll()
        }

        var totalImages = 0
        val albums = mutableListOf<Album>()
        for ((rootAlbums, rootCount) in perRootResults) {
            totalImages += rootCount
            albums.addAll(rootAlbums)
        }

        val distinctAlbums = albums.distinctBy { try { it.path.toRealPath() } catch (_: Exception) { it.path.normalize() } }
        ScanResult(distinctAlbums, distinctAlbums.size, totalImages)
    }

    fun progressiveScan(
        roots: List<Path>,
        currentAlbumPath: Path? = null
    ): Flow<ScanEvent> = flow {
        if (NativeScanner.isAvailable) {
            LinLogger.i("FileIndexer", "NativeScanner available, delegating scan")
            val nativeResult = runCatching { NativeScanner.scanToAlbums(roots) }
            if (nativeResult.isSuccess) {
                val albums = nativeResult.getOrThrow()
                var total = 0
                for (album in albums) {
                    total += album.images.size
                    emit(ScanEvent.AlbumFound(album))
                }
                emit(ScanEvent.ScanComplete(total))
                return@flow
            } else {
                LinLogger.w("FileIndexer", "NativeScanner failed: ${nativeResult.exceptionOrNull()?.message}. Falling back to Kotlin BFS")
            }
        }

        val dirQueue = LinkedList<Path>()
        for (root in roots) {
            if (Files.exists(root)) {
                if (currentAlbumPath != null && isParentOrSelf(root, currentAlbumPath)) {
                    dirQueue.addFirst(currentAlbumPath)
                }
                dirQueue.addLast(root)
            }
        }
        val visited = mutableSetOf<Path>()
        var scannedDirs = 0
        var totalImages = 0
        val seenAlbums = mutableSetOf<Path>()

        while (dirQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            val dir = dirQueue.removeFirst()
            if (!Files.isDirectory(dir) || !visited.add(dir)) continue
            val name = dir.fileName.toString().lowercase()
            if (name.startsWith(".") || name == "lost+found" || name == "android" || name == "music" || name == "movies" || name == "documents") continue

            val albumImages = mutableListOf<ImageFile>()
            val subdirs = mutableListOf<Path>()

            try {
                Files.list(dir).forEach { entry ->
                    if (Files.isDirectory(entry)) {
                        val en = entry.fileName.toString().lowercase()
                        if (!en.startsWith(".") && en != "lost+found" && en != "android" && en != "music" && en != "movies" && en != "documents") {
                            subdirs.add(entry)
                        }
                    } else {
                        if (isSupportedImage(entry)) {
                            try {
                                val size = Files.size(entry)
                                val mtime = Files.getLastModifiedTime(entry)
                                albumImages.add(
                                    ImageFile(
                                        path = entry,
                                        name = entry.fileName.toString(),
                                        extension = extensionOf(entry),
                                        size = size,
                                        lastModified = mtime.toMillis()
                                    )
                                )
                            } catch (_: IOException) {}
                        }
                    }
                }
            } catch (_: IOException) {}

            scannedDirs++

            if (albumImages.isNotEmpty()) {
                totalImages += albumImages.size
                val sorted = albumImages.distinctBy { it.path }
                    .sortedByDescending { it.lastModified }
                val album = Album(
                    path = dir,
                    name = dir.fileName.toString(),
                    images = sorted,
                    previewPath = sorted.first().path
                )
                seenAlbums.add(dir)
                emit(ScanEvent.AlbumFound(album))
            }

            if (scannedDirs % 10 == 0) {
                emit(ScanEvent.ProgressUpdate(scannedDirs, totalImages))
            }

            val normalizedDir = try { dir.toRealPath() } catch (_: Exception) { dir.toAbsolutePath().normalize() }
            val normalizedCurrent = currentAlbumPath?.let { try { it.toRealPath() } catch (_: Exception) { it.toAbsolutePath().normalize() } }

            for (subdir in subdirs) {
                if (subdir !in visited) {
                    if (normalizedCurrent != null) {
                        try {
                            val ns = subdir.toRealPath()
                            if (ns.startsWith(normalizedCurrent)) {
                                dirQueue.addFirst(subdir)
                                continue
                            }
                        } catch (_: Exception) {}
                    }
                    dirQueue.addLast(subdir)
                }
            }
        }

        emit(ScanEvent.ProgressUpdate(scannedDirs, totalImages))
        emit(ScanEvent.ScanComplete(totalImages))
    }.flowOn(Dispatchers.IO)

    private fun isParentOrSelf(parent: Path, child: Path): Boolean {
        return try {
            val p = parent.toRealPath()
            val c = child.toRealPath()
            c == p || c.startsWith(p)
        } catch (_: Exception) {
            false
        }
    }

    private data class DirEntry(
        val dir: Path,
        val images: MutableList<ImageFile> = mutableListOf()
    )

    private fun scanRoot(root: Path): Pair<List<Album>, Int> {
        val dirMap = linkedMapOf<Path, DirEntry>()
        var totalImages = 0

        try {
            Files.walk(root).use { stream ->
                stream.forEach { path ->
                    if (path == root) return@forEach
                    if (Files.isDirectory(path)) {
                        val name = path.fileName.toString()
                        if (name.startsWith(".") || name == "lost+found") return@forEach
                        dirMap[path] = DirEntry(path)
                    } else {
                        val parent = path.parent ?: return@forEach
                        if (parent.startsWith(".")) return@forEach
                        if (!isSupportedImage(path)) return@forEach
                        val entry = dirMap.getOrPut(parent) { DirEntry(parent) }
                        try {
                            val size = Files.size(path)
                            val mtime = Files.getLastModifiedTime(path)
                            entry.images.add(
                                ImageFile(
                                    path = path,
                                    name = path.fileName.toString(),
                                    extension = extensionOf(path),
                                    size = size,
                                    lastModified = mtime.toMillis()
                                )
                            )
                        } catch (_: IOException) { }
                    }
                }
            }
        } catch (_: IOException) { }

        val albums = mutableListOf<Album>()
        for ((dir, entry) in dirMap) {
            if (entry.images.isEmpty()) continue
            val distinctImages = entry.images.distinctBy { it.path }.sortedByDescending { it.lastModified }
            totalImages += distinctImages.size
            albums.add(
                Album(
                    path = dir,
                    name = dir.fileName.toString(),
                    images = distinctImages,
                    previewPath = distinctImages.first().path
                )
            )
        }

        return albums to totalImages
    }

    private fun isSupportedImage(path: Path): Boolean {
        val ext = extensionOf(path)
        return ext in AppConst.SUPPORTED_FORMATS
    }

    fun extensionOf(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot).lowercase() else ""
    }
}
