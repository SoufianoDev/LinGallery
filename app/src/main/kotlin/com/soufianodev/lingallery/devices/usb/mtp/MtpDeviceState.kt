package com.soufianodev.lingallery.devices.usb.mtp

import com.soufianodev.lingallery.devices.core.DeviceState
import java.nio.file.Path

sealed class MtpDeviceState : DeviceState {
    abstract val serial: String
    abstract val manufacturer: String
    abstract val model: String

    data object NotConnected : MtpDeviceState() {
        override val serial: String get() = ""
        override val manufacturer: String get() = ""
        override val model: String get() = ""
    }

    data class Queued(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        override val friendlyName: String,
        val detectedAtNanos: Long,
    ) : MtpDeviceState()

    data class Detected(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
    ) : MtpDeviceState()

    data class PermissionDenied(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        override val friendlyName: String = "",
        val reason: String,
    ) : MtpDeviceState()

    data class Mounting(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
    ) : MtpDeviceState()

    data class Connected(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        override val friendlyName: String,
        val mountPath: Path,
        val imageCount: Int = 0,
    ) : MtpDeviceState()

    data class Ready(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        override val friendlyName: String,
        val mountPath: Path,
        val albumCount: Int = 0,
    ) : MtpDeviceState()

    data class IndexFailed(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        val mountPath: Path,
        val message: String,
    ) : MtpDeviceState()

    data class Disconnecting(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        override val friendlyName: String,
        val mountPath: Path,
    ) : MtpDeviceState()

    data class Error(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
        val message: String,
    ) : MtpDeviceState()

    data class Disconnected(
        override val serial: String,
        override val manufacturer: String,
        override val model: String,
    ) : MtpDeviceState()

    open val friendlyName: String get() = ""

    val isTerminal: Boolean
        get() = this is Ready || this is Error || this is PermissionDenied || this is Disconnected || this is IndexFailed

    val isTransitioning: Boolean
        get() = this is Mounting || this is Detected || this is Connected || this is Queued
}
