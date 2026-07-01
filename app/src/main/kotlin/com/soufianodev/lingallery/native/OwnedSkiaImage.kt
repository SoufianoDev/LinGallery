package com.soufianodev.lingallery.native

import org.jetbrains.skia.Image
import java.util.concurrent.atomic.AtomicBoolean

enum class ImagePipelineRole {
    THUMBNAIL,
    VIEWER,
    SVG_VIEWER,
    SVG_THUMBNAIL,
}

class OwnedSkiaImage(
    val requestId: Long,
    val pathKey: String,
    val role: ImagePipelineRole,
    val sourceWidth: Float,
    val sourceHeight: Float,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val byteCount: Int,
    val skiaImage: Image,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            skiaImage.close()
        }
    }

    val isClosed: Boolean
        get() = closed.get()
}
