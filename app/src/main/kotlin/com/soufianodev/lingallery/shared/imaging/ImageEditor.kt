package com.soufianodev.lingallery.shared.imaging

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.angles.Degrees
import com.sksamuel.scrimage.nio.BmpWriter
import com.sksamuel.scrimage.nio.ImageWriter
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.nio.TiffWriter
import com.sksamuel.scrimage.webp.WebpWriter
import com.soufianodev.lingallery.app.Strings
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.text.SimpleDateFormat
import java.util.*
import javax.imageio.ImageIO

object ImageEditor {

    fun crop(path: Path, x: Float, y: Float, width: Float, height: Float, destPath: Path? = null): Boolean {
        return try {
            val img = ImmutableImage.loader().fromFile(path.toFile()).applyExifOrientation(path)
            if (img.width <= 0 || img.height <= 0 || width <= 0f || height <= 0f) return false
            var left = kotlin.math.floor(x + 0.5f).toInt()
            var top = kotlin.math.floor(y + 0.5f).toInt()
            var right = kotlin.math.floor((x + width) + 0.5f).toInt()
            var bottom = kotlin.math.floor((y + height) + 0.5f).toInt()
            left = maxOf(0, minOf(left, img.width))
            top = maxOf(0, minOf(top, img.height))
            right = maxOf(left + 1, minOf(right, img.width))
            bottom = maxOf(top + 1, minOf(bottom, img.height))
            val cropped = img.subimage(left, top, right - left, bottom - top)
            val dest = destPath ?: path
            val writer = writerForExtension(dest) ?: return false
            writeWithFlush(cropped, writer, dest.toFile())
        } catch (_: Exception) { false }
    }

    fun rotate(path: Path, degrees: Int): Boolean {
        return try {
            val img = ImmutableImage.loader().fromFile(path.toFile())
                .applyExifOrientation(path)
                .rotate(Degrees(degrees))
            val writer = writerForExtension(path) ?: return false
            writeWithFlush(img, writer, path.toFile())
        } catch (_: Exception) { false }
    }

    fun flipHorizontal(path: Path): Boolean {
        return try {
            val img = ImmutableImage.loader().fromFile(path.toFile())
                .applyExifOrientation(path)
                .flipX()
            val writer = writerForExtension(path) ?: return false
            writeWithFlush(img, writer, path.toFile())
        } catch (_: Exception) { false }
    }

    fun readExif(path: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        try {
            val name = path.fileName.toString()
            val ext = name.substringAfterLast('.', "").lowercase()
            try {
                val attrs = Files.readAttributes(path, "*")
                val size = attrs["size"] as? Long ?: 0L
                val sizeKb = size / 1024.0
                result[Strings.Exif.fileSize] = if (sizeKb >= 1024) {
                    "%.2f MB".format(sizeKb / 1024.0)
                } else {
                    "%.1f KB".format(sizeKb)
                }
                val mtime = attrs["lastModifiedTime"] as? FileTime
                if (mtime != null) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
                    result[Strings.Exif.modified] = sdf.format(Date(mtime.toMillis()))
                }
            } catch (_: Exception) { }
            if (ext != "svg") {
                try {
                    val readers = ImageIO.getImageReadersBySuffix(ext)
                    if (readers.hasNext()) {
                        val r = readers.next()
                        val stream = ImageIO.createImageInputStream(path.toFile())
                        r.input = stream
                        val w = r.getWidth(0)
                        val h = r.getHeight(0)
                        if (w > 0 && h > 0) {
                            result[Strings.Exif.dimensions] = "${w} \u00d7 ${h} px"
                        }
                        result[Strings.Exif.format] = r.formatName
                        stream.close()
                    }
                } catch (_: Exception) { }
            } else {
                result[Strings.Exif.format] = Strings.Exif.svg
            }
            if (ext !in setOf("png", "bmp", "svg", "webp")) {
                try {
                    val metadata = ImageMetadataReader.readMetadata(path.toFile())
                    val exifSub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                    if (exifSub != null) {
                        val date = exifSub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                        if (date != null) result[Strings.Exif.dateTaken] = date
                        val iso = exifSub.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)
                        if (iso != null) result[Strings.Exif.iso] = iso.toString()
                        val aperture = exifSub.getString(ExifSubIFDDirectory.TAG_APERTURE)
                        if (aperture != null) result[Strings.Exif.aperture] = aperture
                        val shutter = exifSub.getString(ExifSubIFDDirectory.TAG_SHUTTER_SPEED)
                        if (shutter != null) result[Strings.Exif.shutterSpeed] = shutter
                        val focal = exifSub.getString(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
                        if (focal != null) result[Strings.Exif.focalLength] = focal
                    }
                    val exifIfd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                    if (exifIfd0 != null) {
                        val make = exifIfd0.getString(ExifIFD0Directory.TAG_MAKE)
                        if (make != null) result[Strings.Exif.cameraMake] = make
                        val model = exifIfd0.getString(ExifIFD0Directory.TAG_MODEL)
                        if (model != null) result[Strings.Exif.cameraModel] = model
                    }
                    val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
                    if (gps != null) {
                        val geo = gps.geoLocation
                        if (geo != null) {
                            result[Strings.Exif.gps] = "${geo.latitude}, ${geo.longitude}"
                        }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun writerForExtension(path: Path): ImageWriter? {
        val ext = path.fileName.toString().substringAfterLast('.', "png").lowercase()
        return when (ext) {
            "png" -> PngWriter()
            "jpg", "jpeg" -> JpegWriter()
            "webp" -> WebpWriter.DEFAULT
            "bmp" -> BmpWriter()
            "tiff", "tif" -> TiffWriter()
            else -> null
        }
    }

    private fun writeWithFlush(img: ImmutableImage, writer: ImageWriter, file: java.io.File): Boolean {
        return try {
            img.output(writer, file)
            FileOutputStream(file, true).use { fos ->
                fos.channel.force(true)
            }
            Files.size(file.toPath()) > 0
        } catch (_: Exception) { false }
    }

    private fun ImmutableImage.applyExifOrientation(path: Path): ImmutableImage {
        return try {
            val metadata = ImageMetadataReader.readMetadata(path.toFile())
            val dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val orientation = dir?.getInteger(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
            when (orientation) {
                1 -> this
                2 -> this.flipX()
                3 -> this.rotate(Degrees(180))
                4 -> this.flipY()
                5 -> this.rotate(Degrees(90)).flipX()
                6 -> this.rotate(Degrees(90))
                7 -> this.rotate(Degrees(-90)).flipX()
                8 -> this.rotate(Degrees(-90))
                else -> this
            }
        } catch (_: Exception) { this }
    }
}
