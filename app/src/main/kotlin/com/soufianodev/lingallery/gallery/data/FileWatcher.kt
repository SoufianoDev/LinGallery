package com.soufianodev.lingallery.gallery.data

import com.soufianodev.lingallery.native.LinLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

sealed class FileEvent {
    data class AlbumCreated(val path: Path) : FileEvent()
    data class AlbumDeleted(val path: Path) : FileEvent()
    data class AlbumRenamed(val oldPath: Path, val newPath: Path) : FileEvent()
    data class AlbumModified(val path: Path) : FileEvent()
    data class ImageCreated(val albumPath: Path, val imagePath: Path) : FileEvent()
    data class ImageDeleted(val albumPath: Path, val imagePath: Path) : FileEvent()
    data class ImageModified(val albumPath: Path, val imagePath: Path) : FileEvent()
}

class FileWatcher(
    private val roots: List<Path>,
    private val supportedFormats: Set<String> = setOf(
        ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff", ".tif", ".svg"
    ),
    private val debounceMs: Long = 300L,
    private val pollIntervalMs: Long = 5000L,
) {
    private var watchService: WatchService? = null
    private var job: Job? = null
    private val watchedDirs = ConcurrentHashMap.newKeySet<Path>()

    private val _events = Channel<FileEvent>(Channel.UNLIMITED)
    val events: Flow<FileEvent> = _events.receiveAsFlow()

    fun start(scope: CoroutineScope) {
        watchService = FileSystems.getDefault().newWatchService()
        job = scope.launch(Dispatchers.IO) {
            val pollJob = launch {
                pollLoop()
            }
            while (isActive) {
                try {
                    initializeWatches()
                    runEventLoop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LinLogger.e("FileWatcher", "Event loop crashed: ${e.message}. Restarting in 1s...")
                    delay(1000)
                    try { watchService?.close() } catch (_: Exception) {}
                    watchService = FileSystems.getDefault().newWatchService()
                    watchedDirs.clear()
                }
            }
            pollJob.cancel()
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            for (root in roots) {
                if (!Files.isDirectory(root)) continue
                val current = snapshotDir(root)
                val previous = lastSnapshot[root]
                if (previous != null && previous != current) {
                    _events.send(FileEvent.AlbumModified(root))
                }
                lastSnapshot[root] = current
            }
        }
    }

    private fun snapshotDir(dir: Path): Set<String> {
        return try {
            Files.list(dir).use { stream ->
                stream.toList().map { it.fileName.toString() }.toSet()
            }
        } catch (_: Exception) { emptySet() }
    }

    private val lastSnapshot = java.util.concurrent.ConcurrentHashMap<Path, Set<String>>()

    private fun initializeWatches() {
        for (root in roots) {
            if (Files.isDirectory(root)) {
                registerTree(root)
            }
        }
    }

    private suspend fun runEventLoop() {
        val batch = mutableListOf<FileEvent>()

        while (true) {
            try {
                val key = watchService!!.take()
                val dir = key.watchable() as Path

                key.pollEvents().forEach { event ->
                    val kind = event.kind()

                    when (kind) {
                        StandardWatchEventKinds.OVERFLOW -> {
                            batch.addAll(handleOverflow(dir))
                        }
                        else -> {
                            val filename = event.context() as Path
                            val fullPath = dir.resolve(filename)
                            handleEvent(kind, dir, fullPath, filename)?.let { fe ->
                                batch.add(fe)
                            }
                        }
                    }
                }

                if (!key.reset()) {
                    watchedDirs.remove(dir)
                    batch.add(FileEvent.AlbumDeleted(dir))
                }

                if (batch.isNotEmpty()) {
                    delay(debounceMs)
                    coalesceBatch(batch)
                    for (event in batch) {
                        _events.send(event)
                    }
                    batch.clear()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClosedWatchServiceException) {
                break
            } catch (_: InterruptedException) {
                break
                } catch (e: Exception) {
                LinLogger.e("FileWatcher", "Iteration error: ${e.message}. Re-raising for restart...")
                throw e
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watchService?.close()
        watchService = null
        watchedDirs.clear()
    }

    fun addRoot(root: Path) {
        if (!Files.isDirectory(root)) return
        registerTree(root)
    }

    // ── Registration ────────────────────────────────────────────

    private fun registerTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName.toString()
                if (name.startsWith(".") || name == "lost+found") {
                    return FileVisitResult.SKIP_SUBTREE
                }
                try {
                    dir.register(
                        watchService!!,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                    )
                    watchedDirs.add(dir)
                } catch (_: IOException) { }
                return FileVisitResult.CONTINUE
            }
        })
    }

    // ── Event Classification ────────────────────────────────────

    private fun handleEvent(
        kind: WatchEvent.Kind<*>,
        dir: Path,
        fullPath: Path,
        filename: Path,
    ): FileEvent? {
        val name = filename.fileName.toString()

        if (name.startsWith(".") || name.endsWith("~") ||
            name.endsWith(".tmp") || name.endsWith(".swp")) return null

        return when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE -> handleCreate(dir, fullPath, filename)
            StandardWatchEventKinds.ENTRY_DELETE -> handleDelete(dir, fullPath, filename)
            StandardWatchEventKinds.ENTRY_MODIFY -> handleModify(dir, fullPath, filename)
            else -> null
        }
    }

    private fun isSupportedImage(filename: Path): Boolean {
        val ext = filename.extension.lowercase()
        return ".$ext" in supportedFormats
    }

    private fun handleCreate(dir: Path, fullPath: Path, filename: Path): FileEvent? {
        return if (fullPath.isDirectory()) {
            registerTree(fullPath)
            FileEvent.AlbumCreated(fullPath)
        } else {
            if (isSupportedImage(filename)) {
                FileEvent.ImageCreated(dir, fullPath)
            } else null
        }
    }

    private fun handleDelete(dir: Path, fullPath: Path, filename: Path): FileEvent? {
        return when {
            fullPath in watchedDirs -> {
                watchedDirs.remove(fullPath)
                FileEvent.AlbumDeleted(fullPath)
            }
            else -> {
                if (isSupportedImage(filename)) {
                    FileEvent.ImageDeleted(dir, fullPath)
                } else null
            }
        }
    }

    private fun handleModify(dir: Path, fullPath: Path, filename: Path): FileEvent? {
        if (fullPath.isDirectory()) return null
        return if (isSupportedImage(filename)) {
            FileEvent.ImageModified(dir, fullPath)
        } else null
    }

    // ── Overflow / Fallback ─────────────────────────────────────

    private fun handleOverflow(dir: Path): List<FileEvent> {
        val events = mutableListOf<FileEvent>()
        try {
            Files.list(dir).forEach { path ->
                if (path.isDirectory()) {
                    if (path !in watchedDirs) {
                        registerTree(path)
                        events.add(FileEvent.AlbumCreated(path))
                    }
                } else if (isSupportedImage(path)) {
                    events.add(FileEvent.ImageCreated(dir, path))
                }
            }
        } catch (_: IOException) { }
        events.add(FileEvent.AlbumModified(dir))
        return events
    }

    // ── Coalescing ──────────────────────────────────────────────

    private fun coalesceBatch(batch: MutableList<FileEvent>) {
        val deletes = batch.filterIsInstance<FileEvent.ImageDeleted>().toSet()
        val creates = batch.filterIsInstance<FileEvent.ImageCreated>().toSet()

        val paired = deletes.map { it.imagePath }.intersect(creates.map { it.imagePath })
        if (paired.isNotEmpty()) {
            batch.removeAll { it is FileEvent.ImageDeleted && it.imagePath in paired }
            batch.removeAll { it is FileEvent.ImageCreated && it.imagePath in paired }
            for (imagePath in paired) {
                val albumPath = deletes.first { it.imagePath == imagePath }.albumPath
                batch.add(FileEvent.ImageModified(albumPath, imagePath))
            }
        }

        val albumDeletes = batch.filterIsInstance<FileEvent.AlbumDeleted>()
        val albumCreates = batch.filterIsInstance<FileEvent.AlbumCreated>()

        val renamePairs = mutableListOf<Pair<Path, Path>>()
        for (del in albumDeletes) {
            val match = albumCreates.firstOrNull { it.path.parent == del.path.parent }
            if (match != null) {
                renamePairs.add(del.path to match.path)
            }
        }
        if (renamePairs.isNotEmpty()) {
            val renameOldPaths = renamePairs.map { it.first }.toSet()
            val renameNewPaths = renamePairs.map { it.second }.toSet()
            batch.removeAll { it is FileEvent.AlbumDeleted && it.path in renameOldPaths }
            batch.removeAll { it is FileEvent.AlbumCreated && it.path in renameNewPaths }
            for ((oldPath, newPath) in renamePairs) {
                batch.add(FileEvent.AlbumRenamed(oldPath, newPath))
            }
        }

        val dirAlbumRename = batch.filterIsInstance<FileEvent.AlbumRenamed>().toSet()
        if (dirAlbumRename.isNotEmpty()) {
            for (r in dirAlbumRename) {
                watchedDirs.remove(r.oldPath)
                watchedDirs.add(r.newPath)
            }
        }

        val seen = mutableSetOf<Pair<String, Path>>()
        val toRemove = mutableListOf<FileEvent>()
        for (event in batch.reversed()) {
            val key = when (event) {
                is FileEvent.ImageCreated -> "ic" to event.imagePath
                is FileEvent.ImageDeleted -> "id" to event.imagePath
                is FileEvent.ImageModified -> "im" to event.imagePath
                is FileEvent.AlbumCreated -> "ac" to event.path
                is FileEvent.AlbumDeleted -> "ad" to event.path
                is FileEvent.AlbumRenamed -> "ar" to event.oldPath
                is FileEvent.AlbumModified -> "am" to event.path
            }
            if (key in seen) toRemove.add(event)
            else seen.add(key)
        }
        batch.removeAll(toRemove)

        // Consolidate per-album image events into single AlbumModified
        val albumsToSync = mutableSetOf<Path>()
        val imageEvents = mutableListOf<FileEvent>()
        for (event in batch) {
            when (event) {
                is FileEvent.ImageCreated -> { albumsToSync.add(event.albumPath); imageEvents.add(event) }
                is FileEvent.ImageDeleted -> { albumsToSync.add(event.albumPath); imageEvents.add(event) }
                is FileEvent.ImageModified -> { albumsToSync.add(event.albumPath); imageEvents.add(event) }
                else -> {}
            }
        }
        if (imageEvents.isNotEmpty()) {
            batch.removeAll(imageEvents)
            for (path in albumsToSync) {
                batch.add(FileEvent.AlbumModified(path))
            }
        }
    }
}
