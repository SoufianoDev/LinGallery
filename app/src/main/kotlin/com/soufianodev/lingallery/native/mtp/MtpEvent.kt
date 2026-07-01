package com.soufianodev.lingallery.native.mtp

import java.nio.file.Path

sealed class MtpEvent {
    data class DeviceDetected(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val detectedAtNanos: Long = System.nanoTime(),
    ) : MtpEvent()

    data class DeviceQueued(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val friendlyName: String,
        val detectedAtNanos: Long,
    ) : MtpEvent()

    data class DevicePermissionDenied(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val reason: String,
    ) : MtpEvent()

    data class DeviceAlbumCreated(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path,
        val friendlyName: String,
    ) : MtpEvent()

    data class DeviceAlbumProgress(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path,
        val count: Int,
    ) : MtpEvent()

    data class DeviceAlbumBatch(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path,
        val batchTsv: String,
        val cumulativeCount: Int,
    ) : MtpEvent()

    data class DeviceAlbumReady(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path,
        val totalCount: Int,
    ) : MtpEvent()

    data class DeviceAlbumFailed(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path,
        val message: String,
    ) : MtpEvent()

    data class DeviceError(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val message: String,
    ) : MtpEvent()

    data class DeviceDisconnected(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path?,
    ) : MtpEvent()

    data class DeviceCleanupComplete(
        val serial: String,
    ) : MtpEvent()
}
