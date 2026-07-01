package com.soufianodev.lingallery.devices

import com.soufianodev.lingallery.devices.core.*
import com.soufianodev.lingallery.devices.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DeviceConnectionStateHolder(
    private val scope: CoroutineScope,
    private val deviceRepository: DeviceRepository,
    private val issuePresenter: DeviceIssuePresenter,
) {
    private data class QueuedEffect(val deviceId: String, val effect: DeviceUiEffect)

    private val _effectQueue = MutableStateFlow<List<QueuedEffect>>(emptyList())
    val currentEffect: StateFlow<DeviceUiEffect?> =
        _effectQueue.map { it.firstOrNull()?.effect }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val suppressedDevices = mutableSetOf<String>()

    init {
        scope.launch {
            deviceRepository.deviceDisconnected.collect { deviceId ->
                suppressedDevices.remove(deviceId)
                _effectQueue.update { queue ->
                    queue.filterNot { it.deviceId == deviceId }
                }
            }
        }
        scope.launch {
            deviceRepository.pendingUserActions.collect { actions ->
                val queuedIds = _effectQueue.value.map { it.deviceId }.toSet()
                for ((deviceId, action) in actions) {
                    if (deviceId in suppressedDevices) continue
                    if (deviceId in queuedIds) continue
                    val effect = issuePresenter.present(action)
                    _effectQueue.update { it + QueuedEffect(deviceId, effect) }
                }
                _effectQueue.update { queue ->
                    queue.filter { it.deviceId in actions.keys }
                }
            }
        }
    }

    fun establish() { handleAction(DialogActionKind.RETRY) }
    fun cancel()    { handleAction(DialogActionKind.CANCEL) }
    fun dismiss()   { handleAction(DialogActionKind.DISMISS) }

    private fun handleAction(kind: DialogActionKind) {
        val queued = _effectQueue.value.firstOrNull() ?: return
        _effectQueue.update { it.drop(1) }
        when (kind) {
            DialogActionKind.RETRY -> {
                suppressedDevices.remove(queued.deviceId)
                deviceRepository.establishConnection(queued.deviceId)
            }
            DialogActionKind.CANCEL -> {
                suppressedDevices.add(queued.deviceId)
            }
            DialogActionKind.DISMISS -> { }
        }
    }
}
