package com.soufianodev.lingallery.devices.usb.mtp

import com.soufianodev.lingallery.app.AppConst
import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.devices.core.DeviceActivity
import com.soufianodev.lingallery.devices.core.DeviceActivityMap
import com.soufianodev.lingallery.devices.core.DeviceDisplayNameMetadata
import com.soufianodev.lingallery.devices.core.DeviceDisplayNameResolver
import com.soufianodev.lingallery.devices.core.DeviceIssue
import com.soufianodev.lingallery.devices.core.DeviceManager
import com.soufianodev.lingallery.devices.core.DeviceRepository
import com.soufianodev.lingallery.devices.core.DeviceState
import com.soufianodev.lingallery.devices.core.DeviceUserAction
import com.soufianodev.lingallery.gallery.GalleryStateHolder
import com.soufianodev.lingallery.model.Album
import com.soufianodev.lingallery.native.EventBus
import com.soufianodev.lingallery.native.LinLogger
import com.soufianodev.lingallery.native.MemoryManager
import com.soufianodev.lingallery.native.mtp.NativeMtpBridge
import com.soufianodev.lingallery.native.mtp.MtpEvent
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory

class MtpProtocol(
    private val scope: CoroutineScope,
    private val galleryStateHolder: GalleryStateHolder,
    private val displayNameResolver: DeviceDisplayNameResolver,
) : DeviceManager, DeviceRepository {

    companion object {
        private const val BATCH_COALESCE_MS = 150L

        private const val MTP_OK                   = 0
        private const val MTP_ERR_PERMISSION        = 1
        private const val MTP_ERR_NO_STORAGE        = 2
        private const val MTP_ERR_MOUNT             = 3
        private const val MTP_ERR_ANDROID_NO_STORAGE = 4
        private const val MTP_ERR_SAMSUNG_RESTRICTED = 5
    }

    private val _deviceStates = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    override val deviceStates: StateFlow<Map<String, DeviceState>> = _deviceStates.asStateFlow()

    private val _pendingUserActions = MutableStateFlow<Map<String, DeviceUserAction>>(emptyMap())
    override val pendingUserActions: StateFlow<Map<String, DeviceUserAction>> = _pendingUserActions.asStateFlow()

    private val _deviceDisconnected = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val deviceDisconnected: SharedFlow<String> = _deviceDisconnected.asSharedFlow()

    private val _deviceActivities = MutableStateFlow(DeviceActivityMap())
    override val deviceActivities: StateFlow<DeviceActivityMap> = _deviceActivities.asStateFlow()

    private val mountPaths = ConcurrentHashMap<String, Path>()
    private val lastBatchUpdates = ConcurrentHashMap<String, Long>()
    private var mountJob: Job? = null
    private var initialized = false

    override fun init(scope: CoroutineScope) {}

    override fun start() {
        if (initialized) return
        cleanupStaleMounts()
        killCompetingProcesses()
        mountJob = scope.launch {
            EventBus.mtpEvents.collect { event ->
                handleEvent(event)
            }
        }
        initialized = true
        LinLogger.i("MtpProtocol", "Started")
    }

    override fun stop() {
        mountJob?.cancel()
        mountJob = null
        NativeMtpBridge.setBufferedMode(false)
        LinLogger.endBufferedMode()
        _deviceStates.value = emptyMap()
        _deviceActivities.value = DeviceActivityMap()
        _pendingUserActions.value = emptyMap()
        initialized = false
        LinLogger.i("MtpProtocol", "Stopped")
    }

    private suspend fun handleEvent(event: MtpEvent) {
        when (event) {
            is MtpEvent.DeviceDetected -> {
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.Detected(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                ))
                updateActivity(event.serial, DeviceActivity.Connecting(
                    deviceId = event.serial,
                    deviceName = getDeviceDisplayName(event.serial),
                ))
                mountDevice(event.serial, event.manufacturer, event.model)
            }

            is MtpEvent.DeviceAlbumCreated -> {
                val placeholder = Album(
                    path = event.mountPath,
                    name = event.friendlyName,
                    images = emptyList(),
                    previewPath = null,
                    isDeviceAlbum = true,
                    statusText = "0",
                    statusIsTransition = true,
                )
                galleryStateHolder.updateState { state ->
                    state.copy(
                        albums = listOf(placeholder) + state.albums,
                    )
                }
                updateActivity(event.serial, DeviceActivity.Scanning(
                    deviceId = event.serial,
                    deviceName = getDeviceDisplayName(event.serial),
                    itemsFound = 0,
                ))
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.Connected(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                    friendlyName = event.friendlyName,
                    mountPath = event.mountPath,
                ))
            }

            is MtpEvent.DeviceAlbumProgress -> {
                var deviceName = ""
                galleryStateHolder.updateState { state ->
                    val idx = state.albums.indexOfFirst { it.path == event.mountPath }
                    if (idx < 0) return@updateState state
                    deviceName = state.albums[idx].name
                    val newText = "${event.count}"
                    if (state.albums[idx].statusText == newText) return@updateState state
                    val albums = state.albums.toMutableList()
                    albums[idx] = albums[idx].copy(statusText = newText)
                    state.copy(albums = albums)
                }
                if (deviceName.isNotEmpty()) {
                    updateActivity(event.serial, DeviceActivity.Indexing(
                        deviceId = event.serial,
                        deviceName = deviceName,
                        itemsFound = event.count,
                    ))
                }
            }

            is MtpEvent.DeviceAlbumBatch -> {
                NativeMtpBridge.appendBatch(event.serial, event.batchTsv)
                val now = System.currentTimeMillis()
                val lastUpdate = lastBatchUpdates[event.serial] ?: 0L
                if (now - lastUpdate >= BATCH_COALESCE_MS) {
                    lastBatchUpdates[event.serial] = now
                    val images = NativeMtpBridge.getAccumulatedImages(event.serial)
                    var deviceName = ""
                    galleryStateHolder.updateState { state ->
                        val idx = state.albums.indexOfFirst { it.path == event.mountPath }
                        if (idx < 0) return@updateState state
                        val album = state.albums[idx]
                        deviceName = album.name
                        val statusText = if (images.isNotEmpty()) "${images.size} images" else "Discovering images..."
                        val updated = album.copy(
                            images = images,
                            statusText = statusText,
                            previewPath = album.previewPath ?: images.firstOrNull()?.path,
                        )
                        val albums = state.albums.toMutableList()
                        albums[idx] = updated
                        state.copy(albums = albums)
                    }
                    if (deviceName.isNotEmpty()) {
                        updateActivity(event.serial, DeviceActivity.Indexing(
                            deviceId = event.serial,
                            deviceName = deviceName,
                            itemsFound = images.size,
                        ))
                    }
                }
            }

            is MtpEvent.DeviceAlbumReady -> {
                val batchImages = NativeMtpBridge.getAccumulatedImages(event.serial)
                val finalImages = withContext(Dispatchers.IO) {
                    NativeMtpBridge.getNativeIndexedImages(event.serial)
                }
                val images = finalImages ?: batchImages
                galleryStateHolder.updateState { state ->
                    val idx = state.albums.indexOfFirst { it.path == event.mountPath }
                    if (idx < 0) return@updateState state
                    val updated = state.albums[idx].copy(
                        images = images,
                        statusText = null,
                        statusIsTransition = false,
                        isDeviceAlbum = true,
                    )
                    val albums = state.albums.toMutableList()
                    albums[idx] = updated
                    state.copy(albums = albums)
                }
                removeActivity(event.serial)
                val albumName = galleryStateHolder.uiState.value.albums
                    .firstOrNull { it.path == event.mountPath }?.name
                    ?: getDeviceDisplayName(event.serial)
                galleryStateHolder.setStatus("$albumName: ${images.size} images")
                galleryStateHolder.requestScrollToTop()
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.Ready(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                    friendlyName = albumName,
                    mountPath = event.mountPath,
                    albumCount = images.size,
                ))
                NativeMtpBridge.setBufferedMode(false)
                LinLogger.endBufferedMode()
            }

            is MtpEvent.DeviceAlbumFailed -> {
                removeActivity(event.serial)
                galleryStateHolder.updateState { state ->
                    val idx = state.albums.indexOfFirst { it.path == event.mountPath }
                    if (idx < 0) return@updateState state
                    val updated = state.albums[idx].copy(
                        statusText = "Indexing failed",
                        statusIsTransition = false,
                    )
                    val albums = state.albums.toMutableList()
                    albums[idx] = updated
                    state.copy(albums = albums)
                }
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.IndexFailed(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                    mountPath = event.mountPath,
                    message = event.message,
                ))
                galleryStateHolder.setStatus(event.message)
            }

            is MtpEvent.DeviceDisconnected -> {
                val mountPath = event.mountPath ?: mountPaths[event.serial]
                if (mountPath != null) {
                    val devName = getDeviceDisplayName(event.serial)
                    galleryStateHolder.setStatus("$devName disconnected")
                    _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.Disconnecting(
                        serial = event.serial,
                        manufacturer = event.manufacturer,
                        model = event.model,
                        friendlyName = devName,
                        mountPath = mountPath,
                    ))
                    scope.launch {
                        delay(AppConst.DISCONNECT_TIMEOUT_MS)
                        val currentState = _deviceStates.value[event.serial]
                        if (currentState is MtpDeviceState.Disconnecting) {
                            handleDisconnect(event.serial)
                        }
                    }
                } else {
                    handleDisconnect(event.serial)
                }
            }

            is MtpEvent.DeviceError -> {
                removeActivity(event.serial)
                val name = getDeviceDisplayName(event.serial)
                galleryStateHolder.setStatus("$name error: ${event.message}")
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.Error(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                    message = event.message,
                ))
            }

            is MtpEvent.DevicePermissionDenied -> {
                recordIssue(event.serial, DeviceIssue.PermissionRequired)
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.PermissionDenied(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                    friendlyName = "",
                    reason = event.reason,
                ))
                removeActivity(event.serial)
            }

            is MtpEvent.DeviceQueued -> {
                val name = event.friendlyName
                _deviceStates.value = _deviceStates.value + (event.serial to MtpDeviceState.Queued(
                    serial = event.serial,
                    manufacturer = event.manufacturer,
                    model = event.model,
                    friendlyName = name,
                    detectedAtNanos = event.detectedAtNanos,
                ))
                val queuedPath = Path.of("/__queued__/${event.serial}")
                galleryStateHolder.updateState { state ->
                    state.copy(albums = listOf(
                        Album(
                            path = queuedPath,
                            name = name,
                            images = emptyList(),
                            previewPath = null,
                            isDeviceAlbum = true,
                            statusText = "Queued",
                            statusIsTransition = true,
                        )
                    ) + state.albums)
                }
            }

            is MtpEvent.DeviceCleanupComplete -> {
                NativeMtpBridge.cleanupStaleMounts()
            }
        }
    }

    private fun mountDevice(serial: String, manufacturer: String, model: String): Path? {
        val name = getDeviceDisplayName(serial)

        when (NativeMtpBridge.probeDevice(serial)) {
            MTP_ERR_PERMISSION -> {
                val reason = "Permission denied — unlock your phone and accept the file access prompt"
                LinLogger.w("MtpProtocol", "$name: probe → $reason")
                recordIssue(serial, DeviceIssue.PermissionRequired)
                _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.PermissionDenied(
                    serial = serial, manufacturer = manufacturer, model = model, friendlyName = "", reason = reason,
                ))
                removeActivity(serial)
                return null
            }
            MTP_ERR_ANDROID_NO_STORAGE -> {
                val reason = "Unlock your phone and accept the \"Allow access to device data?\" prompt"
                LinLogger.w("MtpProtocol", "$name: probe → $reason")
                recordIssue(serial, DeviceIssue.DeviceLocked)
                _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.PermissionDenied(
                    serial = serial, manufacturer = manufacturer, model = model, friendlyName = "", reason = reason,
                ))
                removeActivity(serial)
                return null
            }
            MTP_ERR_NO_STORAGE -> {
                val reason = "No accessible storage — unlock your phone and accept the file access prompt"
                LinLogger.w("MtpProtocol", "$name: probe → $reason")
                recordIssue(serial, DeviceIssue.StorageUnavailable)
                _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.Error(
                    serial = serial, manufacturer = manufacturer, model = model, message = reason,
                ))
                removeActivity(serial)
                return null
            }
        }

        val mountDir = try {
            createTempDirectory("lingallery-mtp-$serial")
        } catch (e: Exception) {
            LinLogger.e("MtpProtocol", "Failed to create mount dir: ${e.message}")
            return null
        }

        NativeMtpBridge.setBufferedMode(true)
        LinLogger.startBufferedMode()

        NativeMtpBridge.registerMountPath(serial, mountDir)

        var result = NativeMtpBridge.mountDevice(serial, mountDir)

        if (result == MTP_ERR_PERMISSION || result == MTP_ERR_ANDROID_NO_STORAGE || result == MTP_ERR_MOUNT) {
            LinLogger.w("MtpProtocol", "$name: first mount attempt returned $result, cleaning up and retrying…")
            killCompetingProcesses()
            cleanupStaleMounts()
            Thread.sleep(500)
            NativeMtpBridge.registerMountPath(serial, mountDir)
            result = NativeMtpBridge.mountDevice(serial, mountDir)
        }

        if (result != MTP_OK) {
            NativeMtpBridge.clearMountPath(serial)
            try { Files.deleteIfExists(mountDir) } catch (_: Exception) {}
            NativeMtpBridge.setBufferedMode(false)
            LinLogger.endBufferedMode()
            val reason = when (result) {
                MTP_ERR_PERMISSION         -> "Permission denied — unlock your phone and accept the file access prompt"
                MTP_ERR_ANDROID_NO_STORAGE -> "Unlock your phone and accept the \"Allow access to device data?\" prompt"
                MTP_ERR_NO_STORAGE         -> "No accessible storage — unlock your phone and accept the file access prompt"
                MTP_ERR_MOUNT              -> "FUSE mount failed"
                MTP_ERR_SAMSUNG_RESTRICTED -> "Samsung restricted MTP mode"
                else                       -> "Mount failed (code $result)"
            }
            LinLogger.e("MtpProtocol", "$name: $reason")
            when (result) {
                MTP_ERR_PERMISSION -> {
                    recordIssue(serial, DeviceIssue.PermissionRequired)
                    _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.PermissionDenied(
                        serial = serial, manufacturer = manufacturer, model = model, friendlyName = "", reason = reason,
                    ))
                }
                MTP_ERR_ANDROID_NO_STORAGE -> {
                    recordIssue(serial, DeviceIssue.DeviceLocked)
                    _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.PermissionDenied(
                        serial = serial, manufacturer = manufacturer, model = model, friendlyName = "", reason = reason,
                    ))
                }
                else -> {
                    recordIssue(serial, DeviceIssue.Unknown(code = result))
                    galleryStateHolder.setStatus("$name: $reason")
                    _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.Error(
                        serial = serial, manufacturer = manufacturer, model = model, message = reason,
                    ))
                }
            }
            removeActivity(serial)
            return null
        }

        mountPaths[serial] = mountDir
        updateActivity(serial, DeviceActivity.Mounting(
            deviceId = serial,
            deviceName = name,
        ))
        _deviceStates.value = _deviceStates.value + (serial to MtpDeviceState.Mounting(
            serial = serial,
            manufacturer = manufacturer,
            model = model,
        ))

        val mountInfo = try {
            JSONObject(NativeMtpBridge.getMountInfo(serial))
        } catch (_: Exception) { null }
        val deviceName = mountInfo?.optString("device_name", "")?.ifBlank {
            getDeviceDisplayName(serial)
        } ?: getDeviceDisplayName(serial)

        EventBus.emit(MtpEvent.DeviceAlbumCreated(
            serial = serial,
            manufacturer = manufacturer,
            model = model,
            mountPath = mountDir,
            friendlyName = deviceName,
        ))

        return mountDir
    }

    override fun establishConnection(deviceId: String) {
        val state = _deviceStates.value[deviceId] as? MtpDeviceState ?: return
        val manufacturer = state.manufacturer
        val model = state.model
        scope.launch {
            withContext(Dispatchers.IO) {
                _deviceStates.value = _deviceStates.value + (deviceId to MtpDeviceState.Detected(
                    serial = deviceId,
                    manufacturer = manufacturer,
                    model = model,
                ))
                updateActivity(deviceId, DeviceActivity.Connecting(
                    deviceId = deviceId,
                    deviceName = getDeviceDisplayName(deviceId),
                ))
                mountDevice(deviceId, manufacturer, model)
            }
        }
    }

    override fun getDeviceDisplayName(deviceId: String): String {
        val state = _deviceStates.value[deviceId] as? MtpDeviceState
        return displayNameResolver.resolve(
            metadata = DeviceDisplayNameMetadata(
                friendlyName = state?.friendlyName?.takeIf { it.isNotBlank() },
                model = state?.model?.takeIf { it.isNotBlank() },
            ),
            deviceId = deviceId,
            fallbackLabel = Strings.Device.androidPhone,
        )
    }

    private suspend fun handleDisconnect(serial: String) {
        removeActivity(serial)
        _pendingUserActions.update { it - serial }
        _deviceDisconnected.tryEmit(serial)
        scope.launch {
            val mountDir = mountPaths.remove(serial)

            withContext(Dispatchers.IO) {
                NativeMtpBridge.unmountDevice(serial)
                NativeMtpBridge.cleanupDevice(serial)
                if (mountDir != null) try { Files.deleteIfExists(mountDir) } catch (_: Exception) {}
            }

            _deviceStates.value = _deviceStates.value.toMutableMap().also { it.remove(serial) }

            if (mountDir != null) {
                galleryStateHolder.updateState { it.removeAlbumsByPrefix(mountDir) }
            }

            val s = galleryStateHolder.uiState.value
            val sel = s.currentAlbum
            val isDeviceAlbum = sel != null && mountedRoots.any { sel.path.startsWith(it) }
            if (isDeviceAlbum) {
                galleryStateHolder.setStatus("${sel.name}: ${sel.images.size} images")
            } else {
                galleryStateHolder.setStatus(Strings.Status.summary(s.albums.size, s.albums.sumOf { it.images.size }))
            }

            MemoryManager.trimAfterDisconnect()
        }
    }

    val mountedRoots: List<Path>
        get() = mountPaths.values.toList()

    val mountedSerials: Set<String>
        get() = mountPaths.keys.toSet()

    fun getMountPath(serial: String): Path? = mountPaths[serial]

    private fun recordIssue(deviceId: String, issue: DeviceIssue) {
        _pendingUserActions.value = _pendingUserActions.value + (deviceId to DeviceUserAction(
            deviceId = deviceId,
            issue = issue,
        ))
    }

    private fun updateActivity(deviceId: String, activity: DeviceActivity) {
        val current = _deviceActivities.value.activities.toMutableMap()
        current[deviceId] = activity
        _deviceActivities.value = DeviceActivityMap(activities = current)
    }

    private fun removeActivity(deviceId: String) {
        val current = _deviceActivities.value.activities.toMutableMap()
        current.remove(deviceId)
        _deviceActivities.value = DeviceActivityMap(activities = current)
    }

    private fun killCompetingProcesses() {
        val targets = listOf("gvfsd-mtp", "gvfs-mtp-volume-monitor", "gvfs-gphoto2-volume-monitor")
        for (proc in targets) {
            try {
                val pb = ProcessBuilder("killall", proc)
                pb.redirectErrorStream(true)
                val process = pb.start()
                process.waitFor()
            } catch (_: Exception) {}
        }
    }

    private fun cleanupStaleMounts() {
        try {
            val pb = ProcessBuilder("mount", "-t", "fuse")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            for (line in output.lines()) {
                if (line.contains("lingallery-mtp")) {
                    val parts = line.split(" ")
                    if (parts.size >= 3) {
                        try {
                            val umount = ProcessBuilder("fusermount", "-uz", parts[2])
                                .redirectErrorStream(true)
                                .start()
                            umount.waitFor()
                        } catch (_: Exception) {}
                        try {
                            Files.deleteIfExists(java.nio.file.Paths.get(parts[2]))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
