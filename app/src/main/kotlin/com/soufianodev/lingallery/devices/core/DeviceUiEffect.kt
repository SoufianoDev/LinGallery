package com.soufianodev.lingallery.devices.core

import com.soufianodev.lingallery.devices.ui.DeviceIssueDisplayData

sealed interface DeviceUiEffect {
    data class Dialog(val displayData: DeviceIssueDisplayData) : DeviceUiEffect
    data class Snackbar(val message: String, val isError: Boolean = false) : DeviceUiEffect
}
