package com.soufianodev.lingallery.devices.ui

import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.devices.core.DeviceActivity
import com.soufianodev.lingallery.devices.core.DeviceActivityMap

data class ScrollbarIndicatorState(
    val visible: Boolean,
    val progress: Float?,
)

data class SidebarBadge(
    val deviceId: String,
    val deviceName: String,
    val isSpinning: Boolean,
    val label: String?,
)

data class ProgressOverlayData(
    val message: String,
    val progress: Float?,
)

class DeviceActivityPresenter(
    private val strings: Strings.DeviceActivity,
) {
    fun toScrollbarState(activities: DeviceActivityMap): ScrollbarIndicatorState {
        val active = activities.activities.values.firstOrNull { it !is DeviceActivity.Idle }
            ?: return ScrollbarIndicatorState(visible = false, progress = null)
        return when (active) {
            is DeviceActivity.Connecting -> ScrollbarIndicatorState(visible = true, progress = null)
            is DeviceActivity.Mounting -> ScrollbarIndicatorState(visible = true, progress = null)
            is DeviceActivity.Scanning -> ScrollbarIndicatorState(visible = true, progress = null)
            is DeviceActivity.Indexing -> ScrollbarIndicatorState(visible = true, progress = null)
            is DeviceActivity.Idle -> ScrollbarIndicatorState(visible = false, progress = null)
        }
    }

    fun toSidebarBadges(activities: DeviceActivityMap): List<SidebarBadge> {
        return activities.activities.values.mapNotNull { activity ->
            when (activity) {
                is DeviceActivity.Connecting -> SidebarBadge(
                    deviceId = activity.deviceId,
                    deviceName = activity.deviceName,
                    isSpinning = true,
                    label = strings.connecting(activity.deviceName),
                )
                is DeviceActivity.Mounting -> SidebarBadge(
                    deviceId = activity.deviceId,
                    deviceName = activity.deviceName,
                    isSpinning = true,
                    label = strings.mounting(activity.deviceName),
                )
                is DeviceActivity.Scanning -> SidebarBadge(
                    deviceId = activity.deviceId,
                    deviceName = activity.deviceName,
                    isSpinning = true,
                    label = strings.scanning(activity.deviceName, activity.itemsFound),
                )
                is DeviceActivity.Indexing -> SidebarBadge(
                    deviceId = activity.deviceId,
                    deviceName = activity.deviceName,
                    isSpinning = true,
                    label = strings.indexing(activity.deviceName, activity.itemsFound),
                )
                is DeviceActivity.Idle -> null
            }
        }
    }

    fun toStatusBarText(activities: DeviceActivityMap): String? {
        val active = activities.activities.values.firstOrNull { it !is DeviceActivity.Idle }
            ?: return null
        return when (active) {
            is DeviceActivity.Connecting -> strings.connecting(active.deviceName)
            is DeviceActivity.Mounting -> strings.mounting(active.deviceName)
            is DeviceActivity.Scanning -> strings.scanning(active.deviceName, active.itemsFound)
            is DeviceActivity.Indexing -> strings.indexing(active.deviceName, active.itemsFound)
            is DeviceActivity.Idle -> null
        }
    }

    fun toProgressOverlay(activities: DeviceActivityMap): ProgressOverlayData? {
        val active = activities.activities.values.firstOrNull { it !is DeviceActivity.Idle }
            ?: return null
        val message = when (active) {
            is DeviceActivity.Connecting -> strings.connecting(active.deviceName)
            is DeviceActivity.Mounting -> strings.mounting(active.deviceName)
            is DeviceActivity.Scanning -> strings.scanning(active.deviceName, active.itemsFound)
            is DeviceActivity.Indexing -> strings.indexing(active.deviceName, active.itemsFound)
            is DeviceActivity.Idle -> return null
        }
        return ProgressOverlayData(
            message = message,
            progress = null,
        )
    }
}
