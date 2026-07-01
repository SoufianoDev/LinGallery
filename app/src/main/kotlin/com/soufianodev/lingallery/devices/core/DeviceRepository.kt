package com.soufianodev.lingallery.devices.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface DeviceRepository {
    val deviceStates: StateFlow<Map<String, DeviceState>>
    val deviceActivities: StateFlow<DeviceActivityMap>
    val pendingUserActions: StateFlow<Map<String, DeviceUserAction>>
    val deviceDisconnected: SharedFlow<String>
    fun establishConnection(deviceId: String)
    fun getDeviceDisplayName(deviceId: String): String
}
