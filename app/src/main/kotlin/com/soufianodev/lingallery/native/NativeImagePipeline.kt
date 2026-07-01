package com.soufianodev.lingallery.native

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

data class DecodedImageDto(
    val requestId: Long,
    val pathKey: String,
    val role: ImagePipelineRole,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val sourceWidth: Float,
    val sourceHeight: Float,
    val pixels: ByteArray,
) {
    fun toOwnedImage(): OwnedSkiaImage {
        val info = ImageInfo(
            ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, null),
            decodedWidth,
            decodedHeight
        )
        val image = Image.makeRaster(info, pixels, decodedWidth * 4)
        return OwnedSkiaImage(
            requestId = requestId,
            pathKey = pathKey,
            role = role,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            decodedWidth = decodedWidth,
            decodedHeight = decodedHeight,
            byteCount = pixels.size,
            skiaImage = image,
        )
    }
}

object NativeImagePipeline {
    private val requestIds = AtomicLong(1L)

    init {
        NativeLibLoader.load()
    }

    private external fun nativeDecodeThumbnail(
        requestId: Long,
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): ByteArray?

    private external fun nativeDecodeViewer(
        requestId: Long,
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): ByteArray?

    external fun nativeCancel(requestId: Long)
    external fun nativeCancelAllExcept(requestId: Long)
    external fun nativeTrim()

    fun nextRequestId(): Long = requestIds.getAndIncrement()

    fun decodeThumbnail(
        requestId: Long,
        path: Path,
        lastModified: Long,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): DecodedImageDto? {
        if (!NativeLibLoader.isAvailable) return null
        val bytes = nativeDecodeThumbnail(
            requestId,
            path.toAbsolutePath().normalize().toString(),
            targetWidthPx.coerceAtLeast(1),
            targetHeightPx.coerceAtLeast(1)
        ) ?: return null
        return parseResult(requestId, "${path}_${lastModified}", ImagePipelineRole.THUMBNAIL, bytes)
    }

    fun decodeViewer(
        requestId: Long,
        path: Path,
        lastModified: Long,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        zoomScale: Float,
    ): DecodedImageDto? {
        if (!NativeLibLoader.isAvailable) return null
        val bucket = decodeBucket(zoomScale)
        val targetWidth = ceil(viewportWidthPx.coerceAtLeast(1) * bucket).toInt()
        val targetHeight = ceil(viewportHeightPx.coerceAtLeast(1) * bucket).toInt()
        val bytes = nativeDecodeViewer(
            requestId,
            path.toAbsolutePath().normalize().toString(),
            targetWidth.coerceAtLeast(1),
            targetHeight.coerceAtLeast(1)
        ) ?: return null
        return parseResult(requestId, "${path}_${lastModified}_${targetWidth}x${targetHeight}", ImagePipelineRole.VIEWER, bytes)
    }

    fun cancel(requestId: Long) {
        if (NativeLibLoader.isAvailable) nativeCancel(requestId)
    }

    fun cancelAllExcept(requestId: Long) {
        if (NativeLibLoader.isAvailable) nativeCancelAllExcept(requestId)
    }

    fun trim() {
        if (NativeLibLoader.isAvailable) nativeTrim()
    }

    internal fun decodeBucket(zoomScale: Float): Float {
        val demand = max(1f, zoomScale)
        return when {
            demand <= 1.25f -> 1f
            demand <= 1.75f -> 1.5f
            demand <= 2.5f -> 2f
            demand <= 3.5f -> 3f
            else -> 4f
        }
    }

    internal fun parseResult(
        requestId: Long,
        pathKey: String,
        role: ImagePipelineRole,
        bytes: ByteArray,
    ): DecodedImageDto? {
        if (bytes.size < HEADER_BYTES) return null
        val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val decodedWidth = header.int
        val decodedHeight = header.int
        val sourceWidth = header.float
        val sourceHeight = header.float
        val pixelCount = decodedWidth.toLong() * decodedHeight.toLong() * 4L
        if (decodedWidth <= 0 || decodedHeight <= 0 || pixelCount <= 0 || pixelCount > Int.MAX_VALUE) return null
        if (bytes.size != HEADER_BYTES + pixelCount.toInt()) return null
        val pixels = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        return DecodedImageDto(
            requestId = requestId,
            pathKey = pathKey,
            role = role,
            decodedWidth = decodedWidth,
            decodedHeight = decodedHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            pixels = pixels,
        )
    }

    private const val HEADER_BYTES = 16
}
