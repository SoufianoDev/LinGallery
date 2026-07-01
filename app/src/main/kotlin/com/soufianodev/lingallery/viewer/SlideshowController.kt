package com.soufianodev.lingallery.viewer

import com.soufianodev.lingallery.app.AppConst
import kotlinx.coroutines.*

class SlideshowController(
    private val onAdvance: () -> Unit
) {
    private var job: Job? = null

    fun start(intervalMs: Long = AppConst.SLIDESHOW_DEFAULT_INTERVAL_MS) {
        stop()
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(intervalMs)
                onAdvance()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    val isActive: Boolean get() = job?.isActive == true
}
