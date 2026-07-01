package com.soufianodev.lingallery.model

import java.nio.file.Path

data class Album(
    val path: Path,
    val name: String,
    val images: List<ImageFile>,
    val previewPath: Path?,
    val isDeviceAlbum: Boolean = false,
    val statusText: String? = null,
    val statusIsTransition: Boolean = false,
) {
    val imageCount: Int get() = images.size

    fun containsImage(imagePath: Path): Boolean =
        images.any { it.path == imagePath }
}
