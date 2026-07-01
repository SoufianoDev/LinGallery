package com.soufianodev.lingallery.devices.core

fun createStableDeviceSuffix(deviceId: String): String =
    deviceId.takeLast(4).uppercase()
