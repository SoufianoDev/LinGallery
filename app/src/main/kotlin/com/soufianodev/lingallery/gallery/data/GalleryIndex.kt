package com.soufianodev.lingallery.gallery.data

import com.soufianodev.lingallery.gallery.GalleryUiState
import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.model.ImageFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class GalleryIndex(private val dbPath: Path = defaultDbPath()) {

    private var connection: Connection? = null

    private fun conn(): Connection {
        val c = connection
        if (c != null && !c.isClosed) return c
        Files.createDirectories(dbPath.parent)
        val newConn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        migrate(newConn)
        connection = newConn
        return newConn
    }

    private fun migrate(c: Connection) {
        c.createStatement().executeUpdate(
            """CREATE TABLE IF NOT EXISTS albums (
                path TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                scanned_at INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0
            )"""
        )
        c.createStatement().executeUpdate(
            """CREATE TABLE IF NOT EXISTS images (
                path TEXT PRIMARY KEY,
                album_path TEXT NOT NULL REFERENCES albums(path),
                name TEXT NOT NULL,
                extension TEXT NOT NULL,
                size INTEGER NOT NULL,
                last_modified INTEGER NOT NULL
            )"""
        )
        c.createStatement().executeUpdate(
            """CREATE TABLE IF NOT EXISTS metadata (
                image_path TEXT PRIMARY KEY REFERENCES images(path),
                exif_json TEXT,
                cached_at INTEGER NOT NULL
            )"""
        )
        c.createStatement().executeUpdate(
            """CREATE TABLE IF NOT EXISTS index_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )"""
        )
        try {
            c.createStatement().executeUpdate("ALTER TABLE albums ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
        } catch (_: Exception) {}
    }

    fun loadSnapshot(): GalleryUiState? {
        val c = conn()
        val albumRows = c.createStatement().executeQuery(
            "SELECT path, name FROM albums ORDER BY sort_order ASC"
        )
        val albums = mutableListOf<Album>()
        while (albumRows.next()) {
            val albumPath = albumRows.getString("path")
            val name = albumRows.getString("name")
            val imgStmt = c.prepareStatement(
                "SELECT path, name, extension, size, last_modified FROM images WHERE album_path = ? ORDER BY last_modified DESC"
            )
            imgStmt.setString(1, albumPath)
            val imgRows = imgStmt.executeQuery()
            val images = mutableListOf<ImageFile>()
            while (imgRows.next()) {
                images.add(
                    ImageFile(
                        path = Path.of(imgRows.getString("path")),
                        name = imgRows.getString("name"),
                        extension = imgRows.getString("extension"),
                        size = imgRows.getLong("size"),
                        lastModified = imgRows.getLong("last_modified")
                    )
                )
            }
            imgRows.close()
            imgStmt.close()
            if (images.isNotEmpty()) {
                albums.add(
                    Album(
                        path = Path.of(albumPath),
                        name = name,
                        images = images,
                        previewPath = images.first().path
                    )
                )
            }
        }
        albumRows.close()
        if (albums.isEmpty()) return null
        val validated = albums.mapNotNull { album ->
            val valid = album.images.filter { Files.exists(it.path) }
            if (valid.isEmpty()) null else album.copy(images = valid, previewPath = valid.first().path)
        }
        if (validated.isEmpty()) return null
        return GalleryUiState(
            albums = validated,
            isScanning = false
        ).sortAlbums()
    }

    fun saveState(state: GalleryUiState) {
        val c = conn()
        c.autoCommit = false
        try {
            c.createStatement().executeUpdate("DELETE FROM images")
            c.createStatement().executeUpdate("DELETE FROM albums")
            val albumStmt = c.prepareStatement(
                "INSERT OR REPLACE INTO albums (path, name, scanned_at, sort_order) VALUES (?, ?, ?, ?)"
            )
            val imageStmt = c.prepareStatement(
                "INSERT OR REPLACE INTO images (path, album_path, name, extension, size, last_modified) VALUES (?, ?, ?, ?, ?, ?)"
            )
            val now = System.currentTimeMillis()
            for ((index, album) in state.albums.filter { !it.isDeviceAlbum }.withIndex()) {
                albumStmt.setString(1, album.path.toString())
                albumStmt.setString(2, album.name)
                albumStmt.setLong(3, now)
                albumStmt.setInt(4, index)
                albumStmt.executeUpdate()
                for (image in album.images) {
                    imageStmt.setString(1, image.path.toString())
                    imageStmt.setString(2, album.path.toString())
                    imageStmt.setString(3, image.name)
                    imageStmt.setString(4, image.extension)
                    imageStmt.setLong(5, image.size)
                    imageStmt.setLong(6, image.lastModified)
                    imageStmt.executeUpdate()
                }
            }
            albumStmt.close()
            imageStmt.close()
            c.commit()
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun saveAlbum(album: Album) {
        val c = conn()
        val maxStmt = c.createStatement()
        val maxRows = maxStmt.executeQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 AS next_order FROM albums")
        val nextOrder = if (maxRows.next()) maxRows.getInt("next_order") else 0
        maxRows.close()
        maxStmt.close()

        val stmt = c.prepareStatement(
            "INSERT OR REPLACE INTO albums (path, name, scanned_at, sort_order) VALUES (?, ?, ?, ?)"
        )
        stmt.setString(1, album.path.toString())
        stmt.setString(2, album.name)
        stmt.setLong(3, System.currentTimeMillis())
        stmt.setInt(4, nextOrder)
        stmt.executeUpdate()
        stmt.close()
    }

    fun removeAlbum(albumPath: Path) {
        val c = conn()
        val delImages = c.prepareStatement("DELETE FROM images WHERE album_path = ?")
        delImages.setString(1, albumPath.toString())
        delImages.executeUpdate()
        delImages.close()
        val delAlbum = c.prepareStatement("DELETE FROM albums WHERE path = ?")
        delAlbum.setString(1, albumPath.toString())
        delAlbum.executeUpdate()
        delAlbum.close()
    }

    fun saveImage(albumPath: Path, image: ImageFile) {
        val c = conn()
        val stmt = c.prepareStatement(
            "INSERT OR REPLACE INTO images (path, album_path, name, extension, size, last_modified) VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.setString(1, image.path.toString())
        stmt.setString(2, albumPath.toString())
        stmt.setString(3, image.name)
        stmt.setString(4, image.extension)
        stmt.setLong(5, image.size)
        stmt.setLong(6, image.lastModified)
        stmt.executeUpdate()
        stmt.close()
    }

    fun removeImage(albumPath: Path, imagePath: Path) {
        val c = conn()
        val stmt = c.prepareStatement("DELETE FROM images WHERE path = ?")
        stmt.setString(1, imagePath.toString())
        stmt.executeUpdate()
        stmt.close()
    }

    fun saveMetadata(imagePath: Path, exifJson: String) {
        val c = conn()
        val stmt = c.prepareStatement(
            "INSERT OR REPLACE INTO metadata (image_path, exif_json, cached_at) VALUES (?, ?, ?)"
        )
        stmt.setString(1, imagePath.toString())
        stmt.setString(2, exifJson)
        stmt.setLong(3, System.currentTimeMillis())
        stmt.executeUpdate()
        stmt.close()
    }

    fun loadMetadata(imagePath: Path): String? {
        val c = conn()
        val stmt = c.prepareStatement(
            "SELECT exif_json FROM metadata WHERE image_path = ?"
        )
        stmt.setString(1, imagePath.toString())
        val rows = stmt.executeQuery()
        val result = if (rows.next()) rows.getString("exif_json") else null
        rows.close()
        stmt.close()
        return result
    }

    fun close() {
        try { connection?.close() } catch (_: Exception) {}
        connection = null
    }

    companion object {
        fun defaultDbPath(): Path {
            val home = System.getProperty("user.home")
            return Path.of(home, ".cache", "lingallery", "index.db")
        }
    }
}
