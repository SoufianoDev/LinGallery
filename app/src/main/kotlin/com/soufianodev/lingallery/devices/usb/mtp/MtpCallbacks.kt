package com.soufianodev.lingallery.devices.usb.mtp

interface MtpCallbacks {
    fun onDeviceDetected(serial: String, manufacturer: String, model: String)
    fun onDeviceDisconnected(serial: String)
    fun onIndexingReady(serial: String, totalCount: Int)
}
