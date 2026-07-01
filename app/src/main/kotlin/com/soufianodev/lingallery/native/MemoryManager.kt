package com.soufianodev.lingallery.native

import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.SingletonSketch
import com.soufianodev.lingallery.native.mtp.NativeMtpBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.DirectContext
import org.json.JSONObject

object MemoryManager {

    init {
        NativeLibLoader.load()
    }

    enum class PressureLevel {
        NORMAL,
        WARN,
        CRITICAL,
        EXTREME,
    }

    data class MemoryStats(
        val jvmUsedMb: Long,
        val jvmMaxMb: Long,
        val jvmUsedPercent: Int,
        val nativeHeapMb: Long,
        val jemallocAllocatedMb: Long,
        val jemallocActiveMb: Long,
        val jemallocResidentMb: Long,
        val skiaGpuCacheMb: Long,
        val systemTotalMb: Long,
        val systemAvailMb: Long,
        val sketchCacheMb: Long,
        val pressureLevel: PressureLevel,
    )

    private val _pressureLevel = MutableStateFlow(PressureLevel.NORMAL)
    private val _memoryStats = MutableStateFlow(MemoryStats(
        jvmUsedMb = 0, jvmMaxMb = 0, jvmUsedPercent = 0,
        nativeHeapMb = 0, jemallocAllocatedMb = 0, jemallocActiveMb = 0,
        jemallocResidentMb = 0, skiaGpuCacheMb = 0,
        systemTotalMb = 0, systemAvailMb = 0,
        sketchCacheMb = 0, pressureLevel = PressureLevel.NORMAL,
    ))

    val pressureLevel: StateFlow<PressureLevel> = _pressureLevel.asStateFlow()
    val memoryStats: StateFlow<MemoryStats> = _memoryStats.asStateFlow()

    private const val WARN_THRESHOLD = 65
    private const val CRITICAL_THRESHOLD = 80
    private const val EXTREME_THRESHOLD = 92
    private const val WARN_SYS_AVAIL_MB = 2048L
    private const val CRITICAL_SYS_AVAIL_MB = 1024L
    private const val EXTREME_SYS_AVAIL_MB = 512L
    private const val WARN_COMBINED_PCT = 65
    private const val CRITICAL_COMBINED_PCT = 80
    private const val POLL_INTERVAL_MS = 2000L

    private var monitoringJob: Job? = null

    private external fun nativeGetMemoryUsage(): String
    private external fun nativeTrimLevel(level: Int): Long

    fun startMonitoring(scope: CoroutineScope) {
        if (monitoringJob?.isActive == true) return
        LinLogger.i("MemoryManager", "Monitoring started (poll=${POLL_INTERVAL_MS}ms, WARN>${WARN_THRESHOLD}%, CRITICAL>${CRITICAL_THRESHOLD}%, EXTREME>${EXTREME_THRESHOLD}%)")
        monitoringJob = scope.launch {
            while (true) {
                val pollInterval = when (_pressureLevel.value) {
                    PressureLevel.NORMAL -> POLL_INTERVAL_MS
                    PressureLevel.WARN -> 1500L
                    PressureLevel.CRITICAL -> 1000L
                    PressureLevel.EXTREME -> 800L
                }
                delay(pollInterval)
                val stats = collectStats()
                val newLevel = calculatePressure(
                    jvmPercent = stats.jvmUsedPercent,
                    jvmUsedMb = stats.jvmUsedMb,
                    nativeHeapMb = stats.nativeHeapMb,
                    systemTotalMb = stats.systemTotalMb,
                    sysAvailMb = stats.systemAvailMb,
                )
                _memoryStats.value = stats
                val prevLevel = _pressureLevel.value
                if (newLevel != prevLevel) {
                    LinLogger.i("MemoryManager", "Pressure level changed: ${prevLevel} -> ${newLevel}")
                    _pressureLevel.value = newLevel
                    if (newLevel.ordinal > prevLevel.ordinal) {
                        trimMemory(newLevel)
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        LinLogger.i("MemoryManager", "Monitoring stopped")
    }

    fun trimMemory(level: PressureLevel) {
        when (level) {
            PressureLevel.NORMAL -> {}

            PressureLevel.WARN -> {
                LinLogger.d("MemoryManager", "Trimming at WARN level")
                clearSketchMemoryCache()
                NativeImagePipeline.trim()
                requestSkiaCleanup()
                Runtime.getRuntime().gc()
            }

            PressureLevel.CRITICAL -> {
                LinLogger.w("MemoryManager", "Trimming at CRITICAL level")
                clearSketchMemoryCache()
                clearSketchResultCache()
                NativeImagePipeline.trim()
                requestSkiaCleanup()
                NativeMtpBridge.clearAccumulatedBatches()
                Runtime.getRuntime().gc()
            }

            PressureLevel.EXTREME -> {
                LinLogger.e("MemoryManager", "Trimming at EXTREME level")
                clearSketchMemoryCache()
                clearSketchResultCache()
                NativeImagePipeline.trim()
                requestSkiaCleanup()
                NativeMtpBridge.clearAccumulatedBatches()
                val freed = nativeTrimLevel(3)
                LinLogger.d("MemoryManager", "nativeTrimLevel(3) freed ${freed} bytes")
                Runtime.getRuntime().gc()
                Runtime.getRuntime().gc()
                Runtime.getRuntime().gc()
            }
        }
    }

    fun requestTrim(level: PressureLevel) = trimMemory(level)

    fun trimAfterDisconnect() {
        clearSketchMemoryCache()
        NativeImagePipeline.trim()
        requestSkiaCleanup()
        Runtime.getRuntime().gc()
        nativeTrimLevel(0)
        LinLogger.d("MemoryManager", "Post-disconnect memory trim complete")
    }

    fun requestSkiaCleanup() {
        try {
            val windows = java.awt.Window.getWindows()
            for (window in windows) {
                if (window !is java.awt.Container) continue
                val layer = findSkiaLayer(window) ?: continue
                val ctx = resolveDirectContext(layer) ?: continue
                try {
                    val cleanup = ctx::class.java.methods.firstOrNull { method ->
                        method.name == "performDeferredCleanup" && method.parameterCount == 1
                    }
                    cleanup?.invoke(ctx, 0L)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun collectStats(): MemoryStats {
        val rt = Runtime.getRuntime()
        val jvmUsed = rt.totalMemory() - rt.freeMemory()
        val jvmMax = rt.maxMemory()
        val jvmPercent = ((jvmUsed * 100) / jvmMax).toInt()

        val nativeJson = try {
            JSONObject(nativeGetMemoryUsage())
        } catch (_: Exception) {
            JSONObject()
        }

        val nativeHeapMb = nativeJson.optLong("native_heap_mb", 0)
        val systemTotalMb = nativeJson.optLong("system_total_mb", 0)
        val systemAvailMb = nativeJson.optLong("system_avail_mb", 0)
        val jemallocAllocatedMb = nativeJson.optLong("jemalloc_allocated_mb", 0)
        val jemallocActiveMb = nativeJson.optLong("jemalloc_active_mb", 0)
        val jemallocResidentMb = nativeJson.optLong("jemalloc_resident_mb", 0)

        val sketchCacheMb = sketchCacheMaxSize() / (1024 * 1024)
        val skiaGpuCacheMb = getSkiaGpuCacheMb()

        val level = calculatePressure(
            jvmPercent = jvmPercent,
            jvmUsedMb = jvmUsed / (1024 * 1024),
            nativeHeapMb = nativeHeapMb,
            systemTotalMb = systemTotalMb,
            sysAvailMb = systemAvailMb,
        )

        return MemoryStats(
            jvmUsedMb = jvmUsed / (1024 * 1024),
            jvmMaxMb = jvmMax / (1024 * 1024),
            jvmUsedPercent = jvmPercent,
            nativeHeapMb = nativeHeapMb,
            jemallocAllocatedMb = jemallocAllocatedMb,
            jemallocActiveMb = jemallocActiveMb,
            jemallocResidentMb = jemallocResidentMb,
            skiaGpuCacheMb = skiaGpuCacheMb,
            systemTotalMb = systemTotalMb,
            systemAvailMb = systemAvailMb,
            sketchCacheMb = sketchCacheMb,
            pressureLevel = level,
        )
    }

    private fun calculatePressure(
        jvmPercent: Int,
        jvmUsedMb: Long,
        nativeHeapMb: Long,
        systemTotalMb: Long,
        sysAvailMb: Long,
    ): PressureLevel = when {
        jvmPercent >= EXTREME_THRESHOLD || sysAvailMb < EXTREME_SYS_AVAIL_MB
            -> PressureLevel.EXTREME

        jvmPercent >= CRITICAL_THRESHOLD
            || sysAvailMb < CRITICAL_SYS_AVAIL_MB
            || systemTotalMb > 0 && ((jvmUsedMb + nativeHeapMb) * 100 / systemTotalMb) >= CRITICAL_COMBINED_PCT
            -> PressureLevel.CRITICAL

        jvmPercent >= WARN_THRESHOLD
            || sysAvailMb < WARN_SYS_AVAIL_MB
            || systemTotalMb > 0 && ((jvmUsedMb + nativeHeapMb) * 100 / systemTotalMb) >= WARN_COMBINED_PCT
            -> PressureLevel.WARN

        else -> PressureLevel.NORMAL
    }

    private fun clearSketchMemoryCache() {
        try {
            SingletonSketch.get(PlatformContext.INSTANCE).memoryCache.clear()
            LinLogger.d("MemoryManager", "Sketch memory cache cleared")
        } catch (_: Exception) {}
    }

    private fun clearSketchResultCache() {
        try {
            SingletonSketch.get(PlatformContext.INSTANCE).resultCache.clear()
            LinLogger.d("MemoryManager", "Sketch result cache cleared")
        } catch (_: Exception) {}
    }

    private fun sketchCacheMaxSize(): Long {
        return try {
            SingletonSketch.get(PlatformContext.INSTANCE).memoryCache.maxSize
        } catch (_: Exception) {
            0L
        }
    }

    private fun getSkiaGpuCacheMb(): Long {
        try {
            val windows = java.awt.Window.getWindows()
            for (window in windows) {
                if (window !is java.awt.Container) continue
                val layer = findSkiaLayer(window) ?: continue
                val renderApi = layer::class.java.getMethod("getRenderApi").invoke(layer)?.toString()
                if (renderApi != "OPENGL") continue
                val ctx = resolveDirectContext(layer)
                if (ctx != null) {
                    val limitMb = ctx.resourceCacheLimit / (1024 * 1024)
                    if (limitMb > 0) return limitMb
                }
            }
        } catch (_: Exception) {}

        return try {
            val prop = System.getProperty("skiko.gpu.resourceCacheLimit") ?: return 0L
            if (prop.endsWith("M", ignoreCase = true)) prop.dropLast(1).toLongOrNull() ?: 0L
            else prop.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun findSkiaLayer(container: java.awt.Container): Any? {
        for (child in container.components) {
            if (child::class.java.name == "org.jetbrains.skiko.SkiaLayer") return child
            if (child is java.awt.Container) {
                val found = findSkiaLayer(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun resolveDirectContext(layer: Any): DirectContext? {
        try {
            val redrawerMgrField = layer::class.java.getDeclaredField("redrawerManager")
            redrawerMgrField.isAccessible = true
            val redrawerMgr = redrawerMgrField.get(layer)

            val getRedrawer = redrawerMgr::class.java.getMethod("getRedrawer")
            val redrawer = getRedrawer.invoke(redrawerMgr)

            val ctxHandlerField = redrawer::class.java.getDeclaredField("contextHandler")
            ctxHandlerField.isAccessible = true
            val ctxHandler = ctxHandlerField.get(redrawer)

            val getContext = ctxHandler::class.java.getMethod("getContext")
            getContext.isAccessible = true
            return getContext.invoke(ctxHandler) as? DirectContext
        } catch (_: Exception) {
            return null
        }
    }
}
