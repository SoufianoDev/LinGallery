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
                // Packaged app: java.library.path is a dev-machine absolute path and the
                // .so ships inside the app jar instead. Extract and load it directly.
                try {
                    System.load(extractBundledLibrary())
                    isAvailable = true
                    LinLogger.i("NativeLibLoader", "Native library loaded from bundle")
                } catch (e2: Exception) {
                    LinLogger.e("NativeLibLoader", "Failed to load native library: ${e.message}")
                }
            }
        }
    }

    private fun extractBundledLibrary(): String {
        val name = "liblingallery_native.so"
        val stream = NativeLibLoader::class.java.getResourceAsStream("/native/$name")
            ?: throw IllegalStateException("bundled $name not found")
        val tmp = kotlin.io.path.createTempFile(prefix = "lingallery_", suffix = "_$name").toFile()
        tmp.deleteOnExit()
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return tmp.absolutePath
    }
}
