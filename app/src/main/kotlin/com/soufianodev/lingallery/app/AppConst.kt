package com.soufianodev.lingallery.app

import java.nio.file.Path

object AppConst {
    const val APP_VERSION = "1.0.0"
    val DEFAULT_SCAN_ROOTS = listOf("~/Pictures", "~/Downloads", "~/Desktop")
    val SUPPORTED_FORMATS = setOf(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff", ".tif", ".svg")
    val EDITABLE_FORMATS = setOf(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff", ".tif")
    const val THUMB_SIZE = 220
    const val PRELOAD_AHEAD = 3
    const val SLIDESHOW_DEFAULT_INTERVAL_MS = 4000L
    const val TOP_BAR_HEIGHT = 64
    const val SIDEBAR_WIDTH = 260
    const val BOTTOM_BAR_HEIGHT = 56
    const val THUMB_SPACING = 10
    const val CORNER_RADIUS = 12
    const val ICON_SIZE = 24
    const val ZOOM_STEP = 1.15
    const val ZOOM_MIN = 0.05
    const val ZOOM_MAX = 32.0
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    val PHONE_THUMBNAIL_DIR: Path get() =
        Path.of(System.getProperty("user.home"), ".cache", "lingallery", "phone-thumbnails")

    const val MTP_POLL_INTERVAL_MS = 2000L
    const val MTP_PROBE_RETRIES = 3
    const val MTP_PROBE_BACKOFF_MS = 2000L
    const val MTP_BUFFERED_MODE_DEFAULT = true
    const val NATIVE_TEMP_DIR_PREFIX = "lingallery_native_"
    const val DISCONNECT_TIMEOUT_MS = 10_000L
}
