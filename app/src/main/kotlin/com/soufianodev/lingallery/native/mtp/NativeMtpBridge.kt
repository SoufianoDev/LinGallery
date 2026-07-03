package com.soufianodev.lingallery.native.mtp

import com.soufianodev.lingallery.native.EventBus
import com.soufianodev.lingallery.native.LinLogger
import com.soufianodev.lingallery.native.NativeLibLoader
import com.soufianodev.lingallery.model.ImageFile
import org.json.JSONObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object NativeMtpBridge {

    init {
        NativeLibLoader.load()
    }

    private val _deviceInfos = ConcurrentHashMap<String, DeviceInfo>()
    private val _accumulatedImages = ConcurrentHashMap<String, MutableList<ImageFile>>()

    data class DeviceInfo(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val mountPath: Path?,
        val detectedAtNanos: Long,
    )

    private external fun nativeInit()
    private external fun nativeMtpSetBufferedMode(enabled: Boolean)
    private external fun nativeListDevices(): String
    private external fun nativeProbeDevice(serial: String): Int
    private external fun nativeMountDevice(serial: String, mountDir: String): Int
    private external fun nativeListIndexedImages(serial: String): String
    private external fun nativeGetIndexMetrics(serial: String): String
    private external fun nativeGetMountInfo(serial: String): String
    private external fun nativeUnmountDevice(serial: String): Boolean
    private external fun nativeCleanupDevice(serial: String): Long

    fun init() {
        if (NativeLibLoader.isAvailable) nativeInit()
    }

    fun listDevices(): String = if (NativeLibLoader.isAvailable) nativeListDevices() else "[]"
    fun probeDevice(serial: String): Int = if (NativeLibLoader.isAvailable) nativeProbeDevice(serial) else -1
    fun mountDevice(serial: String, mountPath: Path): Int =
        if (NativeLibLoader.isAvailable) nativeMountDevice(serial, mountPath.toString()) else -1
    fun listIndexedImages(serial: String): String =
        if (NativeLibLoader.isAvailable) nativeListIndexedImages(serial) else ""
    fun getMountInfo(serial: String): String =
        if (NativeLibLoader.isAvailable) nativeGetMountInfo(serial) else ""
    fun getIndexMetrics(serial: String): String =
        if (NativeLibLoader.isAvailable) nativeGetIndexMetrics(serial) else ""
    fun unmountDevice(serial: String): Boolean =
        if (NativeLibLoader.isAvailable) nativeUnmountDevice(serial) else false
    fun cleanupDevice(serial: String): Long =
        if (NativeLibLoader.isAvailable) nativeCleanupDevice(serial) else 0L
    fun setBufferedMode(enabled: Boolean) {
        if (NativeLibLoader.isAvailable) nativeMtpSetBufferedMode(enabled)
    }

    /**
     * Called by MtpProtocol after a successful native mount to register the mount path
     * so that indexing callbacks (onIndexingBatch, onIndexingReady, etc.) can resolve it.
     * Without this, all those callbacks silently bail out because info.mountPath is null.
     */
    fun registerMountPath(serial: String, mountPath: Path) {
        val existing = _deviceInfos[serial]
        if (existing != null) {
            _deviceInfos[serial] = existing.copy(mountPath = mountPath)
        } else {
            // Device info not yet set (race) — store a placeholder so callbacks work
            _deviceInfos[serial] = DeviceInfo(
                serial = serial,
                manufacturer = "",
                model = "",
                mountPath = mountPath,
                detectedAtNanos = System.nanoTime(),
            )
        }
        LinLogger.d("NativeMtpBridge", "Registered mountPath for $serial: $mountPath")
    }

    fun clearMountPath(serial: String) {
        val info = _deviceInfos[serial]
        if (info != null) {
            _deviceInfos[serial] = info.copy(mountPath = null)
        }
        LinLogger.d("NativeMtpBridge", "Cleared mountPath for $serial")
    }

    fun clearAccumulatedBatches() {
        _accumulatedImages.clear()
        LinLogger.d("NativeMtpBridge", "Accumulated image batches cleared by MemoryManager")
    }

    fun appendBatch(serial: String, batchTsv: String) {
        val images = _accumulatedImages.getOrPut(serial) { mutableListOf() }
        if (batchTsv.isBlank()) return
        val existingPaths = images.map { it.path }.toHashSet()
        for (line in batchTsv.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 5) continue
            val image = parseMtpLine(parts) ?: continue
            if (existingPaths.add(image.path)) {
                images.add(image)
            }
        }
        images.sortWith(
            compareByDescending<ImageFile> { it.lastModified }
                .thenByDescending { it.path.toString() }
        )
    }

    fun getAccumulatedImages(serial: String): List<ImageFile> {
        return _accumulatedImages[serial]?.toList() ?: emptyList()
    }

    fun getNativeIndexedImages(serial: String): List<ImageFile>? {
        return try {
            val encoded = listIndexedImages(serial)
            if (encoded.isBlank()) null else parseMtpTsv(encoded)
        } catch (e: Exception) {
            LinLogger.e("NativeMtpBridge", "listIndexedImages failed: ${e.message}")
            null
        }
    }

    private fun parseMtpLine(parts: List<String>): ImageFile? {
        val path = try { Path.of(parts[0]) } catch (_: Exception) { return null }
        val size = parts[1].toLongOrNull() ?: return null
        val mtime = parts[2].toLongOrNull() ?: return null
        val name = parts[3].ifBlank { path.fileName.toString() }
        val ext = parts[4].ifBlank {
            val dot = name.lastIndexOf('.')
            if (dot >= 0) name.substring(dot).lowercase() else ""
        }
        return ImageFile(path = path, name = name, extension = ext, size = size, lastModified = mtime)
    }

    private fun parseMtpTsv(tsv: String): List<ImageFile> {
        val images = mutableListOf<ImageFile>()
        for (line in tsv.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 5) continue
            val image = parseMtpLine(parts) ?: continue
            images.add(image)
        }
        return images.distinctBy { it.path }.sortedByDescending { it.lastModified }
    }

    fun deviceInfoBySerial(serial: String): DeviceInfo? = _deviceInfos[serial]

    fun getMountPath(serial: String): Path? = _deviceInfos[serial]?.mountPath

    @JvmStatic
    fun onDeviceConnected(json: String) {
        val device = parseSingleDeviceJson(json) ?: return
        LinLogger.i("NativeMtpBridge", "Device connected: ${device.serial}")

        // Clean up any stale state for this serial before overwriting
        val stale = _deviceInfos.remove(device.serial)
        if (stale != null) {
            LinLogger.w("NativeMtpBridge", "Stale state found for ${device.serial} — cleaning up")
            EventBus.emit(MtpEvent.DeviceCleanupComplete(serial = device.serial))
        }
        _accumulatedImages.remove(device.serial)

        _deviceInfos[device.serial] = DeviceInfo(
            serial = device.serial,
            manufacturer = device.manufacturer,
            model = device.model,
            mountPath = null,
            detectedAtNanos = device.detectedAtNanos,
        )

        EventBus.emit(
            MtpEvent.DeviceDetected(
                serial = device.serial,
                manufacturer = device.manufacturer,
                model = device.model,
                detectedAtNanos = device.detectedAtNanos,
            )
        )
    }

    @JvmStatic
    fun onDeviceDisconnected(serialOrJson: String) {
        val serial = if (serialOrJson.startsWith("{")) {
            parseSingleDeviceJson(serialOrJson)?.serial ?: serialOrJson
        } else {
            serialOrJson
        }
        _accumulatedImages.remove(serial)
        val info = _deviceInfos.remove(serial)
        LinLogger.i("NativeMtpBridge", "Device disconnected: $serial")
        EventBus.emit(
            MtpEvent.DeviceDisconnected(
                serial = serial,
                manufacturer = info?.manufacturer ?: "",
                model = info?.model ?: "",
                mountPath = info?.mountPath,
            )
        )
    }

    @JvmStatic
    fun onDeviceError(serial: String, message: String) {
        LinLogger.w("NativeMtpBridge", "Device error $serial: $message")
        val info = _deviceInfos[serial]
        EventBus.emit(
            MtpEvent.DeviceError(
                serial = serial,
                manufacturer = info?.manufacturer ?: "",
                model = info?.model ?: "",
                message = message,
            )
        )
    }

    @JvmStatic
    fun onIndexingProgress(serial: String, json: String) {
        val obj = JSONObject(json)
        val count = obj.optInt("count", 0)
        val info = _deviceInfos[serial] ?: return
        EventBus.emit(
            MtpEvent.DeviceAlbumProgress(
                serial = serial,
                manufacturer = info.manufacturer,
                model = info.model,
                mountPath = info.mountPath ?: return,
                count = count,
            )
        )
    }

    @JvmStatic
    fun onIndexingBatch(serial: String, json: String) {
        val obj = JSONObject(json)
        val count = obj.optInt("cumulative_count", -1)
        val tsv = obj.optString("batch_tsv", "")
        if (count < 0 || tsv.isEmpty()) return
        val info = _deviceInfos[serial] ?: return
        EventBus.emit(
            MtpEvent.DeviceAlbumBatch(
                serial = serial,
                manufacturer = info.manufacturer,
                model = info.model,
                mountPath = info.mountPath ?: return,
                batchTsv = tsv,
                cumulativeCount = count,
            )
        )
    }

    @JvmStatic
    fun onIndexingReady(serial: String, json: String) {
        val obj = JSONObject(json)
        val total = obj.optInt("total_count", 0)
        val info = _deviceInfos[serial] ?: return
        val mountPath = info.mountPath ?: return
        EventBus.emit(
            MtpEvent.DeviceAlbumReady(
                serial = serial,
                manufacturer = info.manufacturer,
                model = info.model,
                mountPath = mountPath,
                totalCount = total,
            )
        )
    }

    @JvmStatic
    fun onDeviceCleanupComplete(serial: String, json: String) {
        LinLogger.i("NativeMtpBridge", "Deep native cleanup complete for $serial")
    }

    @JvmStatic
    fun onIndexingFailed(serial: String, json: String) {
        val obj = JSONObject(json)
        val msg = obj.optString("message", "Unknown error")
        val info = _deviceInfos[serial] ?: return
        EventBus.emit(
            MtpEvent.DeviceAlbumFailed(
                serial = serial,
                manufacturer = info.manufacturer,
                model = info.model,
                mountPath = info.mountPath ?: return,
                message = msg,
            )
        )
    }

    fun cleanupStaleMounts() {
        _deviceInfos.clear()
    }

    private data class RawDeviceInfo(
        val serial: String,
        val manufacturer: String,
        val model: String,
        val detectedAtNanos: Long = System.nanoTime(),
    )

    private fun parseSingleDeviceJson(json: String): RawDeviceInfo? {
        if (json.isBlank()) return null
        val fields = json.trim('{', '}').split(",").map { it.split(":", limit = 2) }
        fun field(name: String): String {
            val pair = fields.firstOrNull { it.size == 2 && it[0].trim().trim('"') == name }
            return pair?.get(1)?.trim()?.trim('"')?.replace("\\\"", "\"") ?: ""
        }
        fun fieldLong(name: String): Long {
            val raw = field(name)
            return raw.toLongOrNull() ?: 0L
        }
        val serial = field("serial")
        if (serial.isBlank()) return null
        return RawDeviceInfo(
            serial = serial,
            manufacturer = field("manufacturer"),
            model = field("model"),
            detectedAtNanos = fieldLong("detected_at_nanos"),
        )
    }
}
