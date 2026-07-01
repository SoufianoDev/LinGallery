package com.soufianodev.lingallery.devices.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface DeviceManager {
    val deviceStates: StateFlow<Map<String, DeviceState>>
    fun init(scope: CoroutineScope)
    fun start()
    fun stop()
}
