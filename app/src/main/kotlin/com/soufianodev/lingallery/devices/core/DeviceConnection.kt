package com.soufianodev.lingallery.devices.core

data class DeviceConnection(val serial: String, val state: DeviceState, val connectedAt: Long = System.currentTimeMillis())
