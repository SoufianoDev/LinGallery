package com.soufianodev.lingallery.devices.core

class DeviceDisplayNameResolver {

    /**
     * Resolves the user-visible device name using the project's canonical priority order.
     * The resolution order is considered part of the architectural contract and should
     * remain consistent across all device protocols unless explicitly changed.
     *
     * Priority order:
     * 1. Friendly Name — user-configurable device name (e.g. MTP property 0xD402)
     * 2. Model — USB Product Name (iProduct descriptor)
     * 3. Fallback label + stable suffix — deterministic fallback from device ID
     */
    fun resolve(
        metadata: DeviceDisplayNameMetadata,
        deviceId: String,
        fallbackLabel: String,
    ): String {
        metadata.friendlyName?.takeIf { it.isNotBlank() }?.let { return it }
        metadata.model?.takeIf { it.isNotBlank() }?.let { return it }
        return "$fallbackLabel ${createStableDeviceSuffix(deviceId)}"
    }
}
