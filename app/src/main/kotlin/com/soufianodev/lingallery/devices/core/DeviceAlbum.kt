package com.soufianodev.lingallery.devices.core

import java.nio.file.Path

data class DeviceAlbum(val deviceSerial: String, val mountPath: Path, val name: String, val imageCount: Int = 0)
