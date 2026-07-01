package com.soufianodev.lingallery.shared.filesystem

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TrashManager {

    private val home: Path = Paths.get(System.getProperty("user.home"))
    private val trashDir: Path = home.resolve(".local/share/Trash/files")
    private val infoDir: Path = home.resolve(".local/share/Trash/info")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun ensureDirs() {
        Files.createDirectories(trashDir)
        Files.createDirectories(infoDir)
    }

    fun moveToTrash(file: Path): Result<Path> = runCatching {
        ensureDirs()
        val realFile = file.toRealPath()
        val fileName = realFile.fileName.toString()
        val trashFileName = resolveCollision(trashDir, fileName)
        val trashPath = trashDir.resolve(trashFileName)
        val infoPath = infoDir.resolve("$trashFileName.trashinfo")

        Files.move(realFile, trashPath, StandardCopyOption.ATOMIC_MOVE)

        val infoContent = buildTrashInfo(realFile)
        Files.writeString(infoPath, infoContent, StandardCharsets.UTF_8)

        trashPath
    }

    fun restoreFromTrash(trashPath: Path, originalPath: Path): Result<Unit> = runCatching {
        val realOriginal = originalPath.toAbsolutePath().normalize()
        val infoPath = infoDir.resolve("${trashPath.fileName}.trashinfo")

        Files.createDirectories(realOriginal.parent)

        try {
            Files.move(trashPath, realOriginal, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.copy(trashPath, realOriginal, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(trashPath)
        }

        Files.deleteIfExists(infoPath)
    }

    private fun resolveCollision(dir: Path, fileName: String): String {
        if (!dir.resolve(fileName).toFile().exists()) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot >= 0) fileName.substring(0, dot) else fileName
        val ext = if (dot >= 0) fileName.substring(dot) else ""
        var counter = 1
        while (counter < 10000) {
            val candidate = "$stem ($counter)$ext"
            if (!dir.resolve(candidate).toFile().exists()) return candidate
            counter++
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }

    private fun buildTrashInfo(originalPath: Path): String {
        val encoded = URLEncoder.encode(originalPath.toString(), StandardCharsets.UTF_8)
        val date = LocalDateTime.now().format(dateFormatter)
        return "[Trash Info]\nPath=$encoded\nDeletionDate=$date\n"
    }
}
