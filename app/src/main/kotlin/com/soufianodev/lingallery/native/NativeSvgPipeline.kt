package com.soufianodev.lingallery.native

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

object NativeSvgPipeline {
    init {
        NativeLibLoader.load()
    }

    // Loading
    private external fun nativeSvgLoad(path: String): Long
    private external fun nativeSvgLoadFromString(svgContent: String): Long

    // Rendering — returns [w:4][h:4][sw:4][sh:4][RGBA...] same header as NativeImagePipeline
    private external fun nativeSvgRender(handle: Long, width: Int, height: Int): ByteArray?

    // Info
    private external fun nativeSvgGetSize(handle: Long): ByteArray? // [w:4][h:4]

    // Editing
    private external fun nativeSvgEditRotate(handle: Long, degrees: Float): Boolean
    private external fun nativeSvgEditFlipH(handle: Long): Boolean
    private external fun nativeSvgEditFlipV(handle: Long): Boolean
    private external fun nativeSvgEditCrop(handle: Long, x: Float, y: Float, w: Float, h: Float): Boolean

    // Serialization
    private external fun nativeSvgSerialize(handle: Long): String?
    private external fun nativeSvgSaveToFile(handle: Long, path: String): Boolean

    // Lifecycle
    private external fun nativeSvgRelease(handle: Long)

    // ── Public API ────────────────────────────────────────────────

    fun load(path: Path): Long {
        if (!NativeLibLoader.isAvailable) return -1L
        return nativeSvgLoad(path.toAbsolutePath().normalize().toString())
    }

    fun loadFromString(svgContent: String): Long {
        if (!NativeLibLoader.isAvailable) return -1L
        return nativeSvgLoadFromString(svgContent)
    }

    fun render(handle: Long, width: Int, height: Int): ByteArray? {
        if (!NativeLibLoader.isAvailable || handle < 0L) return null
        return nativeSvgRender(handle, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    data class SvgSize(val width: Float, val height: Float)

    fun getSize(handle: Long): SvgSize? {
        if (!NativeLibLoader.isAvailable || handle < 0L) return null
        val bytes = nativeSvgGetSize(handle) ?: return null
        if (bytes.size < 8) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return SvgSize(buf.float, buf.float)
    }

    fun editRotate(handle: Long, degrees: Float): Boolean {
        if (!NativeLibLoader.isAvailable || handle < 0L) return false
        return nativeSvgEditRotate(handle, degrees)
    }

    fun editFlipH(handle: Long): Boolean {
        if (!NativeLibLoader.isAvailable || handle < 0L) return false
        return nativeSvgEditFlipH(handle)
    }

    fun editFlipV(handle: Long): Boolean {
        if (!NativeLibLoader.isAvailable || handle < 0L) return false
        return nativeSvgEditFlipV(handle)
    }

    fun editCrop(handle: Long, x: Float, y: Float, w: Float, h: Float): Boolean {
        if (!NativeLibLoader.isAvailable || handle < 0L) return false
        return nativeSvgEditCrop(handle, x, y, w, h)
    }

    fun serialize(handle: Long): String? {
        if (!NativeLibLoader.isAvailable || handle < 0L) return null
        return nativeSvgSerialize(handle)
    }

    fun saveToFile(handle: Long, path: Path): Boolean {
        if (!NativeLibLoader.isAvailable || handle < 0L) return false
        return nativeSvgSaveToFile(handle, path.toAbsolutePath().normalize().toString())
    }

    fun release(handle: Long) {
        if (!NativeLibLoader.isAvailable || handle < 0L) return
        nativeSvgRelease(handle)
    }
}
