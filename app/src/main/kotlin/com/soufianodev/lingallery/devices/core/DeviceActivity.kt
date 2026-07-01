package com.soufianodev.lingallery.devices.core

sealed interface DeviceActivity {
    data object Idle : DeviceActivity
    data class Connecting(
        val deviceId: String,
        val deviceName: String,
    ) : DeviceActivity
    data class Mounting(
        val deviceId: String,
        val deviceName: String,
    ) : DeviceActivity
    data class Scanning(
        val deviceId: String,
        val deviceName: String,
        val itemsFound: Int,
    ) : DeviceActivity
    data class Indexing(
        val deviceId: String,
        val deviceName: String,
        val itemsFound: Int,
    ) : DeviceActivity
}

data class DeviceActivityMap(
    val activities: Map<String, DeviceActivity> = emptyMap(),
) {
    val isActive: Boolean get() = activities.isNotEmpty()
}
