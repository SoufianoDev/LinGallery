package com.soufianodev.lingallery.devices.core

sealed interface DeviceIssue {
    data object PermissionRequired : DeviceIssue
    data object DeviceLocked : DeviceIssue
    data object StorageUnavailable : DeviceIssue
    data object AuthenticationRequired : DeviceIssue
    data object PairingRequired : DeviceIssue
    data object UnsupportedProtocol : DeviceIssue
    data object Busy : DeviceIssue
    data object Disconnected : DeviceIssue
    data object Timeout : DeviceIssue
    data class Unknown(val code: Int = -1) : DeviceIssue
}

data class DeviceUserAction(
    val deviceId: String,
    val issue: DeviceIssue,
)
