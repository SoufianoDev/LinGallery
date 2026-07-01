package com.soufianodev.lingallery.native

import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.model.ImageFile
import java.nio.file.Path

object NativeScanner {

    init {
        NativeLibLoader.load()
    }

    val isAvailable: Boolean
        get() = NativeLibLoader.isAvailable

    @JvmStatic
    external fun nativeScan(rootsStr: String): String

    fun scanToAlbums(roots: List<Path>, currentAlbumPath: Path? = null): List<Album> {
        val rootsStr = roots.joinToString(";") { it.toAbsolutePath().toString() }
        val result = nativeScan(rootsStr)

        val albumImagesMap = linkedMapOf<Path, MutableList<ImageFile>>()

        for (line in result.lineSequence()) {
            if (line.isEmpty()) continue
            val firstTab = line.indexOf('\t')
            if (firstTab < 0) continue
            val secondTab = line.indexOf('\t', firstTab + 1)
            if (secondTab < 0) continue

            val pathStr = line.substring(0, firstTab)
            val sizeStr = line.substring(firstTab + 1, secondTab)
            val mtimeStr = line.substring(secondTab + 1)

            val size = sizeStr.toLongOrNull() ?: continue
            val lastModified = mtimeStr.toLongOrNull() ?: continue
            val path = Path.of(pathStr)
            val parent = path.parent ?: continue
            val name = path.fileName.toString()
            val dot = name.lastIndexOf('.')
            val ext = if (dot >= 0) name.substring(dot).lowercase() else ""

            albumImagesMap.getOrPut(parent) { mutableListOf() }.add(
                ImageFile(
                    path = path,
                    name = name,
                    extension = ext,
                    size = size,
                    lastModified = lastModified
                )
            )
        }

        val albums = albumImagesMap.map { (dir, images) ->
            val sorted = images.distinctBy { it.path }.sortedByDescending { it.lastModified }
            Album(
                path = dir,
                name = dir.fileName.toString(),
                images = sorted,
                previewPath = sorted.first().path
            )
        }

        if (currentAlbumPath != null) {
            val normalizedCurrent = try {
                currentAlbumPath.toRealPath()
            } catch (_: Exception) {
                currentAlbumPath.toAbsolutePath().normalize()
            }
            val currentAlbum = albums.find {
                val norm = try { it.path.toRealPath() } catch (_: Exception) { it.path.toAbsolutePath().normalize() }
                norm == normalizedCurrent
            }
            if (currentAlbum != null) {
                return listOf(currentAlbum) + (albums - currentAlbum)
            }
        }

        return albums
    }
}
