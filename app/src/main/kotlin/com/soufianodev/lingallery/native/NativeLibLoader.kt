package com.soufianodev.lingallery.native

object NativeLibLoader {
    private var loaded = false
    var isAvailable = false
        private set

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                System.loadLibrary("lingallery_native")
                isAvailable = true
                LinLogger.i("NativeLibLoader", "Native library loaded: lingallery_native")
            } catch (e: UnsatisfiedLinkError) {
                LinLogger.e("NativeLibLoader", "Failed to load native library: ${e.message}")
            }
        }
    }
}
