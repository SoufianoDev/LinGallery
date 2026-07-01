package com.soufianodev.lingallery.native

import com.soufianodev.lingallery.native.mtp.MtpEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val _mtpEvents = MutableSharedFlow<MtpEvent>(extraBufferCapacity = 512)
    val mtpEvents: SharedFlow<MtpEvent> = _mtpEvents.asSharedFlow()

    fun emit(event: MtpEvent) {
        if (!_mtpEvents.tryEmit(event)) {
            LinLogger.w("EventBus", "Failed to emit ${event::class.simpleName} buffer full")
        }
    }
}
